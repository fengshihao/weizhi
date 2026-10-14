package tests.java;

import com.weizhi.WeizhiEngine;
import com.weizhi.packages.PackageInstaller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Host {@link PackageInstaller} + engine {@code weizhi:} wiring. */
public final class PackageInstallerTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("weizhi-pkg-install-");
        Path src = home.resolve("src-demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("manifest.json"),
                "{\"name\":\"leaf\",\"version\":\"2.0.0\",\"entry\":\"index.js\"}\n", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("index.js"), "export function ping(){ return 'pong'; }\n",
                StandardCharsets.UTF_8);
        PackageInstaller.installPackageDirectory(home, "leaf", "2.0.0", src);
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setPackageRoot(home.toString());
            String out = engine.runJs("import { ping } from 'weizhi:leaf';\nexport default ping();\n", 5000);
            if (!"\"pong\"".equals(out)) {
                throw new AssertionError("import: " + out);
            }
        }
        System.out.println("PackageInstallerTest OK");
    }
}
