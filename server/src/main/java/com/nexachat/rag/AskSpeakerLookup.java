package com.nexachat.rag;

import com.nexachat.summary.engine.Tokenizer;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit attribution supplied in the question, resolved locally to an eligible sender. */
record AskSpeakerLookup(String name, String question) {
    private static final String NAME = "(?<name>[\\p{L}\\p{M}\\p{Nd} .’'-]{1,60}?)";
    private static final Pattern PREFIX = Pattern.compile(
            "(?iu)\\A(?:according to|theo (?:chính )?lời của)\\s+" + NAME + "\\s*,\\s*(?<fact>.+)\\z");
    private static final Pattern OWN_MESSAGE = Pattern.compile(
            "(?iu)\\Ain (?:her|his|their) own (?:message|words),\\s*"
                    + "(?<query>(?:what|when|where|which|how many|how much) did)\\s+" + NAME
                    + "\\s+(?<fact>(?:finish|complete|say|confirm|choose|decide|state|schedule|set|report|announce)\\b.*)\\z");

    static AskSpeakerLookup parse(String question) {
        String normalized = Tokenizer.nfc(question);
        Matcher prefix = PREFIX.matcher(normalized);
        if (prefix.matches() && !prefix.group("fact").isBlank()) {
            return new AskSpeakerLookup(prefix.group("name").strip(), prefix.group("fact").strip());
        }
        Matcher own = OWN_MESSAGE.matcher(normalized);
        if (own.matches()) {
            return new AskSpeakerLookup(own.group("name").strip(), own.group("query") + " they " + own.group("fact"));
        }
        return null;
    }

    static String normalizedName(String label) {
        return Tokenizer.nfc(Tokenizer.nfc(label).strip().toLowerCase(Locale.ROOT));
    }

    record Binding(String name, UUID senderId, Instant admittedFrom) {
    }
}
