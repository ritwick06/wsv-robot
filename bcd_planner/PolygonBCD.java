import java.util.ArrayList;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;


public class PolygonBCD extends JPanel{

    Polygon pond = new Polygon();
    JButton resetButton = new JButton("reset");


    JLabel areaLabel;

    public PolygonBCD(JLabel y){

        this.areaLabel = y;

        this.resetButton.addActionListener(
            new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearPolygon();
            }
        });

        this.addMouseListener(
            new MouseAdapter(){
            @Override
            public void mousePressed(MouseEvent e){
                int clickX = e.getX();
                int clickY = e.getY();
                pond.addPoint(clickX, clickY);
                repaint();
            }
        });
    }

    public void clearPolygon(){
        pond.reset();
        areaLabel.setText("Polygon area is 0.0");
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g){
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        double polyArea = calculateArea(pond.xpoints, pond.ypoints, pond.npoints);
        areaLabel.setText("Area of polygon: " + polyArea);

        for(int i = 0; i < pond.npoints; i++){
            int x = pond.xpoints[i];
            int y = pond.ypoints[i];

            g2d.fillOval(x - 5, y - 5, 10, 10);

        }
        if (pond.npoints > 1) {
            g2d.drawPolygon(pond); 
        }
    }

    public double calculateArea(int[] xPoints, int[] yPoints, int n){

        double sum = 0.0;
        for(int i = 0; i < pond.npoints; i++){

            int j = (i + 1) % n;
            sum += (xPoints[i] * yPoints[j]);
            sum -= (xPoints[j] * yPoints[i]);

        }

        return Math.abs(sum) / 2.0;
    }


    public static void main(String args[]){
        JFrame frame = new JFrame("BCD polygon window");

        JLabel label1 = new JLabel("<html>Draw a closed polygon where start is the dock.</html>", SwingConstants.LEFT);
        JLabel label2 = new JLabel("Polygon area is: 0.0", SwingConstants.RIGHT);

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