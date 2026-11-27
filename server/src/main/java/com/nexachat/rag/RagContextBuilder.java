package com.nexachat.rag;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the ranked messages into a budgeted prompt context: neighbour
 * expansion, reply-parent recovery, short same-sender merging and a hard
 * character budget. Ranked evidence outside the recent window is recovered
 * through a bounded sequence neighborhood instead of being silently dropped.
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
            Instant admittedFrom) {
        return build(ranked, recentMessages, index, config, admittedFrom, false);
    }

    static ContextResult buildSpeakerContext(List<ScoredMessage> ranked, List<RagMessage> recentMessages,
                                             RagProperties.Retrieval config, Instant admittedFrom) {
        return build(ranked, recentMessages, null, config, admittedFrom, true);
    }

    private static ContextResult build(List<ScoredMessage> ranked, List<RagMessage> recentMessages,
                                       RagMessageIndex index, RagProperties.Retrieval config,
                                       Instant admittedFrom, boolean completeMessages) {
        if (ranked == null || ranked.isEmpty()) {
            return new ContextResult(List.of(), 0, Map.of());
        }
        Map<UUID, RagMessage> byId = new HashMap<>();
        Map<UUID, Integer> position = new HashMap<>();
        List<RagMessage> recent = recentMessages == null ? List.of() : recentMessages;
        for (int positionIndex = 0; positionIndex < recent.size(); positionIndex++) {
            RagMessage message = recent.get(positionIndex);
            byId.put(message.id(), message);
            position.put(message.id(), positionIndex);
        }

        Set<UUID> contextIds = new LinkedHashSet<>();
        Set<UUID> rankedIds = new LinkedHashSet<>();
        int radius = Math.max(0, config.getNeighborRadius());
        for (ScoredMessage scored : ranked) {
            RagMessage message = scored.message();
            if (message == null || message.id() == null) {
                continue;
            }
            rankedIds.add(message.id());
            Integer messagePosition = position.get(message.id());
            if (messagePosition != null) {
                int from = Math.max(0, messagePosition - radius);
                int to = Math.min(recent.size() - 1, messagePosition + radius);
                for (int neighbor = from; neighbor <= to; neighbor++) {
                    contextIds.add(recent.get(neighbor).id());
                }
            } else {
                List<RagMessage> neighborhood = index == null
                        ? List.of()
                        : index.messagesAroundSeq(message.conversationId(), message.seq(), radius, admittedFrom);
                for (RagMessage neighbor : neighborhood) {
                    byId.putIfAbsent(neighbor.id(), neighbor);
                    contextIds.add(neighbor.id());
                }
                if (!contextIds.contains(message.id()) && admitted(message, admittedFrom)) {
                    // The ranked row was itself eligible when retrieval selected it. Keep it as
                    // a first-class unit even when a test double or a very sparse sequence
                    // neighborhood cannot supply surrounding rows.
                    byId.putIfAbsent(message.id(), message);
                    contextIds.add(message.id());
                }
            }

            // Reply-parent recovery must test membership in the selected context, not in
            // the pre-populated recent-window map. Otherwise parents inside the window but
            // outside the neighbor radius can never be recovered.
            if (message.replyToId() != null && !contextIds.contains(message.replyToId())) {
                RagMessage parent = byId.get(message.replyToId());
                if (parent == null && index != null) {
                    parent = index.findMessage(message.conversationId(), message.replyToId());
                }
                if (parent != null && admitted(parent, admittedFrom)) {
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

        List<List<RagMessage>> groups = completeMessages ? ordered.stream().map(List::of).toList()
                : groupConsecutive(ordered);
        Map<UUID, String> senderLabels = new LinkedHashMap<>();
        List<RagContextItem> items = new ArrayList<>();
        int budget = Math.max(1, config.getMaxContextChars());
        int used = 0;
        int itemIndex = 0;
        Set<UUID> serializedIds = new LinkedHashSet<>();
        for (List<RagMessage> group : groups) {
            SerializedGroup serialized = serializeGroup(group, budget - used);
            if (completeMessages && serialized.truncated()) {
                continue;
            }
            if (serialized.messageIds().isEmpty()) {
                break;
            }
            itemIndex++;
            String senderLabel = senderLabels.computeIfAbsent(
                    group.get(0).senderId(),
                    ignored -> "U" + (senderLabels.size() + 1));
            List<UUID> primaryIds = serialized.messageIds().stream()
                    .filter(rankedIds::contains)
                    .toList();
            items.add(new RagContextItem(
                    itemIndex, serialized.messageIds(), group.get(0).seq(), senderLabel,
                    serialized.text(), primaryIds));
            serializedIds.addAll(serialized.messageIds());
            used += serialized.codePoints();
            if (used >= budget || serialized.truncated()) {
                break;
            }
        }
        Map<UUID, RagMessage> contextMessages = new HashMap<>();
        for (RagMessage message : ordered) {
            if (serializedIds.contains(message.id())) {
                contextMessages.put(message.id(), message);
            }
        }
        return new ContextResult(List.copyOf(items), used, Map.copyOf(contextMessages));
    }

    private static boolean admitted(RagMessage message, Instant admittedFrom) {
        return admittedFrom == null
                || message.createdAt() == null
                || !message.createdAt().isBefore(admittedFrom);
    }

    /**
     * Serializes one merged group inside the remaining budget. A message id is
     * included only when at least part of that message's own content is present;
     * merely being covered by the pre-truncation group is not citable evidence.
     */
    private static SerializedGroup serializeGroup(List<RagMessage> group, int remainingBudget) {
        if (remainingBudget <= 0) {
            return new SerializedGroup("", List.of(), 0, true);
        }
        StringBuilder text = new StringBuilder();
        List<UUID> serializedIds = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (RagMessage message : group) {
            String content = message.content() == null ? "" : message.content().trim();
            int contentCodePoints = codePoints(content);
            if (contentCodePoints == 0) {
                continue;
            }
            int separator = used > 0 ? 1 : 0;
            if (used + separator >= remainingBudget) {
                truncated = true;
                break;
            }
            if (separator == 1) {
                text.append(' ');
            }
            int available = remainingBudget - used - separator;
            int take = Math.min(contentCodePoints, available);
            text.append(firstCodePoints(content, take));
            if (take > 0) {
                serializedIds.add(message.id());
            }
            used += separator + take;
            if (take < contentCodePoints) {
                truncated = true;
                break;
            }
        }
        return new SerializedGroup(text.toString(), List.copyOf(serializedIds), used, truncated);
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

    private static String firstCodePoints(String text, int maxCodePoints) {
        if (maxCodePoints <= 0 || text == null) {
            return "";
        }
        if (codePoints(text) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }

    private record SerializedGroup(String text, List<UUID> messageIds, int codePoints, boolean truncated) {
    }

    public record ContextResult(
            List<RagContextItem> context,
            int inputChars,
            Map<UUID, RagMessage> messages) {
    }
}
