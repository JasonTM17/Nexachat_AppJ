package com.nexachat.auth.google;

import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A loopback JWKS endpoint for tests, mirroring Google's certificate path
 * ({@code /.well-known} is not used; the URI is injected as a property).
 */
public final class JwksServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> body;

    private JwksServer(HttpServer server, AtomicReference<String> body) {
        this.server = server;
        this.body = body;
    }

    public static JwksServer start(List<RSAKey> initialKeys) {
        try {
            AtomicReference<String> body = new AtomicReference<>(GoogleTestKeys.jwks(initialKeys));
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/certs", exchange -> {
                byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Cache-Control", "public, max-age=60");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
            return new JwksServer(server, body);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not start the test JWKS server", exception);
        }
    }

    public String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
    }

    /** Simulates Google rotating its signing keys. */
    public void publish(List<RSAKey> keys) {
        body.set(GoogleTestKeys.jwks(keys));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
