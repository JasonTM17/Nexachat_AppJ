CREATE TABLE call_records (
    call_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    caller_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    callee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    state VARCHAR(8) NOT NULL CHECK (state IN ('RINGING', 'ACTIVE', 'ENDED')),
    end_reason VARCHAR(12) CHECK (end_reason IN ('COMPLETED', 'REJECTED', 'CANCELLED', 'MISSED', 'BUSY', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL,
    answered_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    duration_seconds BIGINT,
    CONSTRAINT ck_call_participants_distinct CHECK (caller_id <> callee_id),
    CONSTRAINT ck_call_terminal_fields CHECK (
        (state = 'ENDED' AND end_reason IS NOT NULL AND ended_at IS NOT NULL AND duration_seconds IS NOT NULL)
        OR (state <> 'ENDED' AND end_reason IS NULL AND ended_at IS NULL AND duration_seconds IS NULL)
    ),
    CONSTRAINT ck_call_answered_state CHECK (state <> 'ACTIVE' OR answered_at IS NOT NULL),
    CONSTRAINT ck_call_duration_nonnegative CHECK (duration_seconds IS NULL OR duration_seconds >= 0)
);

CREATE INDEX idx_call_records_conversation_started
    ON call_records (conversation_id, started_at DESC, call_id DESC);
CREATE INDEX idx_call_records_caller_started
    ON call_records (caller_id, started_at DESC);
CREATE INDEX idx_call_records_callee_started
    ON call_records (callee_id, started_at DESC);
