package com.nexachat.data;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.AttachmentDto;

/** One upload stream: zero or more progress events, then one terminal result. */
public sealed interface AttachmentUploadEvent
        permits AttachmentUploadEvent.Progress,
                AttachmentUploadEvent.Uploaded,
                AttachmentUploadEvent.Failed {

    record Progress(float fraction) implements AttachmentUploadEvent {}

    record Uploaded(AttachmentDto attachment) implements AttachmentUploadEvent {}

    record Failed(ApiError error) implements AttachmentUploadEvent {}
}
