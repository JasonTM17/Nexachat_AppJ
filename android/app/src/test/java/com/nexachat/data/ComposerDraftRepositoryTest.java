package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.database.ComposerDraftEntity;
import com.nexachat.feature.chat.MentionDraft;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class ComposerDraftRepositoryTest {

    @Test
    public void previewBodiesSkipBlankContent() {
        List<ComposerDraftEntity> rows = List.of(
                new ComposerDraftEntity("c1", " hello ", "[]", -1, 0, 1L),
                new ComposerDraftEntity("c2", "  ", "[]", -1, 0, 1L));

        Map<String, String> bodies = ComposerDraftRepository.previewBodies(rows);

        assertEquals("hello", bodies.get("c1"));
        assertFalse(bodies.containsKey("c2"));
        assertEquals(1, bodies.size());
    }

    @Test
    public void emptyRowsYieldEmptyMap() {
        assertTrue(ComposerDraftRepository.previewBodies(List.of()).isEmpty());
    }

    @Test
    public void toDraftRestoresPlainContent() {
        ComposerDraftEntity row = new ComposerDraftEntity(
                "c1", "later tonight", "[]", -1, 0, 1L);

        MentionDraft draft = ComposerDraftRepository.toDraft(row);

        assertEquals("later tonight", draft.getContent());
        assertTrue(draft.getMentions().isEmpty());
        assertFalse(draft.isMentionAll());
    }
}
