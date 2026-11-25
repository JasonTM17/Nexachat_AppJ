-- RAG embeddings are model-aware: a message may hold one vector per embedding
-- provider/model without local fallback and external-provider rows overwriting
-- each other. Existing rows already satisfy the composite key because the old
-- message_id-only primary key is strictly stronger.

-- Drop whatever name PostgreSQL gave the old primary-key constraint. The V9
-- default is message_embeddings_pkey, but repaired/restored databases must not
-- depend on that generated name surviving unchanged.
DO $$
DECLARE
    old_pk_name text;
BEGIN
    SELECT conname INTO old_pk_name
    FROM pg_constraint
    WHERE conrelid = 'message_embeddings'::regclass
      AND contype = 'p';

    IF old_pk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE message_embeddings DROP CONSTRAINT %I', old_pk_name);
    END IF;
END $$;

ALTER TABLE message_embeddings
    ADD CONSTRAINT message_embeddings_pkey PRIMARY KEY (message_id, model);
