package com.nexachat.feature.summary;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.color.MaterialColors;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.nexachat.R;
import com.nexachat.feature.summary.adapter.SummaryCitationAdapter;
import com.nexachat.summary.SummaryParticipant;
import com.nexachat.feature.summary.adapter.SummaryCitationItem;
import com.nexachat.feature.summary.adapter.SummaryCitationLabels;
import com.nexachat.summary.SummaryActionItem;
import com.nexachat.summary.SummaryItem;
import com.nexachat.summary.SummaryResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Inflates structured summary sections into a vertical container. */
public final class SummarySheetContentBinder {

    public interface OnCitationClickListener {
        void onCitationClick(@NonNull String messageId);
    }

    private final Context context;
    private final Map<String, Long> messageSeqMap;
    private final Set<String> localMessageIds;
    private final Map<String, String> messageSenderMap;
    private final Map<String, Long> messageTimeMap;
    private final OnCitationClickListener citationListener;
    private final HashMap<String, Boolean> actionChecked = new HashMap<>();

    public SummarySheetContentBinder(
            @NonNull Context context,
            @NonNull Map<String, Long> messageSeqMap,
            @NonNull Set<String> localMessageIds,
            @NonNull Map<String, String> messageSenderMap,
            @NonNull Map<String, Long> messageTimeMap,
            @NonNull OnCitationClickListener citationListener
    ) {
        this.context = context;
        this.messageSeqMap = messageSeqMap;
        this.localMessageIds = localMessageIds;
        this.messageSenderMap = messageSenderMap;
        this.messageTimeMap = messageTimeMap;
        this.citationListener = citationListener;
    }

