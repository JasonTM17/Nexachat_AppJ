package com.nexachat.feature.chat.adapter;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.bumptech.glide.Glide;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.nexachat.R;
import com.nexachat.call.ui.CallLogResourceResolver;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.ui.MessageUiRules;
import com.nexachat.core.ui.components.ChatUiRules;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.ReactionChip;
import com.nexachat.feature.chat.ChatBubbleRules;
import com.nexachat.feature.chat.ui.MessageActionListener;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

/**
 * Binds a {@link MessageListItem} message row onto the B1 bubble layouts.
 */
public final class MessageBubbleBinder {

    private final DateFormat timeFormat;
    @Nullable
    private final Function<String, ChatMessage> resolveOriginal;
    @Nullable
    private final MessageActionListener actionListener;

    public MessageBubbleBinder(
            @NonNull DateFormat timeFormat,
            @Nullable Function<String, ChatMessage> resolveOriginal,
            @Nullable MessageActionListener actionListener
    ) {
        this.timeFormat = timeFormat;
        this.resolveOriginal = resolveOriginal;
        this.actionListener = actionListener;
    }

    public void bindMessageRow(@NonNull View root, @NonNull MessageListItem item) {
        ChatMessage message = item.getMessage();
        if (message == null || item.getGroupPosition() == null) {
            return;
        }
        View bubble = root.findViewById(R.id.bubbleContainer);
        if (bubble == null) {
            return;
        }
        applyBubbleShape(bubble, item.getGroupPosition(), message.getMine());
        applyBubbleColors(root.getContext(), bubble, message, item.isPulsing());

        wireGestures(root, message);

        TextView senderName = root.findViewById(R.id.senderName);
        if (senderName != null) {
            if (item.shouldShowSenderName()
                    && !TextUtils.isEmpty(message.getSenderDisplayName())) {
                senderName.setVisibility(View.VISIBLE);
                senderName.setText(message.getSenderDisplayName());
            } else {
                senderName.setVisibility(View.GONE);
            }
        }

        bindReplyQuote(root, message);
        bindPrimaryContent(root, message);
        bindMetaRow(root, message, item.isSeenReceipt(), item.isDeliveredReceipt());
        bindReactions(root, message);
    }

    public void bindDateSeparator(@NonNull View root, @NonNull String label) {
        TextView dateLabel = root.findViewById(R.id.dateSeparatorLabel);
        if (dateLabel != null) {
            dateLabel.setText(label);
        }
    }

