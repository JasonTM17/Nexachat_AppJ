package com.nexachat.feature.inbox.adapter;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.core.ui.components.ChatUiRules;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.inbox.InboxBadgeRules;
import com.nexachat.feature.inbox.InboxRowPreviewRules;
import com.nexachat.feature.inbox.InboxRowTypography;
import com.nexachat.feature.inbox.InboxPreviewKind;
import com.nexachat.feature.label.ConversationLabel;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.util.List;

/**
 * Inbox conversation rows with unread badge and relative timestamps.
 */
public final class ConversationListAdapter extends ListAdapter<ConversationSummary, ConversationListAdapter.Holder> {

    public interface Listener {
        void onConversationClick(@NonNull String conversationId);
        void onConversationLongClick(@NonNull ConversationSummary conversation);
    }

    private final Listener listener;
    private List<ConversationLabel> labelDefinitions = List.of();
    @Nullable
    private final String currentUserId;
    @NonNull
    private final String justNowLabel;
    @NonNull
    private final String yesterdayLabel;

    public ConversationListAdapter(
            @NonNull Listener listener,
            @Nullable String currentUserId,
            @NonNull String justNowLabel,
            @NonNull String yesterdayLabel
    ) {
        super(DIFF);
        this.listener = listener;
        this.currentUserId = currentUserId;
        this.justNowLabel = justNowLabel;
        this.yesterdayLabel = yesterdayLabel;
    }

