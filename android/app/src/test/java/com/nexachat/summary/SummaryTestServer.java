package com.nexachat.summary;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal loopback-only HTTP fake that is available on the Android test boot classpath. */
final class SummaryTestServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread acceptThread;
    private final HttpHandler handler;

    SummaryTestServer(HttpHandler handler) throws IOException {
        this.handler = handler;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        executor = Executors.newCachedThreadPool();
        acceptThread = new Thread(this::acceptConnections, "summary-test-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/";
    }

    static String requestBody(HttpExchange exchange) {
        return new String(exchange.body, StandardCharsets.UTF_8);
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = statusText(status);
        OutputStream output = exchange.socket.getOutputStream();
        output.write(("HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.flush();
    }

    private static String statusText(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            default:
                return "Response";
        }
    }

    private void acceptConnections() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> serve(socket));
            } catch (IOException closed) {
                if (running.get()) {
                    throw new IllegalStateException("Local summary test server failed", closed);
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            InputStream input = connection.getInputStream();
            String requestLine = readLine(input);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ", 3);
            if (parts.length != 3) {
                return;
            }
            int contentLength = 0;
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if (separator > 0 && "content-length".equals(
                        line.substring(0, separator).trim().toLowerCase(Locale.ROOT))) {
                    contentLength = Integer.parseInt(line.substring(separator + 1).trim());
                }
            }
            byte[] body = readExactly(input, contentLength);
            handler.handle(new HttpExchange(connection, parts[0], URI.create(parts[1]), body));
        } catch (IOException ignored) {
            // A canceled Retrofit call closes its socket before the fake replies.
        } catch (RuntimeException ignored) {
            // Assertion failures and malformed test requests are surfaced by the client timeout.
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.write(value);
            }
        }
        if (value == -1 && line.size() == 0) {
            return null;
        }
        return line.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count < 0) {
                throw new IOException("Unexpected end of request body");
            }
            offset += count;
        }
        return body;
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
            acceptThread.join(1_000L);
        } catch (IOException ignored) {
            Thread.currentThread().interrupt();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
    }

    @FunctionalInterface
    interface HttpHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    static final class HttpExchange {
        private final Socket socket;
        private final String requestMethod;
        private final URI requestUri;
        private final byte[] body;

        private HttpExchange(Socket socket, String requestMethod, URI requestUri, byte[] body) {
            this.socket = socket;
            this.requestMethod = requestMethod;
            this.requestUri = requestUri;
            this.body = body;
        }

        String getRequestMethod() {
            return requestMethod;
        }

        URI getRequestURI() {
            return requestUri;
        }

        InputStream getRequestBody() {
            return new ByteArrayInputStream(body);
        }
    }
}
