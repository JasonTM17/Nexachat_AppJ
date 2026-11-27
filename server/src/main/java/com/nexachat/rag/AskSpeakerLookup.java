package com.nexachat.rag;

import com.nexachat.summary.engine.Tokenizer;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit attribution supplied in the question, resolved locally to an eligible sender. */
record AskSpeakerLookup(String name, String question, boolean ambiguous) {
    private static final String NAME_CHARACTERS = "[\\p{L}\\p{M}\\p{Nd} .’'-]";
    private static final String NAME = "(?<name>" + NAME_CHARACTERS + "{1,60}?)";
    private static final Pattern PREFIX = Pattern.compile(
            "(?iu)\\A(?:according to|theo (?:chính )?lời của)\\s+" + NAME + "\\s*,\\s*(?<fact>.+)\\z");
    private static final Pattern VI_SPEAKER_CONNECTOR = Pattern.compile(
            "(?iu)\\Atheo (?:chính )?lời của\\s+(?<name>" + NAME_CHARACTERS + "{1,60})"
                    + "\\s+(?<connector>thì)\\s+(?<fact>.+)\\z");
    private static final Pattern VI_CONNECTOR_TOKEN = Pattern.compile("(?iu)(?<!\\S)thì(?!\\S)");
    private static final Pattern OWN_MESSAGE = Pattern.compile(
            "(?iu)\\Ain (?:her|his|their) own (?:message|words),\\s*"
                    + "(?<query>(?:what|when|where|which|how many|how much) did)\\s+" + NAME
                    + "\\s+(?<fact>(?:finish|complete|say|confirm|choose|decide|state|schedule|set|report|announce)\\b.*)\\z");

    static AskSpeakerLookup parse(String question) {
        String normalized = Tokenizer.nfc(question);
        Matcher prefix = PREFIX.matcher(normalized);
        if (prefix.matches() && !prefix.group("fact").isBlank()) {
            return new AskSpeakerLookup(prefix.group("name").strip(), prefix.group("fact").strip(), false);
        }
        Matcher conversational = VI_SPEAKER_CONNECTOR.matcher(normalized);
        if (conversational.matches() && !conversational.group("fact").isBlank()) {
            String name = conversational.group("name").strip();
            String fact = conversational.group("fact").strip();
            boolean ambiguous = ambiguousConnector(name, conversational.group("connector"), fact);
            return new AskSpeakerLookup(name, fact, ambiguous);
        }
        Matcher own = OWN_MESSAGE.matcher(normalized);
        if (own.matches()) {
            return new AskSpeakerLookup(own.group("name").strip(), own.group("query") + " they " + own.group("fact"), false);
        }
        return null;
    }

    private static boolean ambiguousConnector(String name, String connector, String fact) {
        // A title-cased word in the name can be followed by a lowercase separator.
        Matcher earlier = VI_CONNECTOR_TOKEN.matcher(name);
        while (earlier.find()) {
            if (!"Thì".equals(earlier.group()) || !"thì".equals(connector)) {
                return true;
            }
        }
        return VI_CONNECTOR_TOKEN.matcher(fact).find();
    }

    static String normalizedName(String label) {
        return Tokenizer.nfc(Tokenizer.nfc(label).strip().toLowerCase(Locale.ROOT));
    }

    record Binding(String name, UUID senderId, Instant admittedFrom) {
    }
}
