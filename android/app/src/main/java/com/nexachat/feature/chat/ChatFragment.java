package com.nexachat.feature.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.call.CallLauncher;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.ui.components.DateSeparatorLabels;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.databinding.FragmentChatBinding;
import com.nexachat.feature.chat.ui.MessageActionListener;
import com.nexachat.feature.chat.voice.VoiceRecorder;
import java.io.File;
import com.nexachat.feature.chat.adapter.MessageListAdapter;
import com.nexachat.feature.chat.adapter.MessageListItem;
import com.nexachat.feature.chat.adapter.MessageListMapper;
import com.nexachat.feature.ask.AskBottomSheetFragment;
import com.nexachat.feature.ask.AskCitationJump;
import com.nexachat.feature.inbox.InboxUiState;
import com.nexachat.feature.inbox.InboxViewModel;
import com.nexachat.summary.SummaryResponse;
import com.nexachat.summary.SummaryScope;
import com.nexachat.summary.SummaryUiState;
import com.nexachat.summary.SummaryViewModel;
import dagger.hilt.android.AndroidEntryPoint;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/** View-based direct/group chat. The ViewModel remains the owner of message state and actions. */
@AndroidEntryPoint
public final class ChatFragment extends androidx.fragment.app.Fragment implements MessageActionListener {
    private static final String ARG_CONVERSATION_ID = "conversationId";
    private static final String ARG_ARCHIVE_JUMP_MESSAGE_ID = "archiveJumpMessageId";
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private FragmentChatBinding binding;
    private ChatViewModel viewModel;
    private InboxViewModel inboxViewModel;
    private SummaryViewModel summaryViewModel;
    private MessageListAdapter adapter;
    private ChatViewModel.UiState state = new ChatViewModel.UiState();
    private ChatSearchState searchState = new ChatSearchState();
    private String conversationId = "";
    private String pulsingMessageId;
    private ChatMessage editingMessage;
    private String editInput = "";
    private int pinIndex;
    private boolean settingDraft;
    private boolean searchOpen;
    private final ActivityResultLauncher<PickVisualMediaRequest> visualPicker =
            registerForActivityResult(
                    new ActivityResultContracts.PickMultipleVisualMedia(10),
                    uris -> {
                        if (uris != null && !uris.isEmpty() && viewModel != null) {
                            viewModel.sendAttachments(uris);
                        }
                    });
    private final ActivityResultLauncher<String[]> documentPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (uris != null && !uris.isEmpty() && viewModel != null) {
                            viewModel.sendAttachments(uris);
                        }
                    });
    private final ActivityResultLauncher<String> recordAudioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (binding == null) return;
                        if (Boolean.TRUE.equals(isGranted)) {
                            Snackbar.make(binding.getRoot(), R.string.voice_too_short, Snackbar.LENGTH_SHORT).show();
                        } else {
                            Snackbar.make(binding.getRoot(), R.string.voice_permission_required, Snackbar.LENGTH_LONG).show();
                        }
                    });

    private VoiceRecorder voiceRecorder;
    private final Handler recordingHandler = new Handler(Looper.getMainLooper());
    private Runnable recordingTimerRunnable;
    private float recordStartX;
    private boolean recordingCancelled;

    public static ChatFragment newInstance(@NonNull String conversationId) {
        ChatFragment fragment = new ChatFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CONVERSATION_ID, conversationId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        conversationId = getArguments() == null ? "" : getArguments().getString(ARG_CONVERSATION_ID, "");
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        inboxViewModel = new ViewModelProvider(requireActivity()).get(InboxViewModel.class);
        summaryViewModel = new ViewModelProvider(this).get(SummaryViewModel.class);
        configureList();
        configureToolbar();
        configureComposer();
        observeViewModel();
        if (getChildFragmentManager().findFragmentByTag("summary_sheet")
                instanceof com.nexachat.feature.summary.SummaryBottomSheetFragment existingSheet) {
            attachSummaryListener(existingSheet);
        }
        if (getChildFragmentManager().findFragmentByTag("ask_sheet")
                instanceof AskBottomSheetFragment existingAskSheet) {
            attachAskListener(existingAskSheet);
        }
        inboxViewModel.refresh();
        String archiveJumpMessageId = getArguments() == null
                ? null
                : getArguments().getString(ARG_ARCHIVE_JUMP_MESSAGE_ID);
        if (archiveJumpMessageId != null && !archiveJumpMessageId.isBlank()) {
            getArguments().remove(ARG_ARCHIVE_JUMP_MESSAGE_ID);
            viewModel.jumpToMessage(archiveJumpMessageId);
        }
    }

    private void configureList() {
        DateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        adapter = new MessageListAdapter(
                timeFormat, this::resolveOriginal, this, getViewLifecycleOwner(), this);
        RecyclerView list = binding.messageList;
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        layout.setStackFromEnd(true);
        list.setLayoutManager(layout);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (binding == null || adapter == null) return;
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && searchOpen && manager.findLastVisibleItemPosition() >= adapter.getItemCount() - 3 && searchState.getHasMore()) viewModel.loadMoreSearch();
                else if (manager != null && manager.findFirstVisibleItemPosition() <= 1 && state.getHasMore()) viewModel.loadOlder();
                binding.scrollDownFab.setVisibility(manager != null && manager.findLastVisibleItemPosition() < adapter.getItemCount() - 3 ? View.VISIBLE : View.GONE);
            }
        });
        binding.scrollDownFab.setOnClickListener(v -> binding.messageList.smoothScrollToPosition(Math.max(0, adapter.getItemCount() - 1)));
    }

    private void configureToolbar() {
        binding.chatToolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
        binding.callButton.setOnClickListener(v -> {
            String peerId = state.getOtherUserId();
            if (peerId == null) peerId = state.getMessages().stream().filter(m -> !m.getMine()).map(ChatMessage::getSenderId).findFirst().orElse("");
            CallLauncher.start(requireContext(), conversationId, peerId, state.getPeerName() == null ? "" : state.getPeerName());
        });
        binding.searchInChatButton.setOnClickListener(v -> setSearchOpen(true));
        binding.infoButton.setOnClickListener(v -> {
            Bundle args = new Bundle(); args.putString(ARG_CONVERSATION_ID, conversationId);
            try { NavHostFragment.findNavController(this).navigate(R.id.groupDetailFragment, args); }
            catch (IllegalStateException ignored) { Snackbar.make(binding.getRoot(), R.string.error_generic, Snackbar.LENGTH_SHORT).show(); }
        });
        binding.summaryButton.setOnClickListener(v -> showSummary());
        binding.moreButton.setOnClickListener(v -> showMoreOptions());
        binding.closeChatSearch.setOnClickListener(v -> setSearchOpen(false));
        binding.chatSearchInput.setOnEditorActionListener((text, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) return true;
            return false;
        });
        binding.chatSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (searchOpen) viewModel.search(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    /**
     * MaterialToolbar creates its title view internally and exposes no line-limit attribute, so the
     * title is wrapped here. Two lines with an end ellipsis keep long names readable at large font
     * scales while the navigation icon and the scrollable action row stay reachable.
     */
    private void applyChatTitleWrapping() {
        MaterialToolbar toolbar = binding.chatToolbar;
        CharSequence title = toolbar.getTitle();
        if (title == null) return;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof TextView && TextUtils.equals(((TextView) child).getText(), title)) {
                TextView titleView = (TextView) child;
                titleView.setMaxLines(2);
                titleView.setEllipsize(TextUtils.TruncateAt.END);
                return;
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configureComposer() {
        binding.composerBar.attachButton.setOnClickListener(v -> ChatInteractionsViews.showAttachmentPicker(
                requireContext(),
                () -> visualPicker.launch(new PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE).build()),
                () -> documentPicker.launch(new String[]{"*/*"})));
        binding.composerBar.sendButton.setOnClickListener(v -> {
            if (editingMessage == null) viewModel.send();
            else if (!editInput.isBlank()) { viewModel.editMessage(editingMessage.getId(), editInput.trim()); editingMessage = null; editInput = ""; render(); }
        });
        binding.composerBar.messageInput.setOnEditorActionListener((text, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { binding.composerBar.sendButton.performClick(); return true; }
            return false;
        });
        binding.composerBar.messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (settingDraft) return;
                boolean hasText = s != null && !s.toString().isBlank();
                updateComposerButtons(hasText || editingMessage != null);
                if (editingMessage == null) viewModel.onInputChange(s.toString());
                else { editInput = s.toString(); binding.composerBar.sendButton.setEnabled(!editInput.isBlank()); }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        configureVoiceRecording();
    }

    private void updateComposerButtons(boolean showSend) {
        if (binding == null) return;
        binding.composerBar.sendButton.setVisibility(showSend ? View.VISIBLE : View.GONE);
        binding.composerBar.sendButton.setEnabled(showSend);
        binding.composerBar.micButton.setVisibility(showSend ? View.GONE : View.VISIBLE);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configureVoiceRecording() {
        voiceRecorder = new VoiceRecorder(requireContext());
        binding.composerBar.micButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                        return false;
                    }
                    recordStartX = event.getRawX();
                    recordingCancelled = false;
                    if (voiceRecorder == null) {
                        voiceRecorder = new VoiceRecorder(requireContext());
                    }
                    if (!voiceRecorder.start()) {
                        return false;
                    }
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    binding.composerBar.attachButton.setVisibility(View.GONE);
                    binding.composerBar.messageInput.setVisibility(View.GONE);
                    binding.composerBar.recordingOverlay.setVisibility(View.VISIBLE);
                    binding.composerBar.recordingDuration.setText("00:00");
                    binding.composerBar.recordingCancelLabel.setText(R.string.voice_slide_to_cancel);
                    binding.composerBar.recordingCancelLabel.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.text_secondary_light));

                    recordingTimerRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (voiceRecorder != null && voiceRecorder.isRecording()) {
                                long durationSec = voiceRecorder.getDurationMs() / 1000;
                                long minutes = durationSec / 60;
                                long seconds = durationSec % 60;
                                if (binding != null) {
                                    binding.composerBar.recordingDuration.setText(
                                            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
                                    binding.composerBar.recordingDot.setAlpha(
                                            binding.composerBar.recordingDot.getAlpha() == 1f ? 0.3f : 1f);
                                }
                                recordingHandler.postDelayed(this, 500);
                            }
                        }
                    };
                    recordingHandler.postDelayed(recordingTimerRunnable, 500);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (voiceRecorder == null || !voiceRecorder.isRecording()) {
                        return false;
                    }
                    float deltaX = recordStartX - event.getRawX();
                    float thresholdPx = 80 * getResources().getDisplayMetrics().density;
                    if (deltaX > thresholdPx) {
                        if (!recordingCancelled) {
                            recordingCancelled = true;
                            binding.composerBar.recordingCancelLabel.setText(R.string.voice_release_to_cancel);
                            binding.composerBar.recordingCancelLabel.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.error_red));
                        }
                    } else {
                        if (recordingCancelled) {
                            recordingCancelled = false;
                            binding.composerBar.recordingCancelLabel.setText(R.string.voice_slide_to_cancel);
                            binding.composerBar.recordingCancelLabel.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.text_secondary_light));
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (recordingTimerRunnable != null) {
                        recordingHandler.removeCallbacks(recordingTimerRunnable);
                    }
                    restoreComposerAfterRecording();
                    if (voiceRecorder != null) {
                        if (recordingCancelled) {
                            voiceRecorder.cancel();
                        } else {
                            long durationMs = voiceRecorder.getDurationMs();
                            File audioFile = voiceRecorder.stop();
                            if (durationMs < 1000L || audioFile == null) {
                                if (audioFile != null && audioFile.exists()) {
                                    //noinspection ResultOfMethodCallIgnored
                                    audioFile.delete();
                                }
                                Toast.makeText(requireContext(), R.string.voice_too_short, Toast.LENGTH_SHORT).show();
                            } else {
                                Uri voiceUri = FileProvider.getUriForFile(
                                        requireContext(),
                                        requireContext().getPackageName() + ".fileprovider",
                                        audioFile);
                                viewModel.sendAttachments(Collections.singletonList(voiceUri), durationMs);
                            }
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    if (recordingTimerRunnable != null) {
                        recordingHandler.removeCallbacks(recordingTimerRunnable);
                    }
                    if (voiceRecorder != null) {
                        voiceRecorder.cancel();
                    }
                    restoreComposerAfterRecording();
                    return true;
            }
            return false;
        });
    }

    private void restoreComposerAfterRecording() {
        if (binding == null) return;
        binding.composerBar.recordingOverlay.setVisibility(View.GONE);
        binding.composerBar.attachButton.setVisibility(editingMessage == null ? View.VISIBLE : View.GONE);
        binding.composerBar.messageInput.setVisibility(View.VISIBLE);
        binding.composerBar.recordingDot.setAlpha(1f);
    }

    private void observeViewModel() {
        viewModel.getUiState().observe(getViewLifecycleOwner(), next -> {
            if (next != null) {
                state = next;
                render();
            }
        });
        viewModel.getSearchState().observe(getViewLifecycleOwner(), next -> {
            if (next != null) {
                searchState = next;
                renderSearchResults();
            }
        });
        viewModel.getJumpTargets().observe(getViewLifecycleOwner(), raw -> {
            String id = raw == null ? null : raw.consume();
            if (id != null) {
                jumpToLoadedMessage(id);
            }
        });
        viewModel.getActionEvents().observe(getViewLifecycleOwner(), raw -> {
            ChatActionEvent event = raw == null ? null : raw.consume();
            if (event instanceof ChatActionEvent.MessageEdited) render();
            if (event instanceof ChatActionEvent.MessageForwarded forwarded) {
                Snackbar.make(binding.getRoot(), R.string.forwarded_snackbar, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_view, v -> openConversation(forwarded.getTargetConversationId()))
                        .show();
            }
        });
        inboxViewModel.getUiState().observe(getViewLifecycleOwner(), next -> {
            if (next != null) {
                renderUnread(next);
            }
        });
        summaryViewModel.state().observe(getViewLifecycleOwner(), this::renderSummaryState);
    }

    private void render() {
        if (binding == null) return;
        String peerTitle = state.getPeerName() == null ? getString(R.string.chat_title) : state.getPeerName();
        binding.chatToolbar.setTitle(peerTitle);
        applyChatTitleWrapping();
        if (binding.chatHeaderAvatar != null) {
            binding.chatHeaderAvatar.setName(peerTitle);
            Boolean peerOnline = null;
            if (!state.isGroup() && state.getPeerPresence() != null) {
                peerOnline = Boolean.valueOf(state.getPeerPresence().getOnline());
            } else if (!state.isGroup() && state.getConnected()) {
                peerOnline = Boolean.TRUE;
            }
            binding.chatHeaderAvatar.setOnlineStatus(peerOnline);
        }
        String subtitle = state.getPeerTyping() ? getString(R.string.chat_typing)
                : !state.getConnected() ? getString(R.string.chat_offline)
                : !state.isGroup() && state.getPeerPresence() != null
                    ? getString(state.getPeerPresence().getOnline() ? R.string.presence_active_now : R.string.chat_offline) : "";
        binding.chatToolbar.setSubtitle(subtitle);
        binding.callButton.setVisibility(state.isGroup() ? View.GONE : View.VISIBLE);
        binding.infoButton.setVisibility(state.isGroup() ? View.VISIBLE : View.GONE);
        binding.chatError.setVisibility(state.getError() == null ? View.GONE : View.VISIBLE);
        if (state.getError() != null) binding.chatError.setText(ChatErrorText.messageId(state.getError()));
        renderPinned();
        renderReplyEditBanner();
        renderAttachments();
        if (!searchOpen) submitMessages(state.getMessages());
        settingDraft = true;
        String input = editingMessage == null ? state.getInput() : editInput;
        if (!String.valueOf(binding.composerBar.messageInput.getText()).contentEquals(input)) binding.composerBar.messageInput.setText(input);
        settingDraft = false;
        binding.composerBar.attachButton.setVisibility(editingMessage == null ? View.VISIBLE : View.GONE);
        boolean hasText = input != null && !input.isBlank();
        updateComposerButtons(hasText || editingMessage != null);
    }

    private void renderPinned() {
        View root = binding.pinnedBanner.getRoot();
        List<ChatMessage> pins = state.getPins();
        if (pins.isEmpty()) { root.setVisibility(View.GONE); return; }
        ChatMessage pin = pins.get(pinIndex % pins.size());
        ChatInteractionsViews.bindPinnedBanner(root, pin.getContent() == null ? pin.getType() : pin.getContent(), () -> { viewModel.jumpToMessage(pin.getId()); pinIndex++; renderPinned(); });
    }

    private void renderReplyEditBanner() {
        View root = binding.replyEditBanner.getRoot();
        if (editingMessage != null) {
            ChatInteractionsViews.bindReplyBanner(root, getString(R.string.action_edit) + ": " + editingMessage.getContent(), () -> { editingMessage = null; editInput = ""; render(); });
            return;
        }
        ChatViewModel.ReplyTarget target = state.getReplyTarget();
        if (target == null) { root.setVisibility(View.GONE); return; }
        String prefix = target.getSenderName() == null ? getString(R.string.action_reply) : target.getSenderName();
        ChatInteractionsViews.bindReplyBanner(root, prefix + ": " + target.getSnippet(), viewModel::clearReplyTarget);
    }

    private void renderAttachments() {
        List<com.nexachat.feature.media.PendingAttachment> pending = state.getPendingAttachments();
        binding.pendingAttachments.removeAllViews();
        if (pending.isEmpty()) { binding.pendingAttachments.setVisibility(View.GONE); return; }
        for (com.nexachat.feature.media.PendingAttachment item : pending) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = new TextView(requireContext());
            label.setText((item.getDisplayName() == null ? item.getType() : item.getDisplayName())
                    + (item.getState() == com.nexachat.feature.media.PendingAttachment.PendingState.FAILED
                    ? " — " + getString(R.string.error_generic) : " " + Math.round(item.getProgress() * 100) + "%"));
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView remove = new TextView(requireContext());
            remove.setText("×");
            remove.setTextSize(24f);
            remove.setGravity(android.view.Gravity.CENTER);
            remove.setContentDescription(getString(R.string.action_cancel));
            remove.setMinWidth(getResources().getDimensionPixelSize(R.dimen.min_touch_target));
            remove.setOnClickListener(v -> viewModel.removePendingAttachment(item.getLocalId()));
            row.addView(remove, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            binding.pendingAttachments.addView(row);
        }
        binding.pendingAttachments.setVisibility(View.VISIBLE);
    }

    private void renderUnread(InboxUiState inbox) {
        ConversationSummary row = inbox.getConversations().stream().filter(c -> conversationId.equals(c.getId())).findFirst().orElse(null);
        int unread = row == null ? 0 : row.getUnreadCount();
        binding.unreadBanner.setVisibility(unread >= 20 ? View.VISIBLE : View.GONE);
        if (unread >= 20) binding.unreadBanner.setText(getString(R.string.summary_overflow_action) + " (" + unread + ")");
        binding.unreadBanner.setOnClickListener(v -> showSummary());
    }

    private void submitMessages(List<ChatMessage> messages) {
        LinearLayoutManager manager = (LinearLayoutManager) binding.messageList.getLayoutManager();
        int oldCount = adapter.getItemCount();
        boolean wasAtBottom = manager == null || oldCount == 0 || manager.findLastVisibleItemPosition() >= oldCount - 3;
        String anchorId = null;
        if (manager != null && oldCount > 0) {
            int anchor = manager.findFirstVisibleItemPosition();
            if (anchor >= 0 && anchor < oldCount) anchorId = adapter.getCurrentList().get(anchor).getStableId();
        }
        List<MessageListItem> rows = MessageListMapper.buildDisplayItems(messages, state.isGroup(), state.getPeerReadSeq(), state.getPeerDeliveredSeq(), pulsingMessageId,
                new DateSeparatorLabels(getString(R.string.time_today), getString(R.string.time_yesterday)), System.currentTimeMillis());
        String stableAnchor = anchorId;
        adapter.submitList(rows, () -> {
            if (binding == null || rows.isEmpty()) return;
            if (wasAtBottom) binding.messageList.scrollToPosition(rows.size() - 1);
            else if (stableAnchor != null) {
                for (int i = 0; i < rows.size(); i++) if (stableAnchor.equals(rows.get(i).getStableId())) { binding.messageList.scrollToPosition(i); break; }
            }
        });
    }

    private void setSearchOpen(boolean open) {
        searchOpen = open;
        binding.chatSearchBar.setVisibility(open ? View.VISIBLE : View.GONE);
        binding.messageList.setVisibility(View.VISIBLE);
        if (open) { binding.chatSearchInput.requestFocus(); }
        else { binding.chatSearchInput.setText(""); viewModel.clearSearch(); submitMessages(state.getMessages()); }
    }

    private void renderSearchResults() {
        if (!searchOpen || binding == null) return;
        List<ChatMessage> results = searchState.getResults();
        binding.chatError.setVisibility(searchState.getError() == null ? View.GONE : View.VISIBLE);
        if (searchState.getError() != null) binding.chatError.setText(R.string.search_in_chat_error);
        List<MessageListItem> rows = new ArrayList<>();
        for (ChatMessage message : results) rows.add(MessageListItem.message(message,
                com.nexachat.core.ui.components.MessageGroupPosition.SINGLE, false, false, false));
        adapter.submitList(rows);
    }

    private void jumpToLoadedMessage(String messageId) {
        jumpToLoadedMessage(messageId, 0);
    }

    private void jumpToLoadedMessage(String messageId, int attempt) {
        if (binding == null || adapter == null || attempt > 5) return;
        List<MessageListItem> items = new ArrayList<>();
        for (int i = 0; i < adapter.getItemCount(); i++) items.add(adapter.getCurrentList().get(i));
        for (int i = 0; i < items.size(); i++) {
            ChatMessage item = items.get(i).getMessage();
            if (item != null && item.getId().equals(messageId)) {
                pulsingMessageId = messageId;
                submitMessages(state.getMessages());
                binding.messageList.post(() -> {
                    if (binding == null || adapter == null) return;
                    for (int index = 0; index < adapter.getItemCount(); index++) {
                        ChatMessage candidate = adapter.getCurrentList().get(index).getMessage();
                        if (candidate != null && candidate.getId().equals(messageId)) { binding.messageList.smoothScrollToPosition(index); break; }
                    }
                });
                binding.messageList.postDelayed(() -> {
                    pulsingMessageId = null;
                    if (binding != null && adapter != null) submitMessages(state.getMessages());
                }, 1200L);
                return;
            }
        }
        pulsingMessageId = messageId;
        submitMessages(state.getMessages());
        binding.messageList.post(() -> jumpToLoadedMessage(messageId, attempt + 1));
    }

    private void showMoreOptions() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setItems(new CharSequence[]{
                        getString(R.string.ask_action),
                        getString(R.string.summary_overflow_action),
                        getString(R.string.archive_title),
                        getString(R.string.group_board_title)
                }, (dialog, which) -> {
                    if (which == 0) {
                        showAsk();
                    } else if (which == 1) {
                        showSummary();
                    } else if (which == 2) {
                        openArchive();
                    } else if (which == 3) {
                        openGroupBoard();
                    }
                }).show();
    }

    private void openGroupBoard() {
        if (conversationId.isBlank()) {
            return;
        }
        Bundle args = new Bundle();
        args.putString(ARG_CONVERSATION_ID, conversationId);
        args.putBoolean("isGroup", state.isGroup());
        try {
            NavHostFragment.findNavController(this).navigate(R.id.groupBoardFragment, args);
        } catch (RuntimeException ignored) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.error_generic, Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    private void openArchive() {
        if (conversationId.isBlank()) {
            return;
        }
        Bundle args = new Bundle();
        args.putString(ARG_CONVERSATION_ID, conversationId);
        try {
            NavHostFragment.findNavController(this).navigate(R.id.archiveFragment, args);
        } catch (RuntimeException ignored) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.error_generic, Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    private void showAsk() {
        if (getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag("ask_sheet") != null) {
            return;
        }
        AskBottomSheetFragment sheet = AskBottomSheetFragment.newInstance(conversationId);
        attachAskListener(sheet);
        sheet.show(getChildFragmentManager(), "ask_sheet");
    }

    private void attachAskListener(@NonNull AskBottomSheetFragment sheet) {
        sheet.setListener(target -> {
            if (viewModel != null) {
                viewModel.jumpToMessage(target.getMessageId());
            }
        });
    }

    private void showSummary() {
        if (getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag("summary_sheet") != null) {
            return;
        }
        com.nexachat.feature.summary.SummaryBottomSheetFragment sheet =
                com.nexachat.feature.summary.SummaryBottomSheetFragment.newInstance(conversationId);
        java.util.Map<String, Long> seqMap = new java.util.HashMap<>();
        for (ChatMessage m : state.getMessages()) {
            if (m.getId() != null && m.getSeq() != null) {
                seqMap.put(m.getId(), m.getSeq());
            }
        }
        sheet.putCitationArgs(seqMap);
        attachSummaryListener(sheet);
        sheet.show(getChildFragmentManager(), "summary_sheet");
    }

    private void attachSummaryListener(
            @NonNull com.nexachat.feature.summary.SummaryBottomSheetFragment sheet) {
        sheet.setListener(new com.nexachat.feature.summary.SummaryBottomSheetFragment.Listener() {
            @Override public void onJumpToMessage(@NonNull String messageId) {
                if (viewModel != null) viewModel.jumpToMessage(messageId);
            }
            @Override public void onShareIntoChat(@NonNull String shareText) {
                if (viewModel != null) viewModel.onInputChange(shareText);
            }
        });
    }

    private void renderSummaryState(SummaryUiState next) { }

    private void openConversation(String id) {
        Bundle args = new Bundle(); args.putString(ARG_CONVERSATION_ID, id);
        try { NavHostFragment.findNavController(this).navigate(R.id.chatFragment, args); } catch (IllegalStateException ignored) { }
    }

    @Nullable private ChatMessage resolveOriginal(String id) {
        return state.getMessages().stream().filter(message -> id.equals(message.getId())).findFirst().orElse(null);
    }

    @Override public void onMessageTap(@NonNull String messageId) { if (searchOpen) { setSearchOpen(false); viewModel.jumpToMessage(messageId); } }
    @Override public void onMessageLongPress(@NonNull String messageId) {
        ChatMessage message = resolveOriginal(messageId); if (message == null) return;
        ChatInteractionsViews.showMessageActions(requireContext(), message, state.getCanPin(), state.isPinned(messageId), new ChatInteractionsViews.MessageActions() {
            @Override public void reply(@NonNull ChatMessage value) { viewModel.setReplyTarget(value); }
            @Override public void edit(@NonNull ChatMessage value) { showEdit(value); }
            @Override public void delete(@NonNull ChatMessage value) { viewModel.deleteMessage(value.getId()); }
            @Override public void react(@NonNull ChatMessage value, @NonNull String emoji) { viewModel.toggleReaction(value, emoji); }
            @Override public void pin(@NonNull ChatMessage value) { viewModel.togglePin(value); }
            @Override public void forward(@NonNull ChatMessage value) { showForward(value); }
        });
    }
    @Override public void onRetryTap(@NonNull String messageId) { viewModel.retryFailed(messageId); }
    @Override public void onReplyQuoteTap(@NonNull String replyToId) { viewModel.jumpToMessage(replyToId); }
    @Override public void onOpenMedia(@NonNull String messageId, @NonNull AttachmentDto attachment) {
        if (attachment.getId() == null) return;
        Bundle args = new Bundle(); args.putString("attachmentId", attachment.getId()); args.putString("name", attachment.getOriginalName()); args.putString("mime", attachment.getMimeType());
        try { NavHostFragment.findNavController(this).navigate(R.id.mediaViewerFragment, args); } catch (RuntimeException ignored) { }
    }
    @Override public void onOpenFile(@NonNull String messageId, @NonNull AttachmentDto attachment) {
        subscriptions.add(
                viewModel.attachmentFile(attachment)
                        .subscribe(
                                file -> {
                                    if (!isAdded()) return;
                                    String mime = attachment.getMimeType() == null
                                            ? "application/octet-stream"
                                            : attachment.getMimeType();
                                    com.nexachat.feature.media.AttachmentViewBinder.openExternally(
                                            requireContext(), file, mime);
                                },
                                error -> {
                                    if (isAdded()) {
                                        Toast.makeText(requireContext(), R.string.error_attachment_open, Toast.LENGTH_SHORT).show();
                                    }
                                },
                                () -> {
                                    if (isAdded()) {
                                        Toast.makeText(requireContext(), R.string.error_attachment_open, Toast.LENGTH_SHORT).show();
                                    }
                                }));
    }
    @Override public void onReactionTap(@NonNull String messageId, @NonNull String emoji) { ChatMessage message = resolveOriginal(messageId); if (message != null) viewModel.toggleReaction(message, emoji); }

    private void showEdit(ChatMessage message) {
        editingMessage = message;
        editInput = message.getContent() == null ? "" : message.getContent();
        render();
        binding.composerBar.messageInput.requestFocus();
    }

    private void showForward(ChatMessage message) {
        InboxUiState inbox = inboxViewModel.getUiState().getValue();
        List<ConversationSummary> rows = inbox == null ? Collections.emptyList() : inbox.getConversations();
        ChatInteractionsViews.showForwardPicker(requireContext(), rows, id -> viewModel.forwardMessage(message.getId(), id));
    }

    @Override public void onDestroyView() {
        subscriptions.clear();
        if (recordingTimerRunnable != null) {
            recordingHandler.removeCallbacks(recordingTimerRunnable);
        }
        if (voiceRecorder != null) {
            voiceRecorder.cancel();
            voiceRecorder = null;
        }
        if (binding != null) {
            binding.messageList.setAdapter(null);
        }
        adapter = null;
        binding = null;
        super.onDestroyView();
    }
}
