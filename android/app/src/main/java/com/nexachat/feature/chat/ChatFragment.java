package com.nexachat.feature.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.content.ActivityNotFoundException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.call.CallLauncher;
import com.nexachat.call.core.CallMediaType;
import com.nexachat.core.common.PhoneNormalizer;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageMention;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.ui.components.DateSeparatorLabels;
import com.nexachat.core.ui.RecallWindowRules;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.GroupMember;
import com.nexachat.databinding.FragmentChatBinding;
import com.nexachat.feature.chat.ui.MessageActionListener;
import com.nexachat.feature.chat.voice.VoiceRecorder;
import java.io.File;
import com.nexachat.feature.chat.adapter.ChatActionImageButton;
import com.nexachat.feature.chat.adapter.MessageListAdapter;
import com.nexachat.feature.chat.adapter.MessageListItem;
import com.nexachat.feature.chat.adapter.MessageListMapper;
import com.nexachat.feature.ask.AskBottomSheetFragment;
import com.nexachat.feature.ask.AskCitationJump;
import com.nexachat.feature.inbox.InboxUiState;
import com.nexachat.feature.inbox.InboxViewModel;
import com.nexachat.feature.inbox.ConversationPreferenceMenu;
import com.nexachat.feature.profile.AppSettingsViewModel;
import com.nexachat.feature.label.LabelDialogFragment;
import com.nexachat.feature.safety.ReportSheetFragment;
import com.nexachat.feature.streak.StreakMilestoneSheet;
import com.nexachat.feature.streak.StreakViewModel;
import com.nexachat.notification.ForegroundConversationTracker;
import com.nexachat.summary.SummaryResponse;
import com.nexachat.summary.SummaryScope;
import com.nexachat.summary.SummaryUiState;
import com.nexachat.summary.SummaryViewModel;
import com.nexachat.summary.UnreadBannerRule;
import dagger.hilt.android.AndroidEntryPoint;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import javax.inject.Inject;

