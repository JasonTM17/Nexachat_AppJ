package com.nexachat.rag;

import com.nexachat.summary.engine.Tokenizer;

/**
 * Offline, deterministic embedding: hashed character trigrams plus word term
 * frequencies over accent-folded text. No network, no external cost and no
 * message text leaves the server. The vector space is stable across JVM runs
 * because the hash is a fixed FNV-1a implementation.
 */
public final class LocalLexicalEmbedding implements EmbeddingProvider {

    public static final String ID = "lexical";
    public static final int DEFAULT_DIMENSION = 256;

    private final int dimension;

    public LocalLexicalEmbedding() {
        this(DEFAULT_DIMENSION);
    }

    public LocalLexicalEmbedding(int dimension) {
        if (dimension < 16) {
            throw new IllegalArgumentException("dimension must be at least 16");
        }
        this.dimension = dimension;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        String folded = Tokenizer.foldForMatching(text == null ? "" : text);
        float[] vector = new float[dimension];
        int charDims = dimension / 2;
        int wordDims = dimension - charDims;

        int length = folded.length();
        for (int start = 0; start + 3 <= length; start++) {
            int hash = fnv1a(folded, start, 3);
            vector[Math.floorMod(hash, charDims)] += 1.0f;
        }
        for (String token : folded.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() < 2) {
                continue;
            }
            int hash = fnv1a(token, 0, token.length());
            vector[charDims + Math.floorMod(hash, wordDims)] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    private static int fnv1a(String text, int start, int count) {
        int hash = 0x811c9dc5;
        for (int index = start; index < start + count; index++) {
            hash ^= text.charAt(index);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static void normalize(float[] vector) {
        double sumSquares = 0.0;
        for (float value : vector) {
            sumSquares += (double) value * value;
        }
        if (sumSquares == 0.0) {
            return;
        }
        double norm = Math.sqrt(sumSquares);
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) (vector[index] / norm);
        }
    }
}
