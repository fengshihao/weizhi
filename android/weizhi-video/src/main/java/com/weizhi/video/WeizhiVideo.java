package com.weizhi.video;

import com.weizhi.WeizhiEngine;

/**
 * Installs the bundled {@code weizhi_video} media plugin ({@code libweizhivideo.so})
 * for a Weizhi engine. After {@link #install}, scripts call
 * {@code await host.ensureNative("video")} and get the probe / open_job / run_job /
 * close_job remux surface (docs/video-architecture.md P1).
 *
 * <p>The SO ships in this module's AAR (nativeLibraryDir), which is the supported
 * dlopen location on Android 10+.
 */
public final class WeizhiVideo {
    private WeizhiVideo() {
    }

    public static void install(WeizhiEngine engine) {
        engine.registerBundledPlugin("video", "libweizhivideo.so");
    }
}