    private static final DiffUtil.ItemCallback<ConversationSummary> DIFF =
            new DiffUtil.ItemCallback<ConversationSummary>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull ConversationSummary oldItem,
                        @NonNull ConversationSummary newItem
                ) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull ConversationSummary oldItem,
                        @NonNull ConversationSummary newItem
                ) {
                    return oldItem.equals(newItem);
                }
            };

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_inbox_conversation, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ConversationSummary conversation = getItem(position);
        holder.bind(conversation, currentUserId, justNowLabel, yesterdayLabel, listener, labelDefinitions);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.clearMuteExpiry();
        super.onViewRecycled(holder);
    }

    public void setLabelDefinitions(@NonNull List<ConversationLabel> labels) {
        if (labelDefinitions.equals(labels)) return;
        labelDefinitions = List.copyOf(labels);
        notifyItemRangeChanged(0, getItemCount());
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final AvatarView avatar;
        private final TextView title;
        private final TextView time;
        private final TextView preview;
        private final ImageView previewTypeIcon;
        private final ImageView pinIndicator;
        private final TextView unreadBadge;
        private final ImageView muteIcon;
        private final ChipGroup labelChips;
        @Nullable private ConversationSummary boundConversation;
        @Nullable private Runnable muteExpiryRefresh;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            title = itemView.findViewById(R.id.title);
            time = itemView.findViewById(R.id.time);
            preview = itemView.findViewById(R.id.preview);
            previewTypeIcon = itemView.findViewById(R.id.previewTypeIcon);
            pinIndicator = itemView.findViewById(R.id.pinIndicator);
            unreadBadge = itemView.findViewById(R.id.unreadBadge);
            muteIcon = itemView.findViewById(R.id.muteIcon);
            labelChips = itemView.findViewById(R.id.labelChips);
        }

        void bind(
                @NonNull ConversationSummary conversation,
                @Nullable String currentUserId,
                @NonNull String justNowLabel,
                @NonNull String yesterdayLabel,
                @NonNull Listener listener,
                @NonNull List<ConversationLabel> labelDefinitions
        ) {
            clearMuteExpiry();
            boundConversation = conversation;
            String displayTitle = ConversationPreviewHelper.displayTitle(itemView.getContext(), conversation);
            title.setText(displayTitle);
            float fontScale = itemView.getResources().getConfiguration().fontScale;
            title.setMaxLines(fontScale >= 1.3f ? 2 : 1);
            preview.setMaxLines(fontScale >= 1.3f ? 2 : 1);
            avatar.setName(displayTitle);
            avatar.setAvatarUrl(conversation.getDisplayAvatar());
            Boolean onlineStatus = InboxBadgeRules.resolveOnlineStatus(conversation);
            avatar.setOnlineStatus(onlineStatus);
            applyTitlePresenceDescription(displayTitle, onlineStatus);

            boolean unread = conversation.getUnreadCount() > 0;
            InboxRowTypography.applyUnreadWeight(title, unread);
            String previewText = ConversationPreviewHelper.previewText(
                    itemView.getContext(),
                    conversation,
                    currentUserId
            );
            boolean hasPreviewLine = InboxRowPreviewRules.hasPreviewLine(previewText);
            if (hasPreviewLine) {
                preview.setVisibility(View.VISIBLE);
                preview.setText(previewText);
            } else {
                preview.setVisibility(View.GONE);
                preview.setText("");
            }
            applyTitleVerticalAlignment(hasPreviewLine);
            InboxRowTypography.applyUnreadWeight(preview, unread);
            int previewColor = resolveThemeColor(
                    itemView.getContext(),
                    unread
                            ? com.google.android.material.R.attr.colorOnSurface
                            : com.google.android.material.R.attr.colorOnSurfaceVariant
            );
            preview.setTextColor(previewColor);
            InboxPreviewKind previewKind = ConversationPreviewHelper.previewKind(conversation);
            int previewIcon = 0;
            if (conversation.isLastMessageEncrypted()) {
                previewIcon = R.drawable.ic_lock;
            } else if (previewKind == InboxPreviewKind.PHOTO) {
                previewIcon = R.drawable.ic_image;
            } else if (previewKind == InboxPreviewKind.VOICE) {
                previewIcon = R.drawable.ic_mic;
            } else if (previewKind == InboxPreviewKind.FILE) {
                previewIcon = R.drawable.ic_description;
            } else if (previewKind == InboxPreviewKind.CALL) {
                previewIcon = R.drawable.ic_call;
            }
            if (!hasPreviewLine || previewIcon == 0) {
                previewTypeIcon.setVisibility(View.GONE);
            } else {
                previewTypeIcon.setImageResource(previewIcon);
                previewTypeIcon.setVisibility(View.VISIBLE);
            }

            Long at = conversation.getLastMessageAtEpochMs();
            if (at != null) {
                time.setVisibility(View.VISIBLE);
                time.setText(ChatUiRules.resolveRelativeTimeLabel(
                        at,
                        System.currentTimeMillis(),
                        justNowLabel,
                        yesterdayLabel
                ));
                int timeColor = resolveThemeColor(
                        itemView.getContext(),
                        unread ? com.google.android.material.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant
                );
                time.setTextColor(timeColor);
                InboxRowTypography.applyUnreadWeight(time, unread);
            } else {
                time.setVisibility(View.GONE);
            }

            if (unread) {
                int count = conversation.getUnreadCount();
                unreadBadge.setVisibility(View.VISIBLE);
                unreadBadge.setText(InboxBadgeRules.formatUnreadCount(count));
                unreadBadge.setContentDescription(itemView.getResources().getQuantityString(
                        R.plurals.inbox_unread_messages, count, count));
            } else {
                unreadBadge.setVisibility(View.GONE);
                unreadBadge.setContentDescription(null);
            }

            if (pinIndicator != null) {
                pinIndicator.setVisibility(conversation.isPinned() ? View.VISIBLE : View.GONE);
            }
            bindMuteIndicator(conversation);

            if (labelChips != null) {
                labelChips.removeAllViews();
                for (String labelId : conversation.getLabelIds()) {
                    ConversationLabel label = labelDefinitions.stream()
                            .filter(item -> item.getId().equals(labelId))
                            .findFirst()
                            .orElse(null);
                    if (label == null) continue;
                    Chip chip = new Chip(itemView.getContext());
                    chip.setText(label.getName());
                    chip.setTextColor(android.graphics.Color.WHITE);
                    chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                            label.getColor().getBackgroundColor()));
                    chip.setClickable(false);
                    chip.setCheckable(false);
                    chip.setFocusable(false);
                    chip.setContentDescription(itemView.getContext().getString(
                            R.string.label_chip_description, label.getName()));
                    labelChips.addView(chip, new ChipGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                labelChips.setVisibility(labelChips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
            }

            itemView.setOnClickListener(v -> listener.onConversationClick(conversation.getId()));
            itemView.setOnLongClickListener(v -> {
                listener.onConversationLongClick(conversation);
                return true;
            });
        }

        private void applyTitlePresenceDescription(
                @NonNull String displayTitle,
                @Nullable Boolean onlineStatus
        ) {
            if (onlineStatus == null) {
                title.setContentDescription(null);
                return;
            }
            int presenceRes = Boolean.TRUE.equals(onlineStatus)
                    ? R.string.cd_online_status
                    : R.string.presence_offline;
            title.setContentDescription(
                    displayTitle + ", " + itemView.getContext().getString(presenceRes));
        }

        private void applyTitleVerticalAlignment(boolean hasPreviewLine) {
            ConstraintLayout.LayoutParams titleParams = (ConstraintLayout.LayoutParams) title.getLayoutParams();
            titleParams.topToTop = R.id.avatar;
            if (hasPreviewLine) {
                titleParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
            } else {
                titleParams.bottomToBottom = R.id.avatar;
            }
            title.setLayoutParams(titleParams);
        }

        private void bindMuteIndicator(@NonNull ConversationSummary conversation) {
            if (muteIcon == null) return;
            long now = System.currentTimeMillis();
            muteIcon.setVisibility(conversation.isMutedAt(now) ? View.VISIBLE : View.GONE);
            long refreshDelay = MuteIndicatorRules.refreshDelayMillis(
                    conversation.isMuted(), conversation.getMutedUntilEpochMs(), now);
            if (refreshDelay <= 0L) return;

            Runnable refresh = new Runnable() {
                @Override public void run() {
                    if (boundConversation != conversation) return;
                    muteExpiryRefresh = null;
                    bindMuteIndicator(conversation);
                }
            };
            muteExpiryRefresh = refresh;
            itemView.postDelayed(refresh, refreshDelay);
        }

        void clearMuteExpiry() {
            if (muteExpiryRefresh != null) {
                itemView.removeCallbacks(muteExpiryRefresh);
                muteExpiryRefresh = null;
            }
            boundConversation = null;
        }

        private static int resolveThemeColor(@NonNull Context context, int attr) {
            TypedValue value = new TypedValue();
            if (!context.getTheme().resolveAttribute(attr, value, true)) {
                return 0;
            }
            if (value.resourceId != 0) {
                return ContextCompat.getColor(context, value.resourceId);
            }
            return value.data;
        }
    }
}
