package com.nexachat.feature.inbox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.ui.SnackbarDurations;
import com.nexachat.data.ConversationSummary;
import com.nexachat.databinding.FragmentInboxBinding;
import com.nexachat.feature.chat.ChatErrorIds;
import com.nexachat.feature.inbox.adapter.ConversationListAdapter;
import com.nexachat.feature.label.ConversationLabel;
import com.nexachat.feature.label.LabelDialogFragment;
import com.nexachat.feature.label.LabelText;
import com.nexachat.feature.label.LabelUiState;
import com.nexachat.feature.label.LabelViewModel;
import com.nexachat.feature.profile.ProfileViewModel;
import com.nexachat.feature.streak.StreakCheckInCoordinator;
import com.nexachat.feature.streak.StreakMilestoneSheet;
import com.nexachat.feature.streak.StreakViewModel;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Inbox conversation list backed by {@link InboxViewModel}. */
@AndroidEntryPoint
public final class InboxFragment extends Fragment {

    private static final int LOAD_MORE_REMAINING_ITEMS = 5;
    private static final int MENU_FEED = 1;
    private static final int MENU_NEW_GROUP = 2;
    private static final int MENU_STARRED = 3;
    private static final String ALL_LABEL_FILTER = "__all_labels__";

    private FragmentInboxBinding binding;
    private InboxViewModel viewModel;
    private LabelViewModel labelViewModel;
    private ProfileViewModel profileViewModel;
    private StreakViewModel streakViewModel;
    @Inject StreakCheckInCoordinator streakCheckIn;
    private ConversationListAdapter adapter;
    private LinearLayoutManager layoutManager;
    @Nullable private RecyclerView.OnScrollListener paginationScrollListener;
    @Nullable private InboxUiState lastState;
    @Nullable private LabelUiState lastLabelState;
    @NonNull private Map<String, String> lastComposerDrafts = Map.of();
    private List<ConversationLabel> renderedLabels = List.of();
    private boolean labelFiltersInitialized;
    @Nullable private String boundUserId;
    @Nullable private UserDto boundInboxUser;
    @Nullable private String identityLoadAttemptKey;
    @Nullable private ActiveNowStripBinder activeNowStripBinder;
    private int lastPaginationErrorMessageId;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentInboxBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(InboxViewModel.class);
        labelViewModel = new ViewModelProvider(requireActivity()).get(LabelViewModel.class);
        profileViewModel = new ViewModelProvider(requireActivity()).get(ProfileViewModel.class);
        labelViewModel.load();

