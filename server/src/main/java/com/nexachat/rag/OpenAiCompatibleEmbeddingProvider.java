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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * External embedding provider for OpenAI-compatible {@code /embeddings} and
 * native Gemini {@code :embedContent} endpoints. The API key is only placed in
 * the request header; it is never logged, serialized or echoed in exceptions.
 */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    public static final String ID = "openai-compatible";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int DEFAULT_BATCH_SIZE = 32;
    private static final int MAX_BATCH_SIZE = 128;

    private final String model;
    private final String apiKey;
    private final int dimension;
    private final boolean geminiMode;
    private final URI endpoint;
    private final URI batchEndpoint;
    private final int batchSize;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int timeoutMs;
    private final AtomicBoolean batchShapeUnsupported = new AtomicBoolean();

    public OpenAiCompatibleEmbeddingProvider(
            String baseUrl,
            String model,
            String apiKey,
            int dimension,
            int timeoutMs,
            boolean geminiMode) {
        this(baseUrl, model, apiKey, dimension, timeoutMs, geminiMode, DEFAULT_BATCH_SIZE);
    }

    public OpenAiCompatibleEmbeddingProvider(
            String baseUrl,
            String model,
            String apiKey,
            int dimension,
            int timeoutMs,
            boolean geminiMode,
            int batchSize) {
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
        this.batchEndpoint = batchEndpoint(this.endpoint, geminiMode);
        this.batchSize = Math.max(1, Math.min(MAX_BATCH_SIZE, batchSize));
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
        return parse(execute(requestBody(text), endpoint));
    }

    /**
     * Uses the provider's native batch endpoint in bounded chunks. If a
     * deployment rejects the batch shape itself, fall back to the interface's
     * serial implementation for that chunk; transport and server failures
     * still fail the batch so the caller can degrade locally instead of
     * multiplying an outage across serial calls.
     */
    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (texts.size() == 1) {
            return List.of(embed(texts.get(0)));
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> chunk = texts.subList(start, Math.min(texts.size(), start + batchSize));
            if (batchShapeUnsupported.get()) {
                vectors.addAll(EmbeddingProvider.super.embedAll(chunk));
                continue;
            }
            try {
                vectors.addAll(embedBatch(chunk));
            } catch (EmbeddingProviderException exception) {
                if (!isBatchShapeUnsupported(exception)) {
                    throw exception;
                }
                batchShapeUnsupported.set(true);
                vectors.addAll(EmbeddingProvider.super.embedAll(chunk));
            }
        }
        return List.copyOf(vectors);
    }

    private List<float[]> embedBatch(List<String> texts) {
        return parseBatch(execute(requestBatchBody(texts), batchEndpoint), texts.size());
    }

    private static boolean isBatchShapeUnsupported(EmbeddingProviderException exception) {
        int status = exception.statusCode();
        return status == 400 || status == 404 || status == 405 || status == 422;
    }

    private JsonNode execute(byte[] body, URI target) {
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
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
                throw new EmbeddingProviderException(
                        "embedding endpoint returned HTTP " + response.statusCode(), response.statusCode());
            }
            return objectMapper.readTree(readResponse(response.body()));
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

    private byte[] requestBatchBody(List<String> texts) {
        try {
            var node = objectMapper.createObjectNode();
            if (geminiMode) {
                var requests = node.putArray("requests");
                for (String text : texts) {
                    var item = requests.addObject();
                    item.put("model", "models/" + model);
                    var content = item.putObject("content");
                    var parts = content.putArray("parts");
                    parts.addObject().put("text", text == null ? "" : text);
                    item.put("outputDimensionality", dimension);
                }
            } else {
                node.put("model", model);
                var input = node.putArray("input");
                for (String text : texts) {
                    input.add(text == null ? "" : text);
                }
                node.put("dimensions", dimension);
            }
            return objectMapper.writeValueAsBytes(node);
        } catch (IOException exception) {
            throw new EmbeddingProviderException("embedding batch request could not be encoded", exception);
        }
    }

    private float[] parse(JsonNode root) {
        if (root == null) {
            throw new EmbeddingProviderException("embedding response is empty");
        }
        JsonNode values = geminiMode ? root.path("embedding").path("values") : root.path("data").path(0).path("embedding");
        return parseValues(values);
    }

    private List<float[]> parseBatch(JsonNode root, int expectedCount) {
        if (root == null) {
            throw malformedBatch("embedding batch response is empty");
        }
        JsonNode rows = geminiMode ? root.path("embeddings") : root.path("data");
        if (!rows.isArray() || rows.size() != expectedCount) {
            throw malformedBatch("embedding batch response count does not match the request");
        }
        float[][] ordered = new float[expectedCount][];
        for (int position = 0; position < rows.size(); position++) {
            JsonNode row = rows.get(position);
            JsonNode values = geminiMode ? row.path("values") : row.path("embedding");
            int outputPosition = position;
            if (!geminiMode && row.path("index").isIntegralNumber() && row.path("index").canConvertToInt()) {
                outputPosition = row.path("index").intValue();
            }
            if (outputPosition < 0 || outputPosition >= expectedCount || ordered[outputPosition] != null) {
                throw malformedBatch("embedding batch response ordering is invalid");
            }
            ordered[outputPosition] = parseValues(values);
        }
        List<float[]> vectors = new ArrayList<>(expectedCount);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw malformedBatch("embedding batch response is missing a vector");
            }
            vectors.add(vector);
        }
        return List.copyOf(vectors);
    }

    private static EmbeddingProviderException malformedBatch(String message) {
        return new EmbeddingProviderException(message, 422);
    }

    private float[] parseValues(JsonNode values) {
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

    private static URI batchEndpoint(URI endpoint, boolean geminiMode) {
        if (!geminiMode) {
            return endpoint;
        }
        String value = endpoint.toString();
        String suffix = ":embedContent";
        if (!value.endsWith(suffix)) {
            throw new IllegalArgumentException("embedding batch endpoint is invalid");
        }
        return URI.create(value.substring(0, value.length() - suffix.length()) + ":batchEmbedContents");
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
