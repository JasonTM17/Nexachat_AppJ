package com.nexachat.summary.engine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Tokenizer {
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?…])\\s+|\\R+");

    private Tokenizer() {
    }

    public static String nfc(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC);
    }

    public static String foldForMatching(String text) {
        String decomposed = Normalizer.normalize(nfc(text), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    public static List<Token> tokenData(String text) {
        String normalized = nfc(text);
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        List<Token> result = new ArrayList<>();
        while (matcher.find()) {
            String original = matcher.group();
            result.add(new Token(original, foldForMatching(original)));
        }
        return List.copyOf(result);
    }

    public static List<String> tokens(String text) {
        return tokenData(text).stream().map(Token::folded).toList();
    }

    public static String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.strip();
        String[] sentences = SENTENCE_BREAK.split(trimmed, 2);
        return sentences.length == 0 ? trimmed : sentences[0].strip();
    }

    public record Token(String original, String folded) {
    }
}
