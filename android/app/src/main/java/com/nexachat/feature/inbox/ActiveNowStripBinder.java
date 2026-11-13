package com.nexachat.feature.inbox;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.R;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.inbox.adapter.ConversationPreviewHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Binds the inbox Active Now rail with targeted updates instead of full re-inflation. */
public final class ActiveNowStripBinder {

    private static final int YOUR_STATUS_INDEX = 0;
    private static final long ENTRANCE_MS = 175L;
    private final LinearLayout strip;
    private final LayoutInflater inflater;
    private final Context context;
    private List<String> boundPeerKeys = List.of();
    private boolean entrancePlayed;

    public interface PeerClickListener {
        void onPeerClick(@NonNull ConversationSummary row);
    }

    public interface YourStatusClickListener {
        void onYourStatusClick();
    }

    public ActiveNowStripBinder(
            @NonNull LinearLayout strip,
            @NonNull LayoutInflater inflater,
            @NonNull Context context) {
        this.strip = strip;
        this.inflater = inflater;
        this.context = context;
    }

    public void bind(
            @Nullable UserDto inboxUser,
            @NonNull List<ConversationSummary> allRows,
            @NonNull YourStatusClickListener yourStatusClick,
            @NonNull PeerClickListener peerClick,
            float fontScale) {
        List<ConversationSummary> visibleRows = InboxFilterRules.apply(allRows, InboxFilter.ALL);
        List<ConversationSummary> peers = ActiveNowPeerRules.selectOnlineDirectPeers(visibleRows);
        List<String> peerKeys = new ArrayList<>(peers.size());
        for (ConversationSummary peer : peers) {
            peerKeys.add(ActiveNowPeerRules.peerIdentityKey(peer));
        }

        bindYourStatus(inboxUser, yourStatusClick, fontScale);

        if (ActiveNowStripUpdateRules.needsStructuralRebuild(boundPeerKeys, peerKeys)) {
            rebuildPeerViews(peers, peerClick, fontScale);
            boundPeerKeys = peerKeys;
        } else {
            updatePeerViewsInPlace(peers, peerClick, fontScale);
        }

        maybePlayEntranceAnimation();
    }

    private void bindYourStatus(
            @Nullable UserDto inboxUser,
            @NonNull YourStatusClickListener yourStatusClick,
            float fontScale) {
        View yourStatusView = strip.getChildCount() > YOUR_STATUS_INDEX
                ? strip.getChildAt(YOUR_STATUS_INDEX)
                : null;
        if (yourStatusView == null) {
            yourStatusView = inflater.inflate(R.layout.item_active_now_peer, strip, false);
            strip.addView(yourStatusView, YOUR_STATUS_INDEX);
        }
        AvatarView yourAvatar = yourStatusView.findViewById(R.id.activePeerAvatar);
        TextView yourName = yourStatusView.findViewById(R.id.activePeerName);
        View yourBadge = yourStatusView.findViewById(R.id.activePeerBadge);
        if (inboxUser != null) {
            yourAvatar.setName(inboxUser.getDisplayName());
            yourAvatar.setAvatarUrl(inboxUser.getAvatarUrl());
        } else {
            yourAvatar.setName(context.getString(R.string.inbox_your_status));
        }
        yourAvatar.setOnlineStatus(null);
        yourAvatar.setShowStoryRing(false);
        yourName.setText(R.string.inbox_your_status);
        yourName.setMaxLines(ActiveNowPeerRules.captionMaxLines(fontScale));
        if (yourBadge != null) {
            yourBadge.setVisibility(View.VISIBLE);
        }
        yourStatusView.setContentDescription(context.getString(R.string.inbox_your_status));
        yourStatusView.setOnClickListener(v -> yourStatusClick.onYourStatusClick());
    }

    private void rebuildPeerViews(
            @NonNull List<ConversationSummary> peers,
            @NonNull PeerClickListener peerClick,
            float fontScale) {
        while (strip.getChildCount() > YOUR_STATUS_INDEX + 1) {
            strip.removeViewAt(strip.getChildCount() - 1);
        }
        for (ConversationSummary row : peers) {
            View peerView = inflatePeerView(row, peerClick, fontScale);
            strip.addView(peerView);
        }
    }

