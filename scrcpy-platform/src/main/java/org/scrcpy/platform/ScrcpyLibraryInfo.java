package org.scrcpy.platform;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        value = @Platform(
                preloadpath = {},
                cinclude = {"coords.h", "config.h", "util/tick.h", "scrcpy.h",
                        // ability to push events, we only need control_msg struct
                        "android/input.h", "android/keycodes.h", "control_msg.h"
                },
                preload = {},
                link = {"scrcpy"}
        ),
        target = "org.scrcpy.platform.ScrcpyLibrary"
)
//@Namespace("C")
public class ScrcpyLibraryInfo implements InfoMapper {
    @Override
    public void map(InfoMap infoMap) {
        // just ignore this #define
        infoMap.put(new Info("SCRCPY_OPTIONS_DEFAULT").skip());
        infoMap.put(new Info("PRItick").skip());
    }
}