    public void bind(@NonNull LinearLayout container, @NonNull SummaryResponse summary) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);

        if (!summary.getOverview().trim().isEmpty()) {
            addSectionHeader(container, R.string.summary_section_overview, R.drawable.ic_info);
            TextView overview = new TextView(context);
            overview.setText(summary.getOverview());
            overview.setTextAppearance(R.style.TextAppearance_NexaChat_BodyMedium);
            int pad = context.getResources().getDimensionPixelSize(R.dimen.space_sm);
            overview.setPadding(pad, pad, pad, pad);
            overview.setBackgroundColor(ContextCompat.getColor(context, R.color.bubble_other));
            container.addView(overview);
        }

        appendItems(container, inflater, R.string.summary_section_key_points,
                R.drawable.ic_format_list_bulleted, summary.getKeyPoints());
        appendItems(container, inflater, R.string.summary_section_decisions,
                R.drawable.ic_check_circle, summary.getDecisions());
        appendActionItems(container, inflater, summary.getActionItems(), summary.getParticipants());
        appendItems(container, inflater, R.string.summary_section_questions,
                R.drawable.ic_help_outline, summary.getQuestions());

        if (!summary.getTopics().isEmpty()) {
            addSectionHeader(container, R.string.summary_section_topics, 0);
            container.addView(SummaryTopicsSection.build(context, summary.getTopics()));
        }
    }

    private void appendItems(
            LinearLayout container,
            LayoutInflater inflater,
            int titleRes,
            int iconRes,
            List<SummaryItem> items
    ) {
        if (items == null || items.isEmpty()) {
            return;
        }
        addSectionHeader(container, titleRes, iconRes);
        for (SummaryItem item : items) {
            View row = inflater.inflate(R.layout.item_summary_bullet_row, container, false);
            TextView text = row.findViewById(R.id.summaryBulletText);
            text.setText(item.getText());
            RecyclerView citations = row.findViewById(R.id.summaryBulletCitations);
            bindCitations(citations, item.getMessageIds());
            container.addView(row);
        }
    }

    private void appendActionItems(
            LinearLayout container,
            LayoutInflater inflater,
            List<SummaryActionItem> items,
            List<SummaryParticipant> participants
    ) {
        if (items == null || items.isEmpty()) {
            return;
        }
        addSectionHeader(container, R.string.summary_section_action_items, R.drawable.ic_checklist);
        for (SummaryActionItem action : items) {
            View row = inflater.inflate(R.layout.item_summary_action_row, container, false);
            String key = SummaryActionKeyHelper.getActionItemKey(action);
            CheckBox check = row.findViewById(R.id.summaryActionCheck);
            TextView text = row.findViewById(R.id.summaryActionText);
            TextView meta = row.findViewById(R.id.summaryActionMeta);
            String actionText = action.getText();
            text.setText(actionText);
            check.setContentDescription(actionText);
            final boolean[] applyingCheckState = {false};
            Runnable applyChecked = () -> {
                boolean isChecked = Boolean.TRUE.equals(actionChecked.get(key));
                applyingCheckState[0] = true;
                check.setChecked(isChecked);
                applyingCheckState[0] = false;
                text.setPaintFlags(isChecked
                        ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                        : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            };
            applyChecked.run();
            row.setOnClickListener(v -> {
                actionChecked.put(key, !Boolean.TRUE.equals(actionChecked.get(key)));
                applyChecked.run();
            });
            check.setOnCheckedChangeListener((button, isChecked) -> {
                if (applyingCheckState[0]) {
                    return;
                }
                actionChecked.put(key, isChecked);
                text.setPaintFlags(isChecked
                        ? text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                        : text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            });
            String tag = SummaryOwnerLabel.actionMeta(action, participants);
            if (!TextUtils.isEmpty(tag)) {
                meta.setText(tag);
                meta.setVisibility(View.VISIBLE);
            }
            RecyclerView citations = row.findViewById(R.id.summaryActionCitations);
            bindCitations(citations, action.getMessageIds());
            container.addView(row);
        }
    }

    private void addSectionHeader(LinearLayout container, int titleRes, int iconRes) {
        TextView header = new TextView(context);
        header.setText(titleRes);
        header.setTextAppearance(R.style.TextAppearance_NexaChat_LabelLarge);
        header.setPadding(0, context.getResources().getDimensionPixelSize(R.dimen.space_md), 0, 0);
        if (iconRes != 0) {
            header.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
            header.setCompoundDrawablePadding(
                    context.getResources().getDimensionPixelSize(R.dimen.space_xs));
            int accent = MaterialColors.getColor(
                    context, com.google.android.material.R.attr.colorPrimary, 0);
            TextViewCompat.setCompoundDrawableTintList(header, ColorStateList.valueOf(accent));
        }
        container.addView(header);
    }

    private void bindCitations(RecyclerView list, List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            list.setVisibility(View.GONE);
            return;
        }
        list.setVisibility(View.VISIBLE);
        list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        SummaryCitationAdapter adapter = new SummaryCitationAdapter(messageId -> {
            citationListener.onCitationClick(messageId);
        });
        list.setAdapter(adapter);
        List<SummaryCitationItem> items = new ArrayList<>();
        for (String messageId : messageIds) {
            if (messageId == null) {
                continue;
            }
            Long seq = messageSeqMap.get(messageId);
            boolean jumpable = com.nexachat.core.ui.SummaryUiRules.isCitationJumpable(
                    messageId, localMessageIds);
            Long createdAt = messageTimeMap.get(messageId);
            String senderTime = SummaryCitationLabels.senderTime(
                    messageSenderMap.get(messageId),
                    createdAt == null ? 0L : createdAt,
                    System.currentTimeMillis(),
                    java.util.Locale.getDefault());
            String label = senderTime != null
                    ? senderTime
                    : seq != null
                            ? context.getString(R.string.summary_citation_seq, seq)
                            : SummaryCitationLabels.messageIdPrefix(messageId);
            String chipText = jumpable
                    ? context.getString(R.string.summary_citation_chip, label)
                    : context.getString(R.string.summary_citation_unavailable);
            String contentDescription = senderTime != null
                    ? context.getString(R.string.cd_summary_citation_from, senderTime)
                    : null;
            items.add(new SummaryCitationItem(
                    messageId, chipText, contentDescription, jumpable));
        }
        adapter.submitList(items);
    }
}
