-- Derived answers must be tied to all source context, including uncited text.
ALTER TABLE conversations
    ADD COLUMN ai_context_version BIGINT NOT NULL DEFAULT 0 CHECK (ai_context_version >= 0);

-- Legacy answers do not record their complete source context and cannot be replayed.
DELETE FROM rag_ask_cache;
ALTER TABLE rag_ask_cache
    ADD COLUMN context_version BIGINT NOT NULL DEFAULT 0 CHECK (context_version >= 0);

CREATE INDEX idx_msg_ai_context_expiry
    ON messages (conversation_id, expires_at)
    WHERE type = 'TEXT' AND expires_at IS NOT NULL AND deleted_at IS NULL;

CREATE FUNCTION bump_message_ai_context() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    old_conversation UUID;
    new_conversation UUID;
    affected_conversation UUID;
BEGIN
    IF TG_OP = 'UPDATE' AND
       ROW(OLD.type, OLD.content, OLD.sender_id, OLD.seq, OLD.conversation_id,
           OLD.reply_to_id, OLD.encryption, OLD.created_at, OLD.edited_at,
           OLD.deleted_at, OLD.expires_at) IS NOT DISTINCT FROM
       ROW(NEW.type, NEW.content, NEW.sender_id, NEW.seq, NEW.conversation_id,
           NEW.reply_to_id, NEW.encryption, NEW.created_at, NEW.edited_at,
           NEW.deleted_at, NEW.expires_at) THEN
        RETURN NULL;
    END IF;
    IF TG_OP <> 'INSERT' AND OLD.type = 'TEXT' THEN
        old_conversation := OLD.conversation_id;
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.type = 'TEXT' THEN
        new_conversation := NEW.conversation_id;
    END IF;
    FOR affected_conversation IN
        SELECT DISTINCT id FROM (VALUES (old_conversation), (new_conversation)) AS affected(id)
        WHERE id IS NOT NULL ORDER BY id
    LOOP
        -- A cascading conversation deletion leaves no parent to update, which is harmless.
        UPDATE conversations SET ai_context_version = ai_context_version + 1
        WHERE id = affected_conversation;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER messages_ai_context_changed
    AFTER INSERT OR UPDATE OR DELETE ON messages
    FOR EACH ROW EXECUTE FUNCTION bump_message_ai_context();

CREATE FUNCTION bump_opt_out_ai_context() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    old_conversation UUID;
    new_conversation UUID;
    affected_conversation UUID;
BEGIN
    IF TG_OP = 'UPDATE' AND ROW(OLD.conversation_id, OLD.user_id) IS NOT DISTINCT FROM
                              ROW(NEW.conversation_id, NEW.user_id) THEN
        RETURN NULL;
    END IF;
    IF TG_OP <> 'INSERT' THEN old_conversation := OLD.conversation_id; END IF;
    IF TG_OP <> 'DELETE' THEN new_conversation := NEW.conversation_id; END IF;
    FOR affected_conversation IN
        SELECT DISTINCT id FROM (VALUES (old_conversation), (new_conversation)) AS affected(id)
        WHERE id IS NOT NULL ORDER BY id
    LOOP
        UPDATE conversations SET ai_context_version = ai_context_version + 1
        WHERE id = affected_conversation;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER opt_out_ai_context_changed
    AFTER INSERT OR UPDATE OR DELETE ON conversation_ai_opt_outs
    FOR EACH ROW EXECUTE FUNCTION bump_opt_out_ai_context();

CREATE FUNCTION bump_e2ee_ai_context() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE conversations SET ai_context_version = ai_context_version + 1 WHERE id = NEW.id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER e2ee_ai_context_changed
    AFTER UPDATE OF e2ee_enabled ON conversations
    FOR EACH ROW WHEN (OLD.e2ee_enabled IS DISTINCT FROM NEW.e2ee_enabled)
    EXECUTE FUNCTION bump_e2ee_ai_context();
