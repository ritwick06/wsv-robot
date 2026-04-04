import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class PolygonBCD extends JPanel {

    Polygon pond = new Polygon();
    JButton resetButton = new JButton("Reset");
    JLabel areaLabel;

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

    private void updateArea() {
        double area = calculateArea(pond.xpoints, pond.ypoints, pond.npoints);
        areaLabel.setText("Area of polygon: " + area);
    }

    public void clearPolygon() {
        pond.reset();
        areaLabel.setText("Area of polygon: 0.0");
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int i = 0; i < pond.npoints; i++) {
            int x = pond.xpoints[i];
            int y = pond.ypoints[i];

            if (i == 0) {
                g2d.setColor(Color.RED);
                g2d.fillOval(x - 7, y - 7, 14, 14);
                g2d.setColor(Color.BLACK);
            } else {
                g2d.fillOval(x - 5, y - 5, 10, 10);
            }
        }

        if (pond.npoints > 1) {
            g2d.drawPolygon(pond);
        }
    }

    public double calculateArea(int[] xPoints, int[] yPoints, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += (xPoints[i] * yPoints[j]);
            sum -= (xPoints[j] * yPoints[i]);
        }
        return Math.abs(sum) / 2.0;
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("BCD Polygon Window");

        JLabel label1 = new JLabel("<html>Draw a closed polygon where start is the dock.</html>", SwingConstants.LEFT);
        JLabel label2 = new JLabel("Area of polygon: 0.0", SwingConstants.RIGHT);

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
    }
}