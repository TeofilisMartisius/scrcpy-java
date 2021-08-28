package org.scrcpy;

import org.bytedeco.javacpp.annotation.Namespace;
import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        value = @Platform(
//                includepath = {"/home/teo/workspace47/scrcpy/app/src", "/home/teo/workspace47/scrcpy/builddir/app"},
                preloadpath = {},
//                linkpath = {"/home/teo/workspace47/scrcpy/builddir/app"},
                cinclude = {"coords.h", "config.h", "util/tick.h", "scrcpy.h"
                        // dependencies must be enumerated manually, screw that we'll never get all of them
//                        "server.h", "screen.h", "stream.h", "recorder.h", "decoder.h", "v4l2_sink.h"
//                        "SDL_stdinc.h"
                },
                preload = {},
                link = {"scrcpy"}
        ),
        target = "org.scrcpy.ScrcpyLibrary"
)
//@Namespace("C")
public class ScrcpyLibraryInfo implements InfoMapper {
    @Override
    public void map(InfoMap infoMap) {
        // just ignore this #define
//        infoMap.put(new Info("SCRCPY_OPTIONS_DEFAULT").skip());
        infoMap.put(new Info("SCRCPY_OPTIONS_DEFAULT").skip());
        infoMap.put(new Info("PRItick").skip());
//        infoMap.put(new Info("struct scrcpy").skip());
//        infoMap.put(new Info("server", "screen", "stream", "decoder", "recorder",
//                "sc_v4l2_sink", "controller", "file_handler", "input_manager").skip());
//        infoMap.put(new Info("struct scrcpy").pointerTypes("Pointer"));
//        infoMap.put(new Info("struct scrcpy").cppNames("struct scrcpy_embedded"));
//        infoMap.put(new Info("struct scrcpy").cppTypes("struct scrcpy_embedded"));
    }
}
