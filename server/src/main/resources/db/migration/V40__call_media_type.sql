ALTER TABLE call_records
    ADD COLUMN media_type VARCHAR(5) NOT NULL DEFAULT 'AUDIO',
    ADD CONSTRAINT ck_call_media_type CHECK (media_type IN ('AUDIO', 'VIDEO'));