/** View-based direct/group chat. The ViewModel remains the owner of message state and actions. */
@AndroidEntryPoint
public final class ChatFragment extends androidx.fragment.app.Fragment
        implements MessageActionListener,
        MessageActionSheetFragment.Host,
        MessageActionSheetFragment.Actions,
        ForwardPickerSheet.Host,
        AttachmentPickerSheet.Host,
        SharedContentSheet.Host,
        ReportSheetFragment.Host {
    @Inject ForegroundConversationTracker foregroundConversationTracker;
    @Inject ContactsRepository contactsRepository;
    private static final String PREFS_VOICE_PERMISSION = "chat_voice_permission";
    private static final String KEY_RECORD_AUDIO_REQUESTED = "record_audio_requested";
    private static final String PREFS_LOCATION_PERMISSION = "chat_location_permission";
    private static final String KEY_LOCATION_REQUESTED = "location_requested";
    private static final String ARG_CONVERSATION_ID = "conversationId";
    private static final long STREAK_REFRESH_DELAY_MS = 1500L;
    private static final String ARG_ARCHIVE_JUMP_MESSAGE_ID = "archiveJumpMessageId";
    private static final int MENU_ASK = 1;
    private static final int MENU_SUMMARY = 2;
    private static final int MENU_MUTE = 3;
    private static final int MENU_ARCHIVE = 4;
    private static final int MENU_SETTINGS = 5;
    private static final int MENU_SHARED_MEDIA = 6;
    private static final int MENU_GROUP_BOARD = 7;
    private static final int MENU_PIN = 8;
    private static final int MENU_ASSIGN_LABEL = 9;
    private static final int MENU_SEARCH = 10;
    private static final int MENU_INFO = 11;
    private static final int MENU_ENCRYPTION = 12;
    private static final int MENU_BLOCK_PEER = 13;
    private static final int MENU_UNBLOCK_PEER = 14;
    private static final int MENU_REPORT = 15;
    private static final int MENU_STARRED = 16;
    private static final int MENU_DISAPPEARING = 17;
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private FragmentChatBinding binding;
    private ChatViewModel viewModel;
    private InboxViewModel inboxViewModel;
    private SummaryViewModel summaryViewModel;
    private StreakViewModel streakViewModel;
    private boolean streakRequested;
    @Nullable private String lastActivitySignature;
    @Nullable private Runnable streakRefresh;
    private AppSettingsViewModel settingsViewModel;
    private MessageListAdapter adapter;
    private ChatViewModel.UiState state = new ChatViewModel.UiState();
    private boolean serverStarting;
    private ChatSearchState searchState = new ChatSearchState();
    private RecallWindowConfig recallWindowConfig = RecallWindowConfig.DEFAULT;
    private Set<String> starredIds = Set.of();
    private boolean peerBlocked;
    @Nullable private String lastProbedBlockPeerId;
    private String conversationId = "";
    private String pulsingMessageId;
    private MentionDraft composerMentionDraft = MentionDraft.empty("");
    private MentionDraft.Query mentionQuery;
    private MentionSuggestionAdapter mentionAdapter;
    private List<GroupMember> groupMembers = List.of();
    private boolean groupMembersRequested;
    private int pinIndex;
    private boolean settingDraft;
    private boolean searchOpen;
    @Nullable private Long unreadDividerLocalReadSeq;
    private boolean unreadDividerDismissed;
    private boolean unreadDividerWasVisible;
    private int cachedUnreadDividerAdapterPos = -1;
    private int cachedFirstUnreadAdapterPos = -1;
    private final ChatRowRenderInputs rowRenderInputs = new ChatRowRenderInputs();
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
    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    grants -> {
                        if (binding == null) return;
                        boolean granted = grants != null && grants.containsValue(Boolean.TRUE);
                        ShareLocationPermissionHelper.ResultAction action =
                                ShareLocationPermissionHelper.resultAction(
                                        granted, locationRationaleShown(),
                                        locationPermissionRequested());
                        if (action == ShareLocationPermissionHelper.ResultAction.PROCEED) {
                            beginLocationFetch();
                            return;
                        }
                        showLocationPermissionSnackbar(
                                action == ShareLocationPermissionHelper.ResultAction.PERMANENT_DENIAL);
                    });
    private final ActivityResultLauncher<Intent> contactPicker =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null
                                && result.getData().getData() != null) {
                            handleContactPicked(result.getData().getData());
                        }
                    });
    private final ActivityResultLauncher<String> recordAudioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (binding == null) return;
                        boolean granted = Boolean.TRUE.equals(isGranted);
                        boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                                requireActivity(), Manifest.permission.RECORD_AUDIO);
                        VoiceRecordPermissionHelper.ResultAction action =
                                VoiceRecordPermissionHelper.permissionResultAction(
                                        granted, rationale, recordAudioPermissionRequested());
                        if (action == VoiceRecordPermissionHelper.ResultAction.NONE) {
                            if (!VoiceRecordPermissionHelper.shouldBeginRecordingAfterPermissionGrant()) {
                                Snackbar.make(binding.getRoot(), R.string.voice_too_short,
                                        Snackbar.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        showVoicePermissionSnackbar(
                                action == VoiceRecordPermissionHelper.ResultAction.PERMANENT_DENIAL_SNACKBAR);
                    });

    private VoiceRecorder voiceRecorder;
    private final Handler recordingHandler = new Handler(Looper.getMainLooper());
    private Runnable recordingTimerRunnable;
    private float recordStartX;
    private boolean recordingCancelled;
    private boolean accessibleVoiceRecordingActive;
    private boolean voiceStartPending;
    private boolean voiceStopWhenStarted;
    private boolean voiceSendWhenStarted;
    private long voiceStartGeneration;

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
        // Fragment scope, not the activity store: a retained instance would keep the
        // previous account's user id and inbox rows across an account switch.
        inboxViewModel = new ViewModelProvider(this).get(InboxViewModel.class);
        summaryViewModel = new ViewModelProvider(this).get(SummaryViewModel.class);
        // Activity scope: shares the Profile screen's settings instance so a new
        // swatch propagates here the moment the user returns.
        settingsViewModel = new ViewModelProvider(requireActivity()).get(AppSettingsViewModel.class);
        configureList();
        configureMentionPicker();
        configureToolbar();
        configureComposer();
        composerMentionDraft = viewModel.getMentionDraft();
        observeViewModel();
        observeStreak();
        if (getChildFragmentManager().findFragmentByTag("summary_sheet")
                instanceof com.nexachat.feature.summary.SummaryBottomSheetFragment existingSheet) {
            attachSummaryListener(existingSheet);
        }
        if (getChildFragmentManager().findFragmentByTag("ask_sheet")
                instanceof AskBottomSheetFragment existingAskSheet) {
            attachAskListener(existingAskSheet);
        }
        inboxViewModel.refresh();
        InboxUiState initialInbox = inboxViewModel.getUiState().getValue();
        if (initialInbox != null) {
            captureUnreadDividerBaseline(initialInbox);
        }
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
        // A fresh adapter starts empty; the render memo must not claim the
        // current rows are already submitted.
        rowRenderInputs.invalidate();
        adapter.setTranslationRetry(id -> {
            ChatMessage m = resolveOriginal(id);
            if (m != null) {
                viewModel.retryTranslation(m);
            }
            return true;
        });
        RecyclerView list = binding.messageList;
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        layout.setStackFromEnd(true);
        list.setLayoutManager(layout);
        list.setAdapter(adapter);
        new androidx.recyclerview.widget.ItemTouchHelper(new ReplySwipeCallback(
                adapter, () -> searchOpen || viewModel.isEditing(), viewModel::setReplyTarget)).attachToRecyclerView(list);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (binding == null || adapter == null) return;
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && searchOpen && manager.findLastVisibleItemPosition() >= adapter.getItemCount() - 3 && searchState.getHasMore()) viewModel.loadMoreSearch();
                else if (manager != null && manager.findFirstVisibleItemPosition() <= 1 && state.getHasMore()) viewModel.loadOlder();
                binding.scrollDownFab.setVisibility(manager != null && manager.findLastVisibleItemPosition() < adapter.getItemCount() - 3 ? View.VISIBLE : View.GONE);
                updateUnreadDividerReached(manager);
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
        binding.videoCallButton.setOnClickListener(v -> {
            String peerId = state.getOtherUserId();
            if (peerId == null) peerId = state.getMessages().stream().filter(m -> !m.getMine()).map(ChatMessage::getSenderId).findFirst().orElse("");
            CallLauncher.start(requireContext(), conversationId, peerId, state.getPeerName() == null ? "" : state.getPeerName(),
                    null, CallMediaType.VIDEO);
        });
        binding.moreButton.setOnClickListener(v -> showMoreOptions());
        binding.unblockPeerButton.setOnClickListener(v -> viewModel.unblockPeer());
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
        binding.composerBar.attachButton.setOnClickListener(v -> {
            // Location/contact share through the TEXT path, so the sheet stays
            // reachable in E2EE chats; the sheet hides the media rows there.
            if (!getChildFragmentManager().isStateSaved()
                    && getChildFragmentManager().findFragmentByTag(AttachmentPickerSheet.TAG) == null) {
                AttachmentPickerSheet.newInstance(!state.isE2eeEnabled())
                        .show(getChildFragmentManager(), AttachmentPickerSheet.TAG);
            }
        });
        binding.composerBar.sendButton.setOnClickListener(v -> {
            if (!binding.composerBar.sendButton.isEnabled()) {
                return;
            }
            if (viewModel.isEditing()) {
                viewModel.submitEdit();
                binding.composerBar.sendButton.setEnabled(
                        !viewModel.currentEditDraft().getContent().isBlank());
            } else {
                binding.composerBar.sendButton.setEnabled(false);
                binding.composerBar.sendButton.announceForAccessibility(
                        getString(R.string.cd_sending));
                viewModel.send();
            }
        });
        binding.composerBar.messageInput.setOnEditorActionListener((text, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { binding.composerBar.sendButton.performClick(); return true; }
            return false;
        });
        binding.composerBar.messageInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && binding.mentionPicker.getVisibility() == View.VISIBLE
                    && mentionAdapter != null) {
                return mentionAdapter.focusFirst();
            }
            return false;
        });
        binding.composerBar.messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (settingDraft) return;
                boolean hasText = s != null && !s.toString().isBlank();
                updateComposerButtons(hasText || viewModel.isEditing());
                String value = s == null ? "" : s.toString();
                if (!viewModel.isEditing()) {
                    composerMentionDraft = composerMentionDraft.afterEdit(start, before, count, value);
                    viewModel.onComposerDraftChange(composerMentionDraft);
                    applyMentionSpans(binding.composerBar.messageInput, composerMentionDraft);
                } else {
                    MentionDraft next = viewModel.currentEditDraft().afterEdit(start, before, count, value);
                    viewModel.onEditDraftChange(next);
                    binding.composerBar.sendButton.setEnabled(!next.getContent().isBlank());
                    applyMentionSpans(binding.composerBar.messageInput, next);
                }
                updateMentionPicker(value, binding.composerBar.messageInput.getSelectionStart());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        configureVoiceRecording();
    }

    private void configureMentionPicker() {
        binding.mentionPicker.setLayoutManager(new LinearLayoutManager(requireContext()));
        mentionAdapter = new MentionSuggestionAdapter(this::insertMention);
        binding.mentionPicker.setAdapter(mentionAdapter);
        binding.mentionPicker.setNestedScrollingEnabled(false);
        binding.mentionPicker.setFocusable(false);
        binding.mentionPicker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private MentionDraft currentMentionDraft() {
        return viewModel.isEditing() ? viewModel.currentEditDraft() : composerMentionDraft;
    }

    private void updateMentionPicker(String text, int selectionStart) {
        if (binding == null || mentionAdapter == null || !state.isGroup()) {
            mentionQuery = null;
            if (binding != null) binding.mentionPicker.setVisibility(View.GONE);
            return;
        }
        mentionQuery = MentionDraft.findActiveQuery(text, selectionStart);
        if (mentionQuery == null) {
            mentionAdapter.submit(List.of());
            binding.mentionPicker.setVisibility(View.GONE);
            return;
        }
        InboxUiState inbox = inboxViewModel == null ? null : inboxViewModel.getUiState().getValue();
        List<MentionPickerRules.Suggestion> suggestions = MentionPickerRules.suggestions(
                true, groupMembers, inbox == null ? null : inbox.getCurrentUserId(),
                mentionQuery.getValue(), currentMentionDraft().getMentions(),
                currentMentionDraft().isMentionAll());
        mentionAdapter.submit(suggestions);
        binding.mentionPicker.setVisibility(suggestions.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void insertMention(MentionPickerRules.Suggestion suggestion) {
        if (binding == null || mentionQuery == null || suggestion == null) return;
        MentionDraft draft = currentMentionDraft();
        int total = draft.getMentions().size() + (draft.isMentionAll() ? 1 : 0);
        if (total >= MentionDraft.MAX_MENTIONS) {
            clearComposerAfterMentionLimit();
            return;
        }
        String text = binding.composerBar.messageInput.getText().toString();
        String token = suggestion.isAll() ? MentionDraft.ALL_TOKEN : "@" + suggestion.getDisplayName();
        String insertion = token + " ";
        String nextText = text.substring(0, mentionQuery.getStart()) + insertion
                + text.substring(mentionQuery.getEnd());
        MentionDraft next = draft.afterEdit(mentionQuery.getStart(),
                mentionQuery.getEnd() - mentionQuery.getStart(), insertion.length(), nextText);
        if (suggestion.isAll()) {
            next = next.withMentionAll(nextText, mentionQuery.getStart());
        } else {
            next = next.withMention(nextText, suggestion.getUserId(), mentionQuery.getStart(), token.length());
        }
        int cursor = mentionQuery.getStart() + insertion.length();
        mentionQuery = null;
        settingDraft = true;
        binding.composerBar.messageInput.setText(nextText);
        binding.composerBar.messageInput.setSelection(Math.min(cursor, nextText.length()));
        settingDraft = false;
        binding.composerBar.messageInput.requestFocus();
        if (!viewModel.isEditing()) {
            composerMentionDraft = next;
            viewModel.onComposerDraftChange(next);
        } else {
            viewModel.onEditDraftChange(next);
        }
        applyMentionSpans(binding.composerBar.messageInput, next);
        updateComposerButtons(true);
        updateMentionPicker(nextText, cursor);
    }

    private void clearComposerAfterMentionLimit() {
        mentionQuery = null;
        settingDraft = true;
        binding.composerBar.messageInput.setText("");
        settingDraft = false;
        if (viewModel.isEditing()) {
            viewModel.onEditDraftChange(MentionDraft.empty(""));
        } else {
            composerMentionDraft = MentionDraft.empty("");
            viewModel.onComposerDraftChange(composerMentionDraft);
        }
        binding.mentionPicker.setVisibility(View.GONE);
        updateComposerButtons(false);
        Snackbar.make(binding.getRoot(), R.string.mention_limit_exceeded, Snackbar.LENGTH_LONG).show();
    }

    private void applyMentionSpans(EditText input, MentionDraft draft) {
        Editable editable = input.getText();
        for (BackgroundColorSpan span : editable.getSpans(0, editable.length(), BackgroundColorSpan.class)) editable.removeSpan(span);
        for (ForegroundColorSpan span : editable.getSpans(0, editable.length(), ForegroundColorSpan.class)) editable.removeSpan(span);
        for (StyleSpan span : editable.getSpans(0, editable.length(), StyleSpan.class)) editable.removeSpan(span);
        int background = themeColor(com.google.android.material.R.attr.colorSecondaryContainer);
        int foreground = themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer);
        for (MessageMention mention : draft.getMentions()) {
            addMentionSpan(editable, mention.getOffset(), mention.getLength(), background, foreground);
        }
        if (draft.isMentionAll()) {
            addMentionSpan(editable, draft.getMentionAllOffset(), draft.getMentionAllLength(), background, foreground);
        }
    }

    private void addMentionSpan(Editable editable, int start, int length, int background, int foreground) {
        if (!MentionDraft.isValidRange(editable.toString(), start, length)) return;
        editable.setSpan(new BackgroundColorSpan(background), start, start + length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        editable.setSpan(new ForegroundColorSpan(foreground), start, start + length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        editable.setSpan(new StyleSpan(Typeface.BOLD), start, start + length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private int themeColor(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (requireContext().getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) return ContextCompat.getColor(requireContext(), value.resourceId);
            return value.data;
        }
        return ContextCompat.getColor(requireContext(), R.color.colorPrimary);
    }

    private void updateComposerButtons(boolean showSend) {
        if (binding == null) return;
        binding.composerBar.sendButton.setVisibility(showSend ? View.VISIBLE : View.GONE);
        binding.composerBar.sendButton.setEnabled(showSend);
        binding.composerBar.micButton.setVisibility(showSend ? View.GONE : View.VISIBLE);
        if (showSend) {
            ChatComposerEditPresentation.applySendButton(
                    binding.composerBar.sendButton, viewModel.isEditing());
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configureVoiceRecording() {
        voiceRecorder = viewModel.getOrCreateVoiceRecorder(requireContext());
        ChatActionImageButton micButton = binding.composerBar.micButton;
        micButton.setOnClickListener(v -> handleAccessibleMicClick());
        micButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (!ensureRecordAudioPermission()) {
                        return false;
                    }
                    recordStartX = event.getRawX();
                    recordingCancelled = false;
                    accessibleVoiceRecordingActive = false;
                    resetAccessibleVoiceRecordingUi();
                    if (!beginVoiceRecording(v)) {
                        // Consume the tap so it cannot fall through to the accessible
                        // click path and retry silently; the hint explains the gesture.
                        showQuickTapHint();
                        return true;
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    // voiceStartPending first: isRecording() is synchronized
                    // on the recorder and would block while start() runs.
                    if (voiceRecorder == null || (!voiceStartPending && !voiceRecorder.isRecording())) {
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
                    if (voiceStartPending) {
                        voiceStopWhenStarted = true;
                        if (!recordingCancelled) {
                            // A plain release inside the start window matches the
                            // old too-short path: still cancelled, hint intact.
                            showComposerSnackbar(R.string.voice_too_short);
                        }
                        recordingCancelled = true;
                        return true;
                    }
                    finishVoiceRecording(recordingCancelled, false);
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    if (!v.isAttachedToWindow()
                            || (getActivity() != null
                                    && getActivity().isChangingConfigurations())) {
                        // Teardown cancels the held touch before the view dies;
                        // keep the ViewModel-owned capture running so the new
                        // view reattaches the HUD in tap-controlled mode.
                        viewModel.setVoiceRetainedForConfigChange(true);
                        return true;
                    }
                    if (voiceStartPending) {
                        voiceStopWhenStarted = true;
                        return true;
                    }
                    finishVoiceRecording(true, false);
                    return true;
            }
            return false;
        });
        if (viewModel.isVoiceRetainedForConfigChange()) {
            // The retained flag only releases once an active capture is
            // confirmed; a start still in-flight on the executor must keep it
            // so the completion runnable does not cancel the recording.
            maybeResumeVoiceRecording(micButton, 0);
        }
    }

    @Nullable private Runnable voiceResumePoll;

    private void maybeResumeVoiceRecording(@NonNull ChatActionImageButton micButton, int attempt) {
        voiceResumePoll = null;
        if (binding == null || !isAdded() || !viewModel.isVoiceRetainedForConfigChange()) {
            return;
        }
        if (viewModel.isVoiceRecordingActive()) {
            viewModel.setVoiceRetainedForConfigChange(false);
            resumeVoiceRecordingAfterConfigChange(micButton);
            return;
        }
        if (attempt < 10) {
            // start() can still be mid-prepare on the start executor.
            voiceResumePoll = () -> maybeResumeVoiceRecording(micButton, attempt + 1);
            recordingHandler.postDelayed(voiceResumePoll, 250);
        } else {
            // The recorder never came up; drop it instead of holding the mic.
            viewModel.setVoiceRetainedForConfigChange(false);
            viewModel.releaseVoiceRecorder();
            voiceRecorder = null;
        }
    }

    /**
     * The recorder survived view recreation; the held touch was cancelled by
     * the config change, so the HUD comes back in the tap-controlled
     * (accessible) mode: tap the mic to send, accessibility action cancels.
     */
    private void resumeVoiceRecordingAfterConfigChange(@NonNull ChatActionImageButton micButton) {
        voiceRecorder = viewModel.getVoiceRecorder();
        recordingCancelled = false;
        voiceStartPending = false;
        voiceStopWhenStarted = false;
        voiceSendWhenStarted = false;
        accessibleVoiceRecordingActive = true;
        showVoiceRecordingOverlay();
        binding.composerBar.recordingCancelLabel.setText(R.string.voice_recording_active_hint);
        binding.composerBar.recordingCancelLabel.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_secondary_light));
        micButton.setAccessibleVoiceRecording(true,
                () -> finishVoiceRecording(true, true));
        scheduleRecordingTimer();
        announceVoiceRecordingState(R.string.cd_record_voice);
    }

    private void e2eeMediaUnsupported() {
        Snackbar.make(binding.getRoot(), R.string.e2ee_composer_unsupported,
                Snackbar.LENGTH_SHORT).show();
    }

    private void handleAccessibleMicClick() {
        if (state.isE2eeEnabled()) {
            e2eeMediaUnsupported();
            return;
        }
        // voiceStartPending first: isRecording() is synchronized on the
        // recorder and would block while start() runs off the main thread.
        if (voiceRecorder != null && (voiceStartPending || voiceRecorder.isRecording())) {
            if (voiceStartPending) {
                voiceSendWhenStarted = true;
            } else {
                finishVoiceRecording(false, true);
            }
            announceVoiceRecordingState(R.string.cd_send);
            return;
        }
        if (!ensureRecordAudioPermission()) {
            return;
        }
        recordingCancelled = false;
        accessibleVoiceRecordingActive = true;
        if (!beginVoiceRecording(binding.composerBar.micButton)) {
            accessibleVoiceRecordingActive = false;
            resetAccessibleVoiceRecordingUi();
            return;
        }
        binding.composerBar.micButton.setAccessibleVoiceRecording(true, () -> {
            // voiceStartPending first: isRecording() is synchronized on the
            // recorder and would block while start() runs off the main thread.
            if (voiceStartPending) {
                voiceStopWhenStarted = true;
                announceVoiceRecordingState(R.string.voice_release_to_cancel);
                return;
            }
            if (voiceRecorder != null && voiceRecorder.isRecording()) {
                finishVoiceRecording(true, true);
                announceVoiceRecordingState(R.string.voice_release_to_cancel);
            }
        });
        announceVoiceRecordingState(R.string.cd_record_voice);
    }

    private boolean ensureRecordAudioPermission() {
        boolean granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(), Manifest.permission.RECORD_AUDIO);
        VoiceRecordPermissionHelper.TapAction action = VoiceRecordPermissionHelper.tapAction(
                granted, rationale, recordAudioPermissionRequested());
        if (action == VoiceRecordPermissionHelper.TapAction.PROCEED) {
            return true;
        }
        if (action == VoiceRecordPermissionHelper.TapAction.SHOW_RATIONALE) {
            showMicRationaleThenRequest();
        } else if (action == VoiceRecordPermissionHelper.TapAction.REQUEST_PERMISSION) {
            launchRecordAudioRequest();
        } else if (action == VoiceRecordPermissionHelper.TapAction.OPEN_SETTINGS) {
            showVoicePermissionSnackbar(true);
        }
        return false;
    }

    private void launchRecordAudioRequest() {
        markRecordAudioRequested();
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void showMicRationaleThenRequest() {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.voice_mic_rationale)
                .setPositiveButton(R.string.call_permission_continue,
                        (dialog, which) -> launchRecordAudioRequest())
                .setNegativeButton(R.string.call_permission_cancel, null)
                .show();
    }

    private void showVoicePermissionSnackbar(boolean offerSettings) {
        if (binding == null || !isAdded()) return;
        if (offerSettings) {
            // Permanent denial: a fading snackbar reads as a dead mic button, so
            // show a persistent dialog that routes to app settings instead.
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.voice_permission_required)
                    .setPositiveButton(R.string.action_open_app_settings,
                            (dialog, which) -> openAppSettings())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        Snackbar.make(binding.getRoot(), R.string.voice_permission_required,
                Snackbar.LENGTH_LONG).show();
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + requireContext().getPackageName())));
        } catch (ActivityNotFoundException ignored) {
            // No system handler; the snackbar already explained the requirement.
        }
    }

    private boolean recordAudioPermissionRequested() {
        return requireContext().getSharedPreferences(PREFS_VOICE_PERMISSION, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_RECORD_AUDIO_REQUESTED, false);
    }

    private void markRecordAudioRequested() {
        requireContext().getSharedPreferences(PREFS_VOICE_PERMISSION, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RECORD_AUDIO_REQUESTED, true).apply();
    }

    private boolean ensureLocationPermission() {
        boolean granted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        ShareLocationPermissionHelper.TapAction action =
                ShareLocationPermissionHelper.tapAction(
                        granted, locationRationaleShown(), locationPermissionRequested());
        if (action == ShareLocationPermissionHelper.TapAction.PROCEED) {
            return true;
        }
        if (action == ShareLocationPermissionHelper.TapAction.SHOW_RATIONALE) {
            showLocationRationaleThenRequest();
        } else if (action == ShareLocationPermissionHelper.TapAction.REQUEST_PERMISSION) {
            launchLocationRequest();
        } else if (action == ShareLocationPermissionHelper.TapAction.OPEN_SETTINGS) {
            showLocationPermissionSnackbar(true);
        }
        return false;
    }

    private boolean locationRationaleShown() {
        return ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(), Manifest.permission.ACCESS_FINE_LOCATION)
                || ActivityCompat.shouldShowRequestPermissionRationale(
                        requireActivity(), Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void launchLocationRequest() {
        markLocationRequested();
        locationPermissionLauncher.launch(new String[] {
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION});
    }

    private void showLocationRationaleThenRequest() {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.location_permission_rationale)
                .setPositiveButton(R.string.call_permission_continue,
                        (dialog, which) -> launchLocationRequest())
                .setNegativeButton(R.string.call_permission_cancel, null)
                .show();
    }

    private void showLocationPermissionSnackbar(boolean offerSettings) {
        if (binding == null || !isAdded()) return;
        if (offerSettings) {
            // Permanent denial, same treatment as the mic flow: a dialog
            // routes to app settings instead of a fading snackbar.
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.location_permission_required)
                    .setPositiveButton(R.string.action_open_app_settings,
                            (dialog, which) -> openAppSettings())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        Snackbar.make(binding.getRoot(), R.string.location_permission_required,
                Snackbar.LENGTH_LONG).show();
    }

    private void beginLocationFetch() {
        if (!isAdded()) return;
        CurrentLocationFetcher.fetch(requireContext(), (location, status) -> {
            if (!isAdded() || binding == null || getChildFragmentManager().isStateSaved()) {
                return;
            }
            switch (status) {
                case OK -> {
                    if (location != null && getChildFragmentManager()
                            .findFragmentByTag(SharedContentSheet.TAG) == null) {
                        SharedContentSheet.forLocation(
                                        location.getLatitude(), location.getLongitude())
                                .show(getChildFragmentManager(), SharedContentSheet.TAG);
                    }
                }
                case DISABLED -> Snackbar.make(binding.getRoot(),
                                R.string.location_disabled, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_open_app_settings,
                                v -> openLocationSettings())
                        .show();
                default -> Snackbar.make(binding.getRoot(),
                                R.string.location_unavailable, Snackbar.LENGTH_LONG)
                        .show();
            }
        });
    }

    private void openLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            openAppSettings();
        }
    }

    private void handleContactPicked(@NonNull Uri contactUri) {
        String name = null;
        String number = null;
        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cursor = requireContext().getContentResolver()
                .query(contactUri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
                number = cursor.getString(1);
            }
        } catch (RuntimeException ignored) {
            // Provider revoked the one-row grant or returned a broken row.
        }
        if (binding == null || !isAdded()) {
            return;
        }
        if (number == null || number.isBlank()) {
            Snackbar.make(binding.getRoot(), R.string.contact_pick_error,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        String phone = PhoneNormalizer.toE164(number);
        if (!SharedContent.isE164(phone)) {
            Snackbar.make(binding.getRoot(), R.string.contact_invalid_phone,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        String displayName = SharedContent.sanitizeText(name);
        if (displayName.isEmpty()) {
            displayName = phone;
        }
        if (getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(SharedContentSheet.TAG) != null) {
            return;
        }
        SharedContentSheet.forContact(displayName, phone)
                .show(getChildFragmentManager(), SharedContentSheet.TAG);
    }

    private boolean locationPermissionRequested() {
        return requireContext().getSharedPreferences(PREFS_LOCATION_PERMISSION, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCATION_REQUESTED, false);
    }

    private void markLocationRequested() {
        requireContext().getSharedPreferences(PREFS_LOCATION_PERMISSION, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LOCATION_REQUESTED, true).apply();
    }

    private boolean beginVoiceRecording(@NonNull View source) {
        if (voiceRecorder == null) {
            voiceRecorder = viewModel.getOrCreateVoiceRecorder(requireContext());
        }
        // MediaRecorder prepare()/start() can block for seconds on slow
        // devices; run it off the main thread so ACTION_DOWN cannot ANR.
        if (voiceStartPending || voiceRecorder.isRecording()) {
            return false;
        }
        voiceStartPending = true;
        voiceStopWhenStarted = false;
        voiceSendWhenStarted = false;
        source.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showVoiceRecordingOverlay();
        final VoiceRecorder recorder = voiceRecorder;
        final long startGeneration = ++voiceStartGeneration;
        voiceStartExecutor().execute(() -> {
            boolean started = recorder.start();
            recordingHandler.post(() -> {
                if (VoiceStartCompletionRules.isStale(startGeneration, voiceStartGeneration)) {
                    // A newer attempt or teardown owns the voice flags now;
                    // this completion's only job is to not leak the recorder.
                    if (started) {
                        recorder.cancel();
                    }
                    return;
                }
                voiceStartPending = false;
                if (binding == null) {
                    // View gone mid-start: cancel unless the recorder was
                    // retained for a configuration change; the next view
                    // re-attaches the HUD to the live capture.
                    if (started && !viewModel.isVoiceRetainedForConfigChange()) {
                        recorder.cancel();
                    }
                    return;
                }
                if (!started) {
                    restoreComposerAfterRecording();
                    resetAccessibleVoiceRecordingUi();
                    // The old synchronous failure surfaced the same hint; a
                    // silent return would just flash the recording overlay.
                    showQuickTapHint();
                    return;
                }
                scheduleRecordingTimer();
                if (voiceStopWhenStarted) {
                    finishVoiceRecording(true, false);
                } else if (voiceSendWhenStarted) {
                    finishVoiceRecording(false, true);
                }
            });
        });
        return true;
    }

    private void showQuickTapHint() {
        showComposerSnackbar(VoiceTapFeedbackRules.quickTapHintMessageId());
    }

    /** Anchored feedback for composer-scoped events (voice, attachment open). */
    /** The glossary editor is a dialog, so its error is anchored to the dialog's own content. */
    private void showGlossaryError(@NonNull AlertDialog editor) {
        View content = editor.findViewById(android.R.id.content);
        if (content != null) {
            Snackbar.make(content, R.string.translation_error_generic, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void showComposerSnackbar(int messageId) {
        if (binding == null || !isAdded()) {
            return;
        }
        Snackbar.make(binding.getRoot(), messageId, Snackbar.LENGTH_SHORT)
                .setAnchorView(binding.composerBar.getRoot())
                .show();
    }

    private ExecutorService voiceStartExecutor() {
        return viewModel.voiceStartExecutor();
    }

    private void showVoiceRecordingOverlay() {
        binding.composerBar.attachButton.setVisibility(View.GONE);
        binding.composerBar.messageInput.setVisibility(View.GONE);
        binding.composerBar.recordingOverlay.setVisibility(View.VISIBLE);
        binding.composerBar.recordingDuration.setText(R.string.voice_recording_duration_zero);
        binding.composerBar.recordingCancelLabel.setText(R.string.voice_slide_to_cancel);
        binding.composerBar.recordingCancelLabel.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_secondary_light));
    }

    private void scheduleRecordingTimer() {
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
    }

    private void finishVoiceRecording(boolean cancel, boolean fromAccessibleControl) {
        if (recordingTimerRunnable != null) {
            recordingHandler.removeCallbacks(recordingTimerRunnable);
            recordingTimerRunnable = null;
        }
        restoreComposerAfterRecording();
        if (voiceRecorder == null) {
            resetAccessibleVoiceRecordingUi();
            return;
        }
        if (cancel) {
            voiceRecorder.cancel();
        } else if (VoiceTapFeedbackRules.finishWithoutRecordingIsQuiet(
                voiceRecorder.isRecording())) {
            resetAccessibleVoiceRecordingUi();
            return;
        } else {
            long durationMs = voiceRecorder.getDurationMs();
            File audioFile = voiceRecorder.stop();
            if (durationMs < 1000L || audioFile == null) {
                if (audioFile != null && audioFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    audioFile.delete();
                }
                showComposerSnackbar(R.string.voice_too_short);
            } else {
                Uri voiceUri = FileProvider.getUriForFile(
                        requireContext(),
                        requireContext().getPackageName() + ".fileprovider",
                        audioFile);
                viewModel.sendAttachments(Collections.singletonList(voiceUri), durationMs);
            }
        }
        if (fromAccessibleControl || accessibleVoiceRecordingActive) {
            resetAccessibleVoiceRecordingUi();
        }
    }

    private void resetAccessibleVoiceRecordingUi() {
        accessibleVoiceRecordingActive = false;
        if (binding != null) {
            binding.composerBar.micButton.setAccessibleVoiceRecording(false, null);
        }
    }

    private void announceVoiceRecordingState(int messageId) {
        if (binding == null) {
            return;
        }
        binding.composerBar.micButton.announceForAccessibility(getString(messageId));
    }

    private void restoreComposerAfterRecording() {
        if (binding == null) return;
        binding.composerBar.recordingOverlay.setVisibility(View.GONE);
        binding.composerBar.attachButton.setVisibility(viewModel.isEditing() ? View.GONE : View.VISIBLE);
        binding.composerBar.messageInput.setVisibility(View.VISIBLE);
        binding.composerBar.recordingDot.setAlpha(1f);
    }

    private void observeViewModel() {
        viewModel.getRecallWindowConfig().observe(getViewLifecycleOwner(), config -> {
            if (config != null) recallWindowConfig = config;
        });
        viewModel.getUiState().observe(getViewLifecycleOwner(), next -> {
            if (next != null) {
                state = next;
                render();
                onChatActivity(next);
            }
        });
        viewModel.getEditSession().observe(getViewLifecycleOwner(), ignored -> render());
        viewModel.getStarredIds().observe(getViewLifecycleOwner(), next -> {
            Set<String> previous = starredIds;
            starredIds = next == null ? Set.of() : next;
            if (!previous.equals(starredIds) && adapter != null && !searchOpen) {
                ChatViewModel.UiState current = viewModel.getUiState().getValue();
                if (current != null) submitMessages(current.getMessages());
            }
        });
        viewModel.getPeerBlocked().observe(getViewLifecycleOwner(), next -> {
            peerBlocked = Boolean.TRUE.equals(next);
            renderPeerBlockBanner();
        });
        viewModel.getSearchState().observe(getViewLifecycleOwner(), next -> {
            if (next != null) {
                searchState = next;
                renderSearchResults();
            }
        });
        viewModel.getServerStarting().observe(getViewLifecycleOwner(), starting -> {
            serverStarting = Boolean.TRUE.equals(starting);
            renderSubtitle();
        });
        viewModel.getGroupMembers().observe(getViewLifecycleOwner(), next -> {
            groupMembers = next == null ? List.of() : next;
            renderSubtitle();
            if (binding != null) updateMentionPicker(
                    binding.composerBar.messageInput.getText().toString(),
                    binding.composerBar.messageInput.getSelectionStart());
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
            if (event instanceof ChatActionEvent.PreferenceChanged changed) {
                if (changed.getKind() == ChatActionEvent.PreferenceChanged.Kind.ARCHIVED
                        && leaveChatForArchivedConversation()) {
                    return;
                }
                Snackbar snackbar = Snackbar.make(
                        binding.getRoot(), preferenceMessage(changed.getKind()), Snackbar.LENGTH_LONG);
                if (changed.getUndo() != null) {
                    snackbar.setAction(R.string.action_undo, ignored -> changed.getUndo().run());
                }
                snackbar.show();
            }
        });
        inboxViewModel.getUiState().observe(getViewLifecycleOwner(), next -> {
            if (next != null) {
                if (adapter != null) adapter.setCurrentUserId(next.getCurrentUserId());
                renderUnread(next);
                captureUnreadDividerBaseline(next);
                if (binding != null) updateMentionPicker(
                        binding.composerBar.messageInput.getText().toString(),
                        binding.composerBar.messageInput.getSelectionStart());
            }
        });
        summaryViewModel.state().observe(getViewLifecycleOwner(), this::renderSummaryState);
        settingsViewModel.getChatColor().observe(getViewLifecycleOwner(), next -> {
            if (next != null && adapter != null) adapter.setChatColor(next);
        });
        viewModel.getTranslations().observe(getViewLifecycleOwner(), next -> {
            if (next != null && adapter != null) adapter.setTranslations(next);
        });
        viewModel.loadTranslatePrefs();
    }

    private void observeStreak() {
        streakViewModel = new ViewModelProvider(this).get(StreakViewModel.class);
        StreakMilestoneSheet.bind(this, streakViewModel);
        streakViewModel.getConversationStreak().observe(getViewLifecycleOwner(), streak -> {
            if (binding == null) return;
            binding.chatStreakBadge.setStreak(
                    streak == null ? 0 : streak.getCount(), streak != null && streak.isAtRisk());
        });
    }

    /** Loads the direct-chat streak once the peer is known and refreshes it after new messages. */
    private void onChatActivity(@NonNull ChatViewModel.UiState next) {
        if (binding == null || conversationId.isBlank() || next.getOtherUserId() == null) return;
        if (!streakRequested) {
            streakRequested = true;
            streakViewModel.loadConversation(conversationId, isResumed());
        }
        List<ChatMessage> messages = next.getMessages();
        String signature = messages.size() + ":" + next.getSending().size() + ":"
                + (messages.isEmpty() ? "" : messages.get(messages.size() - 1).getId());
        String previous = lastActivitySignature;
        lastActivitySignature = signature;
        if (previous == null || previous.equals(signature)) return;
        if (streakRefresh != null) binding.getRoot().removeCallbacks(streakRefresh);
        streakRefresh = () -> {
            if (streakViewModel != null) streakViewModel.onChatActivity();
        };
        binding.getRoot().postDelayed(streakRefresh, STREAK_REFRESH_DELAY_MS);
    }

    private void render() {
        if (binding == null) return;
        if (state.isGroup() && !groupMembersRequested) {
            groupMembersRequested = true;
            viewModel.loadGroupMembers();
        }
        String otherUserId = state.getOtherUserId();
        if (otherUserId != null && !otherUserId.equals(lastProbedBlockPeerId)) {
            lastProbedBlockPeerId = otherUserId;
            viewModel.refreshPeerBlocked();
        } else if (otherUserId == null && lastProbedBlockPeerId != null) {
            lastProbedBlockPeerId = null;
        }
        renderPeerBlockBanner();
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
        bindGroupInfoShortcut(state.isGroup());
        renderSubtitle();
        binding.callButton.setVisibility(ChatToolbarPolicy.callAvailable(state.isGroup()) ? View.VISIBLE : View.GONE);
        binding.videoCallButton.setVisibility(ChatToolbarPolicy.callAvailable(state.isGroup()) ? View.VISIBLE : View.GONE);
        applyLargeTypeToolbar();
        renderConversationState();
        binding.chatError.setVisibility(state.getError() == null ? View.GONE : View.VISIBLE);
        if (state.getError() != null) {
            binding.chatErrorMessage.setText(ChatErrorText.messageId(state.getError()));
        }
        ChatViewModel.ChatError chatError = state.getChatError();
        binding.chatErrorRetry.setVisibility(state.canRetryError()
                ? View.VISIBLE : View.GONE);
        binding.chatErrorRetry.setOnClickListener(ignored -> viewModel.retryChatError());
        renderKeysetNotice();
        renderPinned();
        renderReplyEditBanner();
        renderAttachments();
        if (!searchOpen) submitMessages(state.getMessages());
        settingDraft = true;
        ChatEditSession editing = viewModel.currentEditSession();
        String input = editing == null ? state.getInput() : editing.getDraft().getContent();
        if (!String.valueOf(binding.composerBar.messageInput.getText()).contentEquals(input)) binding.composerBar.messageInput.setText(input);
        settingDraft = false;
        MentionDraft visibleDraft = editing == null
                ? viewModel.getMentionDraft() : editing.getDraft();
        if (editing == null && visibleDraft.getContent().equals(input)) composerMentionDraft = visibleDraft;
        applyMentionSpans(binding.composerBar.messageInput, editing == null
                ? composerMentionDraft : editing.getDraft());
        updateMentionPicker(input, binding.composerBar.messageInput.getSelectionStart());
        binding.composerBar.attachButton.setVisibility(editing == null ? View.VISIBLE : View.GONE);
        applyE2eeChrome();
        applyDisappearingChrome();
        boolean hasText = input != null && !input.isBlank();
        updateComposerButtons(hasText || editing != null);
    }

    /**
     * Applies the encrypted-conversation chrome: header lock, encrypted-input
     * hint, and dimmed media buttons (their tap handlers show the unsupported
     * snackbar instead of launching pickers).
     */
    private void applyE2eeChrome() {
        boolean e2ee = state.isE2eeEnabled();
        binding.e2eeLock.setVisibility(e2ee ? View.VISIBLE : View.GONE);
        binding.composerBar.messageInput.setHint(
                e2ee ? R.string.chat_input_hint_encrypted : R.string.chat_input_hint);
        float alpha = e2ee ? 0.38f : 1f;
        binding.composerBar.attachButton.setAlpha(alpha);
        binding.composerBar.micButton.setAlpha(alpha);
    }

    /** Header hourglass chip shown while the conversation timer is on. */
    private void applyDisappearingChrome() {
        boolean on = state.isDisappearingEnabled();
        binding.disappearingChip.setVisibility(on ? View.VISIBLE : View.GONE);
        binding.disappearingChip.setContentDescription(on
                ? getString(R.string.cd_disappearing_messages_on,
                        DisappearingDurations.label(requireContext(),
                                state.getDisappearingSeconds()))
                : null);
    }

    private void applyLargeTypeToolbar() {
        float scale = getResources().getConfiguration().fontScale;
        int widthDp = getResources().getConfiguration().screenWidthDp;
        boolean hideAvatar = scale >= 1.5f || widthDp < 360;
        binding.chatHeaderAvatar.setVisibility(hideAvatar ? View.GONE : View.VISIBLE);
        int titleMarginRes = hideAvatar
                ? R.dimen.space_md
                : R.dimen.chat_toolbar_title_start_with_avatar;
        binding.chatToolbar.setTitleMarginStart(getResources().getDimensionPixelSize(titleMarginRes));
    }

    private void renderConversationState() {
        if (searchOpen) {
            binding.emptyState.setVisibility(View.GONE);
            binding.loadingState.setVisibility(View.GONE);
            binding.errorState.setVisibility(View.GONE);
            return;
        }
        boolean hasMessages = !state.getMessages().isEmpty() || !state.getSending().isEmpty();
        boolean hasError = state.getError() != null;
        binding.emptyState.setVisibility(ChatEmptyPolicy.showEmpty(state.isLoading(), hasError, hasMessages)
                ? View.VISIBLE : View.GONE);
        binding.loadingState.setVisibility(ChatEmptyPolicy.showLoading(state.isLoading(), hasError, hasMessages)
                ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(ChatEmptyPolicy.showError(hasError, hasMessages)
                ? View.VISIBLE : View.GONE);
        if (ChatEmptyPolicy.showEmpty(state.isLoading(), hasError, hasMessages)) {
            binding.emptyState.setMessage(getString(R.string.chat_empty));
            binding.emptyState.clearAction();
        }
        if (ChatEmptyPolicy.showError(hasError, hasMessages)) {
            binding.errorState.setMessage(getString(ChatErrorText.messageId(state.getError())));
            ChatViewModel.ChatError chatError = state.getChatError();
            boolean canRetryInitialHistory = chatError != null
                    && chatError.getOperation() == ChatViewModel.ErrorOperation.INITIAL_HISTORY
                    && state.canRetryError();
            binding.errorState.setOnRetry(canRetryInitialHistory
                    ? () -> viewModel.retryChatError()
                    : null);
        }
    }

    /** In a group, tapping the header name or avatar opens the group details, as in most messengers. */
    private void bindGroupInfoShortcut(boolean group) {
        View.OnClickListener open = group ? v -> openGroupInfo() : null;
        binding.chatToolbar.setOnClickListener(open);
        binding.chatToolbar.setClickable(group);
        // TalkBack says "double-tap to open group info" instead of a bare "double-tap to activate".
        if (group) {
            androidx.core.view.ViewCompat.replaceAccessibilityAction(binding.chatToolbar,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    getString(R.string.cd_group_info), (view, args) -> {
                        openGroupInfo();
                        return true;
                    });
        } else {
            androidx.core.view.ViewCompat.removeAccessibilityAction(binding.chatToolbar,
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.getId());
        }
        if (binding.chatHeaderAvatar != null) {
            binding.chatHeaderAvatar.setOnClickListener(open);
            binding.chatHeaderAvatar.setClickable(group);
        }
    }

    private void openGroupInfo() {
        if (binding == null) return;
        try {
            NavController nav = NavHostFragment.findNavController(this);
            androidx.navigation.NavDestination current = nav.getCurrentDestination();
            if (current == null || current.getId() != R.id.chatFragment) return;
            Bundle args = new Bundle();
            args.putString(ARG_CONVERSATION_ID, conversationId);
            nav.navigate(R.id.groupDetailFragment, args);
        } catch (IllegalStateException ignored) {
            Snackbar.make(binding.getRoot(), R.string.error_generic, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void renderSubtitle() {
        if (binding == null || state == null) return;
        Boolean peerForSubtitle = !state.isGroup() && state.getPeerPresence() != null
                ? Boolean.valueOf(state.getPeerPresence().getOnline()) : null;
        int subtitleRes = ChatSubtitle.textRes(
                state.getPeerTyping(), state.getConnected(), state.isGroup(), peerForSubtitle,
                serverStarting);
        if (subtitleRes != 0) {
            binding.chatToolbar.setSubtitle(getString(subtitleRes));
        } else if (state.isGroup() && !groupMembers.isEmpty()) {
            binding.chatToolbar.setSubtitle(
                    com.nexachat.res.QuantityStrings.memberCount(getResources(), groupMembers.size()));
        } else {
            binding.chatToolbar.setSubtitle("");
        }
    }

    /**
     * The persistent local notice for a paused send path — driven only by the
     * durable keyset verdict carried in render state, never a server SYSTEM
     * message. While it holds, FAILED rows also swap their retry label for
     * the pause reason.
     */
    private void renderKeysetNotice() {
        ChatViewModel.KeysetPause pause = state.getKeysetPause();
        View root = binding.keysetNotice.getRoot();
        if (pause == ChatViewModel.KeysetPause.NONE) {
            root.setVisibility(View.GONE);
        } else {
            boolean changed = pause == ChatViewModel.KeysetPause.CHANGED;
            boolean invalid = pause == ChatViewModel.KeysetPause.INVALID;
            binding.keysetNotice.keysetNoticeText.setText(changed
                    ? R.string.e2ee_keyset_changed_notice
                    : invalid ? R.string.e2ee_keyset_invalid_notice
                    : R.string.e2ee_downgrade_notice);
            // An unusable roster has no trust record to compare against, so
            // the notice only explains the refusal — no action is offered.
            binding.keysetNotice.keysetNoticeAction.setVisibility(
                    invalid ? View.GONE : View.VISIBLE);
            if (!invalid) {
                binding.keysetNotice.keysetNoticeAction.setText(changed
                        ? R.string.e2ee_keyset_notice_action
                        : R.string.e2ee_downgrade_notice_action);
                binding.keysetNotice.keysetNoticeAction.setOnClickListener(
                        v -> openEncryption());
            }
            root.setVisibility(View.VISIBLE);
        }
        if (adapter != null) {
            adapter.setPausedFailReason(switch (pause) {
                case CHANGED -> R.string.e2ee_failed_keyset_changed;
                case INVALID -> R.string.e2ee_failed_keyset_invalid;
                case DOWNGRADE_BLOCKED -> R.string.e2ee_failed_downgrade_blocked;
                default -> 0;
            });
        }
    }

    private void renderPinned() {
        View root = binding.pinnedBanner.getRoot();
        List<ChatMessage> pins = state.getPins();
        if (pins.isEmpty()) { root.setVisibility(View.GONE); return; }
        ChatMessage pin = pins.get(pinIndex % pins.size());
        String pinText = pin.isDecryptFailed() || (pin.isEncrypted() && pin.getContent() == null)
                ? getString(R.string.e2ee_message_placeholder)
                : (pin.getContent() == null
                        ? pin.getType()
                        : SharedContentUi.previewText(requireContext(), pin.getContent()));
        ChatInteractionsViews.bindPinnedBanner(root, pinText, pinIndex % pins.size(), pins.size(),
                () -> { viewModel.jumpToMessage(pin.getId()); pinIndex++; renderPinned(); });
    }

    private void renderReplyEditBanner() {
        View root = binding.replyEditBanner.getRoot();
        ChatEditSession editing = viewModel.currentEditSession();
        if (editing != null) {
            ChatInteractionsViews.bindReplyBanner(root, getString(R.string.action_edit) + ": " + SharedContentUi.previewText(requireContext(), editing.getOriginalContent()), () -> { viewModel.cancelEdit(); mentionQuery = null; render(); });
            root.findViewById(R.id.cancelReplyEdit).setContentDescription(getString(R.string.cd_cancel_edit));
            ((android.widget.ImageView) root.findViewById(R.id.replyIcon)).setImageResource(R.drawable.ic_edit);
            return;
        }
        ChatViewModel.ReplyTarget target = state.getReplyTarget();
        if (target == null) { root.setVisibility(View.GONE); return; }
        String prefix = target.getSenderName() == null ? getString(R.string.action_reply) : target.getSenderName();
        ChatInteractionsViews.bindReplyBanner(root, prefix + ": " + SharedContentUi.previewText(requireContext(), target.getSnippet()), viewModel::clearReplyTarget);
        ((android.widget.ImageView) root.findViewById(R.id.replyIcon)).setImageResource(R.drawable.ic_reply);
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
            String attachmentLabel = item.getDisplayName() != null
                    ? item.getDisplayName()
                    : item.getType();
            if (item.getState() == com.nexachat.feature.media.PendingAttachment.PendingState.FAILED) {
                int reasonRes = item.getError() != null
                        ? ChatErrorIds.messageId(item.getError())
                        : R.string.error_generic;
                label.setText(getString(R.string.a11y_attachment_pending_failed,
                        attachmentLabel, getString(reasonRes)));
            } else {
                label.setText(getString(R.string.a11y_attachment_pending_progress,
                        attachmentLabel, Math.round(item.getProgress() * 100)));
            }
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (item.getState() == com.nexachat.feature.media.PendingAttachment.PendingState.FAILED) {
                // Failed uploads keep the Uri, so a tap can re-run the send.
                TextView retry = new TextView(requireContext());
                retry.setText(R.string.ui_retry_symbol);
                retry.setTextSize(24f);
                retry.setGravity(android.view.Gravity.CENTER);
                retry.setContentDescription(getString(R.string.action_retry));
                retry.setMinWidth(getResources().getDimensionPixelSize(R.dimen.min_touch_target));
                retry.setMinHeight(getResources().getDimensionPixelSize(R.dimen.min_touch_target));
                retry.setOnClickListener(v -> viewModel.retryPendingAttachment(item.getLocalId()));
                row.addView(retry, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            TextView remove = new TextView(requireContext());
            remove.setText(R.string.ui_remove_symbol);
            remove.setTextSize(24f);
            remove.setGravity(android.view.Gravity.CENTER);
            remove.setContentDescription(getString(R.string.action_cancel));
            remove.setMinWidth(getResources().getDimensionPixelSize(R.dimen.min_touch_target));
            remove.setMinHeight(getResources().getDimensionPixelSize(R.dimen.min_touch_target));
            remove.setOnClickListener(v -> viewModel.removePendingAttachment(item.getLocalId()));
            row.addView(remove, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            binding.pendingAttachments.addView(row);
        }
        binding.pendingAttachments.setVisibility(View.VISIBLE);
    }

    private void renderUnread(InboxUiState inbox) {
        ConversationSummary row = inbox.getConversations().stream().filter(c -> conversationId.equals(c.getId())).findFirst().orElse(null);
        int unread = row == null ? 0 : row.getUnreadCount();
        boolean e2ee = ChatMenuPolicy.e2ee(row, state != null && state.isE2eeEnabled());
        if (UnreadBannerRule.shouldShow(unread, null, e2ee)) {
            binding.unreadBanner.setVisibility(View.VISIBLE);
            binding.unreadBanner.bind(unread, this::showSummary,
                    () -> binding.unreadBanner.setVisibility(View.GONE));
        } else {
            binding.unreadBanner.setVisibility(View.GONE);
        }
    }

    private void submitMessages(List<ChatMessage> messages) {
        LinearLayoutManager manager = (LinearLayoutManager) binding.messageList.getLayoutManager();
        int oldCount = adapter.getItemCount();
        boolean wasAtBottom = manager == null || oldCount == 0 || manager.findLastVisibleItemPosition() >= oldCount - 3;
        String anchorId = null;
        int anchorOffset = 0;
        if (manager != null && oldCount > 0) {
            int anchor = manager.findFirstVisibleItemPosition();
            View anchorView = manager.findViewByPosition(anchor);
            if (anchor >= 0 && anchor < oldCount && anchorView != null) {
                anchorId = adapter.getCurrentList().get(anchor).getStableId();
                anchorOffset = manager.getDecoratedTop(anchorView) - binding.messageList.getPaddingTop();
            }
        }
        long effectivePeerReadSeq = state != null && state.isShowSeen() ? state.getPeerReadSeq() : 0L;
        long localReadSeq = resolveUnreadDividerLocalReadSeq();
        int unreadBeforeIndex = -1;
        int unreadCount = 0;
        if (localReadSeq >= 0L) {
            unreadCount = UnreadDividerRule.countUnreadIncoming(messages, localReadSeq);
            unreadBeforeIndex = UnreadDividerRule.indexOfFirstUnread(messages, localReadSeq);
        }
        ConversationSummary inboxRow = currentConversation();
        int inboxUnread = inboxRow == null ? 0 : inboxRow.getUnreadCount();
        boolean firstUnreadVisible = isFirstUnreadVisible(messages, localReadSeq, manager, oldCount);
        boolean showDivider = localReadSeq >= 0L
                && UnreadDividerRule.shouldShowDivider(
                        unreadCount, unreadDividerDismissed, firstUnreadVisible, inboxUnread);
        int dividerIndex = showDivider ? unreadBeforeIndex : -1;
        int dividerCount = showDivider ? unreadCount : 0;
        long nowEpochMs = System.currentTimeMillis();
        if (!rowRenderInputs.requiresRebuild(
                messages,
                state.isGroup(),
                effectivePeerReadSeq,
                state.getPeerDeliveredSeq(),
                pulsingMessageId,
                dividerIndex,
                dividerCount,
                starredIds,
                nowEpochMs)) {
            return;
        }
        List<MessageListItem> rows = MessageListMapper.buildDisplayItems(
                messages,
                state.isGroup(),
                effectivePeerReadSeq,
                state.getPeerDeliveredSeq(),
                pulsingMessageId,
                new DateSeparatorLabels(getString(R.string.time_today), getString(R.string.time_yesterday)),
                nowEpochMs,
                dividerIndex,
                dividerCount,
                starredIds);
        cacheUnreadDividerAdapterPositions(rows, messages, localReadSeq);
        String stableAnchor = anchorId;
        int stableAnchorOffset = anchorOffset;
        boolean dividerShown = showDivider;
        adapter.submitList(rows, () -> {
            if (binding == null || rows.isEmpty()) return;
            // A pending jump owns the scroll position: re-rendering for the
            // pulse highlight must not yank the list back under it.
            if (wasAtBottom && pulsingMessageId == null)
                binding.messageList.scrollToPosition(rows.size() - 1);
            else if (stableAnchor != null && pulsingMessageId == null) {
                for (int i = 0; i < rows.size(); i++) if (stableAnchor.equals(rows.get(i).getStableId())) {
                    manager.scrollToPositionWithOffset(i, stableAnchorOffset);
                    break;
                }
            }
            LinearLayoutManager layoutManager = (LinearLayoutManager) binding.messageList.getLayoutManager();
            if (dividerShown && wasAtBottom) {
                binding.messageList.post(() -> {
                    if (binding == null || unreadDividerDismissed) return;
                    if (isFirstUnreadVisible(messages, localReadSeq, layoutManager, adapter.getItemCount())) {
                        submitMessages(messages);
                        return;
                    }
                    updateUnreadDividerReached(layoutManager);
                });
            } else {
                updateUnreadDividerReached(layoutManager);
            }
        });
    }

    private void captureUnreadDividerBaseline(@NonNull InboxUiState inbox) {
        if (unreadDividerLocalReadSeq != null) {
            return;
        }
        ConversationSummary row = null;
        for (ConversationSummary candidate : inbox.getConversations()) {
            if (conversationId.equals(candidate.getId())) {
                row = candidate;
                break;
            }
        }
        long watermark = computeUnreadDividerWatermark(row);
        if (watermark <= 0L) {
            return;
        }
        unreadDividerLocalReadSeq = watermark;
    }

    private long computeUnreadDividerWatermark(@Nullable ConversationSummary row) {
        long inboxSeq = row != null ? row.getMyLastReadSeq() : viewModel.peekMyLastReadSeq();
        Long snapshot = viewModel.getUnreadDividerReadSnapshot();
        if (snapshot != null) {
            inboxSeq = Math.max(inboxSeq, snapshot);
        }
        long memorySeq = viewModel.peekLocalReadSeq();
        List<ChatMessage> messages = state == null ? List.of() : state.getMessages();
        boolean hasMoreOlder = state != null && state.getHasMore();
        return UnreadDividerRule.resolveReadWatermark(inboxSeq, memorySeq, messages, hasMoreOlder);
    }

    private long resolveUnreadDividerLocalReadSeq() {
        if (unreadDividerLocalReadSeq != null) {
            return unreadDividerLocalReadSeq;
        }
        long watermark = computeUnreadDividerWatermark(currentConversation());
        if (watermark > 0L) {
            unreadDividerLocalReadSeq = watermark;
            return watermark;
        }
        return -1L;
    }

    private boolean isFirstUnreadVisible(
            @NonNull List<ChatMessage> messages,
            long localReadSeq,
            @Nullable LinearLayoutManager manager,
            int previousItemCount
    ) {
        if (manager == null || previousItemCount <= 0 || localReadSeq < 0L || adapter == null) {
            return false;
        }
        int firstVisible = manager.findFirstVisibleItemPosition();
        int lastVisible = manager.findLastVisibleItemPosition();
        if (firstVisible < 0 || lastVisible < 0) {
            return false;
        }
        List<MessageListItem> current = adapter.getCurrentList();
        for (int i = firstVisible; i <= lastVisible && i < current.size(); i++) {
            MessageListItem item = current.get(i);
            if (item.getKind() == MessageListItem.Kind.MESSAGE
                    && UnreadDividerRule.countsAsUnread(item.getMessage(), localReadSeq)) {
                return true;
            }
        }
        return false;
    }

    private void updateUnreadDividerReached(@Nullable LinearLayoutManager manager) {
        if (unreadDividerDismissed || unreadDividerLocalReadSeq == null || manager == null || binding == null) {
            return;
        }
        int dividerPos = cachedUnreadDividerAdapterPos;
        if (dividerPos < 0) {
            return;
        }
        int firstVisible = manager.findFirstVisibleItemPosition();
        int lastVisible = manager.findLastVisibleItemPosition();
        if (UnreadDividerRule.isDividerAdapterPositionVisible(dividerPos, firstVisible, lastVisible)) {
            unreadDividerWasVisible = true;
            return;
        }
        if (UnreadDividerRule.shouldDismissDivider(
                dividerPos, firstVisible, lastVisible, unreadDividerWasVisible)) {
            unreadDividerDismissed = true;
            submitMessages(state.getMessages());
        }
    }

    private void cacheUnreadDividerAdapterPositions(
            @NonNull List<MessageListItem> rows,
            @NonNull List<ChatMessage> messages,
            long localReadSeq
    ) {
        cachedUnreadDividerAdapterPos = -1;
        cachedFirstUnreadAdapterPos = -1;
        for (int i = 0; i < rows.size(); i++) {
            MessageListItem row = rows.get(i);
            if (row.getKind() == MessageListItem.Kind.UNREAD_DIVIDER) {
                cachedUnreadDividerAdapterPos = i;
            } else if (row.getKind() == MessageListItem.Kind.MESSAGE
                    && cachedFirstUnreadAdapterPos < 0
                    && UnreadDividerRule.countsAsUnread(row.getMessage(), localReadSeq)) {
                cachedFirstUnreadAdapterPos = i;
            }
        }
    }

    private void setSearchOpen(boolean open) {
        searchOpen = open;
        binding.chatSearchBar.setVisibility(open ? View.VISIBLE : View.GONE);
        binding.messageList.setVisibility(View.VISIBLE);
        RecyclerView.LayoutManager layoutManager = binding.messageList.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) layoutManager).setStackFromEnd(!open);
        }
        if (open) {
            binding.chatSearchInput.requestFocus();
            renderSearchResults();
            renderConversationState();
        } else {
            binding.chatSearchInput.setText("");
            viewModel.clearSearch();
            clearSearchPresentation();
            submitMessages(state.getMessages());
            renderConversationState();
        }
    }

    private void clearSearchPresentation() {
        binding.chatError.setVisibility(View.GONE);
        binding.loadingState.setLabel(null);
    }

    private void renderSearchResults() {
        if (!searchOpen || binding == null) return;
        List<ChatMessage> results = searchState.getResults();
        String trimmedQuery = searchState.getQuery().trim();
        boolean queryReady = trimmedQuery.length() >= ChatViewModel.MIN_SEARCH_CHARS;
        boolean loading = searchState.isLoading();
        boolean hasError = searchState.getError() != null;
        boolean hasResults = !results.isEmpty();
        boolean showLoading = queryReady && loading && !hasResults;
        boolean showEmpty = queryReady && !loading && !hasError && !hasResults;
        boolean showError = queryReady && hasError && !hasResults;

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        if (showLoading) {
            binding.loadingState.setLabel(getString(R.string.ask_loading));
        } else if (!searchOpen) {
            binding.loadingState.setLabel(null);
        }

        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (showEmpty) {
            binding.emptyState.setMessage(getString(R.string.search_in_chat_no_results));
            binding.emptyState.clearAction();
        }

        binding.errorState.setVisibility(showError ? View.VISIBLE : View.GONE);
        binding.chatError.setVisibility(View.GONE);
        if (showError) {
            binding.errorState.setMessage(getString(R.string.search_in_chat_error));
            String retryQuery = searchState.getQuery();
            binding.errorState.setOnRetry(
                    retryQuery.trim().length() >= ChatViewModel.MIN_SEARCH_CHARS
                            ? () -> viewModel.search(retryQuery)
                            : null);
        }

        List<MessageListItem> rows = new ArrayList<>();
        for (ChatMessage message : results) rows.add(MessageListItem.message(message,
                com.nexachat.core.ui.components.MessageGroupPosition.SINGLE, false, false, false));
        adapter.submitList(rows);
        // The adapter no longer holds the memoed rows; restoring them must not
        // be skipped as a duplicate render.
        rowRenderInputs.invalidate();
        binding.messageList.setVisibility(showLoading || showEmpty || showError ? View.GONE : View.VISIBLE);
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
        ConversationSummary conversation = currentConversation();
        ChatMenuPolicy.Flags flags = ChatMenuPolicy.resolve(
                conversation,
                state != null && state.isGroup(),
                state != null && state.isConversationPinned(),
                state != null && state.isE2eeEnabled(),
                System.currentTimeMillis());
        List<CharSequence> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        if (!flags.e2ee) {
            labels.add(getString(R.string.ask_action));
            actions.add(MENU_ASK);
        }
        if (ChatToolbarPolicy.searchAvailable()) {
            labels.add(getString(R.string.cd_search_in_chat));
            actions.add(MENU_SEARCH);
        }
        if (!flags.e2ee && ChatToolbarPolicy.summaryAvailable()) {
            labels.add(getString(R.string.summary_overflow_action));
            actions.add(MENU_SUMMARY);
        }
        if (!flags.group) {
            labels.add(getString(R.string.e2ee_menu_title));
            actions.add(MENU_ENCRYPTION);
        }
        if (ChatToolbarPolicy.infoAvailable(flags.group)) {
            labels.add(getString(R.string.cd_group_info));
            actions.add(MENU_INFO);
        }
        if (!flags.group || (state != null && state.getCanPin())) {
            labels.add(getString(R.string.disappearing_menu_title));
            actions.add(MENU_DISAPPEARING);
        }
        labels.add(getString(flags.muted ? R.string.unmute : R.string.mute_menu_title));
        actions.add(MENU_MUTE);
        labels.add(getString(flags.archived ? R.string.conversation_unarchive : R.string.conversation_archive));
        actions.add(MENU_ARCHIVE);
        labels.add(getString(ChatMenuPolicy.pinLabelRes(flags)));
        actions.add(MENU_PIN);
        labels.add(getString(R.string.conversation_settings_title));
        actions.add(MENU_SETTINGS);
        labels.add(getString(R.string.label_assign_menu));
        actions.add(MENU_ASSIGN_LABEL);
        labels.add(getString(R.string.archive_title));
        actions.add(MENU_SHARED_MEDIA);
        labels.add(getString(R.string.starred_messages_title));
        actions.add(MENU_STARRED);
        if (flags.group) {
            labels.add(getString(R.string.group_board_title));
            actions.add(MENU_GROUP_BOARD);
        }
        if (!flags.group && state != null && state.getOtherUserId() != null
                && !state.getOtherUserId().isBlank()) {
            labels.add(getString(peerBlocked ? R.string.unblock_user : R.string.block_user));
            actions.add(peerBlocked ? MENU_UNBLOCK_PEER : MENU_BLOCK_PEER);
            labels.add(getString(R.string.action_report));
            actions.add(MENU_REPORT);
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) ->
                        onChatMenuItemSelected(actions.get(which), flags))
                .show();
    }

    private void onChatMenuItemSelected(int action, ChatMenuPolicy.Flags flags) {
        if (action == MENU_ASK) {
            showAsk();
        } else if (action == MENU_SEARCH) {
            setSearchOpen(true);
        } else if (action == MENU_INFO) {
            openGroupInfo();
        } else if (action == MENU_SUMMARY) {
            showSummary();
        } else if (action == MENU_DISAPPEARING) {
            showDisappearingPicker();
        } else if (action == MENU_MUTE) {
            if (flags.muted) {
                viewModel.unmuteConversation();
            } else {
                ConversationPreferenceMenu.showMutePresets(
                        requireContext(), false, preferenceListener());
            }
        } else if (action == MENU_ARCHIVE) {
            viewModel.setConversationArchived(!flags.archived);
        } else if (action == MENU_PIN) {
            viewModel.setConversationPinned(flags.pinTarget());
        } else if (action == MENU_SETTINGS) {
            showConversationSettings();
        } else if (action == MENU_ASSIGN_LABEL && !conversationId.isBlank()) {
            LabelDialogFragment.show(requireActivity(), conversationId);
        } else if (action == MENU_SHARED_MEDIA) {
            openArchive();
        } else if (action == MENU_ENCRYPTION) {
            openEncryption();
        } else if (action == MENU_GROUP_BOARD) {
            openGroupBoard();
        } else if (action == MENU_STARRED) {
            openStarred();
        } else if (action == MENU_BLOCK_PEER) {
            confirmBlockPeer();
        } else if (action == MENU_UNBLOCK_PEER) {
            viewModel.unblockPeer();
        } else if (action == MENU_REPORT) {
            openPeerReport();
        }
    }

    /**
     * Archiving removes the conversation from the inbox, so staying in the chat would be a
     * dead end. The confirmation is anchored to the activity view so it survives the pop,
     * and its undo runs through the activity-scoped inbox store because this screen's view
     * models are destroyed along with it.
     */
    private boolean leaveChatForArchivedConversation() {
        if (!isAdded()) {
            return false;
        }
        View hostView = requireActivity().findViewById(android.R.id.content);
        InboxViewModel sharedInboxViewModel =
                new ViewModelProvider(requireActivity()).get(InboxViewModel.class);
        String archivedId = conversationId;
        boolean popped;
        try {
            popped = NavHostFragment.findNavController(this).popBackStack();
        } catch (IllegalStateException noNav) {
            popped = false;
        }
        if (!popped) {
            return false;
        }
        Snackbar.make(
                hostView, R.string.conversation_archived_snackbar, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo,
                        ignored -> sharedInboxViewModel.setConversationArchived(archivedId, false))
                .show();
        return true;
    }

    /** Single-choice timer picker; the server allow-list is the row order. */
    private void showDisappearingPicker() {
        int current = state == null ? 0 : state.getDisappearingSeconds();
        if (state != null && state.isGroup() && !state.getCanPin()) {
            // Group members who are not admins see the timer read-only; only
            // admins may change it (the server enforces the same rule).
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.disappearing_menu_title)
                    .setMessage(DisappearingDurations.label(requireContext(), current))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        int[] options = DisappearingDurations.OPTIONS_SECONDS;
        CharSequence[] labels = new CharSequence[options.length];
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = DisappearingDurations.label(requireContext(), options[i]);
            if (options[i] == current) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.disappearing_menu_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    viewModel.setDisappearing(options[which]);
                })
                .show();
    }

    private ConversationPreferenceMenu.Listener preferenceListener() {
        return new ConversationPreferenceMenu.Listener() {
            @Override
            public void onMute(@Nullable java.time.Instant until) {
                viewModel.muteConversation(until);
            }

            @Override
            public void onUnmute() {
                viewModel.unmuteConversation();
            }

            @Override
            public void onArchive(boolean archive) {
                viewModel.setConversationArchived(archive);
            }
        };
    }

    @Nullable
    private ConversationSummary currentConversation() {
        InboxUiState inbox = inboxViewModel == null ? null : inboxViewModel.getUiState().getValue();
        if (inbox == null) return null;
        for (ConversationSummary row : inbox.getConversations()) {
            if (conversationId.equals(row.getId())) return row;
        }
        return null;
    }

    private void openEncryption() {
        if (conversationId.isBlank()) {
            return;
        }
        Bundle args = new Bundle();
        args.putString(ARG_CONVERSATION_ID, conversationId);
        args.putString("peerName", state != null ? state.getPeerName() : null);
        try {
            NavHostFragment.findNavController(this).navigate(R.id.encryptionFragment, args);
        } catch (IllegalStateException ignored) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.error_generic, Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    private void showConversationSettings() {
        View content = getLayoutInflater().inflate(R.layout.dialog_conversation_settings, null);
        com.google.android.material.materialswitch.MaterialSwitch toggle =
                content.findViewById(R.id.aiOptOutSwitch);
        TextView status = content.findViewById(R.id.aiOptOutStatus);
        View row = content.findViewById(R.id.aiOptOutRow);

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.conversation_settings_title)
                        .setView(content)
                        .setNegativeButton(R.string.action_close, null)
                        .create();

        androidx.lifecycle.Observer<ChatViewModel.AiOptOutState> observer = state -> {
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(state.getOptOut());
            toggle.setEnabled(!state.isBusy());
            status.setVisibility(state.getError() == null ? View.GONE : View.VISIBLE);
            if (state.getError() != null) {
                status.setText(ChatErrorText.messageId(state.getError()));
            }
            toggle.setOnCheckedChangeListener((button, checked) -> {
                viewModel.setAiOptOut(checked);
            });
        };
        viewModel.getAiOptOutState().observe(getViewLifecycleOwner(), observer);
        dialog.setOnDismissListener(ignored ->
                viewModel.getAiOptOutState().removeObserver(observer));
        row.setOnClickListener(v -> {
            if (toggle.isEnabled()) viewModel.setAiOptOut(!toggle.isChecked());
        });
        bindTranslateSettings(content);
        dialog.show();
        viewModel.loadAiOptOut();
    }

    private void bindTranslateSettings(@NonNull View content) {
        com.google.android.material.materialswitch.MaterialSwitch autoSwitch =
                content.findViewById(R.id.autoTranslateSwitch);
        TextView translateStatus = content.findViewById(R.id.translateStatus);
        TextView langValue = content.findViewById(R.id.translateLangValue);
        View autoRow = content.findViewById(R.id.autoTranslateRow);
        View langRow = content.findViewById(R.id.translateLangRow);
        View glossaryRow = content.findViewById(R.id.glossaryRow);

        androidx.lifecycle.Observer<ChatViewModel.TranslatePrefsState> observer = state -> {
            autoSwitch.setOnCheckedChangeListener(null);
            autoSwitch.setChecked(state.getAutoTranslate());
            autoSwitch.setEnabled(!state.isBusy());
            langRow.setEnabled(!state.isBusy());
            String langName;
            if (state.getTargetLang() != null) {
                int res = TranslationLanguages.nameRes(state.getTargetLang());
                langName = res == 0 ? state.getTargetLang() : getString(res);
            } else {
                int res = TranslationLanguages.nameRes(viewModel.effectiveTargetLang());
                langName = getString(R.string.translate_target_default,
                        res == 0 ? viewModel.effectiveTargetLang() : getString(res));
            }
            langValue.setText(langName);
            translateStatus.setVisibility(state.getError() == null ? View.GONE : View.VISIBLE);
            if (state.getError() != null) {
                translateStatus.setText(ChatErrorText.messageId(state.getError()));
            }
            autoSwitch.setOnCheckedChangeListener((button, checked) -> {
                viewModel.setAutoTranslate(checked);
            });
        };
        viewModel.getTranslatePrefsState().observe(getViewLifecycleOwner(), observer);
        autoRow.setOnClickListener(v -> {
            if (autoSwitch.isEnabled()) viewModel.setAutoTranslate(!autoSwitch.isChecked());
        });
        langRow.setOnClickListener(v -> showTranslateLanguagePicker());
        glossaryRow.setOnClickListener(v -> showGlossaryEditor());
    }

    /** Searchable target-language picker backed by the supported catalog. */
    private void showTranslateLanguagePicker() {
        View content = getLayoutInflater().inflate(R.layout.dialog_language_picker, null);
        android.widget.EditText search = content.findViewById(R.id.languageSearchInput);
        android.widget.ListView list = content.findViewById(R.id.languageList);
        java.util.List<LanguageRow> rows = new java.util.ArrayList<>();
        rows.add(new LanguageRow(null, getString(R.string.translate_target_default_plain)));
        for (TranslationLanguages.Entry entry : TranslationLanguages.SUPPORTED) {
            rows.add(new LanguageRow(entry.code(), getString(entry.nameRes())));
        }
        android.widget.ArrayAdapter<LanguageRow> items = new android.widget.ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_1, rows);
        list.setAdapter(items);
        AlertDialog picker = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.translate_target_language_title)
                .setView(content)
                .setNegativeButton(R.string.action_close, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            LanguageRow row = items.getItem(position);
            if (row != null) {
                viewModel.setTranslateTargetLang(row.code());
            }
            picker.dismiss();
        });
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                items.getFilter().filter(s);
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        picker.show();
    }

    /**
     * Picker row that carries the language code, so a tap resolves by code instead of by the
     * localized display name — two entries sharing a name in one locale would otherwise both
     * map to the first entry's code. {@code code} is null for the "device default" row.
     */
    private record LanguageRow(@Nullable String code, @NonNull String label) {
        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    /** Glossary editor: add/remove term rows with client-side validation. */
    private void showGlossaryEditor() {
        View content = getLayoutInflater().inflate(R.layout.dialog_translation_glossary, null);
        android.widget.LinearLayout termList = content.findViewById(R.id.glossaryTermList);
        View empty = content.findViewById(R.id.glossaryEmpty);
        View addButton = content.findViewById(R.id.glossaryAddButton);
        java.util.List<View> rows = new java.util.ArrayList<>();

        Runnable refreshEmpty = () -> empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        java.util.function.Consumer<com.nexachat.core.network.dto.TranslationGlossaryDto.Term>
                addRow = term -> {
            View rowView = getLayoutInflater().inflate(R.layout.item_glossary_term, termList, false);
            android.widget.EditText termInput = rowView.findViewById(R.id.glossaryTermInput);
            android.widget.EditText replacementInput = rowView.findViewById(R.id.glossaryReplacementInput);
            if (term != null) {
                if (term.getTerm() != null) termInput.setText(term.getTerm());
                if (term.getReplacement() != null) replacementInput.setText(term.getReplacement());
            }
            rowView.findViewById(R.id.glossaryRemoveButton).setOnClickListener(v -> {
                termList.removeView(rowView);
                rows.remove(rowView);
                refreshEmpty.run();
            });
            termList.addView(rowView);
            rows.add(rowView);
            refreshEmpty.run();
        };
        addButton.setOnClickListener(v -> addRow.accept(null));

        AlertDialog editor = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.translate_glossary_title)
                .setView(content)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(R.string.action_close, null)
                .create();
        editor.setOnShowListener(ignored -> editor.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    java.util.List<com.nexachat.core.network.dto.TranslationGlossaryDto.Term> terms =
                            new java.util.ArrayList<>();
                    for (View rowView : rows) {
                        String term = ((android.widget.EditText)
                                rowView.findViewById(R.id.glossaryTermInput)).getText().toString().trim();
                        String replacement = ((android.widget.EditText)
                                rowView.findViewById(R.id.glossaryReplacementInput)).getText().toString().trim();
                        if (term.isEmpty()) continue;
                        terms.add(new com.nexachat.core.network.dto.TranslationGlossaryDto.Term(
                                term, replacement.isEmpty() ? null : replacement));
                    }
                    com.nexachat.core.network.dto.TranslationGlossaryDto.Term[] array =
                            terms.toArray(new com.nexachat.core.network.dto.TranslationGlossaryDto.Term[0]);
                    subscriptions.add(viewModel.saveGlossary(java.util.Arrays.asList(array))
                            .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                            .subscribe(result -> {
                                if (result instanceof com.nexachat.core.common.Result.Success<?>) {
                                    editor.dismiss();
                                } else {
                                    showGlossaryError(editor);
                                }
                            }, error -> showGlossaryError(editor)));
                }));
        editor.show();
        subscriptions.add(viewModel.loadGlossary()
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof com.nexachat.core.common.Result.Success<?> success) {
                        com.nexachat.core.network.dto.TranslationGlossaryDto dto =
                                (com.nexachat.core.network.dto.TranslationGlossaryDto) success.getValue();
                        if (dto.getTerms() != null) {
                            for (com.nexachat.core.network.dto.TranslationGlossaryDto.Term term
                                    : dto.getTerms()) {
                                addRow.accept(term);
                            }
                        }
                    }
                    refreshEmpty.run();
                }, ignored -> refreshEmpty.run()));
    }

    private static int preferenceMessage(@NonNull ChatActionEvent.PreferenceChanged.Kind kind) {
        switch (kind) {
            case MUTED:
                return R.string.conversation_muted;
            case UNMUTED:
                return R.string.conversation_unmuted;
            case ARCHIVED:
                return R.string.conversation_archived;
            case UNARCHIVED:
            default:
                return R.string.conversation_unarchived;
        }
    }

    private void openStarred() {
        try {
            NavHostFragment.findNavController(this)
                    .navigate(R.id.starredMessagesFragment);
        } catch (RuntimeException ignored) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.error_generic, Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    /** Blocking is reversible, so a single confirmation with an undo snackbar is enough. */
    private void confirmBlockPeer() {
        if (state == null || state.getOtherUserId() == null) {
            return;
        }
        String name = state.getPeerName() == null ? "" : state.getPeerName();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.block_user_dialog_title, name))
                .setMessage(R.string.block_user_dialog_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.block_user, (dialog, which) -> {
                    viewModel.blockPeer();
                    if (binding != null) {
                        Snackbar.make(binding.getRoot(), R.string.user_blocked_snackbar,
                                        Snackbar.LENGTH_LONG)
                                .setAction(R.string.action_undo,
                                        ignored -> viewModel.unblockPeer())
                                .show();
                    }
                })
                .show();
    }

    private void openPeerReport() {
        if (state == null || state.getOtherUserId() == null
                || getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(ReportSheetFragment.TAG) != null) {
            return;
        }
        ReportSheetFragment.forUser(state.getOtherUserId())
                .show(getChildFragmentManager(), ReportSheetFragment.TAG);
    }

    /**
     * Blocked peers cannot be messaged, so the composer swaps for a banner that
     * explains the state and offers inline unblock. Re-probed on resume because
     * the blocked-users screen may have changed the edge.
     */
    private void renderPeerBlockBanner() {
        if (binding == null) {
            return;
        }
        boolean blocked = peerBlocked && state != null && !state.isGroup();
        binding.composerBar.getRoot().setVisibility(blocked ? View.GONE : View.VISIBLE);
        binding.blockedPeerBanner.setVisibility(blocked ? View.VISIBLE : View.GONE);
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
        java.util.Map<String, String> senderMap = new java.util.HashMap<>();
        java.util.Map<String, Long> timeMap = new java.util.HashMap<>();
        for (ChatMessage m : state.getMessages()) {
            if (m.getId() != null && m.getSeq() != null) {
                seqMap.put(m.getId(), m.getSeq());
                if (m.getSenderDisplayName() != null) {
                    senderMap.put(m.getId(), m.getSenderDisplayName());
                }
                timeMap.put(m.getId(), m.getCreatedAtEpochMs());
            }
        }
        sheet.putCitationArgs(seqMap, senderMap, timeMap);
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
        try { NavHostFragment.findNavController(this).navigate(R.id.chatFragment, args); } catch (IllegalStateException ignored) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.error_generic, Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    @Nullable private ChatMessage resolveOriginal(String id) {
        // `state` only receives LiveData emissions once the view lifecycle is
        // STARTED; a child sheet restored with this fragment resolves before
        // that, so the retained ViewModel value must lead.
        ChatViewModel.UiState current = viewModel == null ? null : viewModel.getUiState().getValue();
        return ChatBubbleRules.findActionMessage(
                current == null ? null : current.getMessages(), state.getMessages(), id);
    }

    @Override public void onMessageTap(@NonNull String messageId) { if (searchOpen) { setSearchOpen(false); viewModel.jumpToMessage(messageId); } }
    @Override public void onMessageLongPress(@NonNull String messageId) {
        ChatMessage message = resolveOriginal(messageId); if (message == null) return;
        boolean groupAdmin = state.isGroup() && state.getCanPin();
        RecallWindowRules.DeleteAction deleteAction = RecallWindowRules.deleteAction(
                ChatBubbleRules.isMessageReplyable(message.getStatus()),
                message.isDeleted(),
                message.getMine(),
                groupAdmin,
                recallWindowConfig.mayGroupAdminRecallAfterWindow(),
                message.getCreatedAtEpochMs(),
                System.currentTimeMillis(),
                recallWindowConfig.getRecallWindowSeconds());
        if (getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(MessageActionSheetFragment.TAG) != null) {
            return;
        }
        MessageActionSheetFragment.newInstance(messageId, state.getCanPin(),
                        state.isPinned(messageId), starredIds.contains(messageId), deleteAction,
                        viewModel.isTranslated(messageId))
                .show(getChildFragmentManager(), MessageActionSheetFragment.TAG);
    }
    @Override public ChatMessage findActionMessage(@NonNull String messageId) { return resolveOriginal(messageId); }
    @Override public void reply(@NonNull ChatMessage value) { viewModel.setReplyTarget(value); }
    @Override public void edit(@NonNull ChatMessage value) { showEdit(value); }
    @Override public void delete(@NonNull ChatMessage value,
            @NonNull RecallWindowRules.DeleteAction action) {
        if (action == RecallWindowRules.DeleteAction.DELETE_FOR_ME) {
            viewModel.deleteMessageForMe(value.getId());
        } else {
            viewModel.deleteMessage(value.getId());
        }
    }
    @Override public void react(@NonNull ChatMessage value, @NonNull String emoji) { viewModel.toggleReaction(value, emoji); }
    @Override public void pin(@NonNull ChatMessage value) { viewModel.togglePin(value); }
    @Override public void star(@NonNull ChatMessage value) { viewModel.toggleStar(value); }
    @Override public void report(@NonNull ChatMessage value) {
        if (getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(ReportSheetFragment.TAG) != null) {
            return;
        }
        String snapshot = value.isEncrypted() ? value.getContent() : null;
        ReportSheetFragment.forMessage(value.getId(), snapshot)
                .show(getChildFragmentManager(), ReportSheetFragment.TAG);
    }
    @Override public void onReportSent() {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), R.string.report_sent_snackbar,
                    Snackbar.LENGTH_SHORT).show();
        }
    }
    @Override public void forward(@NonNull ChatMessage value) { showForward(value); }
    @Override public void translate(@NonNull ChatMessage value) { viewModel.translateMessage(value); }
    @Override public void showOriginal(@NonNull ChatMessage value) { viewModel.showOriginal(value); }
    @Override public void onRetryTap(@NonNull String messageId) { viewModel.retryFailed(messageId); }
    @Override public void onReplyQuoteTap(@NonNull String replyToId) { viewModel.jumpToMessage(replyToId); }
    @Override public void onOpenMedia(@NonNull String messageId, @NonNull AttachmentDto attachment) {
        if (attachment.getId() == null) return;
        Bundle args = new Bundle(); args.putString("attachmentId", attachment.getId()); args.putString("name", attachment.getOriginalName()); args.putString("mime", attachment.getMimeType());
        for (ChatMessage message : state.getMessages()) {
            if (messageId.equals(message.getId())) {
                args.putString("sender", message.getSenderDisplayName());
                args.putLong("sentAt", message.getCreatedAtEpochMs());
                break;
            }
        }
        try { NavHostFragment.findNavController(this).navigate(R.id.mediaViewerFragment, args); } catch (RuntimeException ignored) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.error_generic, Snackbar.LENGTH_SHORT).show();
            }
        }
    }
    @Override public void onOpenFile(@NonNull String messageId, @NonNull AttachmentDto attachment) {
        subscriptions.add(
                viewModel.attachmentFile(attachment)
                        .subscribe(
                                file -> {
                                    if (!isAdded() || binding == null) return;
                                    String mime = attachment.getMimeType() == null
                                            ? "application/octet-stream"
                                            : attachment.getMimeType();
                                    com.nexachat.feature.media.AttachmentViewBinder.openExternally(
                                            binding.getRoot(), file, mime);
                                },
                                error -> {
                                    if (isAdded()) {
                                        showComposerSnackbar(R.string.error_attachment_open);
                                    }
                                },
                                () -> {
                                    if (isAdded()) {
                                        showComposerSnackbar(R.string.error_attachment_open);
                                    }
                                }));
    }
    @Override public void onReactionTap(@NonNull String messageId, @NonNull String emoji) { ChatMessage message = resolveOriginal(messageId); if (message != null) viewModel.toggleReaction(message, emoji); }

    private void showEdit(ChatMessage message) {
        viewModel.beginEdit(message);
        mentionQuery = null;
        render();
        binding.composerBar.messageInput.requestFocus();
    }

    private void showForward(ChatMessage message) {
        if (getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(ForwardPickerSheet.TAG) != null) {
            return;
        }
        ForwardPickerSheet.newInstance(message.getId())
                .show(getChildFragmentManager(), ForwardPickerSheet.TAG);
    }

    @NonNull
    @Override public List<ConversationSummary> forwardTargets() {
        InboxUiState inbox = inboxViewModel.getUiState().getValue();
        return inbox == null ? Collections.emptyList() : inbox.getConversations();
    }
    @Override public void onForwardTarget(@NonNull String messageId, @NonNull String targetId) {
        viewModel.forwardMessage(messageId, targetId);
    }
    @Override public void onPickVisual() {
        visualPicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                .build());
    }
    @Override public void onPickDocument() {
        documentPicker.launch(com.nexachat.data.AttachmentRepository.UPLOAD_ALLOWED_MIMES);
    }
    @Override public void onPickLocation() {
        if (ensureLocationPermission()) {
            beginLocationFetch();
        }
    }
    @Override public void onPickContact() {
        // ACTION_PICK on the phone row needs no contacts permission; the
        // returned grant covers only the row the user selected.
        try {
            contactPicker.launch(new Intent(
                    Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI));
        } catch (android.content.ActivityNotFoundException ignored) {
            if (binding != null) {
                Snackbar.make(binding.getRoot(), R.string.contact_pick_error,
                        Snackbar.LENGTH_LONG).show();
            }
        }
    }
    @Override public void onShareLocationConfirmed(
            double latitude, double longitude, @Nullable String label) {
        viewModel.sendSharedContent(new SharedContent.Location(latitude, longitude, label));
    }
    @Override public void onShareContactConfirmed(@NonNull String name, @NonNull String phone) {
        viewModel.sendSharedContent(new SharedContent.Contact(name, phone));
    }
    @Override public void onOpenMap(@NonNull SharedContent.Location location) {
        if (isAdded()) {
            SharedContentUi.openMap(requireContext(), location);
        }
    }
    @Override public void onAddContact(@NonNull SharedContent.Contact contact) {
        if (isAdded() && !SharedContentUi.addContact(requireContext(), contact) && binding != null) {
            Snackbar.make(binding.getRoot(), R.string.contact_add_error,
                    Snackbar.LENGTH_LONG).show();
        }
    }
    @Override public void onMessageContact(@NonNull SharedContent.Contact contact) {
        if (!isAdded()) {
            return;
        }
        subscriptions.add(contactsRepository.openDirectConversation(null, contact.getPhone())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (result instanceof Result.Success<?> success) {
                        Bundle args = new Bundle();
                        args.putString("conversationId", (String) success.getValue());
                        try {
                            NavHostFragment.findNavController(this)
                                    .navigate(R.id.chatFragment, args);
                        } catch (RuntimeException ignored) {
                            // Nav graph unavailable in this host; stay put.
                        }
                    } else {
                        showComposerSnackbar(R.string.error_generic);
                    }
                }, error -> {
                    if (isAdded()) {
                        showComposerSnackbar(R.string.error_generic);
                    }
                }));
    }

    @Override public void onResume() {
        super.onResume();
        if (!conversationId.isBlank()) {
            foregroundConversationTracker.onConversationResumed(conversationId);
        }
        if (streakRequested && streakViewModel != null) {
            streakViewModel.loadConversation(conversationId, true);
        }
        viewModel.refreshKeysetNotice();
        viewModel.refreshPeerBlocked();
    }

    @Override public void onPause() {
        foregroundConversationTracker.onConversationPaused(conversationId);
        if (streakViewModel != null) streakViewModel.onScreenHidden();
        super.onPause();
    }

    @Override public void onDestroyView() {
        foregroundConversationTracker.onConversationPaused(conversationId);
        if (streakRefresh != null && binding != null) binding.getRoot().removeCallbacks(streakRefresh);
        streakRefresh = null;
        streakRequested = false;
        lastActivitySignature = null;
        subscriptions.clear();
        if (recordingTimerRunnable != null) {
            recordingHandler.removeCallbacks(recordingTimerRunnable);
        }
        if (voiceResumePoll != null) {
            recordingHandler.removeCallbacks(voiceResumePoll);
            voiceResumePoll = null;
        }
        boolean configChange = requireActivity().isChangingConfigurations();
        if (voiceRecorder != null || viewModel.getVoiceRecorder() != null) {
            if (configChange) {
                // View is being recreated (rotation, font, theme); the recorder
                // and start executor live on the ViewModel and keep running.
                // Only a capture that is running or starting needs its HUD back;
                // an idle recorder just stays parked on the ViewModel.
                if (voiceStartPending || viewModel.isVoiceRecordingActive()) {
                    viewModel.setVoiceRetainedForConfigChange(true);
                }
            } else {
                viewModel.releaseVoiceRecorder();
            }
            voiceRecorder = null;
        }
        if (!configChange) {
            // Mark in-flight start completions stale so they cannot reattach
            // UI or leak state into a future view of this fragment.
            voiceStartGeneration++;
        }
        voiceStartPending = false;
        voiceStopWhenStarted = false;
        voiceSendWhenStarted = false;
        if (binding != null) {
            binding.messageList.setAdapter(null);
        }
        adapter = null;
        binding = null;
        unreadDividerLocalReadSeq = null;
        unreadDividerDismissed = false;
        unreadDividerWasVisible = false;
        cachedUnreadDividerAdapterPos = -1;
        cachedFirstUnreadAdapterPos = -1;
        super.onDestroyView();
    }

    /** Role rules for the chat toolbar. Call stays on the bar; search, info, and summary stay in overflow. */
    static final class ChatToolbarPolicy {
        private ChatToolbarPolicy() {
        }

        static boolean callAvailable(boolean group) {
            return !group;
        }

        static boolean infoAvailable(boolean group) {
            return group;
        }

        static boolean searchAvailable() {
            return true;
        }

        static boolean summaryAvailable() {
            return true;
        }

        /** Title start inset in dp; matches {@code fragment_chat.xml} {@code titleMarginStart}. */
        static int titleMarginStartDp(boolean hideAvatar) {
            return hideAvatar ? 16 : 48;
        }

        static int avatarOccupiedWidthEndDp() {
            return 48 + 36;
        }
    }

    /**
     * Flag resolution for the overflow menu. The Room row hydrates before the
     * chat UiState converges, so its values win whenever the row is present;
     * the state flags are only the fallback while the row is absent.
     */
    static final class ChatMenuPolicy {
        private ChatMenuPolicy() {
        }

        /** The flags the menu was built from; every label and action reads this one instance. */
        static final class Flags {
            final boolean group;
            final boolean muted;
            final boolean archived;
            final boolean pinned;
            final boolean e2ee;

            private Flags(
                    boolean group,
                    boolean muted,
                    boolean archived,
                    boolean pinned,
                    boolean e2ee) {
                this.group = group;
                this.muted = muted;
                this.archived = archived;
                this.pinned = pinned;
                this.e2ee = e2ee;
            }

            /** Pin action target — the inverse of the pin state the label showed. */
            boolean pinTarget() {
                return !pinned;
            }
        }

        static Flags resolve(
                @Nullable ConversationSummary conversation,
                boolean stateGroup,
                boolean statePinned,
                boolean stateE2ee,
                long nowEpochMs) {
            if (conversation != null) {
                return new Flags(
                        conversation.isGroup(),
                        conversation.isMutedAt(nowEpochMs),
                        conversation.isArchived(),
                        conversation.isPinned(),
                        e2ee(conversation, stateE2ee));
            }
            return new Flags(stateGroup, false, false, statePinned, stateE2ee);
        }

        /** The row wins over the chat state; the overflow menu and the unread banner share this. */
        static boolean e2ee(@Nullable ConversationSummary conversation, boolean stateE2ee) {
            return conversation != null ? conversation.isE2eeEnabled() : stateE2ee;
        }

        /** Label for the pin row; pairs with {@link Flags#pinTarget()} off the same flag. */
        static int pinLabelRes(Flags flags) {
            return flags.pinned
                    ? R.string.action_unpin_conversation
                    : R.string.action_pin_conversation;
        }
    }

    /** Empty, loading, and error are mutually exclusive and never cover cached messages. */
    static final class ChatEmptyPolicy {
        private ChatEmptyPolicy() {
        }

        static boolean showEmpty(boolean loading, boolean hasError, boolean hasMessages) {
            return !loading && !hasError && !hasMessages;
        }

        static boolean showLoading(boolean loading, boolean hasError, boolean hasMessages) {
            return loading && !hasError && !hasMessages;
        }

        static boolean showError(boolean hasError, boolean hasMessages) {
            return hasError && !hasMessages;
        }
    }
}
