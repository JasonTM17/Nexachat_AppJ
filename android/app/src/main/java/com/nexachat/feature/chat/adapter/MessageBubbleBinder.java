package com.nexachat.feature.chat.adapter;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.nexachat.R;
import com.nexachat.call.ui.CallLogResourceResolver;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.media.AttachmentBlurHashRegistry;
import com.nexachat.core.media.BlurHashDecoder;
import com.nexachat.core.media.BlurHashPlaceholderCache;
import com.nexachat.core.media.ImageAttachmentBinding;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.ui.MessageUiRules;
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

    private static final int BLUR_HASH_BITMAP_EDGE = 32;
    private static final BlurHashPlaceholderCache<Bitmap> BLUR_HASH_BITMAPS =
            new BlurHashPlaceholderCache<>();
    private static final ImageAttachmentBinding<Drawable> IMAGE_BINDING =
            new ImageAttachmentBinding<>();
    private static final int[] SENDER_NAME_COLORS = {
            R.color.colorPrimary,
            R.color.colorTertiary,
            R.color.slate_muted,
            R.color.colorError,
            R.color.colorOnPrimaryContainer,
            R.color.colorOnSecondaryContainer,
            R.color.colorOnTertiaryContainer,
            R.color.colorOnErrorContainer
    };

    private final DateFormat timeFormat;
    @Nullable
    private final Function<String, ChatMessage> resolveOriginal;
    @Nullable
    private final MessageActionListener actionListener;
    @Nullable
    private final LifecycleOwner voiceLifecycleOwner;
    @Nullable
    private final ViewModelStoreOwner voiceStoreOwner;

    public MessageBubbleBinder(
            @NonNull DateFormat timeFormat,
            @Nullable Function<String, ChatMessage> resolveOriginal,
            @Nullable MessageActionListener actionListener
    ) {
        this(timeFormat, resolveOriginal, actionListener, null, null);
    }

    public MessageBubbleBinder(
            @NonNull DateFormat timeFormat,
            @Nullable Function<String, ChatMessage> resolveOriginal,
            @Nullable MessageActionListener actionListener,
            @Nullable LifecycleOwner voiceLifecycleOwner,
            @Nullable ViewModelStoreOwner voiceStoreOwner
    ) {
        this.timeFormat = timeFormat;
        this.resolveOriginal = resolveOriginal;
        this.actionListener = actionListener;
        this.voiceLifecycleOwner = voiceLifecycleOwner;
        this.voiceStoreOwner = voiceStoreOwner;
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
        applyRowSpacing(root, item.getGroupPosition());
        applyBubbleShape(bubble, item.getGroupPosition(), message.getMine());
        applyBubbleColors(root.getContext(), bubble, message, item.isPulsing());

        wireGestures(root, message);

        TextView senderName = root.findViewById(R.id.senderName);
        if (senderName != null) {
            if (item.shouldShowSenderName()
                    && !TextUtils.isEmpty(message.getSenderDisplayName())) {
                senderName.setVisibility(View.VISIBLE);
                senderName.setText(message.getSenderDisplayName());
                int senderColor;
                if (item.isPulsing()) {
                    senderColor = resolveThemeColor(
                            root.getContext(), com.google.android.material.R.attr.colorOnTertiaryContainer);
                } else if (MessageUiRules.isTombstone(
                        message.isDeleted(), message.getDeletedAtEpochMs())) {
                    senderColor = resolveThemeColor(
                            root.getContext(), com.google.android.material.R.attr.colorOnSurface);
                } else {
                    int colorIndex = ChatBubbleRules.resolveSenderPaletteIndex(message.getSenderId());
                    senderColor = ContextCompat.getColor(root.getContext(), SENDER_NAME_COLORS[colorIndex]);
                }
                senderName.setTextColor(senderColor);
            } else {
                senderName.setVisibility(View.GONE);
            }
        }

        bindReplyQuote(root, message);
        bindPrimaryContent(root, message);
        applyMessageTextColors(root, message, item.isPulsing());
        bindMetaRow(root, message, item.isSeenReceipt(), item.isDeliveredReceipt(), item.isPulsing());
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
        float density = bubble.getResources().getDisplayMetrics().density;
        float radius = 18f * density;
        float tailRadius = 4f * density;
        float bottomLeft = radius;
        float bottomRight = radius;
        boolean isTail = position == MessageGroupPosition.SINGLE || position == MessageGroupPosition.LAST;
        boolean tailOnRight = mine != (bubble.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        if (isTail && tailOnRight) {
            bottomRight = tailRadius;
        } else if (isTail) {
            bottomLeft = tailRadius;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadii(new float[]{
                radius,
                radius,
                radius,
                radius,
                bottomRight,
                bottomRight,
                bottomLeft,
                bottomLeft,
        });
        ColorStateList tint = bubble.getBackgroundTintList();
        if (tint != null) {
            drawable.setColor(tint.getDefaultColor());
        }
        bubble.setBackground(drawable);
    }

    private void applyRowSpacing(View root, MessageGroupPosition position) {
        float density = root.getResources().getDisplayMetrics().density;
        int top = Math.round(ChatBubbleRules.bubbleRowPaddingTopDp(position) * density);
        int bottom = Math.round(ChatBubbleRules.bubbleRowPaddingBottomDp(position) * density);
        root.setPadding(root.getPaddingLeft(), top, root.getPaddingRight(), bottom);
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
        } else if (MessageViewTypeMapper.isSystemMessage(message)) {
            colorInt = resolveThemeColor(context, com.google.android.material.R.attr.colorSurfaceVariant);
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

        if (image != null) {
            image.setClipToOutline(image.getBackground() != null);
            Glide.with(image).clear(image);
            image.setTag(null);
            image.setImageDrawable(null);
        }
        hideAll(body, image, play, fileName, systemLabel);
        if (VoiceBubbleBinder.bindIfVoice(
                root, message, voiceLifecycleOwner, voiceStoreOwner)) return;

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
            String hash = attachment.getBlurHash() != null
                    ? attachment.getBlurHash()
                    : AttachmentBlurHashRegistry.get(attachment.getId());
            Drawable placeholder = createBlurHashPlaceholder(image, hash);
            String remoteUrl = url == null
                    ? null
                    : url.startsWith("http") ? url : AppConfig.absoluteMediaUrl(url);
            bindImage(image, remoteUrl, placeholder);
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

    private void applyMessageTextColors(View root, ChatMessage message, boolean pulsing) {
        int color;
        if (MessageViewTypeMapper.isSystemMessage(message)) {
            color = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        } else if (pulsing) {
            color = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnTertiaryContainer);
        } else if (MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs())) {
            color = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurface);
        } else if (message.getStatus() == DeliveryStatus.FAILED) {
            color = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnErrorContainer);
        } else if (message.getMine()) {
            color = ContextCompat.getColor(root.getContext(), R.color.chat_bubble_outgoing_text);
        } else {
            color = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurface);
        }

        TextView body = root.findViewById(R.id.messageBody);
        TextView fileName = root.findViewById(R.id.fileName);
        TextView systemLabel = root.findViewById(R.id.systemLabel);
        if (body != null) {
            body.setTextColor(color);
        }
        if (fileName != null) {
            boolean fileTile = fileName.getVisibility() == View.VISIBLE;
            if (fileTile) {
                fileName.setTextColor(resolveThemeColor(
                        root.getContext(), com.google.android.material.R.attr.colorOnSurface));
                TextViewCompat.setCompoundDrawableTintList(fileName, ColorStateList.valueOf(resolveThemeColor(
                        root.getContext(), com.google.android.material.R.attr.colorPrimary)));
            } else {
                fileName.setTextColor(color);
                TextViewCompat.setCompoundDrawableTintList(fileName, ColorStateList.valueOf(color));
            }
        }
        if (systemLabel != null) {
            systemLabel.setTextColor(resolveThemeColor(
                    root.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
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

    private static void bindImage(
            ImageView image,
            @Nullable String url,
            @Nullable Drawable placeholder) {
        ImageAttachmentBinding.Target<Drawable> target = new ImageAttachmentBinding.Target<>() {
            @Override
            public void clearRequest() {
                Glide.with(image).clear(image);
            }

            @Override
            public void setRequestToken(@NonNull Object requestToken) {
                image.setTag(requestToken);
            }

            @Nullable
            @Override
            public Object requestToken() {
                return image.getTag();
            }

            @Override
            public void showPlaceholder(@Nullable Drawable value) {
                image.setImageDrawable(value);
            }
        };
        IMAGE_BINDING.bind(url, placeholder, target, (remoteUrl, value, token, bindingTarget) -> {
            RequestListener<Drawable> listener = new RequestListener<>() {
                @Override
                public boolean onLoadFailed(
                        @Nullable GlideException exception,
                        Object model,
                        @NonNull Target<Drawable> requestTarget,
                        boolean firstResource) {
                    return !ImageAttachmentBinding.isCurrent(bindingTarget, token);
                }

                @Override
                public boolean onResourceReady(
                        @NonNull Drawable resource,
                        Object model,
                        @NonNull Target<Drawable> requestTarget,
                        @NonNull DataSource dataSource,
                        boolean firstResource) {
                    return !ImageAttachmentBinding.isCurrent(bindingTarget, token);
                }
            };
            var request = Glide.with(image)
                    .load(remoteUrl)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .listener(listener);
            if (value != null) {
                request = request.placeholder(value);
            }
            request.into(image);
        });
    }

    @Nullable
    private static Drawable createBlurHashPlaceholder(ImageView image, @Nullable String hash) {
        if (hash == null) {
            return null;
        }
        Bitmap bitmap = BLUR_HASH_BITMAPS.getOrCreate(hash, value -> {
            try {
                int[] pixels = BlurHashDecoder.decode(
                        value, BLUR_HASH_BITMAP_EDGE, BLUR_HASH_BITMAP_EDGE);
                return Bitmap.createBitmap(
                        pixels,
                        BLUR_HASH_BITMAP_EDGE,
                        BLUR_HASH_BITMAP_EDGE,
                        Bitmap.Config.ARGB_8888);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                return null;
            }
        });
        return bitmap == null ? null : new BitmapDrawable(image.getResources(), bitmap);
    }

    private void bindMetaRow(
            View root,
            ChatMessage message,
            boolean seen,
            boolean delivered,
            boolean pulsing
    ) {
        View metaInclude = root.findViewById(R.id.bubbleMeta);
        if (metaInclude == null) {
            return;
        }
        MessageContentKind contentKind = MessageViewTypeMapper.resolveContentKind(message);
        boolean mediaMeta = contentKind == MessageContentKind.IMAGE
                || contentKind == MessageContentKind.VIDEO;
        float density = root.getResources().getDisplayMetrics().density;
        if (mediaMeta) {
            metaInclude.setBackgroundResource(R.drawable.bg_message_image_meta);
            int horizontalPadding = Math.round(8f * density);
            int verticalPadding = Math.round(4f * density);
            metaInclude.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        } else {
            metaInclude.setBackground(null);
            metaInclude.setPadding(0, 0, 0, 0);
        }
        TextView edited = metaInclude.findViewById(R.id.editedLabel);
        TextView time = metaInclude.findViewById(R.id.timeLabel);
        ProgressBar sending = metaInclude.findViewById(R.id.sendingProgress);
        ImageView deliveredIcon = metaInclude.findViewById(R.id.deliveryIcon);
        TextView deliveryText = metaInclude.findViewById(R.id.deliveryStatusText);

        boolean isMine = message.getMine();
        int metaColor;
        if (mediaMeta) {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurface);
        } else if (pulsing) {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnTertiaryContainer);
        } else if (MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs())) {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurface);
        } else if (message.getStatus() == DeliveryStatus.FAILED) {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnErrorContainer);
        } else if (isMine) {
            metaColor = ContextCompat.getColor(root.getContext(), R.color.chat_bubble_outgoing_text);
        } else {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        }

        if (edited != null) {
            boolean showEdited = MessageUiRules.isEdited(
                    message.getEditedAtEpochMs(),
                    message.getDeletedAtEpochMs()
            );
            edited.setVisibility(showEdited ? View.VISIBLE : View.GONE);
            if (showEdited) {
                edited.setText(R.string.message_edited);
                edited.setTextColor(metaColor);
                edited.setAlpha(1.0f);
            }
        }
        if (time != null) {
            time.setText(timeFormat.format(new Date(message.getCreatedAtEpochMs())));
            time.setTextColor(metaColor);
            time.setAlpha(1.0f);
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
                        mediaMeta
                                ? resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorTertiary)
                                : ContextCompat.getColor(root.getContext(), R.color.chat_bubble_read_tick)
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
        int overlapPx = Math.round(6f * root.getResources().getDisplayMetrics().density);
        int index = 0;
        for (ReactionChip chip : reactions) {
            Chip view = new Chip(root.getContext());
            view.setText(chip.getEmoji() + " " + chip.getCount());
            view.setCheckable(false);
            view.setClickable(true);
            view.setTextSize(12f);
            view.setChipCornerRadius(16f * root.getResources().getDisplayMetrics().density);
            view.setChipMinHeight(28f * root.getResources().getDisplayMetrics().density);
            view.setEnsureMinTouchTargetSize(false);
            view.setTextColor(resolveThemeColor(
                    root.getContext(), com.google.android.material.R.attr.colorOnSurface));
            view.setChipStrokeWidth(root.getResources().getDisplayMetrics().density);
            view.setChipStrokeColor(ColorStateList.valueOf(resolveThemeColor(
                    root.getContext(), com.google.android.material.R.attr.colorOutlineVariant)));
            view.setChipBackgroundColorResource(
                    chip.getReactedByMe() ? R.color.brand_indigo_container : R.color.bubble_other
            );
            view.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onReactionTap(message.getId(), chip.getEmoji());
                }
            });
            ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(index == 0 ? 0 : -overlapPx);
            group.addView(view, params);
            index++;
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