    private void wireGestures(View root, ChatMessage message) {
        boolean tombstone = MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs());
        boolean retry = ChatBubbleRules.isRetryAffordanceVisible(message.getStatus());
        root.setOnLongClickListener(v -> {
            if (!tombstone && actionListener != null) {
                actionListener.onMessageLongPress(message.getId());
                return true;
            }
            return false;
        });
        root.setOnClickListener(v -> {
            if (actionListener == null) {
                return;
            }
            if (retry) {
                String retryId = message.getClientMessageId() != null
                        ? message.getClientMessageId()
                        : message.getId();
                actionListener.onRetryTap(retryId);
            } else {
                actionListener.onMessageTap(message.getId());
            }
        });
    }

    private void applyBubbleShape(View bubble, MessageGroupPosition position, boolean mine) {
        ChatUiRules.BubbleCornerRadii radii = ChatUiRules.resolveBubbleShape(position, mine);
        float density = bubble.getResources().getDisplayMetrics().density;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadii(new float[]{
                radii.topStartDp * density,
                radii.topStartDp * density,
                radii.topEndDp * density,
                radii.topEndDp * density,
                radii.bottomEndDp * density,
                radii.bottomEndDp * density,
                radii.bottomStartDp * density,
                radii.bottomStartDp * density,
        });
        ColorStateList tint = bubble.getBackgroundTintList();
        if (tint != null) {
            drawable.setColor(tint.getDefaultColor());
        }
        bubble.setBackground(drawable);
    }

    private void applyBubbleColors(
            android.content.Context context,
            View bubble,
            ChatMessage message,
            boolean pulsing
    ) {
        int colorInt;
        if (pulsing) {
            colorInt = resolveThemeColor(context, com.google.android.material.R.attr.colorTertiaryContainer);
        } else if (MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs())) {
            colorInt = resolveThemeColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        } else {
            int colorRes;
            switch (ChatBubbleRules.resolveBubbleContainerRole(message.getStatus(), message.getMine())) {
                case ERROR:
                    colorRes = R.color.colorErrorContainer;
                    break;
                case MINE:
                    colorRes = R.color.bubble_mine;
                    break;
                case PEER:
                default:
                    colorRes = R.color.bubble_other;
                    break;
            }
            colorInt = ContextCompat.getColor(context, colorRes);
        }
        bubble.setBackgroundTintList(ColorStateList.valueOf(colorInt));
    }

    private static int resolveThemeColor(android.content.Context context, int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            return ContextCompat.getColor(context, R.color.bubble_other);
        }
        if (value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }

    private void bindReplyQuote(View root, ChatMessage message) {
        View replyInclude = root.findViewById(R.id.replyQuote);
        if (replyInclude == null) {
            return;
        }
        boolean tombstone = MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs());
        String replyId = message.getReplyToId();
        if (tombstone || replyId == null) {
            replyInclude.setVisibility(View.GONE);
            return;
        }
        ChatMessage original = resolveOriginal != null ? resolveOriginal.apply(replyId) : null;
        String snippet = message.getReplyToSnippet();
        if (snippet == null && original != null) {
            snippet = original.getContent();
        }
        String sender = message.getReplyToSenderName();
        if (sender == null && original != null) {
            sender = original.getSenderDisplayName();
        }
        boolean resolved = original != null || snippet != null || sender != null;
        if (!resolved) {
            replyInclude.setVisibility(View.GONE);
            return;
        }
        replyInclude.setVisibility(View.VISIBLE);
        TextView author = replyInclude.findViewById(R.id.replyAuthor);
        TextView snippetView = replyInclude.findViewById(R.id.replySnippet);
        if (author != null) {
            author.setText(sender != null ? sender : "");
        }
        if (snippetView != null) {
            snippetView.setText(snippet != null ? snippet : "");
        }
        replyInclude.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onReplyQuoteTap(replyId);
            }
        });
    }

    private void bindPrimaryContent(View root, ChatMessage message) {
        TextView body = root.findViewById(R.id.messageBody);
        ImageView image = root.findViewById(R.id.messageImage);
        ImageButton play = root.findViewById(R.id.playButton);
        TextView fileName = root.findViewById(R.id.fileName);
        TextView systemLabel = root.findViewById(R.id.systemLabel);

        hideAll(body, image, play, fileName, systemLabel);

        boolean tombstone = MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs());
        MessageContentKind kind = MessageViewTypeMapper.resolveContentKind(message);

        if (tombstone) {
            if (body != null) {
                body.setVisibility(View.VISIBLE);
                body.setText(R.string.message_deleted);
            }
            return;
        }

        if (kind == MessageContentKind.SYSTEM) {
            if (systemLabel != null) {
                systemLabel.setVisibility(View.VISIBLE);
                String raw = message.getContent() != null ? message.getContent() : "";
                CallLogResourceResolver.ResolvedCallLog resolved =
                        CallLogResourceResolver.fromSystemMessage(raw, message.getMine());
                systemLabel.setText(
                        root.getContext().getString(
                                resolved.getResourceId(),
                                resolved.getArguments()
                        )
                );
            }
            return;
        }

        if (kind == MessageContentKind.IMAGE && image != null && !message.getAttachments().isEmpty()) {
            image.setVisibility(View.VISIBLE);
            AttachmentDto attachment = message.getAttachments().get(0);
            String url = attachment.contentUrl();
            if (url != null) {
                Glide.with(image)
                        .load(url.startsWith("http") ? url : AppConfig.absoluteMediaUrl(url))
                        .centerCrop()
                        .into(image);
            }
            image.setOnClickListener(v -> openAttachment(message, attachment, true));
            return;
        }

        if ((kind == MessageContentKind.VIDEO || kind == MessageContentKind.AUDIO) && play != null) {
            play.setVisibility(View.VISIBLE);
            AttachmentDto attachment = message.getAttachments().isEmpty()
                    ? null
                    : message.getAttachments().get(0);
            play.setOnClickListener(v -> {
                if (attachment != null) {
                    openAttachment(message, attachment, kind == MessageContentKind.VIDEO);
                }
            });
            if (image != null && kind == MessageContentKind.VIDEO) {
                image.setVisibility(View.VISIBLE);
                if (attachment != null && attachment.contentUrl() != null) {
                    Glide.with(image)
                            .load(attachment.contentUrl())
                            .centerCrop()
                            .into(image);
                }
            }
            return;
        }

        if (kind == MessageContentKind.FILE && fileName != null) {
            fileName.setVisibility(View.VISIBLE);
            AttachmentDto attachment = message.getAttachments().isEmpty()
                    ? null
                    : message.getAttachments().get(0);
            String label = attachment != null && attachment.getOriginalName() != null
                    ? attachment.getOriginalName()
                    : root.getContext().getString(R.string.cd_open_attachment);
            fileName.setText(label);
            fileName.setOnClickListener(v -> {
                if (attachment != null) {
                    openAttachment(message, attachment, false);
                }
            });
            return;
        }

        if (body != null) {
            body.setVisibility(View.VISIBLE);
            String raw = message.getContent() != null ? message.getContent() : message.getType();
            if (raw != null && raw.startsWith("CALL|")) {
                CallLogResourceResolver.ResolvedCallLog resolved =
                        CallLogResourceResolver.fromSystemMessage(raw, message.getMine());
                body.setText(
                        root.getContext().getString(resolved.getResourceId(), resolved.getArguments())
                );
            } else {
                body.setText(raw != null ? raw : "");
            }
        }
    }

    private void openAttachment(ChatMessage message, AttachmentDto attachment, boolean media) {
        if (actionListener == null) {
            return;
        }
        if (media) {
            actionListener.onOpenMedia(message.getId(), attachment);
        } else {
            actionListener.onOpenFile(message.getId(), attachment);
        }
    }

    private void bindMetaRow(View root, ChatMessage message, boolean seen, boolean delivered) {
        View metaInclude = root.findViewById(R.id.bubbleMeta);
        if (metaInclude == null) {
            return;
        }
        TextView edited = metaInclude.findViewById(R.id.editedLabel);
        TextView time = metaInclude.findViewById(R.id.timeLabel);
        ProgressBar sending = metaInclude.findViewById(R.id.sendingProgress);
        ImageView deliveredIcon = metaInclude.findViewById(R.id.deliveryIcon);
        TextView deliveryText = metaInclude.findViewById(R.id.deliveryStatusText);

        boolean isMine = message.getMine();
        int metaColor = resolveThemeColor(
                root.getContext(),
                isMine
                        ? com.google.android.material.R.attr.colorOnPrimaryContainer
                        : com.google.android.material.R.attr.colorOnSurfaceVariant
        );
        float labelAlpha = isMine ? 0.78f : 1.0f;

        if (edited != null) {
            boolean showEdited = MessageUiRules.isEdited(
                    message.getEditedAtEpochMs(),
                    message.getDeletedAtEpochMs()
            );
            edited.setVisibility(showEdited ? View.VISIBLE : View.GONE);
            if (showEdited) {
                edited.setText(R.string.message_edited);
                edited.setTextColor(metaColor);
                edited.setAlpha(labelAlpha);
            }
        }
        if (time != null) {
            time.setText(timeFormat.format(new Date(message.getCreatedAtEpochMs())));
            time.setTextColor(metaColor);
            time.setAlpha(labelAlpha);
        }
        if (sending != null) {
            sending.setVisibility(
                    isMine && message.getStatus() == DeliveryStatus.SENDING
                            ? View.VISIBLE
                            : View.GONE
            );
            if (isMine) {
                sending.setIndeterminateTintList(ColorStateList.valueOf(metaColor));
            }
        }
        if (deliveredIcon != null) {
            deliveredIcon.setVisibility(View.GONE);
            deliveredIcon.setImageTintList(ColorStateList.valueOf(metaColor));
            deliveredIcon.setAlpha(1.0f);
        }
        if (deliveryText != null) {
            deliveryText.setVisibility(View.GONE);
            deliveryText.setAlpha(1.0f);
        }

        if (!isMine) {
            return;
        }

        if (message.getStatus() == DeliveryStatus.FAILED) {
            if (deliveryText != null) {
                deliveryText.setVisibility(View.VISIBLE);
                deliveryText.setText(R.string.action_retry);
                deliveryText.setTextColor(
                        ContextCompat.getColor(root.getContext(), R.color.colorError)
                );
            }
            if (deliveredIcon != null) {
                deliveredIcon.setVisibility(View.GONE);
            }
        } else if (message.getStatus() == DeliveryStatus.SENDING) {
            if (deliveryText != null) deliveryText.setVisibility(View.GONE);
            if (deliveredIcon != null) deliveredIcon.setVisibility(View.GONE);
        } else if (message.getStatus() == DeliveryStatus.READ || seen) {
            if (deliveryText != null) deliveryText.setVisibility(View.GONE);
            if (deliveredIcon != null) {
                deliveredIcon.setVisibility(View.VISIBLE);
                deliveredIcon.setImageResource(R.drawable.ic_check_double);
                deliveredIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(root.getContext(), R.color.tick_read)
                ));
                deliveredIcon.setContentDescription(
                        root.getContext().getString(R.string.cd_read)
                );
            }
        } else if (message.getStatus() == DeliveryStatus.DELIVERED || delivered) {
            if (deliveryText != null) deliveryText.setVisibility(View.GONE);
            if (deliveredIcon != null) {
                deliveredIcon.setVisibility(View.VISIBLE);
                deliveredIcon.setImageResource(R.drawable.ic_check_double);
                deliveredIcon.setImageTintList(ColorStateList.valueOf(metaColor));
                deliveredIcon.setContentDescription(
                        root.getContext().getString(R.string.msg_delivered)
                );
            }
        } else if (message.getStatus() == DeliveryStatus.SENT) {
            if (deliveryText != null) deliveryText.setVisibility(View.GONE);
            if (deliveredIcon != null) {
                deliveredIcon.setVisibility(View.VISIBLE);
                deliveredIcon.setImageResource(R.drawable.ic_check);
                deliveredIcon.setImageTintList(ColorStateList.valueOf(metaColor));
                deliveredIcon.setContentDescription(
                        root.getContext().getString(R.string.cd_sent)
                );
            }
        }
    }

    private void bindReactions(View root, ChatMessage message) {
        View reactionsInclude = root.findViewById(R.id.reactionsRow);
        if (reactionsInclude == null) {
            return;
        }
        ChipGroup group = reactionsInclude instanceof ChipGroup
                ? (ChipGroup) reactionsInclude
                : reactionsInclude.findViewById(R.id.reactionChipGroup);
        if (group == null) {
            return;
        }
        group.removeAllViews();
        boolean tombstone = MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs());
        List<ReactionChip> reactions = message.getReactions();
        if (tombstone || reactions == null || reactions.isEmpty()) {
            reactionsInclude.setVisibility(View.GONE);
            return;
        }
        reactionsInclude.setVisibility(View.VISIBLE);
        for (ReactionChip chip : reactions) {
            Chip view = new Chip(root.getContext());
            view.setText(chip.getEmoji() + " " + chip.getCount());
            view.setCheckable(false);
            view.setClickable(true);
            view.setChipBackgroundColorResource(
                    chip.getReactedByMe() ? R.color.brand_indigo_container : R.color.bubble_other
            );
            view.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onReactionTap(message.getId(), chip.getEmoji());
                }
            });
            group.addView(view);
        }
    }

    private static void hideAll(View... views) {
        for (View view : views) {
            if (view != null) {
                view.setVisibility(View.GONE);
            }
        }
    }
}
