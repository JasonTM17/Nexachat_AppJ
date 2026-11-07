package com.nexachat.feature.summary;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.nexachat.R;
import com.nexachat.core.ui.SummaryUiRules;
import com.nexachat.databinding.BottomSheetSummaryBinding;
import com.nexachat.summary.AiConsentRepository;
import com.nexachat.summary.SummaryError;
import com.nexachat.summary.SummaryResponse;
import com.nexachat.summary.SummaryScope;
import com.nexachat.summary.SummaryTextFormatter;
import com.nexachat.summary.SummaryUiState;
import com.nexachat.summary.SummaryViewModel;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

/**
 * Bottom sheet for conversation catch-up summaries (parity with {@code SummaryBottomSheet.kt}).
 */
@AndroidEntryPoint
public final class SummaryBottomSheetFragment extends BottomSheetDialogFragment {

    public interface OnCitationClickListener {
        void onCitationClick(@NonNull String messageId);
    }

    public interface Listener {
        void onJumpToMessage(@NonNull String messageId);

        void onShareIntoChat(@NonNull String shareText);
    }

    private static final String ARG_MESSAGE_IDS = "summary_message_ids";
    private static final String ARG_MESSAGE_SEQS = "summary_message_seqs";

    @Inject
    AiConsentRepository consentRepository;

    private BottomSheetSummaryBinding binding;
    private SummaryViewModel viewModel;
    @Nullable
    private Listener listener;
    @Nullable
    private OnCitationClickListener citationListener;
    private final Map<String, Long> messageSeqMap = new HashMap<>();
    private final Set<String> localMessageIds = new HashSet<>();
    private boolean preferAiMode;

    @NonNull
    public static SummaryBottomSheetFragment newInstance(@NonNull String conversationId) {
        SummaryBottomSheetFragment fragment = new SummaryBottomSheetFragment();
        Bundle args = new Bundle();
        args.putString(SummaryViewModel.ARG_CONVERSATION_ID, conversationId);
        fragment.setArguments(args);
        return fragment;
    }

    public void setListener(@Nullable Listener value) {
        listener = value;
    }

    public void setOnCitationClickListener(@Nullable OnCitationClickListener value) {
        citationListener = value;
    }

    public void setCitationContext(
            @NonNull Map<String, Long> seqMap,
            @NonNull Set<String> messageIds
    ) {
        messageSeqMap.clear();
        messageSeqMap.putAll(seqMap);
        localMessageIds.clear();
        localMessageIds.addAll(messageIds);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = BottomSheetSummaryBinding.inflate(inflater, container, false);
        readCitationArgs();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(SummaryViewModel.class);
        consentRepository.refresh();
        setupScopeChips();
        setupModeChips();
        setupActions();
        consentRepository.aiSummaryConsent().observe(getViewLifecycleOwner(), consent -> {
            if (preferAiMode && Boolean.TRUE.equals(consent)) {
                updateConsentCard(false);
            }
            updateModeSelection();
        });
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.request(SummaryScope.UNREAD, null);
    }

    private void readCitationArgs() {
        Bundle args = getArguments();
        if (args == null) {
            return;
        }
        String[] ids = args.getStringArray(ARG_MESSAGE_IDS);
        long[] seqs = args.getLongArray(ARG_MESSAGE_SEQS);
        if (ids == null || seqs == null || ids.length != seqs.length) {
            return;
        }
        for (int i = 0; i < ids.length; i++) {
            messageSeqMap.put(ids[i], seqs[i]);
        }
    }

    private void setupScopeChips() {
        if (binding == null) {
            return;
        }
        ChipGroup group = binding.summaryScopeChips;
        group.removeAllViews();
        Context context = requireContext();
        for (SummaryScope scope : SummaryScope.values()) {
            Chip chip = new Chip(context);
            chip.setTag(scope);
            chip.setCheckable(true);
            chip.setText(scopeLabel(scope));
            chip.setOnClickListener(v -> {
                if (!(v.getTag() instanceof SummaryScope selected)) {
                    return;
                }
                SummaryUiState current = viewModel.state().getValue();
                if (current != null && current.getScope() == selected) {
                    return;
                }
                Integer limit = selected == SummaryScope.LAST_N ? 50 : null;
                viewModel.request(selected, limit);
            });
            group.addView(chip);
        }
    }

    private void setupModeChips() {
        if (binding == null) {
            return;
        }
        binding.summaryModeLocal.setOnClickListener(v -> selectMode(false));
        binding.summaryModeAi.setOnClickListener(v -> selectMode(true));
        binding.summaryConsentConfirm.setOnClickListener(v -> {
            consentRepository.setConsent(true);
            updateConsentCard(false);
            viewModel.refresh();
        });
        binding.summaryConsentDismiss.setOnClickListener(v -> {
            preferAiMode = false;
            updateModeSelection();
            updateConsentCard(false);
        });
    }

    private void selectMode(boolean aiMode) {
        preferAiMode = aiMode;
        updateModeSelection();
        if (aiMode) {
            Boolean consent = consentRepository.aiSummaryConsent().getValue();
            if (!Boolean.TRUE.equals(consent)) {
                updateConsentCard(true);
                return;
            }
            viewModel.refresh();
        }
    }

    private void updateModeSelection() {
        if (binding == null) {
            return;
        }
        binding.summaryModeLocal.setChecked(!preferAiMode);
        binding.summaryModeAi.setChecked(preferAiMode);
    }

