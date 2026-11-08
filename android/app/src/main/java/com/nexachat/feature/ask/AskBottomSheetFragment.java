package com.nexachat.feature.ask;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.databinding.BottomSheetAskChatBinding;
import com.nexachat.summary.AiConsentRepository;
import com.nexachat.summary.SummaryError;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

/** Bottom sheet for asking one question about the current conversation. */
@AndroidEntryPoint
public final class AskBottomSheetFragment extends com.google.android.material.bottomsheet.BottomSheetDialogFragment {
    public interface Listener {
        void onJumpToMessage(@NonNull AskCitationJump target);
    }

    private static final String ARG_CONVERSATION_ID = AskViewModel.ARG_CONVERSATION_ID;
    private static final int COUNTER_THRESHOLD = 450;

    @Inject AiConsentRepository consentRepository;

    private BottomSheetAskChatBinding binding;
    private AskViewModel viewModel;
    private AskCitationAdapter citationAdapter;
    @Nullable private Listener listener;
    @Nullable private androidx.appcompat.app.AlertDialog consentDialog;
    private boolean updatingDraft;
    private boolean consentDialogShowing;
    private boolean consentSavePending;

    @NonNull
    public static AskBottomSheetFragment newInstance(@NonNull String conversationId) {
        AskBottomSheetFragment fragment = new AskBottomSheetFragment();
        Bundle arguments = new Bundle();
        arguments.putString(ARG_CONVERSATION_ID, conversationId);
        fragment.setArguments(arguments);
        return fragment;
    }

    public void setListener(@Nullable Listener value) {
        listener = value;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = BottomSheetAskChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(AskViewModel.class);
        citationAdapter = new AskCitationAdapter(this::handleCitationClick);
        binding.askCitations.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.askCitations.setNestedScrollingEnabled(false);
        binding.askCitations.setAdapter(citationAdapter);
        configureInput();
        binding.askClose.setOnClickListener(ignored -> dismissSheet());
        binding.askSend.setOnClickListener(ignored -> submitQuestion());
        binding.askRetry.setOnClickListener(ignored -> viewModel.retry());
        binding.askCancel.setOnClickListener(ignored -> viewModel.cancel());
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        consentRepository.error().observe(getViewLifecycleOwner(), this::onConsentError);
    }

