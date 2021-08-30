package org.scrcpy;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.Pointer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;
import static org.scrcpy.ScrcpyLibrary.*;

public class ScrcpyInvoke {

    public static ScrcpyLibrary.scrcpy_options defaultOptions() {
        // TODO: Define default options in Java, parsing scrcpy.h default options fails.
        ScrcpyLibrary.scrcpy_options opt = new ScrcpyLibrary.scrcpy_options();
        opt.log_level(SC_LOG_LEVEL_INFO);
        opt.record_format(SC_RECORD_FORMAT_AUTO);

        sc_port_range port_range = new sc_port_range();
        port_range.first((short) DEFAULT_LOCAL_PORT_RANGE_FIRST);
        port_range.last((short) DEFAULT_LOCAL_PORT_RANGE_LAST);
        opt.port_range(port_range);

        sc_shortcut_mods shortcut_mods = new sc_shortcut_mods(2);
        shortcut_mods.data(0, SC_MOD_LSUPER);
        shortcut_mods.data(1, SC_MOD_LSUPER);
        shortcut_mods.count(2);
        opt.shortcut_mods(shortcut_mods);

        opt.max_size((short) 0);
        opt.bit_rate(DEFAULT_BIT_RATE);
        opt.max_fps((short) 0);
        opt.lock_video_orientation(SC_LOCK_VIDEO_ORIENTATION_UNLOCKED);
        opt.rotation((byte) 0);
        opt.window_x((short) SC_WINDOW_POSITION_UNDEFINED);
        opt.window_y((short) SC_WINDOW_POSITION_UNDEFINED);
        opt.window_width((short) 0);
        opt.window_height((short) 0);
        opt.display_id(0);
        opt.show_touches(false);
        opt.fullscreen(false);
        opt.always_on_top(false);
        opt.control(true);
        opt.display(true);
        opt.turn_screen_off(false);
        opt.prefer_text(false);
        opt.window_borderless(false);
        opt.mipmaps(true);
        opt.stay_awake(false);
        opt.force_adb_forward(false);
        opt.disable_screensaver(false);
        opt.forward_key_repeat(true);
        opt.forward_all_clicks(false);
        opt.legacy_paste(false);
        opt.power_off_on_close(false);
        // changes from default
        opt.force_decoder(true);
        opt.display(false);
        return opt;
    }

