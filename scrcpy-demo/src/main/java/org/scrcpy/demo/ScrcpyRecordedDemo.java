package org.scrcpy.demo;

import org.scrcpy.IScrcpy;
import org.scrcpy.ScrcpyRecorded;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

// TODO: generalize and reuse with ScrcpyInvoke
public class ScrcpyRecordedDemo {

    public static Dimension getDisplayedImageDimensions(JFrame jFrame, Dimension androidSize) {
        // let's try to fit scrcpy image into screen
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle bounds = env.getMaximumWindowBounds();
        System.out.println("Screen Bounds: " + bounds);

        Insets insets = jFrame.getInsets();
        bounds.width = bounds.width - insets.left - insets.right - 10;
        bounds.height = bounds.height - insets.top - insets.bottom - 10;
        System.out.println("Screen Bounds Adjusted: " + bounds);
        ((BorderLayout) jFrame.getLayout()).setHgap(0);
        ((BorderLayout) jFrame.getLayout()).setVgap(0);

        Dimension dimImage = new Dimension();
        if (bounds.width >= androidSize.width && bounds.height >= androidSize.height) {
            // everything fits
            dimImage.width = androidSize.width;
            dimImage.height = androidSize.height;
        } else {
            // we need to shrink the image
            double widthRatio = (double) androidSize.width / bounds.width;
            double heightRatio = (double) androidSize.height / bounds.height;

            double ratio = Math.max(widthRatio, heightRatio);
            dimImage.width = (int) (androidSize.width / ratio);
            dimImage.height = (int) (androidSize.height / ratio);
        }
        return dimImage;
    }

    public static void main(String arg[]) throws Exception {
        JFrame jFrame = new JFrame();

        JLabel jLabel = new JLabel();
        jFrame.getContentPane().setLayout(new FlowLayout());
        jFrame.getContentPane().add(jLabel);
        jFrame.pack();
        jFrame.setVisible(true);

        URL url = ScrcpyRecordedDemo.class.getResource("/recorded.mkv");
        File f = new File(url.toURI());
        IScrcpy scrcpy = new ScrcpyRecorded(f.getAbsolutePath());

        jFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.out.println("Stopping scrcpy");
                scrcpy.stop();
                System.out.println("Stopped scrcpy, exiting");
                System.exit(0);
            }
        });

        Dimension dimImage = getDisplayedImageDimensions(jFrame, scrcpy.originalSize());

        AtomicBoolean first = new AtomicBoolean(true);

        scrcpy.registerScreenListener(dimImage, img -> {
            jLabel.setIcon(new ImageIcon(img));
            if (first.get()) {
                jFrame.pack();
                jFrame.setVisible(true);
                first.set(false);
            }
        });

        jLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                System.out.println("Mouse Pressed Java " + e.getX() + " " + e.getY() + " " + e.getButton());
                Point p = translate(scrcpy.originalSize(), dimImage, e.getX(), e.getY());
                scrcpy.mouseDown(p, e.getButton());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                System.out.println("Mouse Pressed Java " + e.getX() + " " + e.getY() + " " + e.getButton());
                Point p = translate(scrcpy.originalSize(), dimImage, e.getX(), e.getY());
                scrcpy.mouseUp(p, e.getButton());
            }
        });

        if (!scrcpy.start()) {
            System.out.println("FAIL");
            System.exit(0);
        }

        System.out.println("sleeping start");
        Thread.sleep(Long.MAX_VALUE);
    }

    public static Point translate(Dimension dimAndroid, Dimension dimJava, int x, int y) {
        int tx = dimAndroid.width * x / dimJava.width;
        int ty = dimAndroid.height * y / dimJava.height;
        Point point = new Point(tx, ty);
        return point;
    }
}
