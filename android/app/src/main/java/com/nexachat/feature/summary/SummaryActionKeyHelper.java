package com.nexachat.feature.summary;

import com.nexachat.summary.SummaryActionItem;
import java.util.List;

/**
 * Pure Java helper for constructing a stable identity key for summary action items.
 * Used as a stable item identity by summary list adapters.
 */
public final class SummaryActionKeyHelper {

    private SummaryActionKeyHelper() { }

    /**
     * Builds a stable identity key from the action item's text and its first citation message id.
     *
     * @param action the action item (may be null)
     * @return stable identity key string
     */
    public static String getActionItemKey(SummaryActionItem action) {
        if (action == null) {
            return "";
        }
        return buildKey(action.getText(), action.getMessageIds());
    }

    /**
     * Builds a stable identity key from the action item text and list of citation message IDs.
     *
     * @param text action item text (may be null)
     * @param messageIds list of citation message IDs (may be null or empty)
     * @return stable identity key string
     */
    public static String buildKey(String text, List<String> messageIds) {
        String safeText = text != null ? text.trim() : "";
        String firstCitation = null;
        if (messageIds != null) {
            for (String msgId : messageIds) {
                if (msgId != null && !msgId.trim().isEmpty()) {
                    firstCitation = msgId.trim();
                    break;
                }
            }
        }
        if (firstCitation != null && !firstCitation.isEmpty()) {
            return safeText + ":" + firstCitation;
        }
        return safeText;
    }

    /**
     * Builds a stable identity key from the action item text and a single citation message ID.
     *
     * @param text action item text (may be null)
     * @param firstCitationId single citation message ID (may be null or empty)
     * @return stable identity key string
     */
    public static String buildKey(String text, String firstCitationId) {
        String safeText = text != null ? text.trim() : "";
        String citation = firstCitationId != null ? firstCitationId.trim() : null;
        if (citation != null && !citation.isEmpty()) {
            return safeText + ":" + citation;
        }
        return safeText;
    }
}
