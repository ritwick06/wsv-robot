
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class PolygonBCD extends JPanel {

    Polygon pond = new Polygon();
    JButton resetButton = new JButton("Reset");
    JLabel areaLabel;

    double scaleX = 1.0;
    double scaleY = 1.0;

    public PolygonBCD(JLabel areaLabel) {
        this.areaLabel = areaLabel;
        resetButton.addActionListener(e -> clearPolygon());
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pond.addPoint(e.getX(), e.getY());
                updateArea();
                repaint();
            }
        });
    }

    public void setScale(double realWorldWidth, double realWorldHeight) {
        this.scaleX = getWidth() / realWorldWidth;
        this.scaleY = getHeight() / realWorldHeight;
        repaint();
    }

    private void updateArea() {
        double area = calculateArea(pond.xpoints, pond.ypoints, pond.npoints);
        areaLabel.setText(String.format("Area of polygon: %.2f m²", area));
    }

    public void clearPolygon() {
        pond.reset();
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
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;

        g2d.setColor(new Color(255, 200, 0));
        g2d.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 4}, 0));
        g2d.drawLine(cx - 20, cy, cx + 20, cy);
        g2d.drawLine(cx, cy - 20, cx, cy + 20);
        g2d.setStroke(new BasicStroke(1));

        g2d.fillOval(cx - 5, cy - 5, 10, 10);

        double realCX = cx / scaleX;
        double realCY = cy / scaleY;
        g2d.setColor(new Color(255, 200, 0));
        g2d.drawString(String.format("Center (%.1fm, %.1fm)", realCX, realCY), cx + 10, cy - 10);
    }

    private void drawCornerLabels(Graphics2D g2d) {
        double realWidth = getWidth() / scaleX;
        double realHeight = getHeight() / scaleY;

        g2d.setColor(Color.WHITE);
        g2d.drawString("(0.0m, 0.0m)", 5, 15);
        g2d.drawString(String.format("(%.1fm, 0.0m)", realWidth), getWidth() - 90, 15);
        g2d.drawString(String.format("(0.0m, %.1fm)", realHeight), 5, getHeight() - 5);
        g2d.drawString(String.format("(%.1fm, %.1fm)", realWidth, realHeight), getWidth() - 90, getHeight() - 5);
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
            int x = pond.xpoints[i];
            int y = pond.ypoints[i];
            double realX = x / scaleX;
            double realY = y / scaleY;

            if (i == 0) {
                g2d.setColor(Color.RED);
                g2d.fillOval(x - 7, y - 7, 14, 14);
                g2d.setColor(Color.BLACK);
            } else {
                g2d.setColor(Color.BLACK);
                g2d.fillOval(x - 5, y - 5, 10, 10);
            }

            g2d.setColor(Color.BLUE);
            g2d.drawString(String.format("(%.1fm, %.1fm)", realX, realY), x + 8, y - 8);
            g2d.setColor(Color.BLACK);
        }

        if (pond.npoints > 1) {
            g2d.setColor(Color.BLACK);
            g2d.drawPolygon(pond);
        }
    }

    public double calculateArea(int[] xPoints, int[] yPoints, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
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
        String widthInput = JOptionPane.showInputDialog(null, "Enter pond real-world WIDTH in meters:", "Pond Setup", JOptionPane.QUESTION_MESSAGE);
        String heightInput = JOptionPane.showInputDialog(null, "Enter pond real-world HEIGHT in meters:", "Pond Setup", JOptionPane.QUESTION_MESSAGE);

        if (widthInput == null || heightInput == null) return;

        double pondWidth, pondHeight;
        try {
            pondWidth = Double.parseDouble(widthInput.trim());
            pondHeight = Double.parseDouble(heightInput.trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(null, "Invalid input. Please enter numeric values.");
            return;
        }

        JFrame frame = new JFrame("BCD Polygon Window");

        JLabel label1 = new JLabel("<html>Draw a closed polygon where start is the dock.</html>", SwingConstants.LEFT);
        JLabel label2 = new JLabel("Area of polygon: 0.00 m²", SwingConstants.RIGHT);

        PolygonBCD panel = new PolygonBCD(label2);

        JPanel topPanel = new JPanel(new GridLayout(1, 2));
        topPanel.add(label1);
        topPanel.add(label2);

        frame.add(panel, BorderLayout.CENTER);
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(panel.resetButton, BorderLayout.SOUTH);
        frame.setSize(400, 400);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        final double pw = pondWidth;
        final double ph = pondHeight;
        SwingUtilities.invokeLater(() -> panel.setScale(pw, ph));
    }
}