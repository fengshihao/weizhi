package tests.java;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.weizhi.WeizhiEngine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Desktop JNI: manual fetch redirects + allowlist on each hop. */
public final class FetchRedirectTest {

    static void run() throws Exception {
        redirectFollowsToFinalBody();
        redirectBlockedWhenTargetNotAllowlisted();
        System.out.println("Fetch redirect OK");
    }

    private static void redirectFollowsToFinalBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/start", exchange -> sendRedirect(exchange, "http://127.0.0.1:" + port + "/final"));
        server.createContext("/final", exchange -> sendText(exchange, 200, "redirect-ok"));
        server.start();
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.enableFetch(new String[] {"127.0.0.1"});
            String out = engine.runJs(
                    "const r = await fetch('http://127.0.0.1:" + port + "/start');"
                            + "({status:r.status, body: await r.text()})",
                    10_000);
            if (out == null || !out.contains("\"status\":200") || !out.contains("redirect-ok")) {
                throw new AssertionError("redirect follow: " + out);
            }
        } finally {
            server.stop(0);
        }
    }

    private static void redirectBlockedWhenTargetNotAllowlisted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/hop", exchange -> sendRedirect(exchange, "http://evil.example/final"));
        server.start();
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.enableFetch(new String[] {"127.0.0.1"});
            try {
                engine.runJs("await fetch('http://127.0.0.1:" + port + "/hop')", 10_000);
                throw new AssertionError("expected fetch blocked on redirect target");
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                if (msg == null || !msg.contains("fetch blocked") || !msg.contains("evil.example")) {
                    throw e;
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private static void sendRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
