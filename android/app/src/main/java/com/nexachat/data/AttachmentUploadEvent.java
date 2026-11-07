package com.nexachat.data;

import androidx.annotation.NonNull;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.AttachmentDto;
import java.util.Objects;

/** One upload stream: zero or more progress events, then one terminal result. */
public interface AttachmentUploadEvent {

    /** Bytes written as a fraction from {@code 0f} through {@code 1f}. */
    final class Progress implements AttachmentUploadEvent {
        private final float fraction;

        public Progress(float fraction) {
            this.fraction = fraction;
        }

        public float getFraction() {
            return fraction;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Progress progress
                    && Float.compare(fraction, progress.fraction) == 0;
        }

        @Override
        public int hashCode() {
            return Float.hashCode(fraction);
        }
    }

    final class Uploaded implements AttachmentUploadEvent {
        @NonNull private final AttachmentDto attachment;

        public Uploaded(@NonNull AttachmentDto attachment) {
            this.attachment = Objects.requireNonNull(attachment, "attachment");
        }

        @NonNull
        public AttachmentDto getAttachment() {
            return attachment;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Uploaded uploaded
                    && attachment.equals(uploaded.attachment);
        }

        @Override
        public int hashCode() {
            return attachment.hashCode();
        }
    }

    final class Failed implements AttachmentUploadEvent {
        @NonNull private final ApiError error;

        public Failed(@NonNull ApiError error) {
            this.error = Objects.requireNonNull(error, "error");
        }

        @NonNull
        public ApiError getError() {
            return error;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Failed failed && error.equals(failed.error);
        }

        @Override
        public int hashCode() {
            return error.hashCode();
        }
    }
}
