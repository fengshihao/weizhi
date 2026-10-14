package com.weizhi.packages;

import com.weizhi.platform.MiniJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Official package registry: HTTPS JSON manifest, sha256-checked zip, then
 * {@link PackageInstaller}. Scripts never fetch during {@code import}.
 * See docs/WEIZHI_PACKAGES.md §7.
 */
public final class PackageRegistryClient {
    /**
     * Stable-channel manifest. The host is a reserved {@code .example} name until the
     * official CDN hostname is published; change this constant (and {@link #OFFICIAL_HOST})
     * together. Production callers should leave {@code allowedHosts} null.
     */
    public static final String DEFAULT_REGISTRY_URL =
            "https://cdn.weizhi.example/packages/registry/stable.json";

    /** Default allowlisted registry and artifact host. Not an enterprise registry (D5). */
    public static final String OFFICIAL_HOST = "cdn.weizhi.example";

    /** Agent1 ensure budget: network + install, separate from {@code runJs}. */
    public static final long DEFAULT_ENSURE_TIMEOUT_MS = 180_000L;

    public static final long DEFAULT_MANIFEST_TIMEOUT_MS = 30_000L;

    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "WeizhiPackageRegistry/1";
    private static final String CACHE_FILE = "registry-cache.json";

    /** In-process single-flight. File locks cover other processes; same-JVM overlap throws. */
    private static final ConcurrentHashMap<String, Object> GATES = new ConcurrentHashMap<>();

    private PackageRegistryClient() {
    }

    /** One registry document (channel manifest), not a package's own {@code manifest.json}. */
    public static final class RegistryManifest {
        public final int schemaVersion;
        public final String channel;
        private final Map<String, RegistryPackage> byName;
        public final List<RegistryPackage> packages;

        RegistryManifest(int schemaVersion, String channel, List<RegistryPackage> packages) {
            this.schemaVersion = schemaVersion;
            this.channel = channel == null ? "" : channel;
            Map<String, RegistryPackage> map = new LinkedHashMap<>();
            for (RegistryPackage pkg : packages) {
                map.put(pkg.name, pkg);
            }
            this.byName = map;
            this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
        }

        /** @return the package, or null when {@code name} is absent */
        public RegistryPackage find(String name) {
            return byName.get(name);
        }
    }

    /** One package row in the registry manifest. */
    public static final class RegistryPackage {
        public final String name;
        public final String version;
        public final String description;
        public final String minEngine;
        public final String artifactUrl;
        public final String sha256;
        /** Declared zip size, or {@code 0} when the manifest omits it. */
        public final long size;
        public final String capabilityTitle;
        public final String capabilitySummary;
        /** Example import text for find_caps. Not executed by the engine. */
        public final String capabilityEntry;

        RegistryPackage(String name, String version, String description, String minEngine, String artifactUrl,
                String sha256, long size, String capabilityTitle, String capabilitySummary, String capabilityEntry) {
            this.name = name;
            this.version = version;
            this.description = description;
            this.minEngine = minEngine;
            this.artifactUrl = artifactUrl;
            this.sha256 = sha256;
            this.size = size;
            this.capabilityTitle = capabilityTitle;
            this.capabilitySummary = capabilitySummary;
            this.capabilityEntry = capabilityEntry;
        }
    }

    /** Options for {@link #fetchManifest}. Null fields use defaults. */
    public static final class FetchOptions {
        public long timeoutMs = DEFAULT_MANIFEST_TIMEOUT_MS;
        /** When null, no disk cache and no {@code If-None-Match}. */
        public Path cacheFile;
        /**
         * Exact hosts (no suffix match). Null means {@link #OFFICIAL_HOST} only.
         * An empty list allows nothing. Use this for official mirrors and tests, not a private registry.
         */
        public List<String> allowedHosts;
    }

    /** Options for {@link #ensureInstalled}. */
    public static final class EnsureOptions {
        public String registryUrl = DEFAULT_REGISTRY_URL;
        /** Independent of {@code runJs}. Already-installed names return without using this budget. */
        public long timeoutMs = DEFAULT_ENSURE_TIMEOUT_MS;
        /** When null, {@code <weizhi-home>/registry-cache.json}. */
        public Path cacheFile;
        /** Same rules as {@link FetchOptions#allowedHosts}. */
        public List<String> allowedHosts;
        /**
         * When true (default), a different registry version replaces the local one (D7: registry is current).
         * When false, keep any installed copy and only fill names that are missing.
         */
        public boolean upgrade = true;
    }

