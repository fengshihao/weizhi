package com.weizhi.desktop;

import com.weizhi.WeizhiEngine;
import com.weizhi.platform.LocalWorkspace;
import com.weizhi.platform.OfficeScripts;
import com.weizhi.platform.PlatformHost;
import com.weizhi.platform.PlatformScripts;

import java.nio.file.Path;

/**
 * macOS / Linux productivity surface. Installs {@code mac} or {@code linux} only.
 * Photo resize, Android share sheet, and notification reminders are unsupported here.
 */
public final class DesktopCaps {
    private DesktopCaps() {
    }

    public static boolean isAndroidRuntime() {
        String vm = System.getProperty("java.vm.name", "").toLowerCase();
        String runtime = System.getProperty("java.runtime.name", "").toLowerCase();
        return vm.contains("dalvik") || vm.contains("art") || runtime.contains("android");
    }

    /** {@code mac}, {@code linux}, or null when this is not a desktop host. */
    public static String platformObjectName() {
        if (isAndroidRuntime()) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "mac";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return null;
    }

    public static void install(WeizhiEngine engine, Path workspace, PlatformHost.Confirmer confirmer) throws Exception {
        String platform = platformObjectName();
        if (platform == null) {
            throw new IllegalStateException("unsupported: desktop caps on this host");
        }
        LocalWorkspace files = new LocalWorkspace(workspace);
        engine.setFsRoot(workspace.toString());
        engine.setHostCall(new PlatformHost(files, platform, confirmer));
        engine.runJs(PlatformScripts.install(platform), 3000);
        engine.runJs(OfficeScripts.install(), 3000);
    }
}
