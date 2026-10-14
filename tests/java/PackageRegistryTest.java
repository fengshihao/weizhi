package tests.java;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.weizhi.WeizhiEngine;
import com.weizhi.packages.PackageInstaller;
import com.weizhi.packages.PackageRegistryClient;
import com.weizhi.packages.PackageRegistryClient.EnsureOptions;
import com.weizhi.packages.PackageRegistryClient.EnsureResult;
import com.weizhi.packages.PackageRegistryClient.FetchOptions;
import com.weizhi.packages.PackageRegistryClient.RegistryManifest;
import com.weizhi.packages.PackageRegistryClient.RegistryPackage;
import com.weizhi.platform.MiniJson;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Mock-HTTP coverage for {@link PackageRegistryClient}. */
public final class PackageRegistryTest {
    public static void main(String[] args) throws Exception {
        run();
    }

    static void run() throws Exception {
        defaultUrlIsOfficialHttps();
        ensureThenImport();
        sha256MismatchLeavesIndex();
        zipSlipLeavesIndex();
        versionMismatchLeavesIndex();
        unknownName();
        conditionalEtag();
        ensureTimeout();
        blocksNonAllowlistedHost();
        secondEnsureSkipsArtifact();
        singleFlightOneDownload();
        System.out.println("PackageRegistryTest OK");
    }

    private static void defaultUrlIsOfficialHttps() throws Exception {
        URL url = new URL(PackageRegistryClient.DEFAULT_REGISTRY_URL);
        check("https".equals(url.getProtocol()), url.toString());
        check(PackageRegistryClient.OFFICIAL_HOST.equals(url.getHost()), url.getHost());
    }

