package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.SummaryLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

record RagRepresentativeCorpus(
        int version, String split, long seed, List<Conversation> conversations, List<Question> questions) {

    static RagRepresentativeCorpus load(String split) throws IOException {
        String path = "/rag/eval/" + split + "/v1/manifest.json";
        try (InputStream input = RagRepresentativeCorpus.class.getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            RagRepresentativeCorpus corpus = new ObjectMapper().readValue(input, RagRepresentativeCorpus.class);
            assertThat(corpus.version()).isEqualTo(1);
            assertThat(corpus.split()).isEqualTo(split);
            corpus.validate();
            return corpus;
        }
    }

    void assertDisjointFrom(RagRepresentativeCorpus other) {
        assertThat(seed).isNotEqualTo(other.seed());
        assertThat(conversations).extracting(Conversation::id)
                .doesNotContainAnyElementsOf(other.conversations().stream().map(Conversation::id).toList());
        assertThat(messages()).extracting(Message::id)
                .doesNotContainAnyElementsOf(other.messages().stream().map(Message::id).toList());
        assertThat(messages()).extracting(message -> normalized(message.text()))
                .doesNotContainAnyElementsOf(other.messages().stream().map(m -> normalized(m.text())).toList());
        assertThat(questions).extracting(Question::id)
                .doesNotContainAnyElementsOf(other.questions().stream().map(Question::id).toList());
        assertThat(questions).extracting(question -> normalized(question.text()))
                .doesNotContainAnyElementsOf(other.questions().stream().map(q -> normalized(q.text())).toList());
    }

    private void validate() {
        assertThat(seed).isPositive();
        assertThat(conversations).isNotEmpty().extracting(Conversation::id).doesNotHaveDuplicates();
        assertThat(messages()).isNotEmpty().extracting(Message::id).doesNotHaveDuplicates();
        assertThat(questions).isNotEmpty().extracting(Question::id).doesNotHaveDuplicates();
        assertThat(questions).extracting(Question::category).contains(Category.values());
        assertThat(questions).extracting(Question::language).containsOnly(SummaryLanguage.EN, SummaryLanguage.VI);
        assertThat(questions.stream().filter(Question::answerable).map(Question::language).toList())
                .contains(SummaryLanguage.EN, SummaryLanguage.VI);

        Map<String, Conversation> conversationsById = conversations.stream()
                .collect(Collectors.toUnmodifiableMap(Conversation::id, Function.identity()));
        for (Question question : questions) {
            assertThat(question.text()).as(question.id()).isNotBlank();
            Conversation conversation = conversationsById.get(question.conversation());
            assertThat(conversation).as(question.id()).isNotNull();
            assertThat(question.gold()).as(question.id()).isNotNull().doesNotHaveDuplicates();
            if (!question.answerable()) {
                assertThat(question.gold()).as(question.id()).isEmpty();
                assertThat(question.answerContains()).as(question.id()).isNull();
                continue;
            }
            assertThat(question.gold()).as(question.id()).isNotEmpty();
            assertThat(question.answerContains()).as(question.id()).isNotBlank();
            List<Message> gold = conversation.messages().stream()
                    .filter(message -> question.gold().contains(message.id()) && !message.optedOut())
                    .toList();
            assertThat(gold).as(question.id()).extracting(Message::id)
                    .containsExactlyInAnyOrderElementsOf(question.gold());
            assertThat(gold).as(question.id()).anySatisfy(message -> assertThat(message.text())
                    .containsIgnoringCase(question.answerContains()));
            if (question.category() == Category.EDITED) {
                assertThat(gold).as(question.id()).anyMatch(Message::edited);
            } else if (question.category() == Category.NICKNAME) {
                assertThat(gold).as(question.id()).anyMatch(message -> question.text().contains(message.speaker()));
            } else if (question.category() == Category.LONG_HISTORY) {
                assertThat(conversation.fillerCount()).as(question.id()).isGreaterThan(200);
            }
        }
    }

    private List<Message> messages() {
        return conversations.stream().flatMap(conversation -> conversation.messages().stream()).toList();
    }

    private static String normalized(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }

    record Conversation(String id, int fillerCount, List<Message> messages) {}

    record Message(String id, String speaker, String text, boolean edited, boolean optedOut) {}

    record Question(String id, String conversation, SummaryLanguage language, Category category, String text,
                    List<String> gold, String answerContains) {
        boolean answerable() {
            return category.answerable;
        }
    }

    enum Category {
        LOOKUP(true), EDITED(true), NICKNAME(true), UNACCENTED(true), LONG_HISTORY(true),
        AMBIGUOUS(false), DISTRACTOR(false), OPTED_OUT(false), UNSUPPORTED(false);

        private final boolean answerable;

        Category(boolean answerable) {
            this.answerable = answerable;
        }
    }
}
