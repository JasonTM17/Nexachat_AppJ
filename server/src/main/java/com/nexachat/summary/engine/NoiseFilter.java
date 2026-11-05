package com.nexachat.summary.engine;

import java.util.Set;
import java.util.regex.Pattern;

public final class NoiseFilter {
    private static final Pattern URL = Pattern.compile("(?iu)(?:https?://|www\\.)\\S+");
    private static final Pattern LAUGHTER = Pattern.compile(
            "(?iu)^(?:(?:ha|he|hi){2,}|lol+|lmao+|rofl+)[\\p{P}\\s]*$");
    private static final Set<String> STICKER_ONLY = Set.of(
            "sticker", "stickers", "gif", "emoji", "photo", "image", "video", "attachment"
    );

    private NoiseFilter() {
    }

    public static boolean isNoise(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String folded = Tokenizer.foldForMatching(text).strip();
        if (folded.isEmpty() || LAUGHTER.matcher(folded).matches()) {
            return true;
        }

        String withoutUrls = URL.matcher(folded).replaceAll(" ").strip();
        String lettersAndDigits = withoutUrls.replaceAll("[\\p{P}\\p{S}\\s]+", "").strip();
        if (lettersAndDigits.isEmpty()) {
            return true;
        }

        String simple = folded.replaceAll("[\\p{P}\\s]+", " ").strip();
        if (Set.of("ok", "oke", "okay", "k", "kk", "haha", "hahaha").contains(simple)) {
            return true;
        }
        return STICKER_ONLY.contains(simple);
    }
}
