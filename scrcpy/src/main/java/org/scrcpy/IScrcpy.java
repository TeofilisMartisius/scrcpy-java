package org.scrcpy;

import org.scrcpy.platform.ScrcpyLibrary;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import static org.scrcpy.platform.ScrcpyLibrary.*;

public interface IScrcpy {
    static ScrcpyLibrary.scrcpy_options defaultOptions() {
        // TODO: Define default options in Java, parsing scrcpy.h default options fails.
        ScrcpyLibrary.scrcpy_options opt = new ScrcpyLibrary.scrcpy_options();
        opt.log_level(SC_LOG_LEVEL_INFO);
        opt.record_format(SC_RECORD_FORMAT_AUTO);

        ScrcpyLibrary.sc_port_range port_range = new ScrcpyLibrary.sc_port_range();
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

    boolean start();

    void stop();

    Dimension originalSize();

    void registerScreenListener(Dimension size, Consumer<BufferedImage> onScreenRefresh);

    void mouseDown(Point p, int buttons);

    void mouseUp(Point p, int buttons);
}