    public static Rectangle getDisplayedImageDimensions(JFrame jFrame, ScrcpyLibrary.size androidSize) {
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

        Rectangle dimImage = new Rectangle();
        if (bounds.width >= androidSize.width() && bounds.height >= androidSize.height()) {
            // everything fits
            dimImage.width = androidSize.width();
            dimImage.height = androidSize.height();
        } else {
            // we need to shrink the image
            double widthRatio = (double) androidSize.width() / bounds.width;
            double heightRatio = (double) androidSize.height() / bounds.height;

            double ratio = Math.max(widthRatio, heightRatio);
            dimImage.width = (int) (androidSize.width() / ratio);
            dimImage.height = (int) (androidSize.height() / ratio);
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

        ScrcpyLibrary.scrcpy_options opt = defaultOptions();
        scrcpy_process process = ScrcpyLibrary.scrcpy_start(opt);
        System.out.println("process=" + process);
        System.out.println("process size w=" + process.frame_size().width() + " h=" + process.frame_size().height());
        if (process.isNull()) {
            System.out.println("FAIL");
            System.exit(0);
        }

        jFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.out.println("Stopping scrcpy");
                ScrcpyLibrary.scrcpy_stop(process);
                System.out.println("Stopped scrcpy, exiting");
                System.exit(0);
            }
        });

        Rectangle dimImage = getDisplayedImageDimensions(jFrame, process.frame_size());

        Open_Pointer open_function = new Open_Pointer() {
            @Override
            public boolean call(Pointer sink) {
                System.out.println("OPEN CALL");
                return true;
            }
        };
        Close_Pointer close_function = new Close_Pointer() {
            @Override
            public void call(Pointer sink) {
                System.out.println("CLOSE CALL");
            }
        };

        AtomicBoolean first = new AtomicBoolean(true);

        BufferedImage img = new BufferedImage(dimImage.width,
                dimImage.height, BufferedImage.TYPE_3BYTE_BGR);

        AVFrame rgbFrame = av_frame_alloc();
        rgbFrame.format(AV_PIX_FMT_BGR24);
        rgbFrame.width(dimImage.width);
        rgbFrame.height(dimImage.height);
        // align must be 1 for this to work, as Java aligns to 1
        int ret = av_image_alloc(rgbFrame.data(),
                rgbFrame.linesize(),
                rgbFrame.width(),
                rgbFrame.height(),
                rgbFrame.format(),
                1);
        if (ret < 0) {
            throw new RuntimeException("could not allocate buffer!");
        }
        // Use this to scale inside ffmpeg. Faster than doing it in Java
        SwsContext sws_ctx = sws_getContext(
                process.frame_size().width(), process.frame_size().height(), AV_PIX_FMT_YUV420P,
                rgbFrame.width(), rgbFrame.height(), rgbFrame.format(),
                0, null, null, (DoublePointer) null);

        Push_Pointer_Pointer frame_function = new Push_Pointer_Pointer() {
            @Override
            public boolean call(Pointer sink, Pointer avframe_ptr) {
                AVFrame yuv420Frame = new AVFrame(avframe_ptr);
                sws_scale(sws_ctx, yuv420Frame.data(), yuv420Frame.linesize(), 0,
                        yuv420Frame.height(), rgbFrame.data(), rgbFrame.linesize());
                DataBufferByte buffer = (DataBufferByte) img.getRaster().getDataBuffer();
                rgbFrame.data(0).get(buffer.getData());
                jLabel.setIcon(new ImageIcon(img));
                if (first.get()) {
                    jFrame.pack();
                    jFrame.setVisible(true);
                    first.set(false);
                }
                return true;
            }
        };

        scrcpy_add_sink(process, open_function, close_function, frame_function);

        jLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
//                System.out.println("DOWN x="+e.getX() +" y="+e.getY()+" b="+e.getButton());
                control_msg msg = new control_msg();
                msg.type(CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT);

                msg.inject_touch_event_pointer_id(POINTER_ID_MOUSE);
                msg.inject_touch_event_position(translate(process, dimImage, e.getX(), e.getY()));
                // 1.0 => down
                // 0.0 => up
                msg.inject_touch_event_pressure(1.0f);
                msg.inject_touch_event_buttons(convert_buttons(e.getButton()));

                scrcpy_push_event(process, msg);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
//                System.out.println("UP x="+e.getX() +" y="+e.getY()+" b="+e.getButton());
                control_msg msg = new control_msg();
                msg.type(CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT);

                msg.inject_touch_event_pointer_id(POINTER_ID_MOUSE);
                msg.inject_touch_event_position(translate(process, dimImage, e.getX(), e.getY()));
                // 1.0 => down
                // 0.0 => up
                msg.inject_touch_event_pressure(0.0f);
                msg.inject_touch_event_buttons(convert_buttons(e.getButton()));

                scrcpy_push_event(process, msg);
            }
        });

        System.out.println("sleeping start");
        Thread.sleep(Long.MAX_VALUE);
    }

    public static int convert_buttons(int javaButton) {
        int androidButton = 0;
        if (javaButton == MouseEvent.BUTTON1) {
            androidButton |= AMOTION_EVENT_BUTTON_PRIMARY;
        }
        if (javaButton == MouseEvent.BUTTON2) {
            androidButton |= AMOTION_EVENT_BUTTON_SECONDARY;
        }
        if (javaButton == MouseEvent.BUTTON3) {
            androidButton |= AMOTION_EVENT_BUTTON_TERTIARY;
        }
        return androidButton;
    }

    public static _position translate(scrcpy_process p, Rectangle dimImage, int x, int y) {
        point point = new point();
        int tx = p.frame_size().width() * x / dimImage.width;
        int ty = p.frame_size().height() * y / dimImage.height;
        point.x(tx);
        point.y(ty);

        size sz = new size();
        sz.width(p.frame_size().width());
        sz.height(p.frame_size().height());

        _position pos = new _position();
        pos.point(point);
        pos.screen_size(sz);

        return pos;
    }
}