    private void updateConsentCard(boolean visible) {
        if (binding != null) {
            binding.summaryConsentCard.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setupActions() {
        if (binding == null) {
            return;
        }
        binding.summaryClose.setOnClickListener(v -> dismissSheet());
        binding.summaryRegenerate.setOnClickListener(v -> {
            if (preferAiMode) {
                Boolean consent = consentRepository.aiSummaryConsent().getValue();
                if (!Boolean.TRUE.equals(consent)) {
                    updateConsentCard(true);
                    return;
                }
            }
            viewModel.refresh();
        });
        binding.summaryCopy.setOnClickListener(v -> {
            SummaryUiState state = viewModel.state().getValue();
            if (state == null || state.getSummary() == null) {
                return;
            }
            String text = SummaryTextFormatter.toShareText(state.getSummary(), Locale.getDefault());
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("summary", text));
            }
            requireView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Toast.makeText(requireContext(), R.string.summary_copied_to_clipboard, Toast.LENGTH_SHORT)
                    .show();
        });
        binding.summaryShare.setOnClickListener(v -> {
            SummaryUiState state = viewModel.state().getValue();
            if (state == null || state.getSummary() == null) {
                return;
            }
            String text = SummaryTextFormatter.toShareText(state.getSummary(), Locale.getDefault());
            dismissSheet();
            if (listener != null) {
                listener.onShareIntoChat(text);
            }
        });
    }

    private void render(@Nullable SummaryUiState state) {
        if (binding == null || state == null) {
            return;
        }
        updateScopeSelection(state.getScope());
        renderEngineBadge(state.getSummary());
        boolean loading = state.getStatus() == SummaryUiState.Status.LOADING
                || (state.getStatus() == SummaryUiState.Status.IDLE
                && !SummaryUiRules.restoredSummaryMissing(state.getStatus(), state.getLastSummaryId()));
        boolean error = state.getStatus() == SummaryUiState.Status.ERROR
                || SummaryUiRules.restoredSummaryMissing(state.getStatus(), state.getLastSummaryId());
        boolean ready = state.getStatus() == SummaryUiState.Status.READY;

        binding.summarySkeleton.getRoot().setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.summaryError.getRoot().setVisibility(error ? View.VISIBLE : View.GONE);
        binding.summaryContent.setVisibility(ready ? View.VISIBLE : View.GONE);

        if (error) {
            SummaryError err = state.getStatus() == SummaryUiState.Status.ERROR
                    ? state.getError()
                    : SummaryError.UNKNOWN;
            SummaryErrorViewHelper.bind(binding.summaryError.getRoot(), err, viewModel::retry);
        }
        if (ready && state.getSummary() != null) {
            SummarySheetContentBinder binder = new SummarySheetContentBinder(
                    requireContext(),
                    messageSeqMap,
                    localMessageIds,
                    messageId -> handleCitationClick(messageId)
            );
            binder.bind(binding.summaryContent, state.getSummary());
        }

        boolean canAct = SummaryUiRules.canShareOrCopy(state.getStatus(), state.getSummary());
        binding.summaryCopy.setEnabled(canAct);
        binding.summaryShare.setEnabled(canAct);
    }

    private void handleCitationClick(@NonNull String messageId) {
        dismissSheet();
        if (citationListener != null) {
            citationListener.onCitationClick(messageId);
        } else if (listener != null) {
            listener.onJumpToMessage(messageId);
        }
    }

    private void renderEngineBadge(@Nullable SummaryResponse summary) {
        if (binding == null) {
            return;
        }
        String generatedBy = summary == null ? null : summary.getGeneratedBy();
        if (!SummaryUiRules.shouldShowEngineBadge(generatedBy)) {
            binding.summaryBadgeAi.setVisibility(View.GONE);
            binding.summaryBadgeLocal.setVisibility(View.GONE);
            return;
        }
        if (SummaryUiRules.isLlmGenerated(generatedBy)) {
            binding.summaryBadgeAi.setVisibility(View.VISIBLE);
            binding.summaryBadgeLocal.setVisibility(View.GONE);
            binding.summaryBadgeAi.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.brand_indigo_container));
        } else {
            binding.summaryBadgeLocal.setVisibility(View.VISIBLE);
            binding.summaryBadgeAi.setVisibility(View.GONE);
        }
    }

    private void updateScopeSelection(@NonNull SummaryScope selected) {
        if (binding == null) {
            return;
        }
        ChipGroup group = binding.summaryScopeChips;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Chip chip && chip.getTag() == selected) {
                chip.setChecked(true);
                return;
            }
        }
    }

    @NonNull
    private String scopeLabel(@NonNull SummaryScope scope) {
        switch (scope) {
            case LAST_N:
                return getString(R.string.summary_scope_last_n);
            case TODAY:
                return getString(R.string.summary_scope_today);
            case UNREAD:
            default:
                return getString(R.string.summary_scope_unread);
        }
    }

    private void dismissSheet() {
        viewModel.dismiss();
        dismiss();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    public void putCitationArgs(@NonNull Map<String, Long> seqMap) {
        if (getArguments() == null) {
            setArguments(new Bundle());
        }
        Bundle args = requireArguments();
        String[] ids = seqMap.keySet().toArray(new String[0]);
        long[] seqs = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Long seq = seqMap.get(ids[i]);
            seqs[i] = seq == null ? 0L : seq;
        }
        args.putStringArray(ARG_MESSAGE_IDS, ids);
        args.putLongArray(ARG_MESSAGE_SEQS, seqs);
        setCitationContext(seqMap, new HashSet<>(seqMap.keySet()));
    }
}
