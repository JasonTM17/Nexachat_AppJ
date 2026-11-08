package com.nexachat.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal OpenAI-compatible fake for tests. It records every request body
 * and Authorization header so tests can prove which provider was called and
 * that both providers received the same prompt.
 */
public final class FakeOpenAiServer implements AutoCloseable {

    public record Request(String body, String authorization) {
    }

    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange exchange, String body, String authorization) throws IOException;
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Handler handler = (exchange, body, authorization) ->
            respond(exchange, 200, jsonResponse("{\"ok\":true}"));

    public FakeOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(new Request(body, authorization));
            handler.handle(exchange, body, authorization);
        });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public void handle(Handler handler) {
        this.handler = handler;
    }

    public List<Request> requests() {
        return requests;
    }

    public int requestCount() {
        return requests.size();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public static String jsonResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":" + quote(content) + "}}]}";
    }

    public static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
