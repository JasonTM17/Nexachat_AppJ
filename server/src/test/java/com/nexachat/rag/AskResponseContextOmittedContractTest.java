package com.nexachat.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract pin for {@link AskResponse#contextOmitted()}: the flag is a neutral
 * caller-facing boolean — not-found responses can carry {@code true}, the
 * compatibility constructor keeps it {@code false}, and the flag never
 * identifies which member opted out of AI processing.
 */
class AskResponseContextOmittedContractTest {

    @Test
    void notFoundCanCarryContextOmittedTrue() {
        AskResponse response = AskResponse.notFound(true);

        assertThat(response.contextOmitted()).isTrue();
        assertThat(response.mode()).isEqualTo(AskMode.NOT_FOUND);
        assertThat(response.answer()).isNull();
    }

    @Test
    void compatibilityConstructorKeepsContextOmittedFalse() {
        AskResponse response = new AskResponse("answer", List.of(), AskMode.EXTRACTIVE,
                new AskResponse.Usage(0, 0, false));

        assertThat(response.contextOmitted()).isFalse();
    }

    @Test
    void flagDoesNotIdentifyAMember() throws Exception {
        RecordComponent[] components = AskResponse.class.getRecordComponents();
        assertThat(components).isNotNull();
        Set<String> names = java.util.Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "answer", "citations", "mode", "contextOmitted", "usage");
        for (RecordComponent component : components) {
            assertThat(component.getType()).isNotEqualTo(UUID.class);
        }

        JsonNode json = new ObjectMapper().valueToTree(AskResponse.notFound(true));
        Set<String> fields = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "answer", "citations", "mode", "contextOmitted", "usage");
        assertThat(json.get("contextOmitted").isBoolean()).isTrue();
        assertThat(json.get("contextOmitted").asBoolean()).isTrue();
    }
}