    private void updatePeerViewsInPlace(
            @NonNull List<ConversationSummary> peers,
            @NonNull PeerClickListener peerClick,
            float fontScale) {
        Map<String, View> existing = indexPeerViews();
        int insertIndex = YOUR_STATUS_INDEX + 1;
        for (ConversationSummary row : peers) {
            String key = ActiveNowPeerRules.peerIdentityKey(row);
            View peerView = existing.remove(key);
            if (peerView == null) {
                peerView = inflatePeerView(row, peerClick, fontScale);
                strip.addView(peerView, insertIndex);
            } else {
                bindPeerView(peerView, row, peerClick, fontScale);
                int currentIndex = strip.indexOfChild(peerView);
                if (currentIndex != insertIndex) {
                    strip.removeViewAt(currentIndex);
                    strip.addView(peerView, insertIndex);
                }
            }
            insertIndex++;
        }
        for (View orphan : existing.values()) {
            strip.removeView(orphan);
        }
    }

    @NonNull
    private Map<String, View> indexPeerViews() {
        Map<String, View> indexed = new HashMap<>();
        for (int index = YOUR_STATUS_INDEX + 1; index < strip.getChildCount(); index++) {
            View child = strip.getChildAt(index);
            Object tag = child.getTag(R.id.activePeerAvatar);
            if (tag instanceof String key) {
                indexed.put(key, child);
            }
        }
        return indexed;
    }

    @NonNull
    private View inflatePeerView(
            @NonNull ConversationSummary row,
            @NonNull PeerClickListener peerClick,
            float fontScale) {
        View peerView = inflater.inflate(R.layout.item_active_now_peer, strip, false);
        bindPeerView(peerView, row, peerClick, fontScale);
        return peerView;
    }

    private void bindPeerView(
            @NonNull View peerView,
            @NonNull ConversationSummary row,
            @NonNull PeerClickListener peerClick,
            float fontScale) {
        String displayTitle = ConversationPreviewHelper.displayTitle(context, row);
        String peerKey = ActiveNowPeerRules.peerIdentityKey(row);
        peerView.setTag(R.id.activePeerAvatar, peerKey);

        AvatarView peerAvatar = peerView.findViewById(R.id.activePeerAvatar);
        TextView peerName = peerView.findViewById(R.id.activePeerName);
        View peerBadge = peerView.findViewById(R.id.activePeerBadge);
        if (peerBadge != null) {
            peerBadge.setVisibility(View.GONE);
        }

        peerAvatar.setName(displayTitle);
        peerAvatar.setAvatarUrl(row.getDisplayAvatar());
        peerAvatar.setOnlineStatus(Boolean.TRUE);
        peerAvatar.setShowStoryRing(true);

        peerName.setText(displayTitle);
        peerName.setMaxLines(ActiveNowPeerRules.captionMaxLines(fontScale));
        peerView.setContentDescription(
                displayTitle + ", " + context.getString(R.string.presence_active_now));
        peerView.setOnClickListener(v -> peerClick.onPeerClick(row));
    }

    private void maybePlayEntranceAnimation() {
        float durationScale = Settings.Global.getFloat(
                context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        boolean animatorsEnabled = androidx.core.view.ViewCompat.isAttachedToWindow(strip)
                ? android.animation.ValueAnimator.areAnimatorsEnabled()
                : android.animation.ValueAnimator.areAnimatorsEnabled();
        if (!ActiveNowEntranceAnimationPolicy.shouldPlayEntrance(
                durationScale, animatorsEnabled, entrancePlayed)) {
            return;
        }
        int childCount = strip.getChildCount();
        if (childCount <= 1) {
            return;
        }
        entrancePlayed = true;
        for (int index = YOUR_STATUS_INDEX + 1; index < childCount; index++) {
            View child = strip.getChildAt(index);
            child.setAlpha(0f);
            child.setScaleX(0.92f);
            child.setScaleY(0.92f);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                    ObjectAnimator.ofFloat(child, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(child, View.SCALE_X, 0.92f, 1f),
                    ObjectAnimator.ofFloat(child, View.SCALE_Y, 0.92f, 1f));
            set.setDuration(Math.round(ENTRANCE_MS * durationScale));
            set.start();
        }
    }

}
