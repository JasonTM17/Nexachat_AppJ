package com.nexachat.feature.chat.adapter;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.TextViewCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import com.nexachat.res.QuantityStrings;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.nexachat.R;
import com.nexachat.feature.chat.system.SystemEventFormatter;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.media.AttachmentBlurHashRegistry;
import com.nexachat.core.media.BlurHashDecoder;
import com.nexachat.core.media.BlurHashPlaceholderCache;
import com.nexachat.core.media.ImageAttachmentBinding;
import com.nexachat.core.media.VideoThumbnailLoader;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageMention;
import com.nexachat.core.ui.MessageUiRules;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatColor;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.ReactionChip;
import com.nexachat.feature.chat.ChatBubbleColors;
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
            R.color.sender_violet,
            R.color.sender_blue,
            R.color.sender_cyan,
            R.color.sender_olive,
            R.color.sender_bronze,
            R.color.sender_rose,
            R.color.sender_green
    };

    private final DateFormat timeFormat;
    @Nullable private String currentUserId;
    /** Outgoing bubble colour picked in Profile; plum until the view model pushes one. */
    @NonNull private ChatColor chatColor = ChatColor.PLUM;
    /** While non-zero, a conversation-level send pause is in force: rows the
     *  pause itself failed show this short reason instead of the retry label
     *  and taps do not retry; ordinary FAILED rows keep their own copy. */
    @StringRes private int pausedFailReasonRes;
    @Nullable
    private final Function<String, ChatMessage> resolveOriginal;
    @Nullable
    private final MessageActionListener actionListener;
    @Nullable
    private final LifecycleOwner voiceLifecycleOwner;
    @Nullable
    private final ViewModelStoreOwner voiceStoreOwner;
    private final MessageBubbleLayoutBinder layoutBinder = new MessageBubbleLayoutBinder();

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

    public void setCurrentUserId(@Nullable String userId) { currentUserId = userId; }

    /** Applies the user-selected outgoing bubble colour on the next bind. */
    public void setChatColor(@NonNull ChatColor color) { chatColor = color; }

    /** Sets the failure reason shown on FAILED rows while sends are paused, or 0. */
    public void setPausedFailReason(@StringRes int reasonRes) {
        pausedFailReasonRes = reasonRes;
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
        layoutBinder.bind(root);
        applyRowSpacing(root, item.getGroupPosition());
        boolean systemMessage = MessageViewTypeMapper.isSystemMessage(message);
        if (!systemMessage) {
            root.setMinimumHeight(root.getResources().getDimensionPixelSize(R.dimen.min_touch_target));
            int horizontal = root.getResources().getDimensionPixelSize(R.dimen.bubble_content_inset);
            int vertical = root.getResources().getDimensionPixelSize(R.dimen.space_sm);
            bubble.setPadding(horizontal, vertical, horizontal, vertical);
        } else {
            root.setMinimumHeight(0);
        }
        if (systemMessage) {
            bubble.setBackgroundResource(R.drawable.bg_date_separator);
        } else {
            applyBubbleShape(bubble, item.getGroupPosition(), message.getMine());
        }
        applyBubbleColors(root.getContext(), bubble, message, item.isPulsing());
        boolean mentionedMe = isMentionedUser(message, currentUserId);
        if (mentionedMe) {
            int rowHighlight = resolveThemeColor(root.getContext(),
                    com.google.android.material.R.attr.colorSecondaryContainer);
            bubble.setBackgroundTintList(ColorStateList.valueOf(rowHighlight));
        }
        root.setBackground(null);

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
                    senderColor = ContextCompat.getColor(
                            root.getContext(), senderNameColorResourceAt(colorIndex));
                }
                senderName.setTextColor(senderColor);
            } else {
                senderName.setVisibility(View.GONE);
            }
        }

        bindReplyQuote(root, message);
        bindPrimaryContent(root, message);
        applyMessageTextColors(root, message, item.isPulsing());
        applyMentionHighlights(root, message, mentionedMe);
        bindMetaRow(root, message, item.isSeenReceipt(), item.isDeliveredReceipt(), item.isPulsing());
        bindReactions(root, message);
        applyMediaOnlyFrame(root, bubble, message, mentionedMe);
    }

    /**
     * A photo or video with no caption, quote or sender label is shown as the
     * rounded media itself: the coloured bubble frame and its padding would
     * only leave a plum border around the picture.
     */
    private void applyMediaOnlyFrame(
            View root, View bubble, ChatMessage message, boolean mentionedMe) {
        if (mentionedMe) {
            return; // keep the highlighted frame so the mention stays visible
        }
        if (MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs())
                || message.getAttachments().isEmpty()) {
            return;
        }
        MessageContentKind kind = MessageViewTypeMapper.resolveContentKind(message);
        if (kind != MessageContentKind.IMAGE && kind != MessageContentKind.VIDEO) {
            return;
        }
        if (isShown(root, R.id.replyQuote) || isShown(root, R.id.senderName)
                || isShown(root, R.id.messageBody)) {
            return;
        }
        bubble.setBackground(null);
        bubble.setBackgroundTintList(null);
        bubble.setPadding(0, 0, 0, 0);
    }

    private static boolean isShown(View root, @IdRes int id) {
        View view = root.findViewById(id);
        return view != null && view.getVisibility() == View.VISIBLE;
    }

    public void bindDateSeparator(@NonNull View root, @NonNull String label) {
        TextView dateLabel = root.findViewById(R.id.dateSeparatorLabel);
        if (dateLabel != null) {
            dateLabel.setText(label);
        }
    }

    public void bindUnreadDivider(@NonNull View root, int unreadCount) {
        TextView label = root.findViewById(R.id.unreadDividerLabel);
        View dividerRoot = root.findViewById(R.id.unreadDividerRoot);
        if (label != null) {
            label.setText(QuantityStrings.chatUnreadDivider(root.getResources(), unreadCount));
        }
        if (dividerRoot != null) {
            dividerRoot.setContentDescription(
                    QuantityStrings.chatUnreadDivider(root.getResources(), unreadCount));
        }
    }

    private void wireGestures(View root, ChatMessage message) {
        if (MessageViewTypeMapper.isSystemMessage(message)) {
            root.setOnLongClickListener(null);
            root.setOnClickListener(null);
            root.setLongClickable(false);
            root.setClickable(false);
            return;
        }
        boolean tombstone = MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs());
        boolean retry = ChatBubbleRules.isRetryAffordanceVisible(
                message.getStatus(), pausedFailReasonRes != 0);
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
        float radius = bubble.getResources().getDimension(R.dimen.corner_xl);
        float tailRadius = bubble.getResources().getDimension(R.dimen.corner_xs);
        boolean tailOnRight = mine != (bubble.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        boolean[] merged = ChatBubbleRules.bubbleMergedCorners(position, tailOnRight);
        float[] radii = new float[8];
        for (int corner = 0; corner < merged.length; corner++) {
            radii[corner * 2] = merged[corner] ? tailRadius : radius;
            radii[corner * 2 + 1] = merged[corner] ? tailRadius : radius;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadii(radii);
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
            switch (ChatBubbleRules.resolveBubbleContainerRole(message.getStatus(), message.getMine())) {
                case ERROR:
                    colorInt = ContextCompat.getColor(context, R.color.colorErrorContainer);
                    break;
                case MINE:
                    colorInt = ChatBubbleColors.bubbleArgb(
                            chatColor, ChatBubbleColors.isNight(context));
                    break;
                case PEER:
                default:
                    colorInt = ContextCompat.getColor(context, R.color.bubble_other);
                    break;
            }
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

    static int senderNameColorResourceAt(int paletteIndex) {
        return SENDER_NAME_COLORS[Math.floorMod(paletteIndex, SENDER_NAME_COLORS.length)];
    }

    static double contrastRatio(int foregroundArgb, int backgroundArgb) {
        double foregroundLuminance = relativeLuminance(foregroundArgb);
        double backgroundLuminance = relativeLuminance(backgroundArgb);
        double lighter = Math.max(foregroundLuminance, backgroundLuminance);
        double darker = Math.min(foregroundLuminance, backgroundLuminance);
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    private static double relativeLuminance(int argb) {
        double red = linearize((argb >> 16) & 0xFF);
        double green = linearize((argb >> 8) & 0xFF);
        double blue = linearize(argb & 0xFF);
        return 0.2126d * red + 0.7152d * green + 0.0722d * blue;
    }

    private static double linearize(int channel) {
        double srgb = channel / 255.0d;
        return srgb <= 0.04045d
                ? srgb / 12.92d
                : Math.pow((srgb + 0.055d) / 1.055d, 2.4d);
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
            ((View) replyInclude.getParent()).setMinimumWidth(0);
            return;
        }
        ChatMessage original = resolveOriginal != null ? resolveOriginal.apply(replyId) : null;
        String snippet = message.getReplyToSnippet();
        if (message.isReplyToEncrypted()
                || (original != null && original.isDecryptFailed())) {
            snippet = root.getContext().getString(R.string.e2ee_message_placeholder);
        } else if (snippet == null && original != null) {
            snippet = original.getContent();
        }
        String sender = message.getReplyToSenderName();
        if (sender == null && original != null) {
            sender = original.getSenderDisplayName();
        }
        boolean resolved = original != null || snippet != null || sender != null;
        if (!resolved) {
            replyInclude.setVisibility(View.GONE);
            ((View) replyInclude.getParent()).setMinimumWidth(0);
            return;
        }
        replyInclude.setVisibility(View.VISIBLE);
        // match_parent links are re-measured EXACTLY to the bubble's width, so
        // a layout minWidth inside the quote can never widen a narrow bubble;
        // set it on the bubble itself while the quote is visible.
        View bubble = (View) replyInclude.getParent();
        int quoteMin = root.getResources().getDimensionPixelSize(R.dimen.bubble_quote_min_width);
        if (bubble.getMinimumWidth() != quoteMin) {
            bubble.setMinimumWidth(quoteMin);
        }
        tintReplyQuote(root, replyInclude, message.getMine());
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

    /**
     * Paints the embedded quote in the bubble's own family: outgoing rows draw
     * their tones from the selected chat color, incoming rows keep the theme
     * roles. The layer-list is mutated per row so a recycled view never leaks
     * the previous row's palette.
     */
    private void tintReplyQuote(@NonNull View root, @NonNull View replyInclude, boolean mine) {
        android.content.Context context = root.getContext();
        boolean night = ChatBubbleColors.isNight(context);
        int container;
        int accent;
        int content;
        if (mine) {
            int bubble = ChatBubbleColors.bubbleArgb(chatColor, night);
            container = ChatBubbleColors.quoteContainerArgb(bubble, night);
            accent = ChatBubbleColors.quoteAccentArgb(bubble, night);
            content = ChatBubbleColors.quoteContentArgb(bubble, night);
        } else {
            container = resolveThemeColor(
                    context, com.google.android.material.R.attr.colorPrimaryContainer);
            accent = resolveThemeColor(
                    context, com.google.android.material.R.attr.colorPrimary);
            content = resolveThemeColor(
                    context, com.google.android.material.R.attr.colorOnPrimaryContainer);
        }
        Drawable background = replyInclude.getBackground();
        if (background instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) background.mutate();
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                Drawable layer = layers.getDrawable(i);
                if (layer instanceof GradientDrawable) {
                    ((GradientDrawable) layer).setColor(i == 0 ? container : accent);
                }
            }
            replyInclude.setBackground(layers);
        }
        TextView author = replyInclude.findViewById(R.id.replyAuthor);
        TextView snippetView = replyInclude.findViewById(R.id.replySnippet);
        if (author != null) {
            author.setTextColor(content);
        }
        if (snippetView != null) {
            snippetView.setTextColor(content);
        }
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
                body.setContentDescription(null);
                body.setText(R.string.message_deleted);
            }
            return;
        }

        if (kind == MessageContentKind.SYSTEM) {
            if (systemLabel != null) {
                systemLabel.setVisibility(View.VISIBLE);
                String raw = message.getContent() != null ? message.getContent() : "";
                systemLabel.setText(SystemEventFormatter.format(
                        root.getContext(), raw, message.getMine()));
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
                image.setImageResource(R.drawable.bg_media_video_placeholder);
                String videoUrl = attachment == null ? null : attachment.contentUrl();
                if (videoUrl != null) {
                    String absolute = videoUrl.startsWith("content:") || videoUrl.startsWith("file:")
                            ? videoUrl
                            : AppConfig.absoluteMediaUrl(videoUrl);
                    image.setTag(absolute);
                    VideoThumbnailLoader.load(image, absolute);
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
            fileName.setSingleLine(false);
            fileName.setMaxLines(8);
            fileName.setEllipsize(null);
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
            if (message.isDecryptFailed()) {
                body.setText(R.string.e2ee_message_undecryptable);
                body.setTypeface(null, android.graphics.Typeface.ITALIC);
                body.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        R.drawable.ic_lock, 0, 0, 0);
                body.setCompoundDrawablePadding(
                        root.getResources().getDimensionPixelSize(R.dimen.space_xs));
                body.setContentDescription(
                        root.getContext().getString(R.string.e2ee_message_undecryptable));
                return;
            }
            body.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            body.setTypeface(null, android.graphics.Typeface.NORMAL);
            if (raw != null && raw.startsWith("CALL|")) {
                body.setText(SystemEventFormatter.format(
                        root.getContext(), raw, message.getMine()));
            } else {
                body.setContentDescription(null);
                body.setText(raw != null ? raw : "");
            }
        }
    }

    private void applyMentionHighlights(View root, ChatMessage message, boolean mentionedMe) {
        TextView body = root.findViewById(R.id.messageBody);
        String content = message.getContent();
        if (body == null || content == null || body.getVisibility() != View.VISIBLE
                || MessageViewTypeMapper.isSystemMessage(message) || message.isDeleted()
                || !body.getText().toString().contentEquals(content)) return;
        SpannableString styled = new SpannableString(body.getText());
        int otherBackground = resolveThemeColor(root.getContext(),
                com.google.android.material.R.attr.colorTertiaryContainer);
        int otherForeground = resolveThemeColor(root.getContext(),
                com.google.android.material.R.attr.colorOnTertiaryContainer);
        int ownBackground = resolveThemeColor(root.getContext(),
                com.google.android.material.R.attr.colorPrimaryContainer);
        int ownForeground = resolveThemeColor(root.getContext(),
                com.google.android.material.R.attr.colorOnPrimaryContainer);
        for (MessageMention mention : message.getMentions()) {
            int start = mention.getOffset();
            int end = start + mention.getLength();
            if (!com.nexachat.feature.chat.MentionDraft.isValidRange(content, start, mention.getLength())
                    || end > styled.length()) continue;
            boolean own = currentUserId != null && currentUserId.equals(mention.getUserId());
            addBubbleSpan(styled, start, end, own ? ownBackground : otherBackground,
                    own ? ownForeground : otherForeground);
        }
        if (message.isMentionAll()) {
            int start = content.indexOf(com.nexachat.feature.chat.MentionDraft.ALL_TOKEN);
            if (start >= 0 && start + com.nexachat.feature.chat.MentionDraft.ALL_TOKEN.length() <= styled.length()) {
                addBubbleSpan(styled, start, start + com.nexachat.feature.chat.MentionDraft.ALL_TOKEN.length(),
                        mentionedMe ? ownBackground : otherBackground,
                        mentionedMe ? ownForeground : otherForeground);
            }
        }
        body.setTextColor(mentionedMe ? resolveThemeColor(root.getContext(),
                com.google.android.material.R.attr.colorOnSecondaryContainer)
                : body.getCurrentTextColor());
        body.setText(styled);
        if (!message.getMentions().isEmpty() || message.isMentionAll()) {
            int label = mentionedMe ? R.string.message_mentions_you : R.string.message_has_mentions;
            body.setContentDescription(content + ". " + root.getContext().getString(label));
        } else {
            body.setContentDescription(null);
        }
    }

    private static void addBubbleSpan(SpannableString text, int start, int end,
            int background, int foreground) {
        text.setSpan(new BackgroundColorSpan(background), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(foreground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static boolean isMentionedUser(ChatMessage message, @Nullable String userId) {
        if (message.isMentionAll()) return true;
        if (userId == null) return false;
        for (MessageMention mention : message.getMentions()) {
            if (userId.equals(mention.getUserId())) return true;
        }
        return false;
    }

    private void applyMessageTextColors(View root, ChatMessage message, boolean pulsing) {
        int color;
        if (MessageViewTypeMapper.isSystemMessage(message)) {
            color = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        } else if (pulsing) {
            color = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnTertiaryContainer);
        } else if (MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs())) {
            color = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
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
        if (MessageBubbleMetaRules.policyFor(message) == MessageBubbleMetaRules.MetaRowPolicy.HIDDEN) {
            metaInclude.setVisibility(View.GONE);
            return;
        }
        metaInclude.setVisibility(View.VISIBLE);
        boolean systemMessage = MessageViewTypeMapper.isSystemMessage(message);
        MessageContentKind contentKind = MessageViewTypeMapper.resolveContentKind(message);
        boolean mediaMeta = contentKind == MessageContentKind.IMAGE
                || contentKind == MessageContentKind.VIDEO;
        if (systemMessage) {
            TextView time = metaInclude.findViewById(R.id.timeLabel);
            TextView edited = metaInclude.findViewById(R.id.editedLabel);
            com.google.android.material.progressindicator.CircularProgressIndicator sending = metaInclude.findViewById(R.id.sendingProgress);
            ImageView deliveredIcon = metaInclude.findViewById(R.id.deliveryIcon);
            TextView deliveryText = metaInclude.findViewById(R.id.deliveryStatusText);
            int metaColor = resolveThemeColor(
                    root.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
            metaInclude.setBackground(null);
            metaInclude.setPadding(0, 0, 0, 0);
            if (metaInclude.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams params
                    && params.gravity != android.view.Gravity.CENTER_HORIZONTAL) {
                // A system notice is centred, so its time sits under it rather than at a bubble edge.
                params.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                metaInclude.setLayoutParams(params);
            }
            if (edited != null) {
                edited.setVisibility(View.GONE);
            }
            if (time != null) {
                time.setText(timeFormat.format(new Date(message.getCreatedAtEpochMs())));
                time.setTextColor(metaColor);
                time.setAlpha(1.0f);
                time.setVisibility(View.VISIBLE);
            }
            if (sending != null) {
                sending.setVisibility(View.GONE);
            }
            if (deliveredIcon != null) {
                deliveredIcon.setVisibility(View.GONE);
            }
            if (deliveryText != null) {
                deliveryText.setVisibility(View.GONE);
            }
            return;
        }
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
        com.google.android.material.progressindicator.CircularProgressIndicator sending = metaInclude.findViewById(R.id.sendingProgress);
        ImageView deliveredIcon = metaInclude.findViewById(R.id.deliveryIcon);
        TextView deliveryText = metaInclude.findViewById(R.id.deliveryStatusText);

        boolean isMine = message.getMine();
        int metaColor;
        if (mediaMeta) {
            metaColor = ContextCompat.getColor(root.getContext(), R.color.nexa_on_media_meta);
        } else if (pulsing) {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnTertiaryContainer);
        } else if (MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs())) {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        } else if (message.getStatus() == DeliveryStatus.FAILED) {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnErrorContainer);
        } else if (isMine) {
            metaColor = ContextCompat.getColor(root.getContext(), R.color.chat_bubble_outgoing_text);
        } else {
            metaColor = resolveThemeColor(root.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        }

        boolean tombstone = MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs());
        boolean quietOutgoingMeta = isMine
                && !mediaMeta
                && message.getStatus() != DeliveryStatus.FAILED
                && !tombstone;
        float metaLabelAlpha = quietOutgoingMeta ? 0.75f : 1.0f;

        if (edited != null) {
            boolean showEdited = MessageUiRules.isEdited(
                    message.getEditedAtEpochMs(),
                    message.getDeletedAtEpochMs()
            );
            edited.setVisibility(showEdited ? View.VISIBLE : View.GONE);
            if (showEdited) {
                edited.setText(R.string.message_edited);
                edited.setTextColor(metaColor);
                edited.setAlpha(metaLabelAlpha);
            }
        }
        if (time != null) {
            time.setText(timeFormat.format(new Date(message.getCreatedAtEpochMs())));
            time.setTextColor(metaColor);
            time.setAlpha(metaLabelAlpha);
        }
        if (sending != null) {
            boolean sendingNow = isMine && message.getStatus() == DeliveryStatus.SENDING;
            sending.setVisibility(sendingNow ? View.VISIBLE : View.GONE);
            sending.setContentDescription(sendingNow
                    ? root.getContext().getString(R.string.cd_sending)
                    : null);
            if (isMine) {
                sending.setIndicatorColor(metaColor);
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
                deliveryText.setText(ChatBubbleRules.failedRowLabelRes(
                        message.getStatus(), message.isPausedRefusal(),
                        pausedFailReasonRes));
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
                deliveredIcon.setImageResource(R.drawable.ic_visibility);
                deliveredIcon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(root.getContext(), R.color.chat_bubble_read_tick)
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
            boolean mineReaction = chip.getReactedByMe();
            view.setText(root.getResources().getString(
                    R.string.a11y_reaction_chip, chip.getEmoji(), chip.getCount()));
            if (mineReaction) {
                // "yours" is for screen readers only; sighted users read ownership from the chip colours.
                view.setContentDescription(root.getResources().getString(
                        R.string.a11y_reaction_chip_by_me, chip.getEmoji(), chip.getCount()));
            }
            view.setCheckable(false);
            view.setClickable(true);
            TextViewCompat.setTextAppearance(view, R.style.TextAppearance_NexaChat_LabelMedium);
            view.setChipCornerRadius(root.getResources().getDimension(R.dimen.corner_full));
            // The chip draws at 32dp; the 48dp touch target is padded around it, not painted.
            view.setChipMinHeight(root.getResources().getDimension(R.dimen.chip_min_height));
            view.setEnsureMinTouchTargetSize(true);
            view.setTextColor(resolveThemeColor(
                    root.getContext(),
                    mineReaction
                            ? com.google.android.material.R.attr.colorOnSecondaryContainer
                            : com.google.android.material.R.attr.colorOnSurfaceVariant));
            view.setChipStrokeWidth(0f);
            view.setChipBackgroundColorResource(
                    mineReaction ? R.color.colorSecondaryContainer : R.color.colorSurfaceContainerHigh
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