    public static final class EnsureResult {
        /** Names installed or upgraded by this call, in request order. */
        public final List<String> installed;
        /** Names already satisfied, in request order. */
        public final List<String> unchanged;

        EnsureResult(List<String> installed, List<String> unchanged) {
            this.installed = Collections.unmodifiableList(installed);
            this.unchanged = Collections.unmodifiableList(unchanged);
        }
    }

    public static RegistryManifest fetchManifest(String registryUrl, FetchOptions options) throws IOException {
        if (options == null) {
            options = new FetchOptions();
        }
        if (options.timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs");
        }
        long deadline = System.currentTimeMillis() + options.timeoutMs;
        return fetchManifest(registryUrl, options.cacheFile, hosts(options.allowedHosts), deadline,
                "registry fetch timeout", "registry fetch cancelled");
    }

    /**
     * Install or upgrade {@code names} from the registry into {@code weizhiHome}.
     * Does not call {@code setPackageRoot}. A sha256 or zip failure leaves {@code packages-index.json}
     * unchanged for that name.
     */
    public static EnsureResult ensureInstalled(Path weizhiHome, Collection<String> names, EnsureOptions options)
            throws IOException {
        if (weizhiHome == null) {
            throw new IllegalArgumentException("weizhiHome");
        }
        if (names == null) {
            throw new IllegalArgumentException("names");
        }
        if (options == null) {
            options = new EnsureOptions();
        }
        if (options.timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs");
        }
        List<String> unique = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || !name.matches("[a-z0-9][a-z0-9._-]*")) {
                throw new IOException("bad argument: package name");
            }
            if (seen.add(name)) {
                unique.add(name);
            }
        }
        if (unique.isEmpty()) {
            return new EnsureResult(new ArrayList<>(), new ArrayList<>());
        }

        long deadline = System.currentTimeMillis() + options.timeoutMs;
        List<String> allowed = hosts(options.allowedHosts);
        Path cache = options.cacheFile != null ? options.cacheFile : weizhiHome.resolve(CACHE_FILE);
        String registryUrl = options.registryUrl == null || options.registryUrl.isEmpty()
                ? DEFAULT_REGISTRY_URL
                : options.registryUrl;
        RegistryManifest manifest = fetchManifest(registryUrl, cache, allowed, deadline,
                "package ensure timeout", "package ensure cancelled");

        List<RegistryPackage> plan = new ArrayList<>();
        for (String name : unique) {
            RegistryPackage pkg = manifest.find(name);
            if (pkg == null) {
                throw new IOException("package not in registry: \"" + name + "\"");
            }
            plan.add(pkg);
        }

        List<String> installed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        for (RegistryPackage pkg : plan) {
            Object gate = GATES.computeIfAbsent(gateKey(weizhiHome, pkg.name), k -> new Object());
            synchronized (gate) {
                try (LockHold hold = lockName(weizhiHome, pkg.name)) {
                    String have = PackageInstaller.readIndex(weizhiHome).get(pkg.name);
                    if (satisfied(weizhiHome, pkg.name, have, pkg.version, options.upgrade)) {
                        unchanged.add(pkg.name);
                        continue;
                    }
                    installOne(weizhiHome, pkg, allowed, deadline);
                    installed.add(pkg.name);
                }
            }
        }
        return new EnsureResult(installed, unchanged);
    }

    /** Parse a registry body. Does not check URL allowlists. */
    public static RegistryManifest parseManifest(String json) throws IOException {
        if (json == null || json.isEmpty()) {
            throw new IOException("registry manifest missing packages");
        }
        final Map<String, Object> root;
        try {
            root = MiniJson.object(json);
        } catch (RuntimeException e) {
            throw new IOException("bad argument: registry manifest", e);
        }
        if (!root.containsKey("schema_version") || MiniJson.intVal(root, "schema_version") != 1) {
            throw new IOException("unsupported: registry schema");
        }
        Object rawPackages = root.get("packages");
        if (!(rawPackages instanceof List)) {
            throw new IOException("registry manifest missing packages");
        }
        List<?> list = (List<?>) rawPackages;
        List<RegistryPackage> packages = new ArrayList<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map)) {
                throw new IOException("bad argument: registry package");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) item;
            RegistryPackage pkg = parsePackage(obj);
            if (!names.add(pkg.name)) {
                throw new IOException("registry manifest duplicate package");
            }
            packages.add(pkg);
        }
        return new RegistryManifest(1, MiniJson.str(root, "channel"), packages);
    }

    private static RegistryManifest fetchManifest(String registryUrl, Path cacheFile, List<String> allowed,
            long deadline, String timeoutMessage, String cancelMessage) throws IOException {
        if (registryUrl == null || registryUrl.isEmpty()) {
            throw new IllegalArgumentException("registryUrl");
        }
        checkUrl(registryUrl, allowed);
        String[] cached = readCache(cacheFile, registryUrl);
        String etag = cached == null ? null : cached[0];
        HttpBody response = httpGet(registryUrl, etag, allowed, deadline, timeoutMessage, cancelMessage,
                MAX_MANIFEST_BYTES, "application/json");
        String body;
        if (response.status == 304) {
            if (cached == null) {
                throw new IOException("registry cache miss");
            }
            body = cached[1];
        } else {
            body = new String(response.body, StandardCharsets.UTF_8);
        }
        RegistryManifest manifest = parseManifest(body);
        for (RegistryPackage pkg : manifest.packages) {
            checkUrl(pkg.artifactUrl, allowed);
        }
        if (response.status != 304 && response.etag != null && !response.etag.isEmpty() && cacheFile != null) {
            writeCache(cacheFile, registryUrl, response.etag, body);
        }
        return manifest;
    }

    private static void installOne(Path weizhiHome, RegistryPackage pkg, List<String> allowed, long deadline)
            throws IOException {
        checkDeadline(deadline, "package ensure timeout");
        if (pkg.size > MAX_ARTIFACT_BYTES) {
            throw new IOException("too large: package artifact");
        }
        Path zip = Files.createTempFile("weizhi-registry-", ".zip");
        try {
            MessageDigest digest = sha256();
            try (OutputStream out = new java.security.DigestOutputStream(Files.newOutputStream(zip), digest)) {
                httpGetTo(pkg.artifactUrl, allowed, deadline, "package ensure timeout", "package ensure cancelled",
                        MAX_ARTIFACT_BYTES, pkg.size, "application/octet-stream", out);
            }
            if (pkg.size > 0 && Files.size(zip) != pkg.size) {
                throw new IOException("size mismatch");
            }
            String got = hex(digest.digest());
            if (!got.equalsIgnoreCase(pkg.sha256)) {
                throw new IOException("sha256 mismatch");
            }
            verifyZip(zip, pkg);
            try {
                PackageInstaller.installPackageZip(weizhiHome, zip);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage() == null ? "invalid package" : e.getMessage(), e);
            }
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static RegistryPackage parsePackage(Map<String, Object> obj) throws IOException {
        String name = MiniJson.str(obj, "name");
        String version = MiniJson.str(obj, "version");
        if (!name.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IOException("bad argument: package name");
        }
        if (!version.matches("[0-9A-Za-z][0-9A-Za-z._+-]*")) {
            throw new IOException("invalid package version");
        }
        Map<String, Object> artifact = mapField(obj, "artifact");
        if (artifact == null) {
            throw new IOException("registry manifest missing artifact");
        }
        String url = MiniJson.str(artifact, "url");
        String sha = MiniJson.str(artifact, "sha256");
        if (url.isEmpty()) {
            throw new IOException("bad argument: artifact.url");
        }
        if (!sha.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("bad argument: artifact.sha256");
        }
        long size = 0L;
        if (artifact.containsKey("size") && artifact.get("size") != null) {
            try {
                size = MiniJson.longVal(artifact, "size");
            } catch (RuntimeException e) {
                throw new IOException("bad argument: artifact.size", e);
            }
            if (size < 0) {
                throw new IOException("bad argument: artifact.size");
            }
        }
        String title = "";
        String summary = "";
        String entry = "";
        Map<String, Object> capability = mapField(obj, "capability");
        if (capability != null) {
            title = MiniJson.str(capability, "title");
            summary = MiniJson.str(capability, "summary");
            entry = MiniJson.str(capability, "entry");
        }
        return new RegistryPackage(name, version, MiniJson.str(obj, "description"), MiniJson.str(obj, "min_engine"),
                url, sha.toLowerCase(), size, title, summary, entry);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> obj, String key) throws IOException {
        Object value = obj.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map)) {
            throw new IOException("bad argument: " + key);
        }
        return (Map<String, Object>) value;
    }

    private static boolean satisfied(Path home, String name, String have, String want, boolean upgrade)
            throws IOException {
        if (have == null || have.isEmpty()) {
            return false;
        }
        if (upgrade && !have.equals(want)) {
            return false;
        }
        Path manifest = home.resolve("packages").resolve(name + "@" + have).resolve("manifest.json");
        return Files.isRegularFile(manifest);
    }

    private static void verifyZip(Path zip, RegistryPackage expected) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            int count = 0;
            ZipEntry manifestEntry = null;
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ZIP_ENTRIES) {
                    throw new IOException("too many zip entries");
                }
                String raw = entry.getName();
                if (raw.indexOf('\\') >= 0 || raw.startsWith("/") || raw.contains("..")) {
                    throw new IOException("zip slip: path escape");
                }
                if ("manifest.json".equals(raw) && !entry.isDirectory()) {
                    manifestEntry = entry;
                }
            }
            if (manifestEntry == null) {
                throw new IOException("package zip missing manifest.json");
            }
            String json = readZipEntry(archive, manifestEntry, MAX_MANIFEST_BYTES);
            Map<String, Object> root;
            try {
                root = MiniJson.object(json);
            } catch (RuntimeException e) {
                throw new IOException("package zip missing manifest.json", e);
            }
            String name = MiniJson.str(root, "name");
            String version = MiniJson.str(root, "version");
            if (!expected.name.equals(name) || !expected.version.equals(version)) {
                throw new IOException("package manifest does not match registry");
            }
        }
    }

    private static String readZipEntry(ZipFile archive, ZipEntry entry, int max) throws IOException {
        try (InputStream in = archive.getInputStream(entry)) {
            byte[] buf = new byte[4096];
            int total = 0;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (total > max - n) {
                    throw new IOException("too large: registry manifest");
                }
                bos.write(buf, 0, n);
                total += n;
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class HttpBody {
        final int status;
        final String etag;
        final byte[] body;

        HttpBody(int status, String etag, byte[] body) {
            this.status = status;
            this.etag = etag;
            this.body = body;
        }
    }

    private static HttpBody httpGet(String url, String ifNoneMatch, List<String> allowed, long deadline,
            String timeoutMessage, String cancelMessage, int maxBytes, String accept) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        HttpMeta meta = httpGetTo(url, ifNoneMatch, allowed, deadline, timeoutMessage, cancelMessage, maxBytes, 0L,
                accept, bos, true);
        if (meta.status == 304) {
            return new HttpBody(304, meta.etag, null);
        }
        return new HttpBody(meta.status, meta.etag, bos.toByteArray());
    }

    private static void httpGetTo(String url, List<String> allowed, long deadline, String timeoutMessage,
            String cancelMessage, int maxBytes, long expectedSize, String accept, OutputStream sink)
            throws IOException {
        HttpMeta meta = httpGetTo(url, null, allowed, deadline, timeoutMessage, cancelMessage, maxBytes, expectedSize,
                accept, sink, false);
        if (meta.status != 200) {
            throw new IOException("registry HTTP " + meta.status);
        }
    }

    private static final class HttpMeta {
        final int status;
        final String etag;

        HttpMeta(int status, String etag) {
            this.status = status;
            this.etag = etag;
        }
    }

    private static HttpMeta httpGetTo(String urlString, String ifNoneMatch, List<String> allowed, long deadline,
            String timeoutMessage, String cancelMessage, int maxBytes, long expectedSize, String accept,
            OutputStream sink, boolean keep304) throws IOException {
        checkUrl(urlString, allowed);
        URL current = new URL(urlString);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkInterrupt(cancelMessage);
            checkDeadline(deadline, timeoutMessage);
            checkUrl(current.toString(), allowed);
            int budget = timeoutBudget(deadline, timeoutMessage);
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setUseCaches(false);
                conn.setConnectTimeout(budget);
                conn.setReadTimeout(budget);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", accept);
                if (ifNoneMatch != null && hop == 0) {
                    conn.setRequestProperty("If-None-Match", ifNoneMatch);
                }
                int status;
                try {
                    status = conn.getResponseCode();
                } catch (SocketTimeoutException e) {
                    throw new IOException(timeoutMessage, e);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("registry HTTP " + status);
                    }
                    current = new URL(current, location);
                    continue;
                }
                String etag = conn.getHeaderField("ETag");
                if (status == 304 && keep304) {
                    return new HttpMeta(304, etag);
                }
                if (status != 200) {
                    throw new IOException("registry HTTP " + status);
                }
                int declared = conn.getContentLength();
                if (declared > maxBytes || (expectedSize > 0 && declared > expectedSize)) {
                    throw new IOException(maxBytes == MAX_MANIFEST_BYTES
                            ? "too large: registry manifest"
                            : "too large: package artifact");
                }
                try (InputStream in = conn.getInputStream()) {
                    copyLimited(in, sink, deadline, timeoutMessage, cancelMessage, maxBytes, expectedSize);
                } catch (SocketTimeoutException e) {
                    throw new IOException(timeoutMessage, e);
                }
                return new HttpMeta(200, etag);
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("registry HTTP redirect");
    }

    private static void copyLimited(InputStream in, OutputStream out, long deadline, String timeoutMessage,
            String cancelMessage, int maxBytes, long expectedSize) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) >= 0) {
            checkInterrupt(cancelMessage);
            checkDeadline(deadline, timeoutMessage);
            if (total > maxBytes - n || (expectedSize > 0 && total > expectedSize - n)) {
                throw new IOException(maxBytes == MAX_MANIFEST_BYTES
                        ? "too large: registry manifest"
                        : "too large: package artifact");
            }
            out.write(buf, 0, n);
            total += n;
        }
        if (expectedSize > 0 && total != expectedSize) {
            throw new IOException("size mismatch");
        }
    }

    private static void checkUrl(String urlString, List<String> allowed) throws IOException {
        final URL url;
        try {
            url = new URL(urlString);
        } catch (IOException e) {
            throw new IOException("fetch blocked: host \"\" is not allowlisted", e);
        }
        String protocol = url.getProtocol();
        String host = url.getHost() == null ? "" : url.getHost();
        boolean http = "http".equalsIgnoreCase(protocol);
        boolean https = "https".equalsIgnoreCase(protocol);
        if ((!http && !https) || url.getUserInfo() != null || !hostAllowed(host, allowed)
                || (http && !isLoopback(host))) {
            throw new IOException("fetch blocked: host \"" + host + "\" is not allowlisted");
        }
    }

    private static boolean hostAllowed(String host, List<String> allowed) {
        for (String item : allowed) {
            if (item != null && item.equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static List<String> hosts(List<String> allowed) {
        if (allowed == null) {
            return Collections.singletonList(OFFICIAL_HOST);
        }
        return allowed;
    }

    private static void checkDeadline(long deadline, String timeoutMessage) throws IOException {
        if (System.currentTimeMillis() > deadline) {
            throw new IOException(timeoutMessage);
        }
    }

    private static void checkInterrupt(String cancelMessage) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException(cancelMessage);
        }
    }

    private static int timeoutBudget(long deadline, String timeoutMessage) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            throw new IOException(timeoutMessage);
        }
        if (left > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) left;
    }

    private static String[] readCache(Path file, String url) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, Object> obj = MiniJson.object(Files.readString(file, StandardCharsets.UTF_8));
            if (!url.equals(MiniJson.str(obj, "url"))) {
                return null;
            }
            String etag = MiniJson.str(obj, "etag");
            String body = MiniJson.str(obj, "body");
            if (etag.isEmpty() || body.isEmpty()) {
                return null;
            }
            return new String[] {etag, body};
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private static void writeCache(Path file, String url, String etag, String body) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        } else {
            parent = Path.of(".");
        }
        String json = "{\"url\":" + MiniJson.quote(url) + ",\"etag\":" + MiniJson.quote(etag) + ",\"body\":"
                + MiniJson.quote(body) + "}";
        Path tmp = Files.createTempFile(parent, ".registry-cache-", ".tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String gateKey(Path home, String name) {
        return home.toAbsolutePath().normalize() + "\0" + name;
    }

    private static LockHold lockName(Path home, String name) throws IOException {
        Path lockFile = home.resolve("packages").resolve(".locks").resolve(name + ".lock");
        Files.createDirectories(lockFile.getParent());
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            return new LockHold(channel, lock);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    private static final class LockHold implements AutoCloseable {
        final FileChannel channel;
        final FileLock lock;

        LockHold(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.close();
            } finally {
                channel.close();
            }
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("sha256 mismatch", e);
        }
    }

    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(out);
    }
}
