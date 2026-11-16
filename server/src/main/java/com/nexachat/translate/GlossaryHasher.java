package com.nexachat.translate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Stable fingerprint of the caller's glossary — part of the cache key so an
 * edited glossary never replays a translation produced under different
 * terms. SHA-256 over the canonical {@code term\u0000replacement} rows joined
 * by {@code \u0001}; the empty glossary hashes to the empty-input digest, a
 * constant.
 */
public final class GlossaryHasher {

    /** SHA-256 of the empty input — the stable "no glossary" fingerprint. */
    public static final String EMPTY_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private GlossaryHasher() {
    }

    public static String hash(List<TranslationGlossaryRepository.GlossaryTerm> terms) {
        if (terms == null || terms.isEmpty()) {
            return EMPTY_HASH;
        }
        List<TranslationGlossaryRepository.GlossaryTerm> sorted = terms.stream()
                .sorted(java.util.Comparator.comparing(
                        (TranslationGlossaryRepository.GlossaryTerm t) -> t.term().toLowerCase(java.util.Locale.ROOT))
                        .thenComparing(TranslationGlossaryRepository.GlossaryTerm::term))
                .toList();
        StringBuilder canonical = new StringBuilder();
        for (TranslationGlossaryRepository.GlossaryTerm term : sorted) {
            canonical.append(term.term()).append('\u0000')
                    .append(term.replacement() == null ? "" : term.replacement())
                    .append('\u0001');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
