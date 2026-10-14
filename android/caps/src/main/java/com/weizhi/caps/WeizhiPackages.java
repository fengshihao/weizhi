package com.weizhi.caps;

import android.content.Context;
import android.content.res.AssetManager;

import com.weizhi.packages.PackageInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Sync built-in package trees from {@code assets/weizhi-packages/<name>@<version>/} into app-private
 * Weizhi home. Call before {@link com.weizhi.WeizhiEngine#setPackageRoot}.
 */
public final class WeizhiPackages {
    private static final String ASSET_ROOT = "weizhi-packages";

    private WeizhiPackages() {
    }

    /**
     * Install every {@code name@version} folder under assets into {@code filesDir/weizhi-home}.
     *
     * @return absolute path to pass to {@code setPackageRoot}
     */
    public static String syncBundled(Context context) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("context");
        }
        Path home = PackageInstaller.weizhiHome(context.getFilesDir().toPath());
        AssetManager assets = context.getAssets();
        String[] bundles = assets.list(ASSET_ROOT);
        if (bundles == null) {
            return home.toAbsolutePath().toString();
        }
        for (String bundleId : bundles) {
            if (bundleId == null || bundleId.isEmpty() || !bundleId.contains("@")) {
                continue;
            }
            int at = bundleId.lastIndexOf('@');
            if (at <= 0 || at >= bundleId.length() - 1) {
                continue;
            }
            String name = bundleId.substring(0, at);
            String version = bundleId.substring(at + 1);
            Path temp = java.nio.file.Files.createTempDirectory("weizhi-asset-pkg-");
            try {
                copyAssetTree(assets, ASSET_ROOT + "/" + bundleId, temp.toFile());
                PackageInstaller.installPackageDirectory(home, name, version, temp);
            } finally {
                deleteDir(temp.toFile());
            }
        }
        return home.toAbsolutePath().toString();
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File destDir) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            destDir.getParentFile().mkdirs();
            try (InputStream in = assets.open(assetPath);
                    OutputStream out = new FileOutputStream(destDir)) {
                in.transferTo(out);
            }
            return;
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("mkdir failed: " + destDir);
        }
        for (String child : children) {
            copyAssetTree(assets, assetPath + "/" + child, new File(destDir, child));
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    deleteDir(k);
                } else {
                    k.delete();
                }
            }
        }
        dir.delete();
    }
}