        binding.inboxUserAvatar.setContentDescription(getString(R.string.profile_title));
        binding.inboxUserAvatar.setOnlineStatus(null);
        binding.inboxUserAvatar.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.profileFragment));
        profileViewModel.getUiState().observe(getViewLifecycleOwner(), this::renderInboxIdentity);
        ensureSessionIdentity();
        binding.inboxContactsButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.contactsFragment));
        binding.inboxPublicButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.publicFeedFragment));
        streakViewModel = new ViewModelProvider(this).get(StreakViewModel.class);
        StreakMilestoneSheet.bind(this, streakViewModel);
        streakCheckIn.onAppForeground();
        streakViewModel.loadMine();
        binding.moreButton.setOnClickListener(this::showInboxMenu);
        layoutManager = new LinearLayoutManager(requireContext());
        binding.conversationList.setLayoutManager(layoutManager);
        // Label-free scroll surfaces: rows, chips and the active-now strip's
        // avatars are the focus targets, so the containers' constructor-set
        // focusability only adds unlabeled stops for TalkBack and findings for
        // the a11y sweep's flat dump (#254 — conversationList,
        // inboxFilterScroll, activeNowScroll). Scroll semantics stay intact.
        com.nexachat.core.ui.components.A11yContainerRules.suppressFocusTarget(
                binding.conversationList);
        com.nexachat.core.ui.components.A11yContainerRules.suppressFocusTarget(
                binding.inboxFilterScroll);
        com.nexachat.core.ui.components.A11yContainerRules.suppressFocusTarget(
                binding.activeNowScroll);
        attachSwipeGestureHelper();
        paginationScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                maybeLoadMore();
            }
        };
        binding.conversationList.addOnScrollListener(paginationScrollListener);
        binding.swipeRefresh.setOnRefreshListener(viewModel::refresh);
        binding.inboxSearchPill.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));

        binding.activeNowViewAll.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.contactsFragment));
        updateActiveNowHeaderLayout();
        activeNowStripBinder = new ActiveNowStripBinder(
                binding.activeNowStrip,
                LayoutInflater.from(requireContext()),
                requireContext());

        applyFilterChipRowLayout();
        expandAppBarForHeaderFocus();
        expandAppBarForKeyboardTraversal();
        binding.inboxFilterChips.check(R.id.filterAllChip);
        updateFilterChipsUi(0);
        binding.inboxFilterChips.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    if (checkedIds.isEmpty()) {
                        return;
                    }
                    binding.appBar.setExpanded(true);
                    viewModel.selectFilter(filterForChip(checkedIds.get(0)));
                });
        binding.labelFilterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            View checked = group.findViewById(checkedIds.get(0));
            if (checked == null) return;
            binding.appBar.setExpanded(true);
            Object tag = checked.getTag();
            if (ALL_LABEL_FILTER.equals(tag)) labelViewModel.selectFilter(null);
            else if (tag instanceof String labelId) labelViewModel.selectFilter(labelId);
        });
        binding.createGroupFab.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
        binding.inboxRefreshRetry.setOnClickListener(v -> viewModel.refresh());
        binding.errorState.setOnRetry(() -> {
            if (lastLabelState != null && lastLabelState.getSelectedFilterId() != null
                    && lastLabelState.getFilterError() != LabelUiState.Error.NONE) {
                labelViewModel.retrySelectedFilter();
                return;
            }
            if (lastState != null && lastState.getNextPageError() != null) {
                viewModel.loadNextPage();
            } else {
                viewModel.refresh();
            }
        });
        binding.emptyState.setTitle(getString(R.string.inbox_empty));
        binding.emptyState.setMessage(getString(R.string.inbox_empty_hint));
        binding.emptyState.setIcon(R.drawable.ic_empty_chat);
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
        viewModel.getComposerDrafts()
                .observe(getViewLifecycleOwner(), this::renderComposerDrafts);
        labelViewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::renderLabels);
        viewModel.getEvents().observe(getViewLifecycleOwner(), raw -> {
            InboxEvent event = raw == null ? null : raw.consume();
            if (event == null || binding == null) {
                return;
            }
            if (event instanceof InboxErrorEvent failure) {
                Snackbar.make(binding.getRoot(),
                                ChatErrorIds.messageId(failure.getError()),
                                Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.createGroupFab)
                        .show();
                return;
            }
            if (event instanceof InboxPreferenceSuccess success) {
                Snackbar snackbar = Snackbar.make(
                        binding.getRoot(), success.getMessageId(), Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.createGroupFab);
                if (success.getUndo() != null) {
                    snackbar.setDuration(SnackbarDurations.UNDO_MS);
                    snackbar.setAction(R.string.action_undo, ignored -> success.getUndo().run());
                }
                snackbar.show();
                return;
            }
            if (event instanceof InboxGroupRemovalEvent removal) {
                String groupTitle = removal.getGroupTitle();
                Snackbar.make(binding.getRoot(),
                                groupTitle == null || groupTitle.isBlank()
                                        ? getString(R.string.inbox_removed_from_group)
                                        : getString(R.string.inbox_removed_from_group_named, groupTitle),
                                Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.createGroupFab)
                        .show();
            }
        });
        viewModel.getPinError().observe(getViewLifecycleOwner(), error -> {
            if (error != null) {
                viewModel.clearPinError();
            }
        });
    }

    private void renderLabels(@NonNull LabelUiState state) {
        if (binding == null) return;
        lastLabelState = state;
        renderLabelFilters(state);
        if (adapter != null) adapter.setLabelDefinitions(state.getLabels());
        if (lastState != null) render(lastState);
    }

    private void renderInboxIdentity(@NonNull ProfileViewModel.UiState state) {
        if (binding == null) {
            return;
        }
        boundInboxUser = InboxIdentityRules.renderableUser(state.getUser(), signedInUserId());
        binding.inboxUserAvatar.setName(InboxIdentityRules.ownAvatarName(boundInboxUser));
        binding.inboxUserAvatar.setAvatarUrl(
                boundInboxUser == null ? null : boundInboxUser.getAvatarUrl());
        binding.inboxUserAvatar.setOnlineStatus(null);
        ensureSessionIdentity();
        if (lastState != null) {
            renderActiveNowStrip(lastState.getConversations());
        }
    }

    /**
     * The shared profile state is activity-scoped and can outlive the session that loaded it.
     * A missing user — or one whose id does not match the signed-in session — triggers a
     * reload so the inbox never renders the previous account's identity.
     */
    private void ensureSessionIdentity() {
        if (profileViewModel == null) {
            return;
        }
        ProfileViewModel.UiState profileState = profileViewModel.getUiState().getValue();
        if (profileState != null && profileState.isLoading()) {
            return;
        }
        String signedInUserId = signedInUserId();
        // One automatic load per view entry and session id; a later entry or a
        // different signed-in user re-arms the retry.
        String attemptKey = signedInUserId == null ? "" : signedInUserId;
        boolean alreadyAttempted = attemptKey.equals(identityLoadAttemptKey);
        if (InboxIdentityRules.needsProfileLoad(profileState, signedInUserId, alreadyAttempted)) {
            identityLoadAttemptKey = attemptKey;
            profileViewModel.load();
        }
    }

    @Nullable
    private String signedInUserId() {
        return lastState == null ? null : lastState.getCurrentUserId();
    }

    private void renderLabelFilters(@NonNull LabelUiState state) {
        ChipGroup group = binding.labelFilterChips;
        if (!InboxFilterChipRules.showLabelFilterRow(state.getLabels().size())) {
            group.setVisibility(View.GONE);
            group.removeAllViews();
            labelFiltersInitialized = false;
            renderedLabels = List.of();
            return;
        }
        group.setVisibility(View.VISIBLE);

        if (!labelFiltersInitialized || !renderedLabels.equals(state.getLabels())) {
            group.removeAllViews();
            labelFiltersInitialized = true;
            renderedLabels = List.copyOf(state.getLabels());
            Chip all = new Chip(requireContext(), null, R.style.Widget_NexaChat_Chip_Filter);
            all.setId(View.generateViewId());
            all.setCheckable(true);
            // Chips built in code never pick up android:clickable from the
            // style (the constructor arg is a defStyleAttr, not a style), and
            // setCheckable alone does not make them clickable — without this
            // every tap is swallowed by the scroll container and the label
            // filter can never be selected.
            all.setClickable(true);
            all.setCheckedIconVisible(true);
            all.setText(R.string.label_filter_all);
            all.setTag(ALL_LABEL_FILTER);
            InboxFilterChipColourRules.apply(all, requireContext());
            group.addView(all, new ChipGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (ConversationLabel label : state.getLabels()) {
                Chip chip = new Chip(requireContext(), null, R.style.Widget_NexaChat_Chip_Filter);
                chip.setId(View.generateViewId());
                chip.setCheckable(true);
                chip.setClickable(true);
                chip.setCheckedIconVisible(true);
                chip.setCheckedIconTint(ColorStateList.valueOf(Color.WHITE));
                chip.setText(label.getName());
                chip.setTag(label.getId());
                chip.setTextColor(Color.WHITE);
                chip.setChipBackgroundColor(ColorStateList.valueOf(label.getColor().getBackgroundColor()));
                chip.setContentDescription(getString(R.string.label_filter_description, label.getName()));
                group.addView(chip, new ChipGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            Chip manage = new Chip(requireContext(), null, R.style.Widget_NexaChat_Chip_Filter);
            manage.setId(View.generateViewId());
            manage.setCheckable(false);
            manage.setText(R.string.label_manage);
            manage.setContentDescription(getString(R.string.label_manage_description));
            manage.setOnClickListener(v -> LabelDialogFragment.show(requireActivity(), null));
            group.addView(manage, new ChipGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (!(child instanceof Chip chip) || !chip.isCheckable()) continue;
            boolean selected = state.getSelectedFilterId() == null
                    ? ALL_LABEL_FILTER.equals(chip.getTag())
                    : state.getSelectedFilterId().equals(chip.getTag());
            chip.setChecked(selected);
            if (ALL_LABEL_FILTER.equals(chip.getTag())) {
                InboxFilterChipColourRules.apply(chip, requireContext());
            }
        }
    }

    private void showInboxMenu(@NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(Menu.NONE, MENU_FEED, Menu.NONE, R.string.feed_title);
        menu.getMenu().add(Menu.NONE, MENU_NEW_GROUP, Menu.NONE, R.string.group_create_title);
        menu.getMenu().add(Menu.NONE, MENU_STARRED, Menu.NONE, R.string.starred_messages_title);
        menu.setOnMenuItemClickListener(this::onInboxMenuItemSelected);
        menu.show();
    }

    private boolean onInboxMenuItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_FEED) {
            NavHostFragment.findNavController(this).navigate(R.id.publicFeedFragment);
            return true;
        }
        if (item.getItemId() == MENU_NEW_GROUP) {
            NavHostFragment.findNavController(this).navigate(R.id.groupCreateFragment);
            return true;
        }
        if (item.getItemId() == MENU_STARRED) {
            NavHostFragment.findNavController(this).navigate(R.id.starredMessagesFragment);
            return true;
        }
        return false;
    }

    private void openConversation(@NonNull String conversationId) {
        Bundle args = new Bundle();
        args.putString("conversationId", conversationId);
        NavHostFragment.findNavController(this).navigate(R.id.chatFragment, args);
    }

    private void ensureAdapter(@Nullable String currentUserId) {
        if (adapter != null && Objects.equals(boundUserId, currentUserId)) {
            return;
        }
        boundUserId = currentUserId;
        adapter = new ConversationListAdapter(
                new ConversationListAdapter.Listener() {
                    @Override
                    public void onConversationClick(@NonNull String conversationId) {
                        openConversation(conversationId);
                    }

                    @Override
                    public void onConversationLongClick(@NonNull ConversationSummary conversation) {
                        showPreferenceActions(conversation);
                    }
                },
                currentUserId,
                getString(R.string.time_just_now),
                getString(R.string.time_yesterday));
        adapter.setComposerDrafts(lastComposerDrafts);
        binding.conversationList.setAdapter(adapter);
    }

    private void renderComposerDrafts(@Nullable Map<String, String> drafts) {
        lastComposerDrafts = drafts == null ? Map.of() : Map.copyOf(drafts);
        if (adapter != null) {
            adapter.setComposerDrafts(lastComposerDrafts);
        }
    }

    private void showPreferenceActions(@NonNull ConversationSummary conversation) {
        ConversationPreferenceMenu.showInboxActions(
                requireContext(),
                conversation,
                new ConversationPreferenceMenu.Listener() {
                    @Override
                    public void onTogglePin(boolean pin) {
                        viewModel.togglePin(conversation);
                    }

                    @Override
                    public void onMute(@Nullable java.time.Instant until) {
                        if (until == null) {
                            viewModel.muteConversationForever(conversation.getId());
                        } else {
                            viewModel.muteConversation(conversation.getId(), until);
                        }
                    }

                    @Override
                    public void onUnmute() {
                        viewModel.unmuteConversation(conversation.getId());
                    }

                    @Override
                    public void onArchive(boolean archive) {
                        viewModel.setConversationArchived(conversation.getId(), archive);
                    }

                    @Override
                    public void onSetAiOptOut(boolean optOut) {
                        viewModel.setConversationAiOptOut(conversation.getId(), optOut);
                    }

                    @Override
                    public void onMarkUnread() {
                        viewModel.markConversationUnread(conversation.getId());
                    }

                    @Override
                    public void onMarkRead() {
                        viewModel.markConversationRead(conversation.getId());
                    }
                }, () -> LabelDialogFragment.show(requireActivity(), conversation.getId()));
    }

    private void render(@NonNull InboxUiState state) {
        if (binding == null) {
            return;
        }
        String previousUserId = lastState == null ? null : lastState.getCurrentUserId();
        lastState = state;
        ensureAdapter(state.getCurrentUserId());
        ensureSessionIdentity();
        if (!Objects.equals(previousUserId, state.getCurrentUserId())) {
            // The avatar binds only a session-verified user, so it must rebind once the
            // session id resolves — otherwise a profile that landed early stays blank.
            ProfileViewModel.UiState profileState = profileViewModel.getUiState().getValue();
            if (profileState != null) {
                renderInboxIdentity(profileState);
            }
        }
        if (lastLabelState != null) adapter.setLabelDefinitions(lastLabelState.getLabels());
        List<ConversationSummary> rows = state.getConversations();
        List<ConversationSummary> visibleRows = state.getVisibleConversations();
        boolean labelFiltering = lastLabelState != null
                && lastLabelState.getSelectedFilterId() != null;
        boolean labelFilterLoading = labelFiltering && lastLabelState.isFilterLoading();
        boolean labelFilterError = labelFiltering
                && lastLabelState.getFilterError() != LabelUiState.Error.NONE
                && !lastLabelState.isFilterLoading();
        if (labelFiltering && !labelFilterLoading && !labelFilterError) {
            Set<String> filteredIds = lastLabelState.getFilteredConversationIds();
            visibleRows = visibleRows.stream().filter(row -> filteredIds.contains(row.getId())).toList();
        } else if (labelFiltering) {
            visibleRows = List.of();
        }
        submitRowsPreservingPosition(visibleRows);
        syncFilterChip(state.getSelectedFilter());
        updateFilterChipsUi(state.getTotalUnread());
        renderActiveNowStrip(rows);
        binding.swipeRefresh.setRefreshing(state.isLoading() && !rows.isEmpty());

        boolean showList = !visibleRows.isEmpty();
        boolean showLoading = labelFilterLoading || (!labelFilterError && !showList
                && (state.isLoading() || (state.isLoadingMore() && state.hasMore())));
        ApiError error = state.getError() != null ? state.getError() : state.getNextPageError();
        boolean showError = labelFilterError || (!showLoading && error != null && !showList);
        boolean showEmpty = !showLoading && !showError && !showList;
        boolean showRefreshError = showList && !state.isLoading() && state.getError() != null;

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.conversationList.setVisibility(showList ? View.VISIBLE : View.GONE);
        // swipeRefresh stays visible even when empty: the empty/error/loading
        // overlays in the FrameLayout do not consume touches, so pull-to-refresh
        // still works on an empty inbox.
        binding.swipeRefresh.setVisibility(View.VISIBLE);
        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(showError ? View.VISIBLE : View.GONE);
        if (showRefreshError) {
            int message = ChatErrorIds.messageId(state.getError());
            String text = getString(message);
            if (!TextUtils.equals(binding.inboxRefreshErrorMessage.getText(), text)) {
                binding.inboxRefreshErrorMessage.setText(text);
            }
        }
        binding.inboxRefreshError.setVisibility(showRefreshError ? View.VISIBLE : View.GONE);
        if (showEmpty) {
            if (labelFiltering) {
                ConversationLabel selected = lastLabelState.getLabels().stream()
                        .filter(label -> label.getId().equals(lastLabelState.getSelectedFilterId()))
                        .findFirst().orElse(null);
                binding.emptyState.setTitle(selected == null
                        ? getString(R.string.label_empty_manage)
                        : getString(R.string.label_empty_filter, selected.getName()));
                binding.emptyState.setMessage("");
                binding.emptyState.clearAction();
            } else {
                binding.emptyState.setTitle(getString(emptyMessageFor(state.getSelectedFilter())));
                binding.emptyState.setMessage(state.getSelectedFilter() == InboxFilter.ALL
                        ? getString(R.string.inbox_empty_hint)
                        : "");
                if (state.getSelectedFilter() == InboxFilter.ALL) {
                    binding.emptyState.setAction(
                            R.string.inbox_new_chat,
                            () -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
                } else {
                    binding.emptyState.clearAction();
                }
            }
        } else {
            binding.emptyState.clearAction();
        }
        if (labelFilterError) {
            binding.errorState.setMessage(getString(LabelText.errorMessage(lastLabelState.getFilterError())));
        } else if (showError && error != null) {
            binding.errorState.setMessage(getString(ChatErrorIds.messageId(error)));
        }
        if (showList) showPaginationError(state);
        else lastPaginationErrorMessageId = 0;
    }

    private void submitRowsPreservingPosition(@NonNull List<ConversationSummary> rows) {
        if (adapter == null || binding == null) return;
        if (adapter.getCurrentList().equals(rows)) return;
        int firstPosition = layoutManager == null ? RecyclerView.NO_POSITION
                : layoutManager.findFirstVisibleItemPosition();
        // A row restored at the very top (archive undo, a newer message bump)
        // must become visible: re-anchoring to the displaced first item leaves
        // it one row-height above the viewport — submitted but never laid out.
        boolean atTop = firstPosition <= 0;
        String anchorId = null;
        int anchorOffset = 0;
        if (!atTop && firstPosition < adapter.getItemCount()) {
            anchorId = adapter.getCurrentList().get(firstPosition).getId();
            View anchor = layoutManager.findViewByPosition(firstPosition);
            if (anchor != null) anchorOffset = anchor.getTop() - binding.conversationList.getPaddingTop();
        }
        final String preservedId = anchorId;
        final int preservedOffset = anchorOffset;
        final boolean scrollToTop = atTop;
        adapter.submitList(rows, () -> {
            if (binding == null) return;
            if (scrollToTop) {
                if (layoutManager != null) {
                    layoutManager.scrollToPositionWithOffset(0, binding.conversationList.getPaddingTop());
                }
                binding.appBar.setExpanded(true, false);
            } else if (preservedId != null && layoutManager != null) {
                List<ConversationSummary> current = adapter.getCurrentList();
                for (int i = 0; i < current.size(); i++) {
                    if (preservedId.equals(current.get(i).getId())) {
                        layoutManager.scrollToPositionWithOffset(i, preservedOffset);
                        break;
                    }
                }
            }
            binding.conversationList.post(this::maybeLoadMore);
        });
    }

    private void maybeLoadMore() {
        if (binding == null || adapter == null || lastState == null
                || (lastLabelState != null && lastLabelState.getSelectedFilterId() != null)
                || !lastState.hasMore()
                || lastState.isLoading()
                || lastState.isLoadingMore()
                || lastState.getError() != null
                || lastState.getNextPageError() != null) {
            return;
        }
        int itemCount = adapter.getItemCount();
        if (itemCount == 0) {
            viewModel.loadNextPage();
            return;
        }
        if (layoutManager == null) return;
        int lastVisible = layoutManager.findLastVisibleItemPosition();
        if (lastVisible == RecyclerView.NO_POSITION) {
            if (itemCount <= LOAD_MORE_REMAINING_ITEMS) viewModel.loadNextPage();
            return;
        }
        int remaining = itemCount - lastVisible - 1;
        if (remaining <= LOAD_MORE_REMAINING_ITEMS) viewModel.loadNextPage();
    }

    private void showPaginationError(@NonNull InboxUiState state) {
        ApiError error = state.getNextPageError();
        if (error == null) {
            lastPaginationErrorMessageId = 0;
            return;
        }
        int messageId = ChatErrorIds.messageId(error);
        if (lastPaginationErrorMessageId == messageId || binding == null) return;
        lastPaginationErrorMessageId = messageId;
        Snackbar.make(binding.getRoot(), messageId, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.createGroupFab)
                .setAction(R.string.action_retry, ignored -> viewModel.loadNextPage())
                .show();
    }

    private void updateFilterChipsUi(int totalUnread) {
        if (binding == null) {
            return;
        }
        if (totalUnread > 0) {
            binding.filterUnreadChip.setText(getString(
                    R.string.a11y_inbox_filter_unread_count,
                    getString(R.string.inbox_filter_unread),
                    totalUnread));
        } else {
            binding.filterUnreadChip.setText(R.string.inbox_filter_unread);
        }
        refreshInboxFilterChipColours();
    }

    private void refreshInboxFilterChipColours() {
        ChipGroup group = binding.inboxFilterChips;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof Chip chip) {
                InboxFilterChipColourRules.apply(chip, requireContext());
            }
        }
    }

    /**
     * Large text makes the horizontal carousel unreliable, so the filter and label
     * chip groups wrap onto stacked rows that fill the screen width instead. Inside
     * a HorizontalScrollView children measure with unbounded width, so each group
     * gets the viewport's inner width explicitly before it can wrap. The rule is
     * re-run on every width change because the first runnable can beat the first
     * layout pass and measure a zero viewport.
     */
    /**
     * Re-expands the collapsed header when a control inside it receives focus.
     * TalkBack and keyboard users can land on a filter chip while the app bar
     * is still hidden under the list; expanding on focus keeps the control
     * visible instead of leaving a focused, clipped node in the tree.
     */
    private void expandAppBarForHeaderFocus() {
        binding.getRoot().getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> {
                    if (binding == null) {
                        return;
                    }
                    for (View view = newFocus; view != null; ) {
                        if (view == binding.appBar) {
                            binding.appBar.setExpanded(true);
                            return;
                        }
                        view = view.getParent() instanceof View parent ? parent : null;
                    }
                });
    }

    /**
     * Restores a keyboard path through the header. Three separate blockers
     * made header controls unreachable from {@code conversationList}:
     *
     * <p>1) Material's {@code AppBarLayout} is a keyboard navigation cluster
     * with {@code touchscreenBlocksFocus}, so {@code requestFocus} on any
     * descendant that is not {@code focusableInTouchMode} returns false —
     * every header control (search pill, filter chips, toolbar buttons) was
     * permanently unfocusable whether the bar was expanded or collapsed. The
     * cluster grouping stays; only the touchscreen block is cleared.
     *
     * <p>2) On this platform build the directional focus search never
     * resolves a downward target out of the toolbar, and user-specified
     * {@code nextFocusDown}/{@code nextFocusForward}/{@code nextClusterForward}
     * lookups fail the same way. The unhandled DOWN listener on the app bar
     * therefore re-resolves the XML-wired {@code nextFocusDownId} manually
     * (only when the framework dead-ends), and a shared key listener performs
     * the wired {@code nextFocusForwardId} jump for TAB at each segment
     * boundary (last toolbar button, search pill, last filter chip).
     *
     * <p>3) With the bar collapsed, header children are also pruned from the
     * focus tree, so DPAD_UP from the top conversation row can have no
     * target and surfaces here as an unhandled key. The listener only
     * consumes that dead-end case: any real upward target (a row above, or
     * the off-screen-but-laid-out header chips/pill via the root-wide
     * finder) belongs to ordinary traversal — expand, then hand focus to
     * the search pill.
     */
    private void expandAppBarForKeyboardTraversal() {
        binding.appBar.setTouchscreenBlocksFocus(false);
        ViewCompat.addOnUnhandledKeyEventListener(binding.appBar,
                (view, event) -> {
                    if (binding == null
                            || event.getAction() != KeyEvent.ACTION_DOWN
                            || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_DOWN) {
                        return false;
                    }
                    View focused = binding.getRoot().findFocus();
                    if (focused == null
                            || focused.getNextFocusDownId() == View.NO_ID) {
                        return false;
                    }
                    View next = binding.getRoot().findViewById(
                            focused.getNextFocusDownId());
                    return next != null && next.requestFocus();
                });
        // The appBar cluster wraps TAB inside itself and never reaches the
        // nextFocusForward resolution on this framework build, so the views
        // that close a forward segment get an explicit key path instead.
        View.OnKeyListener forwardTab = (v, keyCode, event) -> {
            if (binding == null
                    || event.getAction() != KeyEvent.ACTION_DOWN
                    || keyCode != KeyEvent.KEYCODE_TAB
                    || event.isShiftPressed()
                    || v.getNextFocusForwardId() == View.NO_ID) {
                return false;
            }
            View next = binding.getRoot().findViewById(v.getNextFocusForwardId());
            return next != null && next.requestFocus();
        };
        binding.moreButton.setOnKeyListener(forwardTab);
        binding.inboxSearchPill.setOnKeyListener(forwardTab);
        binding.activeNowViewAll.setOnKeyListener(forwardTab);
        binding.filterArchivedChip.setOnKeyListener(forwardTab);
        ViewCompat.addOnUnhandledKeyEventListener(binding.conversationList,
                (view, event) -> {
                    if (binding == null
                            || event.getAction() != KeyEvent.ACTION_DOWN
                            || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP) {
                        return false;
                    }
                    View focused = binding.conversationList.getFocusedChild();
                    if (focused == null) {
                        return false;
                    }
                    View next = focused.focusSearch(View.FOCUS_UP);
                    if (next == null) {
                        next = FocusFinder.getInstance()
                                .findNextFocus(binding.getRoot(), focused, View.FOCUS_UP);
                    }
                    if (next != null && next != focused) {
                        return false;
                    }
                    binding.appBar.setExpanded(true);
                    binding.inboxSearchPill.post(() -> {
                        if (binding != null) {
                            binding.inboxSearchPill.requestFocus();
                        }
                    });
                    return true;
                });
    }

    private void applyFilterChipRowLayout() {
        if (binding == null || !InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(
                getResources().getConfiguration().fontScale,
                getResources().getConfiguration().screenWidthDp)) {
            return;
        }
        WidthDrivenLayoutAction action = new WidthDrivenLayoutAction(
                () -> binding == null ? 0 : binding.inboxFilterScroll.getWidth(),
                this::wrapFilterChipRows);
        binding.inboxFilterScroll.post(action::run);
        binding.inboxFilterScroll.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right - left != oldRight - oldLeft) {
                        action.run();
                    }
                });
    }

    private void wrapFilterChipRows(int scrollWidth) {
        if (binding == null) {
            return;
        }
        int width = scrollWidth
                - binding.inboxFilterScroll.getPaddingLeft()
                - binding.inboxFilterScroll.getPaddingRight();
        binding.inboxFilterRow.setOrientation(LinearLayout.VERTICAL);
        wrapChipGroup(binding.inboxFilterChips, width);
        wrapChipGroup(binding.labelFilterChips, width);
        ViewGroup.MarginLayoutParams labelParams =
                (ViewGroup.MarginLayoutParams) binding.labelFilterChips.getLayoutParams();
        if (labelParams.getMarginStart() != 0) {
            labelParams.topMargin = labelParams.getMarginStart();
            labelParams.setMarginStart(0);
            binding.labelFilterChips.setLayoutParams(labelParams);
        }
    }

    private static void wrapChipGroup(@NonNull ChipGroup group, int widthPx) {
        if (group.isSingleLine()) {
            group.setSingleLine(false);
        }
        ViewGroup.LayoutParams params = group.getLayoutParams();
        if (params.width != widthPx) {
            params.width = widthPx;
            group.setLayoutParams(params);
        }
    }

    /**
     * "View all" sits beside the title only while both fit with a clear gap;
     * crowded rows (Vietnamese at 2x text) stack the action under the title.
     * The decision is re-run on every width change because the first runnable
     * can beat the first layout pass and measure a zero-width row.
     */
    private void updateActiveNowHeaderLayout() {
        final LinearLayout row = binding.activeNowHeaderRow;
        WidthDrivenLayoutAction action = new WidthDrivenLayoutAction(
                () -> binding == null ? 0 : row.getWidth(),
                this::stackActiveNowHeaderIfNeeded);
        row.post(action::run);
        row.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right - left != oldRight - oldLeft) {
                        action.run();
                    }
                });
    }

    private void stackActiveNowHeaderIfNeeded(int rowWidth) {
        if (binding == null) {
            return;
        }
        LinearLayout row = binding.activeNowHeaderRow;
        TextView title = binding.activeNowTitle;
        TextView action = binding.activeNowViewAll;
        float titleWidth = measuredTextWidth(title);
        float actionWidth = Math.max(
                Layout.getDesiredWidth(action.getText(), action.getPaint()),
                action.getMinWidth());
        // The whole horizontal gap between title and action is the action's
        // margin; while stacked that margin reads zero, so the gap the row
        // would restore is what the rule measures against.
        float separation = getResources().getDimension(R.dimen.space_sm);
        float available = rowWidth - row.getPaddingLeft() - row.getPaddingRight();
        boolean wrapped = title.getLayout() != null && title.getLineCount() > 1;
        applyActiveNowHeaderOrientation(
                wrapped || ActiveNowHeaderRules.actionStacksBelowTitle(
                        titleWidth, actionWidth, separation, available));
    }

    private void applyActiveNowHeaderOrientation(boolean stack) {
        LinearLayout row = binding.activeNowHeaderRow;
        int target = stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
        if (row.getOrientation() == target) {
            return;
        }
        row.setOrientation(target);
        LinearLayout.LayoutParams titleParams =
                (LinearLayout.LayoutParams) binding.activeNowTitle.getLayoutParams();
        LinearLayout.LayoutParams actionParams =
                (LinearLayout.LayoutParams) binding.activeNowViewAll.getLayoutParams();
        int gap = getResources().getDimensionPixelSize(R.dimen.space_sm);
        if (stack) {
            titleParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            titleParams.weight = 0;
            actionParams.gravity = Gravity.END;
            actionParams.setMarginStart(0);
            actionParams.topMargin = gap;
        } else {
            titleParams.width = 0;
            titleParams.weight = 1;
            actionParams.gravity = Gravity.NO_GRAVITY;
            actionParams.setMarginStart(gap);
            actionParams.topMargin = 0;
        }
        binding.activeNowTitle.setLayoutParams(titleParams);
        binding.activeNowViewAll.setLayoutParams(actionParams);
    }

    /** Widest laid-out line; falls back to the one-line desired width pre-layout. */
    private static float measuredTextWidth(@NonNull TextView view) {
        Layout layout = view.getLayout();
        if (layout == null || layout.getLineCount() == 0) {
            return Layout.getDesiredWidth(view.getText(), view.getPaint());
        }
        float width = 0;
        for (int line = 0; line < layout.getLineCount(); line++) {
            width = Math.max(width, layout.getLineWidth(line));
        }
        return width;
    }

    private void renderActiveNowStrip(@NonNull List<ConversationSummary> rows) {
        if (binding == null || activeNowStripBinder == null) {
            return;
        }
        float fontScale = getResources().getConfiguration().fontScale;
        List<ConversationSummary> onlinePeers = ActiveNowPeerRules.selectOnlineDirectPeers(
                InboxFilterRules.apply(rows, InboxFilter.ALL));
        binding.activeNowSection.setVisibility(onlinePeers.isEmpty() ? View.GONE : View.VISIBLE);
        if (onlinePeers.isEmpty()) {
            return;
        }
        activeNowStripBinder.bind(
                boundInboxUser,
                rows,
                () -> NavHostFragment.findNavController(this).navigate(R.id.profileFragment),
                row -> openConversation(row.getId()),
                fontScale);
    }

    private void attachSwipeGestureHelper() {
        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTextSize(getResources().getDimension(R.dimen.inbox_swipe_text_size));
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override
            public boolean onMove(
                    @NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder viewHolder,
                    @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                if (adapter == null || binding == null) {
                    return;
                }
                int position = viewHolder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION || position >= adapter.getItemCount()) {
                    return;
                }
                ConversationSummary conversation = adapter.getCurrentList().get(position);
                adapter.notifyItemChanged(position);
                if (direction == ItemTouchHelper.END) {
                    InboxBadgeRules.SwipeAction action =
                            InboxBadgeRules.resolveStartSwipeAction(conversation);
                    if (action == InboxBadgeRules.SwipeAction.PIN
                            || action == InboxBadgeRules.SwipeAction.UNPIN) {
                        viewModel.togglePin(conversation);
                    }
                } else if (direction == ItemTouchHelper.START) {
                    InboxBadgeRules.SwipeAction action =
                            InboxBadgeRules.resolveEndSwipeAction(conversation);
                    boolean archive = action == InboxBadgeRules.SwipeAction.ARCHIVE;
                    viewModel.setConversationArchived(conversation.getId(), archive);
                }
            }

            @Override
            public void onChildDraw(
                    @NonNull Canvas c,
                    @NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder viewHolder,
                    float dX,
                    float dY,
                    int actionState,
                    boolean isCurrentlyActive) {
                View itemView = viewHolder.itemView;
                int position = viewHolder.getBindingAdapterPosition();
                if (binding != null && adapter != null
                        && position != RecyclerView.NO_POSITION && position < adapter.getItemCount()
                        && actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    boolean swipeRight = dX > 0f;
                    InboxBadgeRules.SwipeAction action = InboxBadgeRules.resolvePhysicalSwipeAction(
                            adapter.getCurrentList().get(position), dX,
                            recyclerView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
                    boolean pinAction = action == InboxBadgeRules.SwipeAction.PIN
                            || action == InboxBadgeRules.SwipeAction.UNPIN;
                    int bgColor = resolveThemeColor(
                            requireContext(),
                            pinAction
                                    ? com.google.android.material.R.attr.colorPrimaryContainer
                                    : com.google.android.material.R.attr.colorSecondaryContainer);
                    int iconTint = resolveThemeColor(
                            requireContext(),
                            pinAction
                                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                                    : com.google.android.material.R.attr.colorOnSecondaryContainer);
                    backgroundPaint.setColor(bgColor);
                    RectF rect = swipeRight
                            ? new RectF(
                                    itemView.getLeft(),
                                    itemView.getTop(),
                                    itemView.getLeft() + dX,
                                    itemView.getBottom())
                            : new RectF(
                                    itemView.getRight() + dX,
                                    itemView.getTop(),
                                    itemView.getRight(),
                                    itemView.getBottom());
                    c.drawRect(rect, backgroundPaint);

                    int label = switch (action) {
                        case PIN -> R.string.action_pin_conversation;
                        case UNPIN -> R.string.action_unpin_conversation;
                        case UNARCHIVE -> R.string.conversation_unarchive;
                        default -> R.string.conversation_archive;
                    };
                    float margin = getResources().getDimension(R.dimen.space_md);
                    float available = Math.abs(dX) - 2 * margin;
                    if (available > 0f) {
                        labelPaint.setColor(iconTint);
                        CharSequence text = TextUtils.ellipsize(
                                getString(label), labelPaint, available, TextUtils.TruncateAt.END);
                        float width = labelPaint.measureText(text.toString());
                        float x = swipeRight ? itemView.getLeft() + margin
                                : itemView.getRight() - margin - width;
                        float y = itemView.getTop() + (itemView.getHeight()
                                - labelPaint.ascent() - labelPaint.descent()) / 2f;
                        int save = c.save();
                        c.clipRect(rect);
                        c.drawText(text.toString(), x, y, labelPaint);
                        c.restoreToCount(save);
                    }
                }
                super.onChildDraw(
                        c,
                        recyclerView,
                        viewHolder,
                        dX,
                        dY,
                        actionState,
                        isCurrentlyActive);
            }
        };
        new ItemTouchHelper(callback).attachToRecyclerView(binding.conversationList);
    }

    private void syncFilterChip(@NonNull InboxFilter filter) {
        if (binding == null) {
            return;
        }
        int chipId = chipForFilter(filter);
        if (binding.inboxFilterChips.getCheckedChipId() != chipId) {
            binding.inboxFilterChips.check(chipId);
        }
    }

    private static int chipForFilter(@NonNull InboxFilter filter) {
        if (filter == InboxFilter.UNREAD) {
            return R.id.filterUnreadChip;
        }
        if (filter == InboxFilter.GROUPS) {
            return R.id.filterGroupsChip;
        }
        if (filter == InboxFilter.ARCHIVED) {
            return R.id.filterArchivedChip;
        }
        return R.id.filterAllChip;
    }

    @NonNull
    private static InboxFilter filterForChip(int chipId) {
        if (chipId == R.id.filterUnreadChip) {
            return InboxFilter.UNREAD;
        }
        if (chipId == R.id.filterGroupsChip) {
            return InboxFilter.GROUPS;
        }
        if (chipId == R.id.filterArchivedChip) {
            return InboxFilter.ARCHIVED;
        }
        return InboxFilter.ALL;
    }

    private static int emptyMessageFor(@NonNull InboxFilter filter) {
        if (filter == InboxFilter.UNREAD) {
            return R.string.inbox_empty_unread;
        }
        if (filter == InboxFilter.GROUPS) {
            return R.string.inbox_empty_groups;
        }
        if (filter == InboxFilter.ARCHIVED) {
            return R.string.inbox_empty_archived;
        }
        return R.string.inbox_empty;
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

    @Override
    public void onPause() {
        if (streakViewModel != null) {
            streakViewModel.onScreenHidden();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            if (paginationScrollListener != null) {
                binding.conversationList.removeOnScrollListener(paginationScrollListener);
            }
            binding.conversationList.setAdapter(null);
        }
        paginationScrollListener = null;
        layoutManager = null;
        adapter = null;
        boundUserId = null;
        boundInboxUser = null;
        identityLoadAttemptKey = null;
        lastState = null;
        lastLabelState = null;
        renderedLabels = List.of();
        labelFiltersInitialized = false;
        lastPaginationErrorMessageId = 0;
        super.onDestroyView();
        binding = null;
    }
}
