package com.nexachat.feature.chat;

import androidx.annotation.StringRes;

import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.AttachmentRepository;

/** Maps an {@link ApiError} to its user-facing string resource id for chat/inbox. */
public final class ChatErrorIds {

    /** Server error code for the conversation pin cap. */
    public static final String ERROR_PIN_LIMIT = "PIN_LIMIT";

    private ChatErrorIds() {}

    @StringRes
    public static int messageId(ApiError error) {
        if (error instanceof ApiError.InvalidCredentials) {
            return R.string.error_invalid_credentials;
        }
        if (error instanceof ApiError.PhoneExists) {
            return R.string.error_phone_exists;
        }
        if (error instanceof ApiError.RateLimited) {
            return R.string.error_rate_limited;
        }
        if (error instanceof ApiError.Network) {
            return R.string.error_network;
        }
        if (error instanceof ApiError.Unknown unknown) {
            String code = unknown.getCode();
            if (AttachmentRepository.ERROR_TOO_LARGE.equals(code)) {
                return R.string.error_attachment_too_large;
            }
            if (ERROR_PIN_LIMIT.equals(code)) {
                return R.string.error_pin_limit;
            }
            if (ChatViewModel.ERROR_MESSAGE_GONE.equals(code)) {
                return R.string.reply_unavailable;
            }
            if (ChatViewModel.ERROR_RECALL_WINDOW_EXPIRED.equals(code)) {
                return R.string.recall_window_expired;
            }
            if (com.nexachat.data.MessageRepository.ERROR_NOT_MEMBER.equals(code)
                    || com.nexachat.data.MessageRepository.ERROR_CONVERSATION_NOT_FOUND.equals(code)) {
                return R.string.error_not_member;
            }
        }
        return R.string.error_generic;
    }
}
