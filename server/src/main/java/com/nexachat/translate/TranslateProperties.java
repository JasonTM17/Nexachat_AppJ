package com.nexachat.translate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Translation feature configuration ({@code nexachat.translate}). Provider
 * selection, endpoints and model names are inherited from the shared
 * {@code nexachat.summary.llm} stack; these values bound only the translate
 * path itself.
 */
@Component
@ConfigurationProperties(prefix = "nexachat.translate")
public class TranslateProperties {

    /** Recent conversation messages sent as grounding context. */
    private int contextMessages = 12;
    /** Total context budget in characters across those messages. */
    private int contextMaxChars = 2_000;
    /** Per-message cap inside the context block. */
    private int contextMessageMaxChars = 500;
    /** Cache time-to-live for stored translations. */
    private long cacheTtlHours = 24 * 30;
    /** Maximum cached target languages retained per message revision. */
    private int cacheMaxLangsPerMessage = 8;
    /** Maximum glossary terms per member per conversation. */
    private int glossaryMaxTerms = 50;
    /** Maximum characters per glossary term or replacement. */
    private int glossaryMaxTermChars = 100;
    /** Maximum message ids accepted by the batch endpoint. */
    private int batchMaxMessages = 20;
    /** Per-user generation requests allowed per minute (cache hits are free). */
    private int rateLimitPerMinute = 30;
    /** Per-user generation requests allowed per day (cache hits are free). */
    private int rateLimitPerDay = 300;

    public int getContextMessages() {
        return contextMessages;
    }

    public void setContextMessages(int contextMessages) {
        this.contextMessages = contextMessages;
    }

    public int getContextMaxChars() {
        return contextMaxChars;
    }

    public void setContextMaxChars(int contextMaxChars) {
        this.contextMaxChars = contextMaxChars;
    }

    public int getContextMessageMaxChars() {
        return contextMessageMaxChars;
    }

    public void setContextMessageMaxChars(int contextMessageMaxChars) {
        this.contextMessageMaxChars = contextMessageMaxChars;
    }

    public long getCacheTtlHours() {
        return cacheTtlHours;
    }

    public void setCacheTtlHours(long cacheTtlHours) {
        this.cacheTtlHours = cacheTtlHours;
    }

    public int getCacheMaxLangsPerMessage() {
        return cacheMaxLangsPerMessage;
    }

    public void setCacheMaxLangsPerMessage(int cacheMaxLangsPerMessage) {
        this.cacheMaxLangsPerMessage = cacheMaxLangsPerMessage;
    }

    public int getGlossaryMaxTerms() {
        return glossaryMaxTerms;
    }

    public void setGlossaryMaxTerms(int glossaryMaxTerms) {
        this.glossaryMaxTerms = glossaryMaxTerms;
    }

    public int getGlossaryMaxTermChars() {
        return glossaryMaxTermChars;
    }

    public void setGlossaryMaxTermChars(int glossaryMaxTermChars) {
        this.glossaryMaxTermChars = glossaryMaxTermChars;
    }

    public int getBatchMaxMessages() {
        return batchMaxMessages;
    }

    public void setBatchMaxMessages(int batchMaxMessages) {
        this.batchMaxMessages = batchMaxMessages;
    }

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
}