    private static void ensureThenImport() throws Exception {
        byte[] zip = packageZip("leaf", "1.0.0", "export function ping(){ return 'pong'; }\n", null);
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.artifact = zip;
            registry.manifestJson = manifestJson(registry.artifactUrl(), sha256(zip), zip.length);
            Path home = Files.createTempDirectory("weizhi-reg-ok-");
            EnsureResult result = PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"),
                    options(registry, home));
            check(result.installed.contains("leaf"), "installed");
            RegistryManifest manifest = PackageRegistryClient.fetchManifest(registry.registryUrl(),
                    fetchOptions(registry, home));
            RegistryPackage pkg = manifest.find("leaf");
            check(pkg != null, "find");
            check("Leaf".equals(pkg.capabilityTitle), pkg.capabilityTitle);
            check(pkg.capabilityEntry.contains("weizhi:leaf"), pkg.capabilityEntry);
            check("stable".equals(manifest.channel), manifest.channel);
            check(pkg.size == zip.length, "size");
            try (WeizhiEngine engine = new WeizhiEngine()) {
                engine.setPackageRoot(home.toString());
                String out = engine.runJs("import { ping } from 'weizhi:leaf';\nexport default ping();\n", 5000);
                check("\"pong\"".equals(out), "import: " + out);
            }
        }
    }

    private static void sha256MismatchLeavesIndex() throws Exception {
        byte[] zip = packageZip("leaf", "1.0.0", "export default 1;\n", null);
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.artifact = zip;
            String bad = "0000000000000000000000000000000000000000000000000000000000000000";
            registry.manifestJson = manifestJson(registry.artifactUrl(), bad, zip.length);
            Path home = Files.createTempDirectory("weizhi-reg-sha-");
            seedIndex(home);
            String before = indexText(home);
            try {
                PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), options(registry, home));
                throw new AssertionError("expected sha256 mismatch");
            } catch (IOException e) {
                check(e.getMessage() != null && e.getMessage().contains("sha256 mismatch"), e.getMessage());
            }
            check(before.equals(indexText(home)), indexText(home));
            check(!Files.exists(home.resolve("packages").resolve("leaf@1.0.0")), "package dir");
        }
    }

    private static void zipSlipLeavesIndex() throws Exception {
        String marker = "weizhi-slip-" + System.nanoTime();
        byte[] zip = packageZip("leaf", "1.0.0", "export default 1;\n", "../" + marker + ".txt");
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.artifact = zip;
            registry.manifestJson = manifestJson(registry.artifactUrl(), sha256(zip), zip.length);
            Path home = Files.createTempDirectory("weizhi-reg-slip-");
            seedIndex(home);
            String before = indexText(home);
            try {
                PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), options(registry, home));
                throw new AssertionError("expected zip slip");
            } catch (IOException e) {
                String msg = e.getMessage();
                check(msg != null && msg.contains("zip slip") && msg.contains("path escape"), msg);
            }
            check(before.equals(indexText(home)), "index changed");
            Path leaked = Path.of(System.getProperty("java.io.tmpdir")).resolve(marker + ".txt");
            Path zipFile = home.resolve("bad.zip");
            Files.write(zipFile, zip);
            PackageInstaller.installPackageZip(home, zipFile);
            check(!Files.exists(leaked), "leaked " + leaked);
            check(!Files.exists(home.resolve("packages").resolve("leaf@1.0.0").resolve(marker + ".txt")), "inside");
            Files.deleteIfExists(leaked);
        }
    }

    private static void versionMismatchLeavesIndex() throws Exception {
        byte[] zip = packageZip("leaf", "9.9.9", "export default 1;\n", null);
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.artifact = zip;
            registry.manifestJson = manifestJson(registry.artifactUrl(), sha256(zip), zip.length);
            Path home = Files.createTempDirectory("weizhi-reg-ver-");
            seedIndex(home);
            String before = indexText(home);
            try {
                PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), options(registry, home));
                throw new AssertionError("expected manifest mismatch");
            } catch (IOException e) {
                check(e.getMessage() != null && e.getMessage().contains("package manifest does not match registry"),
                        e.getMessage());
            }
            check(before.equals(indexText(home)), indexText(home));
        }
    }

    private static void unknownName() throws Exception {
        byte[] zip = packageZip("leaf", "1.0.0", "export default 1;\n", null);
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.artifact = zip;
            registry.manifestJson = manifestJson(registry.artifactUrl(), sha256(zip), zip.length);
            Path home = Files.createTempDirectory("weizhi-reg-miss-");
            seedIndex(home);
            String before = indexText(home);
            try {
                PackageRegistryClient.ensureInstalled(home, Collections.singletonList("missing"),
                        options(registry, home));
                throw new AssertionError("expected missing name");
            } catch (IOException e) {
                check(e.getMessage() != null && e.getMessage().contains("package not in registry"), e.getMessage());
            }
            check(before.equals(indexText(home)), indexText(home));
            check(registry.artifactHits.get() == 0, "artifact hits " + registry.artifactHits.get());
        }
    }

    private static void conditionalEtag() throws Exception {
        byte[] zip = packageZip("leaf", "1.0.0", "export default 1;\n", null);
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.conditional = true;
            registry.artifact = zip;
            registry.manifestJson = manifestJson(registry.artifactUrl(), sha256(zip), zip.length);
            Path home = Files.createTempDirectory("weizhi-reg-etag-");
            FetchOptions fetch = fetchOptions(registry, home);
            RegistryManifest first = PackageRegistryClient.fetchManifest(registry.registryUrl(), fetch);
            check(first.find("leaf") != null, "first");
            check(registry.manifestHits.get() == 1, "hits " + registry.manifestHits.get());
            RegistryManifest second = PackageRegistryClient.fetchManifest(registry.registryUrl(), fetch);
            check("leaf".equals(second.find("leaf").name), "second");
            check(registry.manifestHits.get() == 2, "hits " + registry.manifestHits.get());
            check(registry.notModifiedHits.get() == 1, "304 " + registry.notModifiedHits.get());
            check("\"r1\"".equals(registry.lastIfNoneMatch.get()), registry.lastIfNoneMatch.get());
        }
    }

    private static void ensureTimeout() throws Exception {
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.manifestDelayMs = 3000;
            registry.manifestJson = "{\"schema_version\":1,\"packages\":[]}";
            Path home = Files.createTempDirectory("weizhi-reg-time-");
            EnsureOptions opt = options(registry, home);
            opt.timeoutMs = 400;
            long started = System.currentTimeMillis();
            try {
                PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), opt);
                throw new AssertionError("expected timeout");
            } catch (IOException e) {
                check(e.getMessage() != null && e.getMessage().contains("package ensure timeout"), e.getMessage());
            }
            check(System.currentTimeMillis() - started < 2500, "timeout too slow");
            check(!Files.exists(home.resolve("packages-index.json")), "index written");
        }
    }

    private static void blocksNonAllowlistedHost() throws Exception {
        Path home = Files.createTempDirectory("weizhi-reg-block-");
        EnsureOptions opt = new EnsureOptions();
        opt.registryUrl = "https://evil.example/registry.json";
        opt.timeoutMs = 2000;
        try {
            PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), opt);
            throw new AssertionError("expected block");
        } catch (IOException e) {
            String msg = e.getMessage();
            check(msg != null && msg.contains("fetch blocked") && msg.contains("evil.example"), msg);
        }

        byte[] zip = packageZip("leaf", "1.0.0", "export default 1;\n", null);
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.artifact = zip;
            registry.manifestJson = manifestJson("http://evil.example/leaf.zip", sha256(zip), zip.length);
            try {
                PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), options(registry, home));
                throw new AssertionError("expected artifact block");
            } catch (IOException e) {
                String msg = e.getMessage();
                check(msg != null && msg.contains("fetch blocked") && msg.contains("evil.example"), msg);
            }
            check(!Files.exists(home.resolve("packages-index.json")), "index");
        }
    }

    private static void secondEnsureSkipsArtifact() throws Exception {
        byte[] zip = packageZip("leaf", "1.0.0", "export default 1;\n", null);
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.artifact = zip;
            registry.manifestJson = manifestJson(registry.artifactUrl(), sha256(zip), zip.length);
            Path home = Files.createTempDirectory("weizhi-reg-fast-");
            EnsureOptions opt = options(registry, home);
            EnsureResult first = PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), opt);
            check(first.installed.contains("leaf"), "first");
            EnsureResult second = PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), opt);
            check(second.unchanged.contains("leaf"), "second");
            check(registry.artifactHits.get() == 1, "hits " + registry.artifactHits.get());
        }
    }

    private static void singleFlightOneDownload() throws Exception {
        byte[] zip = packageZip("leaf", "1.0.0", "export default 1;\n", null);
        try (LocalRegistry registry = new LocalRegistry()) {
            registry.artifactDelayMs = 400;
            registry.artifact = zip;
            registry.manifestJson = manifestJson(registry.artifactUrl(), sha256(zip), zip.length);
            Path home = Files.createTempDirectory("weizhi-reg-race-");
            EnsureOptions opt = options(registry, home);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<EnsureResult> a = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), opt);
                });
                Future<EnsureResult> b = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return PackageRegistryClient.ensureInstalled(home, Collections.singletonList("leaf"), opt);
                });
                ready.await();
                go.countDown();
                EnsureResult ra = a.get();
                EnsureResult rb = b.get();
                int installed = (ra.installed.contains("leaf") ? 1 : 0) + (rb.installed.contains("leaf") ? 1 : 0);
                int unchanged = (ra.unchanged.contains("leaf") ? 1 : 0) + (rb.unchanged.contains("leaf") ? 1 : 0);
                check(installed == 1 && unchanged == 1, "installed=" + installed + " unchanged=" + unchanged);
                check(registry.artifactHits.get() == 1, "hits " + registry.artifactHits.get());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static EnsureOptions options(LocalRegistry registry, Path home) {
        EnsureOptions opt = new EnsureOptions();
        opt.registryUrl = registry.registryUrl();
        opt.allowedHosts = Collections.singletonList("127.0.0.1");
        opt.timeoutMs = 10_000;
        opt.cacheFile = home.resolve("registry-cache.json");
        return opt;
    }

    private static FetchOptions fetchOptions(LocalRegistry registry, Path home) {
        FetchOptions opt = new FetchOptions();
        opt.allowedHosts = Collections.singletonList("127.0.0.1");
        opt.timeoutMs = 10_000;
        opt.cacheFile = home.resolve("registry-cache.json");
        return opt;
    }

    private static void seedIndex(Path home) throws IOException {
        Files.createDirectories(home);
        Files.writeString(home.resolve("packages-index.json"), "{\"keep\":\"0.1.0\"}\n");
    }

    private static String indexText(Path home) throws IOException {
        Path index = home.resolve("packages-index.json");
        if (!Files.isRegularFile(index)) {
            return "";
        }
        return Files.readString(index);
    }

    private static String manifestJson(String artifactUrl, String sha, long size) {
        return "{"
                + "\"schema_version\":1,"
                + "\"channel\":\"stable\","
                + "\"packages\":[{"
                + "\"name\":\"leaf\","
                + "\"version\":\"1.0.0\","
                + "\"description\":\"demo\","
                + "\"min_engine\":\"0.1.0\","
                + "\"artifact\":{"
                + "\"url\":" + MiniJson.quote(artifactUrl) + ","
                + "\"sha256\":" + MiniJson.quote(sha) + ","
                + "\"size\":" + size
                + "},"
                + "\"capability\":{"
                + "\"title\":\"Leaf\","
                + "\"summary\":\"ping\","
                + "\"entry\":" + MiniJson.quote("import { ping } from \"weizhi:leaf\";")
                + "}}]}";
    }

    private static byte[] packageZip(String name, String version, String js, String slipName) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String manifest = "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"entry\":\"index.js\"}\n";
        entries.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("index.js", js.getBytes(StandardCharsets.UTF_8));
        if (slipName != null) {
            entries.put(slipName, "pwned".getBytes(StandardCharsets.UTF_8));
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static final class LocalRegistry implements AutoCloseable {
        final HttpServer server;
        final int port;
        final AtomicInteger manifestHits = new AtomicInteger();
        final AtomicInteger notModifiedHits = new AtomicInteger();
        final AtomicInteger artifactHits = new AtomicInteger();
        final AtomicReference<String> lastIfNoneMatch = new AtomicReference<>();
        volatile byte[] artifact = new byte[0];
        volatile String manifestJson = "{}";
        volatile long manifestDelayMs;
        volatile long artifactDelayMs;
        volatile boolean conditional;
        volatile String etag = "\"r1\"";

        LocalRegistry() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            server.setExecutor(command -> {
                Thread thread = new Thread(command, "weizhi-registry-test");
                thread.setDaemon(true);
                thread.start();
            });
            server.createContext("/registry/stable.json", this::serveManifest);
            server.createContext("/artifacts/leaf.zip", this::serveArtifact);
            server.start();
        }

        String registryUrl() {
            return "http://127.0.0.1:" + port + "/registry/stable.json";
        }

        String artifactUrl() {
            return "http://127.0.0.1:" + port + "/artifacts/leaf.zip";
        }

        private void serveManifest(HttpExchange exchange) throws IOException {
            manifestHits.incrementAndGet();
            sleep(manifestDelayMs);
            String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
            lastIfNoneMatch.set(inm);
            if (conditional && etag.equals(inm)) {
                notModifiedHits.incrementAndGet();
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] body = manifestJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }

        private void serveArtifact(HttpExchange exchange) throws IOException {
            artifactHits.incrementAndGet();
            sleep(artifactDelayMs);
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, artifact.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(artifact);
            }
        }

        private static void sleep(long ms) {
            if (ms <= 0) {
                return;
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
