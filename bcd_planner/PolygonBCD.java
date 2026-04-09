import javax.swing.*;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PolygonBCD extends JPanel {

    Polygon pond = new Polygon();
    JButton resetButton  = new JButton("Reset");
    JButton startButton  = new JButton("Start");
    JButton sendButton   = new JButton("Send to ESP32");
    JLabel  areaLabel;
    JLabel  coverageLabel;

    double scaleX         = 1.0;
    double scaleY         = 1.0;
    double pondRealWidth  = 0;
    double pondRealHeight = 0;

    List<Point2D> waypoints    = new ArrayList<>();
    Point2D       dockPoint    = null;
    boolean       missionReady = false;

    // Coverage tracking
    boolean[] visited      = new boolean[0];
    int       visitedCount = 0;
    volatile boolean stopCoverage = false;
    Thread    coverageThread;
    String    esp32Ip   = "";
    int       esp32Port = 8080;
    
    double    currentPosX = 0;
    double    currentPosY = 0;

    // Telemetry Dashboard Elements
    Boat3DView boat3DView = new Boat3DView();
    JProgressBar throttleLBar = new JProgressBar(0, 100);
    JProgressBar throttleRBar = new JProgressBar(0, 100);

    // ── Constructor ──────────────────────────────────────────────────────────

    public PolygonBCD(JLabel areaLabel, JLabel coverageLabel) {
        this.areaLabel     = areaLabel;
        this.coverageLabel = coverageLabel;

        resetButton.addActionListener(e -> clearAll());
        startButton.addActionListener(e -> startMission());
        sendButton.addActionListener(e -> sendWaypointsToESP32());
        sendButton.setEnabled(false);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!missionReady) {
                    pond.addPoint(e.getX(), e.getY());
                    updateArea();
                    repaint();
                }
            }
        });
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    static class Point2D {
        double x, y;
        Point2D(double x, double y) { this.x = x; this.y = y; }
    }

    static class Boat3DView extends JPanel {
        double roll = 0, pitch = 0, yaw = 0;
        
        public void updateTelemetry(double r, double p, double y) {
            this.roll = r; 
            this.pitch = p; 
            this.yaw = y;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Draw background
            g2d.setColor(new Color(30, 30, 30));
            g2d.fillRect(0, 0, getWidth(), getHeight());
            
            g2d.translate(getWidth() / 2, getHeight() / 2);

            // Simple 3D box points to represent the boat
            // +x right, +y down, +z forward
            double[][] pts = {
                {-20, -10, -30}, {20, -10, -30}, {20, 10, -30}, {-20, 10, -30}, // back
                {-20, -10,  30}, {20, -10,  30}, {20, 10,  30}, {-20, 10,  30}, // front
                {0, 0, 50} // front tip (bow)
            };

            double r = Math.toRadians(roll);
            double p = Math.toRadians(pitch);
            double y_rot = Math.toRadians(yaw); 
            
            double cy = Math.cos(y_rot), sy = Math.sin(y_rot);
            double cp = Math.cos(p), sp = Math.sin(p);
            double cr = Math.cos(r), sr = Math.sin(r);

            int[][] proj = new int[pts.length][2];
            for (int i = 0; i < pts.length; i++) {
                double x = pts[i][0], y_ = pts[i][1], z = pts[i][2];
                
                // Roll (Z axis)
                double x1 = x * cr - y_ * sr;
                double y1 = x * sr + y_ * cr;
                double z1 = z;
                
                // Pitch (X axis)
                double x2 = x1;
                double y2 = y1 * cp - z1 * sp;
                double z2 = y1 * sp + z1 * cp;
                
                // Yaw (Y axis)
                double x3 = x2 * cy + z2 * sy;
                double y3 = y2;
                double z3 = -x2 * sy + z2 * cy;
                
                // Project to 2D
                double scale = 200.0 / (250.0 + z3);
                if (scale < 0) scale = 0.1;
                proj[i][0] = (int)(x3 * scale * 2.0);
                proj[i][1] = (int)(y3 * scale * 2.0);
            }

            // Draw edges
            g2d.setColor(new Color(0, 255, 200));
            g2d.setStroke(new BasicStroke(2));
            int[][] edges = {
                {0,1}, {1,2}, {2,3}, {3,0},
                {4,5}, {5,6}, {6,7}, {7,4},
                {0,4}, {1,5}, {2,6}, {3,7},
                {4,8}, {5,8}, {6,8}, {7,8}
            };
            for (int[] e : edges) {
                g2d.drawLine(proj[e[0]][0], proj[e[0]][1], proj[e[1]][0], proj[e[1]][1]);
            }
            
            // Draw labels
            g2d.translate(-getWidth() / 2, -getHeight() / 2);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Monospaced", Font.BOLD, 12));
            g2d.drawString(String.format("Roll:  %6.1f°", roll), 10, 20);
            g2d.drawString(String.format("Pitch: %6.1f°", pitch), 10, 35);
            g2d.drawString(String.format("Yaw:   %6.1f°", yaw), 10, 50);
        }
    }

    // ── Pond definition ───────────────────────────────────────────────────────

    private void startMission() {
        if (pond.npoints < 3) {
            JOptionPane.showMessageDialog(this,
                "Please draw a polygon with at least 3 points first.");
            return;
        }

        String boatInput = JOptionPane.showInputDialog(this,
            "Enter boat width in meters:", "Boat Parameters", JOptionPane.QUESTION_MESSAGE);
        if (boatInput == null) return;

        String fovInput = JOptionPane.showInputDialog(this,
            "Enter camera FOV width in meters:", "Boat Parameters", JOptionPane.QUESTION_MESSAGE);
        if (fovInput == null) return;

        double boatWidth, cameraFOV;
        try {
            boatWidth  = Double.parseDouble(boatInput.trim());
            cameraFOV  = Double.parseDouble(fovInput.trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid input. Please enter numeric values.");
            return;
        }

        double stripWidth = Math.max(boatWidth, cameraFOV);
        double margin     = boatWidth / 2.0;

        dockPoint = new Point2D(
            pond.xpoints[0] / scaleX,
            pond.ypoints[0] / scaleY
        );

        waypoints    = generateBoustrophedon(stripWidth, margin);
        missionReady = true;
        visited      = new boolean[waypoints.size()];
        visitedCount = 0;
        sendButton.setEnabled(true);
        coverageLabel.setText("Coverage: 0.0%  WP: 0/" + waypoints.size());
        repaint();
    }

    private double flipY(double screenY) {
        return pondRealHeight - screenY;
    }

    // ── Send to ESP32 + start coverage polling ────────────────────────────────

    private void sendWaypointsToESP32() {
        if (waypoints.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No waypoints to send. Click Start first.");
            return;
        }

        String ipInput = JOptionPane.showInputDialog(this,
            "Enter ESP32 IP address:", "ESP32 Connection", JOptionPane.QUESTION_MESSAGE);
        if (ipInput == null) return;

        String portInput = JOptionPane.showInputDialog(this,
            "Enter ESP32 TCP port (default 8080):", "ESP32 Connection", JOptionPane.QUESTION_MESSAGE);
        if (portInput == null) return;

        int port;
        try {
            port = Integer.parseInt(portInput.trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid port number.");
            return;
        }

        // Build JSON payload
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"pondWidth\":").append(String.format("%.4f", pondRealWidth)).append(",");
        json.append("\"pondHeight\":").append(String.format("%.4f", pondRealHeight)).append(",");
        json.append("\"dock\":{");
        json.append("\"x\":").append(String.format("%.4f", dockPoint.x)).append(",");
        json.append("\"y\":").append(String.format("%.4f", flipY(dockPoint.y)));
        json.append("},");
        json.append("\"waypoints\":[");
        for (int i = 0; i < waypoints.size(); i++) {
            Point2D wp     = waypoints.get(i);
            boolean isDock = (i == waypoints.size() - 1);
            json.append("{");
            json.append("\"id\":").append(i + 1).append(",");
            json.append("\"x\":").append(String.format("%.4f", wp.x)).append(",");
            json.append("\"y\":").append(String.format("%.4f", flipY(wp.y))).append(",");
            json.append("\"dock\":").append(isDock);
            json.append("}");
            if (i < waypoints.size() - 1) json.append(",");
        }
        json.append("]}\n");

        // Reset coverage state
        visited      = new boolean[waypoints.size()];
        visitedCount = 0;
        esp32Ip      = ipInput.trim();
        esp32Port    = port;

        sendButton.setEnabled(false);
        sendButton.setText("Sending...");

        final String finalIp      = esp32Ip;
        final int    finalPort    = esp32Port;
        final String payload      = json.toString();
        final int    totalWP      = waypoints.size();

        new Thread(() -> {
            try (Socket socket         = new Socket(finalIp, finalPort);
                 PrintWriter writer    = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream()))) {

                writer.print(payload);
                writer.flush();
                String response = reader.readLine();

                SwingUtilities.invokeLater(() -> {
                    sendButton.setText("Send to ESP32");
                    sendButton.setEnabled(true);
                    if ("OK".equals(response)) {
                        JOptionPane.showMessageDialog(this,
                            "Mission started!\n"
                            + totalWP + " waypoints transmitted.\n"
                            + "Dashboard: http://" + finalIp + "/");
                        startCoveragePolling(finalIp);
                    } else {
                        JOptionPane.showMessageDialog(this,
                            "ESP32 responded unexpectedly: " + response);
                    }
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    sendButton.setText("Send to ESP32");
                    sendButton.setEnabled(true);
                    JOptionPane.showMessageDialog(this,
                        "Failed to connect to ESP32:\n" + e.getMessage());
                });
            }
        }).start();
    }

    // ── Coverage polling — hits ESP32 GET /status every 200ms ───────────────

    private void startCoveragePolling(String ip) {
        // Stop any existing poll thread
        stopCoverage = true;
        if (coverageThread != null) {
            coverageThread.interrupt();
        }
        stopCoverage = false;

        coverageThread = new Thread(() -> {
            while (!stopCoverage) {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(ip, 80), 300); // 300ms connect timeout
                    s.setSoTimeout(300); // 300ms read timeout
                    PrintWriter   pw = new PrintWriter(s.getOutputStream(), true);
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(s.getInputStream()));

                    pw.print("GET /status HTTP/1.0\r\nHost: " + ip + "\r\n\r\n");
                    pw.flush();

                    // Skip HTTP response headers
                    String line;
                    while ((line = br.readLine()) != null && !line.isEmpty()) { /* skip */ }

                    // Body: {"wp":3,"total":20,"coverage":15.0,"done":false,"roll":0,"pitch":0,"yaw":0,"throttleL":0,"throttleR":0}
                    String body = br.readLine();
                    s.close();

                    if (body != null && body.startsWith("{")) {
                        int     wp       = extractInt(body,    "wp");
                        int     total    = extractInt(body,    "total");
                        double  coverage = extractDouble(body, "coverage");
                        boolean done     = body.contains("\"done\":true");
                        
                        double posX      = extractDouble(body, "posX");
                        double posY      = extractDouble(body, "posY");
                        double roll      = extractDouble(body, "roll");
                        double pitch     = extractDouble(body, "pitch");
                        double yaw       = extractDouble(body, "yaw");
                        double throttleL = extractDouble(body, "throttleL");
                        double throttleR = extractDouble(body, "throttleR");

                        // Update visited array
                        for (int i = 0; i < Math.min(wp, visited.length); i++) {
                            visited[i] = true;
                        }
                        visitedCount = wp;

                        String labelText = String.format(
                            "Coverage: %.1f%%  WP: %d/%d%s",
                            coverage, wp, total, done ? "  ✓ Mission Complete" : "");

                        SwingUtilities.invokeLater(() -> {
                            coverageLabel.setText(labelText);
                            currentPosX = posX;
                            currentPosY = posY;
                            boat3DView.updateTelemetry(roll, pitch, yaw);
                            throttleLBar.setValue(Math.max(0, Math.min(100, (int)throttleL)));
                            throttleRBar.setValue(Math.max(0, Math.min(100, (int)throttleR)));
                            repaint();
                        });

                        if (done) {
                            // If done, we can optionally slow down or stop polling, 
                            // but usually it's fine to keep polling for telemetry.
                        }
                    }

                } catch (Exception ignored) {
                    // ESP32 might be busy — silently retry
                }

                // Increased polling rate to 50ms (20fps) for incredibly butter-smooth 3D visuals
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
        });
        coverageThread.setDaemon(true);
        coverageThread.start();
    }

    private int extractInt(String json, String key) {
        try {
            int idx   = json.indexOf("\"" + key + "\":");
            if (idx < 0) return 0;
            int start = idx + key.length() + 3;
            int end   = start;
            while (end < json.length() &&
                   (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) { return 0; }
    }

    private double extractDouble(String json, String key) {
        try {
            int idx   = json.indexOf("\"" + key + "\":");
            if (idx < 0) return 0.0;
            int start = idx + key.length() + 3;
            int end   = start;
            while (end < json.length() &&
                   (Character.isDigit(json.charAt(end)) ||
                    json.charAt(end) == '.'             ||
                    json.charAt(end) == '-')) end++;
            return Double.parseDouble(json.substring(start, end));
        } catch (Exception e) { return 0.0; }
    }

    // ── BCD path generation ───────────────────────────────────────────────────

    private List<Point2D> generateBoustrophedon(double stripWidth, double margin) {
        int      n        = pond.npoints;
        double[] rxPoints = new double[n];
        double[] ryPoints = new double[n];
        for (int i = 0; i < n; i++) {
            rxPoints[i] = pond.xpoints[i] / scaleX;
            ryPoints[i] = pond.ypoints[i] / scaleY;
        }

        double minY = ryPoints[0], maxY = ryPoints[0];
        for (int i = 1; i < n; i++) {
            if (ryPoints[i] < minY) minY = ryPoints[i];
            if (ryPoints[i] > maxY) maxY = ryPoints[i];
        }

        double sweepMinY = minY + margin;
        double sweepMaxY = maxY - margin;

        List<Double> sweepYs = new ArrayList<>();
        double y = sweepMinY + stripWidth / 2.0;
        while (y < sweepMaxY) { sweepYs.add(y); y += stripWidth; }

        List<List<Double>> allIntersections = new ArrayList<>();
        for (double sweepY : sweepYs) {
            List<Double> xIntersections = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int    j  = (i + 1) % n;
                double y1 = ryPoints[i], y2 = ryPoints[j];
                double x1 = rxPoints[i], x2 = rxPoints[j];
                if ((y1 <= sweepY && y2 > sweepY) || (y2 <= sweepY && y1 > sweepY)) {
                    xIntersections.add(x1 + (sweepY - y1) * (x2 - x1) / (y2 - y1));
                }
            }
            Collections.sort(xIntersections);
            allIntersections.add(xIntersections);
        }

        List<Point2D> result = new ArrayList<>();
        if (dockPoint != null) {
            result.add(new Point2D(dockPoint.x, dockPoint.y)); // Start at the Dock
        }
        for (int row = 0; row < sweepYs.size(); row++) {
            double       sweepY        = sweepYs.get(row);
            List<Double> intersections = allIntersections.get(row);
            if (intersections.size() < 2) continue;
            double leftX  = intersections.get(0) + margin;
            double rightX = intersections.get(intersections.size() - 1) - margin;
            if (leftX >= rightX) continue;
            if (row % 2 == 0) {
                result.add(new Point2D(leftX,  sweepY));
                result.add(new Point2D(rightX, sweepY));
            } else {
                result.add(new Point2D(rightX, sweepY));
                result.add(new Point2D(leftX,  sweepY));
            }
        }

        if (dockPoint != null) {
            result.add(new Point2D(dockPoint.x, dockPoint.y));
        }
        return result;
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    private int toPixelX(double realX) { return (int)(realX * scaleX); }
    private int toPixelY(double realY) { return (int)(realY * scaleY); }

    public void setScale(double realWorldWidth, double realWorldHeight) {
        this.scaleX         = getWidth()  / realWorldWidth;
        this.scaleY         = getHeight() / realWorldHeight;
        this.pondRealWidth  = realWorldWidth;
        this.pondRealHeight = realWorldHeight;
        repaint();
    }

    private void updateArea() {
        double area = calculateArea(pond.xpoints, pond.ypoints, pond.npoints);
        areaLabel.setText(String.format("Area of polygon: %.2f m²", area));
    }

    public void clearAll() {
        // Stop ESP32 Mission if we know the IP
        if (esp32Ip != null && !esp32Ip.isEmpty()) {
            final String ipToStop = esp32Ip;
            new Thread(() -> {
                try {
                    URL url = new URL("http://" + ipToStop + "/stop");
                    HttpURLConnection con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("GET");
                    con.setConnectTimeout(2000);
                    con.setReadTimeout(2000);
                    int rc = con.getResponseCode();
                    System.out.println("Stop command sent to ESP32. HTTP Response Code: " + rc);
                } catch (Exception ex) {
                    System.out.println("Failed to send stop command to ESP32: " + ex.getMessage());
                }
            }).start();
        }

        stopCoverage = true;
        pond.reset();
        waypoints.clear();
        visited      = new boolean[0];
        visitedCount = 0;
        dockPoint    = null;
        missionReady = false;
        sendButton.setEnabled(false);
        areaLabel.setText("Area of polygon: 0.00 m²");
        coverageLabel.setText("Coverage: 0.0%  WP: 0/0");
        
        // Reset telemetry visual
        boat3DView.updateTelemetry(0, 0, 0);
        throttleLBar.setValue(0);
        throttleRBar.setValue(0);
        
        repaint();
    }

    // ── Paint helpers ─────────────────────────────────────────────────────────

    private void drawBoundaries(Graphics2D g2d) {
        g2d.setColor(new Color(0, 150, 255, 60));
        g2d.fillRect(0, 0, getWidth(), getHeight());
        g2d.setColor(new Color(0, 100, 200));
        g2d.setStroke(new BasicStroke(3));
        g2d.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        g2d.setStroke(new BasicStroke(1));
    }

    private void drawGrid(Graphics2D g2d) {
        g2d.setColor(new Color(255, 255, 255, 50));
        for (int x = 0; x <= getWidth();  x += (int) scaleX) g2d.drawLine(x, 0, x, getHeight());
        for (int yg = 0; yg <= getHeight(); yg += (int) scaleY) g2d.drawLine(0, yg, getWidth(), yg);
    }

    private void drawCenter(Graphics2D g2d) {
        int cx = getWidth() / 2, cy = getHeight() / 2;
        g2d.setColor(new Color(255, 200, 0));
        g2d.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10, new float[]{6, 4}, 0));
        g2d.drawLine(cx - 20, cy, cx + 20, cy);
        g2d.drawLine(cx, cy - 20, cx, cy + 20);
        g2d.setStroke(new BasicStroke(1));
        g2d.fillOval(cx - 5, cy - 5, 10, 10);
        g2d.drawString(String.format("Center (%.1fm, %.1fm)", cx / scaleX, cy / scaleY),
            cx + 10, cy - 10);
    }

    private void drawCornerLabels(Graphics2D g2d) {
        double rw = getWidth() / scaleX, rh = getHeight() / scaleY;
        g2d.setColor(Color.WHITE);
        g2d.drawString("(0.0m, 0.0m)", 5, 15);
        g2d.drawString(String.format("(%.1fm, 0.0m)", rw),     getWidth() - 90, 15);
        g2d.drawString(String.format("(0.0m, %.1fm)", rh),     5,               getHeight() - 5);
        g2d.drawString(String.format("(%.1fm, %.1fm)", rw, rh), getWidth() - 90, getHeight() - 5);
    }

    private void drawWaypoints(Graphics2D g2d) {
        if (waypoints.size() < 2) return;

        // Path lines
        for (int i = 0; i < waypoints.size() - 2; i++) {
            Point2D a = waypoints.get(i);
            Point2D b = waypoints.get(i + 1);
            boolean aVis = visited != null && i < visited.length && visited[i];
            boolean bVis = visited != null && (i + 1) < visited.length && visited[i + 1];
            if (aVis && bVis) {
                g2d.setColor(new Color(0, 220, 100));
                g2d.setStroke(new BasicStroke(2.5f));
            } else {
                g2d.setColor(new Color(255, 165, 0, 160));
                g2d.setStroke(new BasicStroke(2));
            }
            g2d.drawLine(toPixelX(a.x), toPixelY(a.y), toPixelX(b.x), toPixelY(b.y));
        }

        // Dock return — dashed red
        Point2D last = waypoints.get(waypoints.size() - 2);
        Point2D dock = waypoints.get(waypoints.size() - 1);
        g2d.setColor(new Color(255, 80, 80));
        g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10, new float[]{8, 5}, 0));
        g2d.drawLine(toPixelX(last.x), toPixelY(last.y), toPixelX(dock.x), toPixelY(dock.y));
        g2d.setStroke(new BasicStroke(1));

        // Waypoint dots
        for (int i = 0; i < waypoints.size(); i++) {
            Point2D wp = waypoints.get(i);
            int px = toPixelX(wp.x), py = toPixelY(wp.y);

            if (i == waypoints.size() - 1) {
                // Dock
                g2d.setColor(Color.RED);
                g2d.fillOval(px - 7, py - 7, 14, 14);
                g2d.setColor(Color.WHITE);
                g2d.drawString("DOCK", px + 10, py);

            } else if (visited != null && i < visited.length && visited[i]) {
                // Visited — green
                g2d.setColor(new Color(0, 200, 80));
                g2d.fillOval(px - 5, py - 5, 10, 10);

            } else if (i == visitedCount) {
                // Active target — cyan ring
                g2d.setColor(new Color(0, 220, 255));
                g2d.setStroke(new BasicStroke(2));
                g2d.drawOval(px - 9, py - 9, 18, 18);
                g2d.fillOval(px - 4, py - 4, 8, 8);
                g2d.setStroke(new BasicStroke(1));
                g2d.setColor(Color.WHITE);
                g2d.drawString(String.valueOf(i + 1), px + 8, py - 5);

            } else {
                // Future — orange
                g2d.setColor(new Color(255, 165, 0));
                g2d.fillOval(px - 4, py - 4, 8, 8);
                g2d.setColor(Color.WHITE);
                g2d.drawString(String.valueOf(i + 1), px + 6, py - 4);
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawBoundaries(g2d);
        drawGrid(g2d);
        drawCenter(g2d);
        drawCornerLabels(g2d);

        for (int i = 0; i < pond.npoints; i++) {
            int x = pond.xpoints[i], y = pond.ypoints[i];
            if (i == 0) { g2d.setColor(Color.RED); g2d.fillOval(x - 7, y - 7, 14, 14); }
            else        { g2d.setColor(Color.BLACK); g2d.fillOval(x - 5, y - 5, 10, 10); }
            g2d.setColor(Color.BLUE);
            g2d.drawString(String.format("(%.1fm, %.1fm)", x / scaleX, y / scaleY), x + 8, y - 8);
        }

        if (pond.npoints > 1) {
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawPolygon(pond);
            g2d.setStroke(new BasicStroke(1));
        }

        if (missionReady) {
            drawWaypoints(g2d);
            
            // Draw real-time boat location on the 2D map
            int bx = toPixelX(currentPosX);
            int by = toPixelY(flipY(currentPosY));
            
            g2d.translate(bx, by);
            // ESP Yaw: 0=East, 90=North. GUI Y is flipped down.
            // Converting ESP yaw to GUI rotation rads
            double guiYawRad = -Math.toRadians(boat3DView.yaw); 
            g2d.rotate(guiYawRad);
            
            // Draw a Magenta triangle pointing forward
            g2d.setColor(Color.MAGENTA);
            int[] xTri = {12, -8, -8};
            int[] yTri = {0, -8, 8};
            g2d.fillPolygon(xTri, yTri, 3);
            
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(1.5f));
            g2d.drawPolygon(xTri, yTri, 3);
            
            g2d.rotate(-guiYawRad);
            
            // Draw floating coordinate text label above the tracker
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("Monospaced", Font.BOLD, 12));
            String coordText = String.format("Boat: (%.1fm, %.1fm)", currentPosX, currentPosY);
            g2d.drawString(coordText, 15, -15);
            
            g2d.translate(-bx, -by);
        }
    }

    public double calculateArea(int[] xPoints, int[] yPoints, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            int    j  = (i + 1) % n;
            double x1 = xPoints[i] / scaleX, y1 = yPoints[i] / scaleY;
            double x2 = xPoints[j] / scaleX, y2 = yPoints[j] / scaleY;
            sum += x1 * y2 - x2 * y1;
        }
        return Math.abs(sum) / 2.0;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String widthInput = JOptionPane.showInputDialog(null,
            "Enter pond real-world WIDTH in meters:", "Pond Setup", JOptionPane.QUESTION_MESSAGE);
        String heightInput = JOptionPane.showInputDialog(null,
            "Enter pond real-world HEIGHT in meters:", "Pond Setup", JOptionPane.QUESTION_MESSAGE);
        if (widthInput == null || heightInput == null) return;

        double pondWidth, pondHeight;
        try {
            pondWidth  = Double.parseDouble(widthInput.trim());
            pondHeight = Double.parseDouble(heightInput.trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(null, "Invalid input. Please enter numeric values.");
            return;
        }

        JFrame frame = new JFrame("BCD Polygon Window — AWSCR");

        JLabel label1        = new JLabel("<html>Draw polygon then click Start.</html>",
                                   SwingConstants.LEFT);
        JLabel label2        = new JLabel("Area of polygon: 0.00 m²", SwingConstants.RIGHT);
        JLabel coverageLbl   = new JLabel("Coverage: 0.0%  WP: 0/0", SwingConstants.CENTER);
        coverageLbl.setFont(new Font("Monospaced", Font.BOLD, 13));
        coverageLbl.setForeground(new Color(0, 160, 80));

        PolygonBCD panel = new PolygonBCD(label2, coverageLbl);

        JPanel topPanel = new JPanel(new GridLayout(1, 2));
        topPanel.add(label1);
        topPanel.add(label2);

        JPanel northWrapper = new JPanel(new BorderLayout());
        northWrapper.add(topPanel,    BorderLayout.NORTH);
        northWrapper.add(coverageLbl, BorderLayout.SOUTH);

        JPanel bottomPanel = new JPanel(new GridLayout(1, 3));
        bottomPanel.add(panel.resetButton);
        bottomPanel.add(panel.startButton);
        bottomPanel.add(panel.sendButton);

        // --- DASHBOARD SETUP ---
        JPanel dashboardPanel = new JPanel(new BorderLayout());
        dashboardPanel.setPreferredSize(new Dimension(250, 0));
        dashboardPanel.setBorder(BorderFactory.createTitledBorder("Telemetry Dashboard"));
        
        panel.boat3DView.setPreferredSize(new Dimension(250, 250));
        dashboardPanel.add(panel.boat3DView, BorderLayout.NORTH);
        
        JPanel throttlePanel = new JPanel(new GridLayout(1, 2, 10, 0));
        throttlePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        panel.throttleLBar.setOrientation(SwingConstants.VERTICAL);
        panel.throttleLBar.setStringPainted(true);
        panel.throttleRBar.setOrientation(SwingConstants.VERTICAL);
        panel.throttleRBar.setStringPainted(true);
        
        JPanel leftThrotPane = new JPanel(new BorderLayout());
        leftThrotPane.add(new JLabel("Motor L", SwingConstants.CENTER), BorderLayout.NORTH);
        leftThrotPane.add(panel.throttleLBar, BorderLayout.CENTER);
        
        JPanel rightThrotPane = new JPanel(new BorderLayout());
        rightThrotPane.add(new JLabel("Motor R", SwingConstants.CENTER), BorderLayout.NORTH);
        rightThrotPane.add(panel.throttleRBar, BorderLayout.CENTER);

        throttlePanel.add(leftThrotPane);
        throttlePanel.add(rightThrotPane);
        
        dashboardPanel.add(throttlePanel, BorderLayout.CENTER);
        // -----------------------

        frame.add(panel,          BorderLayout.CENTER);
        frame.add(northWrapper,   BorderLayout.NORTH);
        frame.add(bottomPanel,    BorderLayout.SOUTH);
        frame.add(dashboardPanel, BorderLayout.EAST);
        frame.setSize(950, 650); // Increased width to fit dashboard
        frame.setResizable(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        final double pw = pondWidth, ph = pondHeight;
        SwingUtilities.invokeLater(() -> panel.setScale(pw, ph));
    }
}