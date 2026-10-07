package tests.java;

import com.weizhi.WeizhiEngine;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Engine smoke for catalog modules and the built-in zip module.
 * Office document scripts live in Agent1, not in this repository.
 */
public final class CatalogSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("weizhi-catalog-");
        Path scriptDir = Files.createTempDirectory("weizhi-catalog-scripts-");
        Files.writeString(scriptDir.resolve("leaf.js"), "export function ping(){ return 'pong'; }\n");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(root.toString());
            engine.setScriptFolder(scriptDir.toString());
            String imported = engine.runJs(
                    "import { ping } from 'leaf.js';\nexport default ping();\n",
                    5000);
            if (!"\"pong\"".equals(imported)) {
                throw new AssertionError("catalog import: " + imported);
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
        System.out.println("catalog + zip smoke OK");
    }
}
