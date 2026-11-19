package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

/**
 * A {@code Range} header that is malformed or cannot be satisfied for the
 * current representation. Carries the representation size so the advice can
 * re-emit {@code Content-Range: bytes &#42;/size} alongside the error envelope.
 */
public class RangeNotSatisfiableException extends ApiException {

    private final long sizeBytes;

    public RangeNotSatisfiableException(long sizeBytes, String message) {
        super(ApiErrorCode.RANGE_NOT_SATISFIABLE, message);
        this.sizeBytes = sizeBytes;
    }

    /** @return the current representation length in bytes */
    public long getSizeBytes() {
        return sizeBytes;
    }
}
