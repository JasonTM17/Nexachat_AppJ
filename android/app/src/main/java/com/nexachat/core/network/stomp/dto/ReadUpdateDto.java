package com.nexachat.core.network.stomp.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ReadUpdateDto {
    @Nullable private final String userId;
    @Nullable private final String readerId;
    @Nullable private final Long upToSeq;
    @Nullable private final Long lastReadSeq;

    public ReadUpdateDto() {
        this(null, null, null, null);
    }

    public ReadUpdateDto(@Nullable String userId, @Nullable String readerId, @Nullable Long upToSeq, @Nullable Long lastReadSeq) {
        this.userId = userId;
        this.readerId = readerId;
        this.upToSeq = upToSeq;
        this.lastReadSeq = lastReadSeq;
    }

    @Nullable public String getUserId() {
        return userId;
    }

    @Nullable public String getReaderId() {
        return readerId;
    }

    @Nullable public Long getUpToSeq() {
        return upToSeq;
    }

    @Nullable public Long getLastReadSeq() {
        return lastReadSeq;
    }


    @Nullable
    public String getResolvedUserId() {
        return userId != null ? userId : readerId;
    }

    @Nullable
    public Long getResolvedSeq() {
        return upToSeq != null ? upToSeq : lastReadSeq;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ReadUpdateDto that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && Objects.equals(readerId, that.readerId)
                && Objects.equals(upToSeq, that.upToSeq)
                && Objects.equals(lastReadSeq, that.lastReadSeq);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, readerId, upToSeq, lastReadSeq);
    }
}