    private void configureInput() {
        binding.askQuestion.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!updatingDraft && viewModel != null) {
                    viewModel.updateDraft(s.toString());
                }
                updateCounter(s.length());
            }

            @Override public void afterTextChanged(Editable editable) { }
        });
        binding.askQuestion.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitQuestion();
                return true;
            }
            return false;
        });
    }

    private void submitQuestion() {
        if (binding != null) {
            InputMethodManager inputMethod = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethod != null) {
                inputMethod.hideSoftInputFromWindow(binding.askQuestion.getWindowToken(), 0);
            }
        }
        if (viewModel != null) {
            viewModel.submit();
        }
    }

    private void render(@NonNull AskUiState state) {
        if (binding == null) {
            return;
        }
        if (!state.getDraft().contentEquals(binding.askQuestion.getText())) {
            updatingDraft = true;
            binding.askQuestion.setText(state.getDraft());
            binding.askQuestion.setSelection(binding.askQuestion.length());
            updatingDraft = false;
        }
        updateCounter(state.getDraft().length());

        boolean loading = state.getStatus() == AskUiState.Status.LOADING;
        binding.askQuestion.setEnabled(!loading);
        binding.askSend.setEnabled(!loading
                && !state.getDraft().trim().isEmpty()
                && state.getDraft().trim().length() <= 500);
        binding.askSkeleton.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.askCancel.setVisibility(loading ? View.VISIBLE : View.GONE);

        boolean answerVisible = state.getStatus() == AskUiState.Status.ANSWER
                && state.getResponse() != null;
        AskResponse response = state.getResponse();
        binding.askAnswerCard.setVisibility(answerVisible ? View.VISIBLE : View.GONE);
        if (answerVisible) {
            binding.askAnswer.setText(response.getAnswer());
            boolean extractive = "EXTRACTIVE".equals(response.getMode());
            binding.askModeBadge.setVisibility(extractive ? View.VISIBLE : View.GONE);
            boolean hasCitations = !response.getCitations().isEmpty();
            binding.askCitationsLabel.setVisibility(hasCitations ? View.VISIBLE : View.GONE);
            binding.askCitations.setVisibility(hasCitations ? View.VISIBLE : View.GONE);
            citationAdapter.submitList(response.getCitations());
        } else {
            binding.askModeBadge.setVisibility(View.GONE);
            binding.askCitationsLabel.setVisibility(View.GONE);
            binding.askCitations.setVisibility(View.GONE);
            citationAdapter.submitList(java.util.Collections.emptyList());
        }

        boolean showStatus = state.getStatus() == AskUiState.Status.NOT_FOUND
                || state.getStatus() == AskUiState.Status.ERROR
                || state.getStatus() == AskUiState.Status.RATE_LIMITED
                || state.getStatus() == AskUiState.Status.CONSENT_REQUIRED;
        binding.askStatusMessage.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        if (showStatus) {
            binding.askStatusMessage.setText(statusMessage(state));
        }
        boolean retryVisible = state.getStatus() == AskUiState.Status.ERROR
                || state.getStatus() == AskUiState.Status.RATE_LIMITED;
        binding.askRetry.setVisibility(retryVisible ? View.VISIBLE : View.GONE);
        if (state.getStatus() == AskUiState.Status.CONSENT_REQUIRED) {
            showConsentDialog();
        }
    }

    @NonNull
    private CharSequence statusMessage(@NonNull AskUiState state) {
        if (state.getStatus() == AskUiState.Status.NOT_FOUND) {
            return getString(R.string.ask_not_found);
        }
        if (state.getStatus() == AskUiState.Status.RATE_LIMITED) {
            Long retryAfter = state.getRetryAfterSeconds();
            return retryAfter == null
                    ? getString(R.string.ask_rate_limited)
                    : getString(R.string.ask_rate_limited_retry, retryAfter);
        }
        AskError error = state.getError();
        if (error == AskError.CHAT_UNAVAILABLE) {
            return getString(R.string.ask_chat_unavailable);
        }
        if (error == AskError.VALIDATION) {
            return getString(R.string.ask_invalid_question);
        }
        if (error == AskError.CONSENT_REQUIRED) {
            return getString(R.string.ask_consent_required);
        }
        return getString(R.string.ask_error_generic);
    }

    private void updateCounter(int length) {
        if (binding == null) {
            return;
        }
        boolean visible = length >= COUNTER_THRESHOLD;
        binding.askCounter.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            binding.askCounter.setText(getString(R.string.ask_question_counter, length));
        }
    }

    private void showConsentDialog() {
        if (consentDialogShowing || !isAdded()) {
            return;
        }
        consentDialogShowing = true;
        consentDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ask_consent_title)
                .setMessage(R.string.dialog_ai_consent_message)
                .setNegativeButton(R.string.dialog_ai_consent_dismiss,
                        (dialog, which) -> {
                            consentDialogShowing = false;
                            viewModel.declineConsent();
                        })
                .setPositiveButton(R.string.dialog_ai_consent_confirm,
                        (dialog, which) -> {
                            consentDialogShowing = false;
                            saveConsentAndRetry();
                        })
                .setOnCancelListener(dialog -> {
                    consentDialogShowing = false;
                    if (viewModel != null) {
                        viewModel.declineConsent();
                    }
                })
                .create();
        consentDialog.setOnDismissListener(dialog -> {
            consentDialogShowing = false;
            consentDialog = null;
        });
        consentDialog.show();
    }

    private void saveConsentAndRetry() {
        consentSavePending = true;
        consentRepository.setConsent(true, () -> {
            consentSavePending = false;
            if (viewModel != null) {
                viewModel.retryAfterConsentOnce();
            }
        });
    }

    private void onConsentError(@Nullable SummaryError error) {
        if (!consentSavePending || error == null) {
            return;
        }
        consentSavePending = false;
        if (viewModel != null) {
            viewModel.consentUpdateFailed();
        }
    }

    private void handleCitationClick(@NonNull AskCitationJump target) {
        if (viewModel != null) {
            viewModel.close();
        }
        dismiss();
        if (listener != null) {
            listener.onJumpToMessage(target);
        }
    }

    private void dismissSheet() {
        if (viewModel != null) {
            viewModel.close();
        }
        if (consentDialog != null) {
            consentDialog.dismiss();
            consentDialog = null;
        }
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) {
            return;
        }
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (dialog instanceof BottomSheetDialog bottomSheetDialog) {
            FrameLayout bottomSheet = bottomSheetDialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                ViewGroup.LayoutParams params = bottomSheet.getLayoutParams();
                params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.9f);
                bottomSheet.setLayoutParams(params);
                bottomSheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior<FrameLayout> behavior =
                        BottomSheetBehavior.from(bottomSheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    @Override
    public void onCancel(@NonNull android.content.DialogInterface dialog) {
        if (viewModel != null) {
            viewModel.close();
        }
        super.onCancel(dialog);
    }

    @Override
    public void onDestroyView() {
        if (consentDialog != null) {
            consentDialog.dismiss();
            consentDialog = null;
        }
        binding = null;
        super.onDestroyView();
    }
}
