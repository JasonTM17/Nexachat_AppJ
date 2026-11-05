package com.nexachat.summary.engine;

import java.util.Set;

public final class StopWords {
    private static final Set<String> VIETNAMESE = Set.of(
            "va", "la", "cua", "cho", "voi", "trong", "nhom", "minh", "cac", "nhung",
            "duoc", "da", "se", "nay", "do", "nhe", "de", "co", "khong", "dang", "tai",
            "ve", "mot", "ban", "phan", "hom", "mai", "roi", "rat", "theo", "neu", "thi",
            "khi", "den", "tu", "tren", "duoi", "giua", "cung", "nhu", "hon", "hay", "em",
            "anh", "chi", "toi", "ta", "no", "vay", "a", "uh", "ok"
    );

    private static final Set<String> ENGLISH = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could",
            "did", "do", "does", "for", "from", "had", "has", "have", "he", "her", "here",
            "him", "his", "i", "if", "in", "into", "is", "it", "its", "me", "my", "of", "on",
            "or", "our", "she", "so", "that", "the", "their", "them", "there", "they", "this",
            "to", "us", "was", "we", "were", "what", "when", "where", "which", "who", "will",
            "with", "would", "you", "your"
    );

    private StopWords() {
    }

    public static boolean contains(String foldedToken, SummaryLanguage language) {
        if (foldedToken == null) {
            return false;
        }
        return switch (language == null ? SummaryLanguage.AUTO : language) {
            case VI -> VIETNAMESE.contains(foldedToken) || ENGLISH.contains(foldedToken);
            case EN -> ENGLISH.contains(foldedToken) || VIETNAMESE.contains(foldedToken);
            case AUTO -> VIETNAMESE.contains(foldedToken) || ENGLISH.contains(foldedToken);
        };
    }

    public static Set<String> vietnamese() {
        return VIETNAMESE;
    }

    public static Set<String> english() {
        return ENGLISH;
    }
}
