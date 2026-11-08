package com.nexachat.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operator knobs for the conversation RAG layer. Every value has an offline
 * default so the feature works with no external key: lexical embeddings,
 * lexical retrieval and extractive answers only.
 */
@Component
@ConfigurationProperties(prefix = "nexachat.rag")
public class RagProperties {

    private boolean enabled = true;
    private final Embedding embedding = new Embedding();
    private final Retrieval retrieval = new Retrieval();
    private final Index index = new Index();
    private final Ask ask = new Ask();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public Index getIndex() {
        return index;
    }

    public Ask getAsk() {
        return ask;
    }

    public static class Embedding {
        /** lexical | openai-compatible */
        private String provider = "lexical";
        /** openai-compatible | gemini */
        private String mode = "openai-compatible";
        private String baseUrl = "";
        private String model = "gemini-embedding-001";
        private String apiKey = "";
        private int dim = 256;
        private int timeoutMs = 20_000;
        private int batchSize = 32;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getDim() {
            return dim;
        }

        public void setDim(int dim) {
            this.dim = dim;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class Retrieval {
        private int maxCandidateMessages = 5_000;
        private int lexicalCandidateLimit = 300;
        private int lexicalTermLimit = 8;
        private int vectorCandidateLimit = 200;
        private int neighborRadius = 2;
        private int maxContextChars = 3_000;
        private int mmrLimit = 12;
        private double mmrLambda = 0.72;
        private int rrfK = 60;
        private double notFoundThreshold = 0.18;
        private double extractiveThreshold = 0.62;
        private double semanticCacheThreshold = 0.92;
        private int maxSnippetChars = 280;

        public int getMaxCandidateMessages() {
            return maxCandidateMessages;
        }

        public void setMaxCandidateMessages(int maxCandidateMessages) {
            this.maxCandidateMessages = maxCandidateMessages;
        }

        public int getLexicalCandidateLimit() {
            return lexicalCandidateLimit;
        }

        public void setLexicalCandidateLimit(int lexicalCandidateLimit) {
            this.lexicalCandidateLimit = lexicalCandidateLimit;
        }

        public int getLexicalTermLimit() {
            return lexicalTermLimit;
        }

        public void setLexicalTermLimit(int lexicalTermLimit) {
            this.lexicalTermLimit = lexicalTermLimit;
        }

        public int getVectorCandidateLimit() {
            return vectorCandidateLimit;
        }

        public void setVectorCandidateLimit(int vectorCandidateLimit) {
            this.vectorCandidateLimit = vectorCandidateLimit;
        }

        public int getNeighborRadius() {
            return neighborRadius;
        }

        public void setNeighborRadius(int neighborRadius) {
            this.neighborRadius = neighborRadius;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }

        public int getMmrLimit() {
            return mmrLimit;
        }

        public void setMmrLimit(int mmrLimit) {
            this.mmrLimit = mmrLimit;
        }

        public double getMmrLambda() {
            return mmrLambda;
        }

        public void setMmrLambda(double mmrLambda) {
            this.mmrLambda = mmrLambda;
        }

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public double getNotFoundThreshold() {
            return notFoundThreshold;
        }

        public void setNotFoundThreshold(double notFoundThreshold) {
            this.notFoundThreshold = notFoundThreshold;
        }

        public double getExtractiveThreshold() {
            return extractiveThreshold;
        }

        public void setExtractiveThreshold(double extractiveThreshold) {
            this.extractiveThreshold = extractiveThreshold;
        }

        public double getSemanticCacheThreshold() {
            return semanticCacheThreshold;
        }

        public void setSemanticCacheThreshold(double semanticCacheThreshold) {
            this.semanticCacheThreshold = semanticCacheThreshold;
        }

        public int getMaxSnippetChars() {
            return maxSnippetChars;
        }

        public void setMaxSnippetChars(int maxSnippetChars) {
            this.maxSnippetChars = maxSnippetChars;
        }
    }

    public static class Index {
        private int lazyLimit = 500;
        private int backfillRatePerMinute = 200;
        private int cacheMaxVectors = 4_096;
        private boolean backfillEnabled = true;
        private long backfillIntervalMs = 60_000;

        public int getLazyLimit() {
            return lazyLimit;
        }

        public void setLazyLimit(int lazyLimit) {
            this.lazyLimit = lazyLimit;
        }

        public int getBackfillRatePerMinute() {
            return backfillRatePerMinute;
        }

        public void setBackfillRatePerMinute(int backfillRatePerMinute) {
            this.backfillRatePerMinute = backfillRatePerMinute;
        }

        public int getCacheMaxVectors() {
            return cacheMaxVectors;
        }

        public void setCacheMaxVectors(int cacheMaxVectors) {
            this.cacheMaxVectors = cacheMaxVectors;
        }

        public boolean isBackfillEnabled() {
            return backfillEnabled;
        }

        public void setBackfillEnabled(boolean backfillEnabled) {
            this.backfillEnabled = backfillEnabled;
        }

        public long getBackfillIntervalMs() {
            return backfillIntervalMs;
        }

        public void setBackfillIntervalMs(long backfillIntervalMs) {
            this.backfillIntervalMs = backfillIntervalMs;
        }
    }

    public static class Ask {
        private int rateLimitPerMinute = 20;
        private int rateLimitPerDay = 200;
        private int cacheTtlHours = 24;

        public int getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(int rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }

        public int getRateLimitPerDay() {
            return rateLimitPerDay;
        }

        public void setRateLimitPerDay(int rateLimitPerDay) {
            this.rateLimitPerDay = rateLimitPerDay;
        }

        public int getCacheTtlHours() {
            return cacheTtlHours;
        }

        public void setCacheTtlHours(int cacheTtlHours) {
            this.cacheTtlHours = cacheTtlHours;
        }
    }
}
