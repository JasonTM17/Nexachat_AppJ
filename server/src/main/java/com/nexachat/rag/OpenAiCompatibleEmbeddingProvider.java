package com.nexachat.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * External embedding provider for OpenAI-compatible {@code /embeddings} and
 * native Gemini {@code :embedContent} endpoints. The API key is only placed in
 * the request header; it is never logged, serialized or echoed in exceptions.
 */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    public static final String ID = "openai-compatible";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final String model;
    private final String apiKey;
    private final int dimension;
    private final boolean geminiMode;
    private final URI endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int timeoutMs;

    public OpenAiCompatibleEmbeddingProvider(
            String baseUrl,
            String model,
            String apiKey,
            int dimension,
            int timeoutMs,
            boolean geminiMode) {
        if (model == null || model.isBlank() || model.length() > 128) {
            throw new IllegalArgumentException("embedding model is invalid");
        }
        if (dimension < 8 || dimension > 1024) {
            throw new IllegalArgumentException("embedding dimension is out of range");
        }
        this.model = model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.dimension = dimension;
        this.geminiMode = geminiMode;
        this.timeoutMs = timeoutMs;
        this.endpoint = validateEndpoint(baseUrl, geminiMode, this.model);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(100, timeoutMs)))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Stable provider identifier scoped to the configured model, stored with
     * every vector so a model change never reuses an incompatible space.
     */
    @Override
    public String id() {
        return ID + ":" + model;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        if (text == null) {
            text = "";
        }
        byte[] body = requestBody(text);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(Math.max(100, timeoutMs)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (geminiMode) {
            request.header("x-goog-api-key", apiKey.isEmpty() ? "missing-key" : apiKey);
        } else {
            request.header("Authorization", "Bearer " + (apiKey.isEmpty() ? "missing-key" : apiKey));
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new EmbeddingProviderException("embedding endpoint returned HTTP " + response.statusCode());
            }
            return parse(objectMapper.readTree(readResponse(response.body())));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EmbeddingProviderException("embedding request failed", exception);
        }
    }

    private byte[] requestBody(String text) {
        try {
            var node = objectMapper.createObjectNode();
            if (geminiMode) {
                var content = node.putObject("content");
                var parts = content.putArray("parts");
                parts.addObject().put("text", text);
                node.put("outputDimensionality", dimension);
            } else {
                node.put("model", model);
                node.put("input", text);
                node.put("dimensions", dimension);
            }
            return objectMapper.writeValueAsBytes(node);
        } catch (IOException exception) {
            throw new EmbeddingProviderException("embedding request could not be encoded", exception);
        }
    }

    private float[] parse(JsonNode root) {
        if (root == null) {
            throw new EmbeddingProviderException("embedding response is empty");
        }
        JsonNode values = geminiMode ? root.path("embedding").path("values") : root.path("data").path(0).path("embedding");
        if (!values.isArray() || values.isEmpty()) {
            throw new EmbeddingProviderException("embedding response has no vector");
        }
        List<Float> numbers = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isNumber()) {
                throw new EmbeddingProviderException("embedding response contains a non-numeric value");
            }
            numbers.add(value.floatValue());
        }
        if (numbers.size() < dimension) {
            throw new EmbeddingProviderException("embedding response is shorter than the configured dimension");
        }
        float[] vector = new float[dimension];
        for (int index = 0; index < dimension; index++) {
            vector[index] = numbers.get(index);
        }
        return vector;
    }

    private static byte[] readResponse(InputStream body) throws IOException {
        if (body == null) {
            throw new EmbeddingProviderException("embedding response body is missing");
        }
        try (body) {
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new EmbeddingProviderException("embedding response exceeds its size limit");
            }
            return bytes;
        }
    }

    private static void closeQuietly(InputStream body) {
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // The response is discarded; its body is never logged or retained.
            }
        }
    }

    private static URI validateEndpoint(String baseUrl, boolean geminiMode, String model) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("embedding base URL is not configured");
        }
        try {
            URI base = new URI(baseUrl.trim());
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            String host = base.getHost();
            if (host == null || host.isBlank() || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("embedding base URL is invalid");
            }
            boolean https = scheme.equals("https");
            boolean loopbackHttp = scheme.equals("http") && isLoopback(host);
            if (!https && !loopbackHttp) {
                throw new IllegalArgumentException("embedding base URL must be HTTPS or loopback HTTP");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (geminiMode) {
                path += "/models/" + model + ":embedContent";
                return new URI(base.getScheme(), null, host, base.getPort(), path, null, null);
            }
            if (!path.endsWith("/embeddings")) {
                path += "/embeddings";
            }
            return new URI(base.getScheme(), null, host, base.getPort(), path, null, null);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("embedding base URL is invalid", exception);
        }
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
    }
}
