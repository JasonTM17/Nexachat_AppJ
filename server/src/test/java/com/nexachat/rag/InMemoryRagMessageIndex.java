package com.nexachat.rag;

import com.nexachat.summary.engine.Tokenizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test double for the retrieval index. Never used by production code. */
public class InMemoryRagMessageIndex implements RagMessageIndex {

    private final List<RagMessage> messages = new ArrayList<>();
    private final Map<String, Map<UUID, float[]>> vectors = new HashMap<>();
    private final Map<String, Map<UUID, String>> hashes = new HashMap<>();

    public void add(RagMessage message) {
        messages.add(message);
    }

    public void clear() {
        messages.clear();
        vectors.clear();
        hashes.clear();
    }

    @Override
    public List<RagMessage> recentMessages(UUID conversationId, int limit) {
        List<RagMessage> matching = new ArrayList<>();
        for (RagMessage message : messages) {
            if (message.conversationId().equals(conversationId)) {
                matching.add(message);
            }
        }
        matching.sort(Comparator.comparingLong(RagMessage::seq).reversed());
        List<RagMessage> limited = matching.subList(0, Math.min(limit, matching.size()));
        List<RagMessage> ordered = new ArrayList<>(limited);
        java.util.Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    @Override
    public List<RagMessage> lexicalCandidates(UUID conversationId, List<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<RagMessage> matching = new ArrayList<>();
        for (RagMessage message : messages) {
            if (!message.conversationId().equals(conversationId)) {
                continue;
            }
            String folded = Tokenizer.foldForMatching(message.content());
            long hits = terms.stream().filter(term -> folded.contains(term)).count();
            if (hits > 0) {
                matching.add(message);
            }
        }
        matching.sort(Comparator
                .comparingLong((RagMessage message) -> matchingScore(message, terms))
                .reversed()
                .thenComparing(Comparator.comparingLong(RagMessage::seq).reversed()));
        return List.copyOf(matching.subList(0, Math.min(limit, matching.size())));
    }

    private long matchingScore(RagMessage message, List<String> terms) {
        String folded = Tokenizer.foldForMatching(message.content());
        return terms.stream().filter(term -> folded.contains(term)).count();
    }

    @Override
    public Map<UUID, float[]> loadVectors(String model, Collection<UUID> messageIds) {
        Map<UUID, float[]> modelVectors = vectors.getOrDefault(model, Map.of());
        Map<UUID, float[]> result = new LinkedHashMap<>();
        for (UUID id : messageIds) {
            float[] vector = modelVectors.get(id);
            if (vector != null) {
                result.put(id, vector);
            }
        }
        return result;
    }

    @Override
    public Map<UUID, String> loadContentHashes(String model, Collection<UUID> messageIds) {
        Map<UUID, String> modelHashes = hashes.getOrDefault(model, Map.of());
        Map<UUID, String> result = new LinkedHashMap<>();
        for (UUID id : messageIds) {
            String hash = modelHashes.get(id);
            if (hash != null) {
                result.put(id, hash);
            }
        }
        return result;
    }

    @Override
    public void saveVectors(String model, int dim, List<RagMessage> messages,
                            Map<UUID, float[]> newVectors, Map<UUID, String> newHashes) {
        vectors.computeIfAbsent(model, ignored -> new HashMap<>()).putAll(newVectors);
        hashes.computeIfAbsent(model, ignored -> new HashMap<>()).putAll(newHashes);
    }

    @Override
    public List<RagMessage> findUnindexed(String model, int limit) {
        Map<UUID, float[]> modelVectors = vectors.getOrDefault(model, Map.of());
        List<RagMessage> missing = new ArrayList<>();
        for (RagMessage message : messages) {
            if (!modelVectors.containsKey(message.id())) {
                missing.add(message);
            }
        }
        missing.sort(Comparator.comparingLong(RagMessage::seq).reversed());
        return List.copyOf(missing.subList(0, Math.min(limit, missing.size())));
    }

    @Override
    public Map<UUID, String> loadMessageContentHashes(Collection<UUID> messageIds) {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (RagMessage message : messages) {
            if (messageIds.contains(message.id())) {
                result.put(message.id(), RagHashes.messageContentHash(
                        message.id(), message.editedAt(), message.content()));
            }
        }
        return result;
    }

    @Override
    public RagMessage findMessage(UUID conversationId, UUID messageId) {
        return messages.stream()
                .filter(message -> message.conversationId().equals(conversationId))
                .filter(message -> message.id().equals(messageId))
                .findFirst()
                .orElse(null);
    }
}
