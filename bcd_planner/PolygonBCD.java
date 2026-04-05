import javax.swing.*;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
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
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PolygonBCD extends JPanel {

    Polygon pond = new Polygon();
    JButton resetButton = new JButton("Reset");
    JButton startButton = new JButton("Start");
    JButton sendButton  = new JButton("Send to ESP32");
    JLabel areaLabel;

    double scaleX        = 1.0;
    double scaleY        = 1.0;
    double pondRealWidth  = 0;
    double pondRealHeight = 0;

    List<Point2D> waypoints = new ArrayList<>();
    Point2D dockPoint = null;
    boolean missionReady = false;

    public PolygonBCD(JLabel areaLabel) {
        this.areaLabel = areaLabel;

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

    static class Point2D {
        double x, y;
        Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private void startMission() {
        if (pond.npoints < 3) {
            JOptionPane.showMessageDialog(this, "Please draw a polygon with at least 3 points first.");
            return;
        }

        String boatInput = JOptionPane.showInputDialog(this, "Enter boat width in meters:", "Boat Parameters", JOptionPane.QUESTION_MESSAGE);
        if (boatInput == null) return;

        String fovInput = JOptionPane.showInputDialog(this, "Enter camera FOV width in meters:", "Boat Parameters", JOptionPane.QUESTION_MESSAGE);
        if (fovInput == null) return;

        double boatWidth, cameraFOV;
        try {
            boatWidth = Double.parseDouble(boatInput.trim());
            cameraFOV = Double.parseDouble(fovInput.trim());
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
        sendButton.setEnabled(true);
        repaint();
    }

    private double flipY(double screenY) {
        return pondRealHeight - screenY;
    }

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
            Point2D wp       = waypoints.get(i);
            boolean isDock   = (i == waypoints.size() - 1);
            double  flippedY = flipY(wp.y);
            json.append("{");
            json.append("\"id\":").append(i + 1).append(",");
            json.append("\"x\":").append(String.format("%.4f", wp.x)).append(",");
            json.append("\"y\":").append(String.format("%.4f", flippedY)).append(",");
            json.append("\"dock\":").append(isDock);
            json.append("}");
            if (i < waypoints.size() - 1) json.append(",");
        }
        json.append("]}");
        json.append("\n");

        sendButton.setEnabled(false);
        sendButton.setText("Sending...");

        String finalIp   = ipInput.trim();
        int    finalPort = port;
        String payload   = json.toString();

        new Thread(() -> {
            try (Socket socket           = new Socket(finalIp, finalPort);
                 PrintWriter writer      = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader   = new BufferedReader(
                         new InputStreamReader(socket.getInputStream()))) {

                writer.print(payload);
                writer.flush();

                String response = reader.readLine();

                SwingUtilities.invokeLater(() -> {
                    sendButton.setText("Send to ESP32");
                    sendButton.setEnabled(true);
                    if ("OK".equals(response)) {
                        JOptionPane.showMessageDialog(this,
                                "Waypoints sent and confirmed by ESP32.\n"
                                + waypoints.size() + " waypoints transmitted.");
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

    private List<Point2D> generateBoustrophedon(double stripWidth, double margin) {
        int n = pond.npoints;
        double[] rxPoints = new double[n];
        double[] ryPoints = new double[n];
        for (int i = 0; i < n; i++) {
            rxPoints[i] = pond.xpoints[i] / scaleX;
            ryPoints[i] = pond.ypoints[i] / scaleY;
        }

        double minY = ryPoints[0];
        double maxY = ryPoints[0];
        for (int i = 1; i < n; i++) {
            if (ryPoints[i] < minY) minY = ryPoints[i];
            if (ryPoints[i] > maxY) maxY = ryPoints[i];
        }

        double sweepMinY = minY + margin;
        double sweepMaxY = maxY - margin;

        List<Double> sweepYs = new ArrayList<>();
        double y = sweepMinY + stripWidth / 2.0;
        while (y < sweepMaxY) {
            sweepYs.add(y);
            y += stripWidth;
        }

        List<List<Double>> allIntersections = new ArrayList<>();
        for (double sweepY : sweepYs) {
            List<Double> xIntersections = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double y1 = ryPoints[i];
                double y2 = ryPoints[j];
                double x1 = rxPoints[i];
                double x2 = rxPoints[j];
                if ((y1 <= sweepY && y2 > sweepY) || (y2 <= sweepY && y1 > sweepY)) {
                    double xIntersect = x1 + (sweepY - y1) * (x2 - x1) / (y2 - y1);
                    xIntersections.add(xIntersect);
                }
            }
            Collections.sort(xIntersections);
            allIntersections.add(xIntersections);
        }

        List<Point2D> result = new ArrayList<>();
        for (int row = 0; row < sweepYs.size(); row++) {
            double sweepY = sweepYs.get(row);
            List<Double> intersections = allIntersections.get(row);
            if (intersections.size() < 2) continue;
            double leftX  = intersections.get(0) + margin;
            double rightX = intersections.get(intersections.size() - 1) - margin;
            if (leftX >= rightX) continue;
            if (row % 2 == 0) {
                result.add(new Point2D(leftX, sweepY));
                result.add(new Point2D(rightX, sweepY));
            } else {
                result.add(new Point2D(rightX, sweepY));
                result.add(new Point2D(leftX, sweepY));
            }
        }

        if (dockPoint != null) {
            result.add(new Point2D(dockPoint.x, dockPoint.y));
        }

        return result;
    }

    private int toPixelX(double realX) { return (int) (realX * scaleX); }
    private int toPixelY(double realY) { return (int) (realY * scaleY); }

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
        pond.reset();
        waypoints.clear();
        dockPoint    = null;
        missionReady = false;
        sendButton.setEnabled(false);
        areaLabel.setText("Area of polygon: 0.00 m²");
        repaint();
    }

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
        for (int x = 0; x <= getWidth(); x += (int) scaleX) {
            g2d.drawLine(x, 0, x, getHeight());
        }
        for (int y = 0; y <= getHeight(); y += (int) scaleY) {
            g2d.drawLine(0, y, getWidth(), y);
        }
    }

    private void drawCenter(Graphics2D g2d) {
        int cx = getWidth()  / 2;
        int cy = getHeight() / 2;
        g2d.setColor(new Color(255, 200, 0));
        g2d.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10, new float[]{6, 4}, 0));
        g2d.drawLine(cx - 20, cy, cx + 20, cy);
        g2d.drawLine(cx, cy - 20, cx, cy + 20);
        g2d.setStroke(new BasicStroke(1));
        g2d.fillOval(cx - 5, cy - 5, 10, 10);
        g2d.drawString(String.format("Center (%.1fm, %.1fm)", cx / scaleX, cy / scaleY), cx + 10, cy - 10);
    }

    private void drawCornerLabels(Graphics2D g2d) {
        double realWidth  = getWidth()  / scaleX;
        double realHeight = getHeight() / scaleY;
        g2d.setColor(Color.WHITE);
        g2d.drawString("(0.0m, 0.0m)", 5, 15);
        g2d.drawString(String.format("(%.1fm, 0.0m)", realWidth),              getWidth() - 90, 15);
        g2d.drawString(String.format("(0.0m, %.1fm)", realHeight),             5,               getHeight() - 5);
        g2d.drawString(String.format("(%.1fm, %.1fm)", realWidth, realHeight), getWidth() - 90, getHeight() - 5);
    }

    private void drawWaypoints(Graphics2D g2d) {
        if (waypoints.size() < 2) return;

        g2d.setColor(new Color(255, 165, 0));
        g2d.setStroke(new BasicStroke(2));
        for (int i = 0; i < waypoints.size() - 2; i++) {
            Point2D a = waypoints.get(i);
            Point2D b = waypoints.get(i + 1);
            g2d.drawLine(toPixelX(a.x), toPixelY(a.y), toPixelX(b.x), toPixelY(b.y));
        }

        Point2D last = waypoints.get(waypoints.size() - 2);
        Point2D dock = waypoints.get(waypoints.size() - 1);
        g2d.setColor(new Color(255, 80, 80));
        g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10, new float[]{8, 5}, 0));
        g2d.drawLine(toPixelX(last.x), toPixelY(last.y), toPixelX(dock.x), toPixelY(dock.y));
        g2d.setStroke(new BasicStroke(1));

        for (int i = 0; i < waypoints.size(); i++) {
            Point2D wp = waypoints.get(i);
            int px = toPixelX(wp.x);
            int py = toPixelY(wp.y);
            if (i == waypoints.size() - 1) {
                g2d.setColor(Color.RED);
                g2d.fillOval(px - 7, py - 7, 14, 14);
                g2d.setColor(Color.WHITE);
                g2d.drawString("DOCK", px + 10, py);
            } else {
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
            int    x     = pond.xpoints[i];
            int    y     = pond.ypoints[i];
            double realX = x / scaleX;
            double realY = y / scaleY;
            if (i == 0) {
                g2d.setColor(Color.RED);
                g2d.fillOval(x - 7, y - 7, 14, 14);
            } else {
                g2d.setColor(Color.BLACK);
                g2d.fillOval(x - 5, y - 5, 10, 10);
            }
            g2d.setColor(Color.BLUE);
            g2d.drawString(String.format("(%.1fm, %.1fm)", realX, realY), x + 8, y - 8);
        }

        if (pond.npoints > 1) {
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawPolygon(pond);
            g2d.setStroke(new BasicStroke(1));
        }

        if (missionReady) {
            drawWaypoints(g2d);
        }
    }

    public double calculateArea(int[] xPoints, int[] yPoints, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            int    j  = (i + 1) % n;
            double x1 = xPoints[i] / scaleX;
            double y1 = yPoints[i] / scaleY;
            double x2 = xPoints[j] / scaleX;
            double y2 = yPoints[j] / scaleY;
            sum += (x1 * y2);
            sum -= (x2 * y1);
        }
        return Math.abs(sum) / 2.0;
    }

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

        JFrame frame = new JFrame("BCD Polygon Window");

        JLabel label1 = new JLabel("<html>Draw polygon then click Start.</html>", SwingConstants.LEFT);
        JLabel label2 = new JLabel("Area of polygon: 0.00 m²", SwingConstants.RIGHT);

        PolygonBCD panel = new PolygonBCD(label2);

        JPanel topPanel = new JPanel(new GridLayout(1, 2));
        topPanel.add(label1);
        topPanel.add(label2);

        JPanel bottomPanel = new JPanel(new GridLayout(1, 3));
        bottomPanel.add(panel.resetButton);
        bottomPanel.add(panel.startButton);
        bottomPanel.add(panel.sendButton);

        frame.add(panel, BorderLayout.CENTER);
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(bottomPanel, BorderLayout.SOUTH);
        frame.setSize(600, 600);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        final double pw = pondWidth;
        final double ph = pondHeight;
        SwingUtilities.invokeLater(() -> panel.setScale(pw, ph));
    }
}