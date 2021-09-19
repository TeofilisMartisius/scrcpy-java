package org.scrcpy;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.Pointer;
import org.scrcpy.platform.ScrcpyLibrary;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;
import static org.scrcpy.platform.ScrcpyLibrary.*;

/**
 * Real scrcpy implementation using scrcpy Native library.
 */
public class Scrcpy implements IScrcpy {
    private final ScrcpyLibrary.scrcpy_options options;
    private ScrcpyLibrary.scrcpy_process process = null;

    public Scrcpy(ScrcpyLibrary.scrcpy_options options) {
        this.options = options;

    }

    public boolean start() {
        this.process = ScrcpyLibrary.scrcpy_start(this.options);
        return process != null;
    }

    public void stop() {
        if (process == null) {
            return;
        }
        ScrcpyLibrary.scrcpy_stop(this.process);
    }

    @Override
    public Dimension originalSize() {
        if (process == null) {
            throw new IllegalStateException("Scrcpy Process is not started");
        }
        return new Dimension(process.frame_size().width(), process.frame_size().height());
    }


    // TODO: some way to unregister?
    private final Map<Consumer<BufferedImage>, ScreenListener> listeners = new HashMap<>();

    @Override
    public void registerScreenListener(Dimension size, Consumer<BufferedImage> onScreenRefresh) {
        ScreenListener listener = new ScreenListener(originalSize(), size, onScreenRefresh);
        listeners.put(onScreenRefresh, listener);
    }

    private static class DummyOpenFunction extends Open_Pointer {
        @Override
        public boolean call(Pointer sink) {
            return true;
        }
    }

    private static class DummyCloseFunction extends Close_Pointer {
        @Override
        public void call(Pointer sink) {
        }
    }

    private static DummyOpenFunction DUMMY_OPEN = new DummyOpenFunction();
    private static DummyCloseFunction DUMMY_CLOSE = new DummyCloseFunction();

    private class ScreenListener extends Push_Pointer_Pointer {
        final Dimension frameSize;
        final Dimension targetSize;
        final Consumer<BufferedImage> onScreenRefresh;
        final BufferedImage img;
        final AVFrame rgbFrame;
        final SwsContext swsContext;

        public ScreenListener(Dimension frameSize, Dimension targetSize, Consumer<BufferedImage> onScreenRefresh) {
            this.frameSize = frameSize;
            this.targetSize = targetSize;
            this.onScreenRefresh = onScreenRefresh;

            img = new BufferedImage(targetSize.width,
                    targetSize.height, BufferedImage.TYPE_3BYTE_BGR);

            rgbFrame = av_frame_alloc();
            rgbFrame.format(AV_PIX_FMT_BGR24);
            rgbFrame.width(targetSize.width);
            rgbFrame.height(targetSize.height);
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
            swsContext = swscale.sws_getContext(
                    frameSize.width, frameSize.height, AV_PIX_FMT_YUV420P,
                    rgbFrame.width(), rgbFrame.height(), rgbFrame.format(),
                    0, null, null, (DoublePointer) null);

            ScrcpyLibrary.scrcpy_add_sink(process, DUMMY_OPEN, DUMMY_CLOSE, this);
        }

        @Override
        public boolean call(Pointer sink, Pointer avframe_ptr) {
            AVFrame yuv420Frame = new AVFrame(avframe_ptr);
            sws_scale(swsContext, yuv420Frame.data(), yuv420Frame.linesize(), 0,
                    yuv420Frame.height(), rgbFrame.data(), rgbFrame.linesize());
            DataBufferByte buffer = (DataBufferByte) img.getRaster().getDataBuffer();
            rgbFrame.data(0).get(buffer.getData());

            onScreenRefresh.accept(img);
            return true;
        }
    }

    private _position toPosition(Point p) {
        point point = new point();
        point.x(p.x);
        point.y(p.y);

        size sz = new size();
        sz.width(process.frame_size().width());
        sz.height(process.frame_size().height());

        _position pos = new _position();
        pos.point(point);
        pos.screen_size(sz);

        return pos;
    }

    public static int convertButtons(int javaButton) {
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

    @Override
    public void mouseDown(Point p, int buttons) {
        control_msg msg = new control_msg();
        msg.type(CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT);

        msg.inject_touch_event_pointer_id(POINTER_ID_MOUSE);
        msg.inject_touch_event_position(toPosition(p));
        // 1.0 => down
        // 0.0 => up
        msg.inject_touch_event_pressure(1.0f);
        msg.inject_touch_event_buttons(convertButtons(buttons));
        msg.inject_touch_event_action(AMOTION_EVENT_ACTION_DOWN);
        System.out.format("Mouse Pressed Scrcpy action=%d %d %d  press=%.1f btn=%d\n", msg.inject_touch_event_action(), p.x, p.y, msg.inject_touch_event_pressure(), msg.inject_touch_event_buttons());
        scrcpy_push_event(process, msg);
    }

    @Override
    public void mouseUp(Point p, int buttons) {
        control_msg msg = new control_msg();
        msg.type(CONTROL_MSG_TYPE_INJECT_TOUCH_EVENT);

        msg.inject_touch_event_pointer_id(POINTER_ID_MOUSE);
        msg.inject_touch_event_position(toPosition(p));
        // 1.0 => down
        // 0.0 => up
        msg.inject_touch_event_pressure(0.0f);
        msg.inject_touch_event_buttons(convertButtons(buttons));
        msg.inject_keycode_action(AMOTION_EVENT_ACTION_UP);
        System.out.format("Mouse Up Scrcpy action=%d %d %d  press=%.1f btn=%d\n", msg.inject_touch_event_action(), p.x, p.y, msg.inject_touch_event_pressure(), msg.inject_touch_event_buttons());
        scrcpy_push_event(process, msg);
    }

}
