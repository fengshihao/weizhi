package tests.java;

import com.weizhi.WeizhiEngine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Engine smoke for {@code weizhi:} packages and the built-in zip module. */
public final class PackageSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("weizhi-ws-");
        Path home = Files.createTempDirectory("weizhi-pkg-home-");
        Path pkgDir = home.resolve("packages").resolve("leaf@1.0.0");
        Files.createDirectories(pkgDir);
        Files.writeString(home.resolve("packages-index.json"), "{\"leaf\":\"1.0.0\"}\n");
        Files.writeString(pkgDir.resolve("manifest.json"),
                "{\"name\":\"leaf\",\"version\":\"1.0.0\",\"entry\":\"index.js\"}\n");
        Files.writeString(pkgDir.resolve("index.js"), "export function ping(){ return 'pong'; }\n");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(root.toString());
            engine.setPackageRoot(home.toString());
            String imported = engine.runJs(
                    "import { ping } from 'weizhi:leaf';\nexport default ping();\n",
                    5000);
            if (!"\"pong\"".equals(imported)) {
                throw new AssertionError("package import: " + imported);
            }
            String zipped = engine.runJs(
                    "const zip = require('zip');\n"
                            + "fs.mkdirSync('pack');\n"
                            + "fs.writeFileSync('pack/a.txt', 'hello');\n"
                            + "const created = zip.createSync('pack', 'out.zip');\n"
                            + "zip.extractSync('out.zip', 'unz');\n"
                            + "({ files: created.files, text: fs.readFileSync('unz/a.txt').toString() });\n",
                    8000);
            if (zipped == null || !zipped.contains("hello")) {
                throw new AssertionError("zip round-trip: " + zipped);
            }
        }
        System.out.println("package + zip smoke OK");
    }
}
