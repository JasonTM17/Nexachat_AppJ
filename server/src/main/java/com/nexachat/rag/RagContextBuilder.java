package com.nexachat.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the ranked messages into a budgeted prompt context: neighbour
 * expansion, reply-parent recovery, short same-sender merging and a hard
 * character budget.
 */
public final class RagContextBuilder {

    private static final int MERGE_CHAR_BUDGET = 400;
    private static final long MERGE_GAP_MILLIS = 120_000;

    private RagContextBuilder() {
    }

    public static ContextResult build(
            List<ScoredMessage> ranked,
            List<RagMessage> recentMessages,
            RagMessageIndex index,
            RagProperties.Retrieval config) {
        return build(ranked, recentMessages, index, config, null);
    }

    /** As above, but a replied-to parent from before {@code admittedFrom} is left out. */
    public static ContextResult build(
            List<ScoredMessage> ranked,
            List<RagMessage> recentMessages,
            RagMessageIndex index,
            RagProperties.Retrieval config,
            java.time.Instant admittedFrom) {
        if (ranked == null || ranked.isEmpty()) {
            return new ContextResult(List.of(), 0, Map.of());
        }
        Map<UUID, RagMessage> byId = new HashMap<>();
        Map<UUID, Integer> position = new HashMap<>();
        for (int positionIndex = 0; positionIndex < recentMessages.size(); positionIndex++) {
            RagMessage message = recentMessages.get(positionIndex);
            byId.put(message.id(), message);
            position.put(message.id(), positionIndex);
        }

        Set<UUID> contextIds = new java.util.LinkedHashSet<>();
        for (ScoredMessage scored : ranked) {
            RagMessage message = scored.message();
            Integer messagePosition = position.get(message.id());
            if (messagePosition == null) {
                continue;
            }
            int from = Math.max(0, messagePosition - config.getNeighborRadius());
            int to = Math.min(recentMessages.size() - 1, messagePosition + config.getNeighborRadius());
            for (int index2 = from; index2 <= to; index2++) {
                contextIds.add(recentMessages.get(index2).id());
            }
            if (message.replyToId() != null && !byId.containsKey(message.replyToId())) {
                RagMessage parent = index.findMessage(message.conversationId(), message.replyToId());
                if (parent != null
                        && (admittedFrom == null || !parent.createdAt().isBefore(admittedFrom))) {
                    byId.put(parent.id(), parent);
                    contextIds.add(parent.id());
                }
            }
        }

        List<RagMessage> ordered = contextIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(RagMessage::seq))
                .toList();

        List<List<RagMessage>> groups = groupConsecutive(ordered);
        Map<UUID, String> senderLabels = new LinkedHashMap<>();
        List<RagContextItem> items = new ArrayList<>();
        int budget = Math.max(1, config.getMaxContextChars());
        int used = 0;
        int itemIndex = 0;
        for (List<RagMessage> group : groups) {
            String text = group.stream()
                    .map(RagMessage::content)
                    .filter(content -> content != null && !content.isEmpty())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
            if (text.isEmpty()) {
                continue;
            }
            int textChars = codePoints(text);
            if (used + textChars > budget) {
                int remaining = budget - used;
                if (remaining <= 0) {
                    break;
                }
                text = truncateByCodePoints(text, remaining);
                if (text.isEmpty()) {
                    break;
                }
                textChars = codePoints(text);
            }
            itemIndex++;
            String senderLabel = senderLabels.computeIfAbsent(
                    group.get(0).senderId(),
                    ignored -> "U" + (senderLabels.size() + 1));
            List<UUID> messageIds = group.stream().map(RagMessage::id).toList();
            items.add(new RagContextItem(itemIndex, messageIds, group.get(0).seq(), senderLabel, text));
            used += textChars;
            if (used >= budget) {
                break;
            }
        }
        Map<UUID, RagMessage> contextMessages = new HashMap<>();
        for (RagMessage message : ordered) {
            contextMessages.put(message.id(), message);
        }
        return new ContextResult(List.copyOf(items), used, Map.copyOf(contextMessages));
    }

    private static List<List<RagMessage>> groupConsecutive(List<RagMessage> ordered) {
        List<List<RagMessage>> groups = new ArrayList<>();
        List<RagMessage> current = new ArrayList<>();
        for (RagMessage message : ordered) {
            if (current.isEmpty() || canMerge(current, message)) {
                current.add(message);
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(message);
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private static boolean canMerge(List<RagMessage> group, RagMessage next) {
        RagMessage last = group.get(group.size() - 1);
        if (!Objects.equals(last.senderId(), next.senderId())) {
            return false;
        }
        if (last.createdAt() == null || next.createdAt() == null) {
            return false;
        }
        long gapMillis = Math.abs(Duration.between(last.createdAt(), next.createdAt()).toMillis());
        if (gapMillis > MERGE_GAP_MILLIS) {
            return false;
        }
        int combined = group.stream().mapToInt(message -> codePoints(message.content())).sum();
        combined += codePoints(next.content());
        return combined <= MERGE_CHAR_BUDGET;
    }

    private static int codePoints(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private static String truncateByCodePoints(String text, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            return "";
        }
        if (codePoints(text) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }

    public record ContextResult(
            List<RagContextItem> context,
            int inputChars,
            Map<UUID, RagMessage> messages) {
    }
}
