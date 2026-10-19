package com.weizhi.video;

import com.weizhi.WeizhiEngine;

/**
 * Installs the bundled Weizhi media plugins for a Weizhi engine:
 * <ul>
 *   <li>{@code video} ({@code libweizhivideo.so}) — probe / trim / concat remux,
 *       no re-encode (docs/video-architecture.md P1);</li>
 *   <li>{@code transcode} ({@code libweizhitranscode.so}) — re-encoding export:
 *       decoder → encoder surface tunnel → muxer, audio passthrough (P2).</li>
 * </ul>
 * After {@link #install}, scripts call {@code await host.ensureNative("video")}
 * / {@code ensureNative("transcode")}. The SOs ship in this module's AAR
 * (nativeLibraryDir), the supported dlopen location on Android 10+.
 */
public final class WeizhiVideo {
    private WeizhiVideo() {
    }

    public static void install(WeizhiEngine engine) {
        engine.registerBundledPlugin("video", "libweizhivideo.so");
        engine.registerBundledPlugin("transcode", "libweizhitranscode.so");
    }
}
