package com.nexachat.summary.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryProviderException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class LlmClient {

    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    public record TargetConfig(URI endpoint, String apiKey, String model, int timeoutMs, int maxOutputTokens) {
    }

    record LlmCompletion(String content, Integer promptTokens, Integer completionTokens) {
    }

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor executor;
    private final HttpClient httpClient;

    public LlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = new ThreadPoolExecutor(
                2,
                4,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                daemonThreads(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        int maxTimeout = Math.max(1, Math.max(properties.getTimeoutMs(), properties.getLocalTimeoutMs()));
        this.httpClient = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofMillis(maxTimeout))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public TargetConfig cloudTarget() {
        if (!properties.isUsable()) {
            return null;
        }
        return new TargetConfig(
                properties.endpoint(),
                properties.getApiKey(),
                properties.getModel(),
                properties.getTimeoutMs(),
                properties.getMaxOutputTokens());
    }

    public TargetConfig localTarget() {
        if (!properties.isLocalUsable()) {
            return null;
        }
        return new TargetConfig(
                properties.localEndpoint(),
                properties.getLocalApiKey(),
                properties.getLocalModel(),
                properties.getLocalTimeoutMs(),
                properties.getMaxOutputTokens());
    }

    String complete(Prompt prompt) {
        return completeWithUsage(prompt).content();
    }

    LlmCompletion completeWithUsage(Prompt prompt) {
        TargetConfig target = cloudTarget();
        if (target == null) {
            target = localTarget();
        }
        if (target == null) {
            throw unavailable();
        }
        return completeWithTarget(prompt, target);
    }

    /**
     * Plain-text completion for callers outside the summary package. The
     * summary {@link Prompt} record stays package-private; only the two prompt
     * strings are needed to build the OpenAI-compatible request body.
     */
    public String completeTextWithTarget(String systemMessage, String userMessage, TargetConfig target) {
        Prompt prompt = new Prompt(systemMessage, userMessage, java.util.Map.of(),
                java.util.Map.of(), java.util.List.of(), com.nexachat.summary.SummaryLanguage.EN);
        return completeWithTarget(prompt, target).content();
    }

    public LlmCompletion completeWithTarget(Prompt prompt, TargetConfig target) {
        if (target == null || target.endpoint() == null || target.model() == null) {
            throw unavailable();
        }
        byte[] requestBody;
        try {
            requestBody = requestBody(prompt, target.model(), target.maxOutputTokens());
        } catch (JsonProcessingException | RuntimeException exception) {
            throw badOutput();
        }

        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(100, target.timeoutMs()));
        long deadline = System.nanoTime() + budgetNanos;
        for (int attempt = 0; attempt < 2; attempt++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw timeout();
            }
            String key = target.apiKey() == null ? "" : target.apiKey().trim();
            if (key.isEmpty()) {
                key = "local-key";
            }
            HttpRequest request = HttpRequest.newBuilder(target.endpoint())
                    .timeout(Duration.ofNanos(remaining))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 500 && status <= 599 && attempt == 0) {
                    closeQuietly(response.body());
                    if (deadline - System.nanoTime() > 0) {
                        continue;
                    }
                    throw timeout();
                }
                if (status < 200 || status >= 300) {
                    closeQuietly(response.body());
                    throw httpError();
                }
                byte[] responseBody = readResponse(response.body());
                return parseCompletion(responseBody);
            } catch (java.net.http.HttpTimeoutException exception) {
                throw timeout();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw timeout();
            } catch (SummaryProviderException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                throw httpError();
            }
        }
        throw httpError();
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private byte[] requestBody(Prompt prompt, String modelName, int maxTokens) throws JsonProcessingException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName.trim());
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0);
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", prompt.systemMessage());
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt.userMessage());
        return objectMapper.writeValueAsBytes(body);
    }

    private static byte[] readResponse(InputStream body) throws IOException {
        if (body == null) {
            throw badOutput();
        }
        try (body) {
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw badOutput();
            }
            return bytes;
        }
    }

    private LlmCompletion parseCompletion(byte[] responseBody) {
        try (JsonParser parser = objectMapper.getFactory().createParser(responseBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw badOutput();
            }
            String content = null;
            Integer promptTokens = null;
            Integer completionTokens = null;
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token == null) {
                    throw badOutput();
                }
                String field = parser.currentName();
                token = parser.nextToken();
                if ("choices".equals(field) && token == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() == JsonToken.START_OBJECT) {
                            String choiceContent = readChoice(parser);
                            if (content == null && choiceContent != null) {
                                content = choiceContent;
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else if ("usage".equals(field) && token == JsonToken.START_OBJECT) {
                    while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                        if (token == null) {
                            throw badOutput();
                        }
                        String usageField = parser.currentName();
                        token = parser.nextToken();
                        if ("prompt_tokens".equals(usageField) && token.isNumeric()) {
                            promptTokens = parser.getIntValue();
                        } else if ("completion_tokens".equals(usageField) && token.isNumeric()) {
                            completionTokens = parser.getIntValue();
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (content == null || content.isBlank()) {
                throw badOutput();
            }
            return new LlmCompletion(content, promptTokens, completionTokens);
        } catch (JsonProcessingException exception) {
            throw badOutput();
        } catch (IOException exception) {
            throw httpError();
        }
    }

    private static String readChoice(JsonParser parser) throws IOException {
        String content = null;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token == null) {
                throw badOutput();
            }
            String field = parser.currentName();
            token = parser.nextToken();
            if ("message".equals(field) && token == JsonToken.START_OBJECT) {
                String messageContent = readMessage(parser);
                if (content == null) {
                    content = messageContent;
                }
            } else {
                parser.skipChildren();
            }
        }
        return content;
    }

    private static String readMessage(JsonParser parser) throws IOException {
        String content = null;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token == null) {
                throw badOutput();
            }
            String field = parser.currentName();
            token = parser.nextToken();
            if ("content".equals(field) && token == JsonToken.VALUE_STRING) {
                content = parser.getText();
            } else {
                parser.skipChildren();
            }
        }
        return content;
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

    private static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "summary-llm-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static SummaryProviderException unavailable() {
        return new SummaryProviderException(SummaryFallbackReason.LLM_DISABLED);
    }

    private static SummaryProviderException timeout() {
        return new SummaryProviderException(SummaryFallbackReason.LLM_TIMEOUT);
    }

    private static SummaryProviderException httpError() {
        return new SummaryProviderException(SummaryFallbackReason.LLM_HTTP_ERROR);
    }

    private static SummaryProviderException badOutput() {
        return new SummaryProviderException(SummaryFallbackReason.LLM_BAD_OUTPUT);
    }
}
