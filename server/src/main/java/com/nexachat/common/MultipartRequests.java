package com.nexachat.common;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Validation shared by the multipart upload endpoints.
 */
public final class MultipartRequests {

    private MultipartRequests() {
        // Utility class.
    }

    /**
     * A multipart request must carry exactly one {@code file} part. Spring
     * silently resolves duplicate parts to the first one, so an ambiguous
     * request is rejected instead of being stored under whichever part won.
     *
     * @param files parts bound to the {@code file} name
     * @return the single part
     * @throws ApiException {@code VALIDATION_FAILED} when the count is not one
     */
    public static MultipartFile requireSingleFile(List<MultipartFile> files) {
        if (files == null || files.size() != 1) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Exactly one file part is required");
        }
        return files.get(0);
    }
}
