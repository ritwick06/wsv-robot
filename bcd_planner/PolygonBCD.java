import java.util.ArrayList;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;


public class PolygonBCD extends JPanel{

    Polygon pond = new Polygon();

    public PolygonBCD(){

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

    @Override
    protected void paintComponent(Graphics g){
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;

        for(int i = 0; i < pond.npoints; i++){
            int x = pond.xpoints[i];
            int y = pond.ypoints[i];

            g2d.fillOval(x - 5, y - 5, 10, 10);
        }
        if (pond.npoints > 1) {
            g2d.drawPolygon(pond); 
        }
    }

    public static void main(String args[]){
        JFrame frame = new JFrame("BCD polygon window");
        PolygonBCD panel = new PolygonBCD();

        JLabel label = new JLabel("Draw a closed polygon where start is the dock.", SwingConstants.LEFT);

        label.setHorizontalAlignment(SwingConstants.LEFT);
        label.setVerticalAlignment(SwingConstants.TOP);


        frame.add(panel, BorderLayout.CENTER);
        frame.add(label, BorderLayout.NORTH);
        frame.setSize(400, 400);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

    }

}