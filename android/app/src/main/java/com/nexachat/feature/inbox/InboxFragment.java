package com.nexachat.feature.inbox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.animation.ValueAnimator;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.ThemeMode;
import com.nexachat.databinding.FragmentInboxBinding;
import com.nexachat.feature.chat.ChatErrorIds;
import com.nexachat.feature.inbox.adapter.ConversationListAdapter;
import com.nexachat.feature.inbox.adapter.ConversationPreviewHelper;
import com.nexachat.feature.label.ConversationLabel;
import com.nexachat.feature.label.LabelDialogFragment;
import com.nexachat.feature.label.LabelText;
import com.nexachat.feature.label.LabelUiState;
import com.nexachat.feature.label.LabelViewModel;
import com.nexachat.feature.profile.AppSettingsViewModel;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Inbox conversation list backed by {@link InboxViewModel}. */
@AndroidEntryPoint
public final class InboxFragment extends Fragment {

    private static final int LOAD_MORE_REMAINING_ITEMS = 5;
    private static final int MENU_CONTACTS = 1;
    private static final int MENU_NEW_GROUP = 2;
    private static final int MENU_THEME = 3;
    private static final int MENU_ASSIGN_LABEL = 4;
    private static final String ALL_LABEL_FILTER = "__all_labels__";

    private FragmentInboxBinding binding;
    private InboxViewModel viewModel;
    private LabelViewModel labelViewModel;
    private AppSettingsViewModel settingsViewModel;
    private ConversationListAdapter adapter;
    private LinearLayoutManager layoutManager;
    @Nullable private RecyclerView.OnScrollListener paginationScrollListener;
    @Nullable private InboxUiState lastState;
    @Nullable private LabelUiState lastLabelState;
    private List<ConversationLabel> renderedLabels = List.of();
    private boolean labelFiltersInitialized;
    @Nullable private String boundUserId;
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
        settingsViewModel = new ViewModelProvider(requireActivity()).get(AppSettingsViewModel.class);
        labelViewModel.load();

        binding.inboxUserAvatar.setName("Nexa");
        binding.inboxUserAvatar.setOnlineStatus(true);
        binding.inboxUserAvatar.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.profileFragment));
        binding.moreButton.setOnClickListener(this::showInboxMenu);
        layoutManager = new LinearLayoutManager(requireContext());
        binding.conversationList.setLayoutManager(layoutManager);
        paginationScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (binding != null && dy != 0) {
                    boolean extend = dy < 0;
                    if (extend != binding.createGroupFab.isExtended()) {
                        if (!ValueAnimator.areAnimatorsEnabled()) {
                            binding.createGroupFab.setExtended(extend);
                        } else if (extend) {
                            binding.createGroupFab.extend();
                        } else {
                            binding.createGroupFab.shrink();
                        }
                    }
                }
                maybeLoadMore();
            }
        };
        binding.conversationList.addOnScrollListener(paginationScrollListener);
        binding.swipeRefresh.setOnRefreshListener(viewModel::refresh);
        binding.inboxSearchPill.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));

        binding.activeNowViewAll.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.contactsFragment));

        binding.inboxFilterChips.check(R.id.filterAllChip);
        updateFilterChipsUi(0);
        binding.inboxFilterChips.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    if (checkedIds.isEmpty()) {
                        return;
                    }
                    viewModel.selectFilter(filterForChip(checkedIds.get(0)));
                });
        binding.labelFilterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            View checked = group.findViewById(checkedIds.get(0));
            if (checked == null) return;
            Object tag = checked.getTag();
            if (ALL_LABEL_FILTER.equals(tag)) labelViewModel.selectFilter(null);
            else if (tag instanceof String labelId) labelViewModel.selectFilter(labelId);
        });
        binding.createGroupFab.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
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
        binding.emptyState.setMessage(getString(R.string.inbox_empty));
        binding.emptyState.setIcon(R.drawable.ic_empty_chat);
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
        labelViewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::renderLabels);
    }

    private void showThemeDialog() {
        ThemeMode[] modes = {ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK};
        String[] labels = {
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        };
        ThemeMode current = settingsViewModel.getThemeMode().getValue();
        int checkedIndex = current == null ? 0 : current.ordinal();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_theme_title)
                .setSingleChoiceItems(
                        labels,
                        checkedIndex,
                        (dialog, which) -> {
                            settingsViewModel.setThemeMode(modes[which]);
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showInboxMenu(@NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(Menu.NONE, MENU_CONTACTS, Menu.NONE, R.string.contacts_title);
        menu.getMenu().add(Menu.NONE, MENU_NEW_GROUP, Menu.NONE, R.string.group_create_title);
        menu.getMenu().add(Menu.NONE, MENU_THEME, Menu.NONE, R.string.settings_theme_title);
        menu.setOnMenuItemClickListener(this::onInboxMenuItemSelected);
        menu.show();
    }

    private boolean onInboxMenuItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_CONTACTS) {
            NavHostFragment.findNavController(this).navigate(R.id.contactsFragment);
            return true;
        }
        if (item.getItemId() == MENU_NEW_GROUP) {
            NavHostFragment.findNavController(this).navigate(R.id.groupCreateFragment);
            return true;
        }
        if (item.getItemId() == MENU_THEME) {
            showThemeDialog();
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
                this::openConversation,
                this::showConversationLabelMenu,
                currentUserId,
                getString(R.string.time_just_now),
                getString(R.string.time_yesterday));
        binding.conversationList.setAdapter(adapter);
    }

    private void renderLabels(@NonNull LabelUiState state) {
        if (binding == null) return;
        lastLabelState = state;
        renderLabelFilters(state);
        if (adapter != null) adapter.setLabelDefinitions(state.getLabels());
        if (lastState != null) render(lastState);
    }

    private void renderLabelFilters(@NonNull LabelUiState state) {
        ChipGroup group = binding.labelFilterChips;
        if (!labelFiltersInitialized || !renderedLabels.equals(state.getLabels())) {
            group.removeAllViews();
            labelFiltersInitialized = true;
            renderedLabels = List.copyOf(state.getLabels());
            Chip all = new Chip(requireContext());
            all.setId(View.generateViewId());
            all.setCheckable(true);
            all.setMinHeight(dp(48));
            all.setText(R.string.label_filter_all);
            all.setTag(ALL_LABEL_FILTER);
            group.addView(all, new ChipGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (ConversationLabel label : state.getLabels()) {
                Chip chip = new Chip(requireContext());
                chip.setId(View.generateViewId());
                chip.setCheckable(true);
                chip.setText(label.getName());
                chip.setTag(label.getId());
                chip.setMinHeight(dp(48));
                chip.setTextColor(android.graphics.Color.WHITE);
                chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                        label.getColor().getBackgroundColor()));
                chip.setContentDescription(getString(R.string.label_filter_description, label.getName()));
                group.addView(chip, new ChipGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            Chip manage = new Chip(requireContext());
            manage.setId(View.generateViewId());
            manage.setCheckable(false);
            manage.setText(R.string.label_manage);
            manage.setMinHeight(dp(48));
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
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showConversationLabelMenu(@NonNull String conversationId, @NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(Menu.NONE, MENU_ASSIGN_LABEL, Menu.NONE, R.string.label_assign_menu);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != MENU_ASSIGN_LABEL) return false;
            LabelDialogFragment.show(requireActivity(), conversationId);
            return true;
        });
        menu.show();
    }

    private void render(@NonNull InboxUiState state) {
        if (binding == null) {
            return;
        }
        lastState = state;
        ensureAdapter(state.getCurrentUserId());
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
            visibleRows = visibleRows.stream()
                    .filter(row -> filteredIds.contains(row.getId()))
                    .toList();
        } else if (labelFiltering) {
            visibleRows = List.of();
        }
        submitRowsPreservingPosition(visibleRows);
        syncFilterChip(state.getSelectedFilter());
        updateFilterChipsUi(state.getTotalUnread());
        renderActiveNowStrip(rows);
        binding.swipeRefresh.setRefreshing(state.isLoading() && !rows.isEmpty());

        boolean showLoading = labelFilterLoading
                || (state.isLoading() && rows.isEmpty())
                || (state.isLoadingMore() && visibleRows.isEmpty() && state.hasMore());
        ApiError error = state.getError() != null ? state.getError() : state.getNextPageError();
        boolean showError = labelFilterError || (error != null && rows.isEmpty());
        boolean showEmpty = !showLoading && !showError && visibleRows.isEmpty();
        boolean showList = !visibleRows.isEmpty();

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.conversationList.setVisibility(showList ? View.VISIBLE : View.GONE);
        binding.swipeRefresh.setVisibility(showList ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(showError ? View.VISIBLE : View.GONE);
        if (showEmpty) {
            if (labelFiltering) {
                ConversationLabel selected = lastLabelState.getLabels().stream()
                        .filter(label -> label.getId().equals(lastLabelState.getSelectedFilterId()))
                        .findFirst().orElse(null);
                binding.emptyState.setMessage(selected == null
                        ? getString(R.string.label_empty_manage)
                        : getString(R.string.label_empty_filter, selected.getName()));
            } else {
                binding.emptyState.setMessage(getString(emptyMessageFor(state.getSelectedFilter())));
            }
        }
        if (labelFilterError) {
            binding.errorState.setMessage(getString(LabelText.errorMessage(lastLabelState.getFilterError())));
        } else if (showError && error != null) {
            binding.errorState.setMessage(getString(ChatErrorIds.messageId(error)));
        }
        showPaginationError(state);
    }

    private void submitRowsPreservingPosition(@NonNull List<ConversationSummary> rows) {
        if (adapter == null || binding == null) return;
        int firstPosition = layoutManager == null ? RecyclerView.NO_POSITION
                : layoutManager.findFirstVisibleItemPosition();
        String anchorId = null;
        int anchorOffset = 0;
        if (firstPosition != RecyclerView.NO_POSITION && firstPosition < adapter.getItemCount()) {
            anchorId = adapter.getCurrentList().get(firstPosition).getId();
            View anchor = layoutManager.findViewByPosition(firstPosition);
            if (anchor != null) anchorOffset = anchor.getTop() - binding.conversationList.getPaddingTop();
        }
        final String preservedId = anchorId;
        final int preservedOffset = anchorOffset;
        adapter.submitList(rows, () -> {
            if (binding == null) return;
            if (preservedId != null && layoutManager != null) {
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
                .setAction(R.string.action_retry, ignored -> viewModel.loadNextPage())
                .show();
    }

    private void updateFilterChipsUi(int totalUnread) {
        if (binding == null) {
            return;
        }
        if (totalUnread > 0) {
            binding.filterUnreadChip.setText(getString(R.string.inbox_filter_unread) + " (" + totalUnread + ")");
        } else {
            binding.filterUnreadChip.setText(R.string.inbox_filter_unread);
        }

        int primaryColor = resolveThemeColor(requireContext(), com.google.android.material.R.attr.colorPrimary);
        int onPrimaryColor = resolveThemeColor(requireContext(), com.google.android.material.R.attr.colorOnPrimary);
        int surfaceContainerColor = resolveThemeColor(requireContext(), com.google.android.material.R.attr.colorSurfaceContainer);
        int onSurfaceVariantColor = resolveThemeColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        int outlineColor = resolveThemeColor(requireContext(), com.google.android.material.R.attr.colorOutlineVariant);

        Chip[] chips = new Chip[] {
                binding.filterAllChip,
                binding.filterUnreadChip,
                binding.filterGroupsChip
        };

        for (Chip chip : chips) {
            boolean isSelected = chip.getId() == binding.inboxFilterChips.getCheckedChipId();
            if (isSelected) {
                chip.setChipBackgroundColor(ColorStateList.valueOf(primaryColor));
                chip.setTextColor(onPrimaryColor);
                chip.setChipStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
                chip.setChipStrokeWidth(0f);
            } else {
                chip.setChipBackgroundColor(ColorStateList.valueOf(surfaceContainerColor));
                chip.setTextColor(onSurfaceVariantColor);
                chip.setChipStrokeColor(ColorStateList.valueOf(outlineColor));
                chip.setChipStrokeWidth(1f);
            }
        }
    }

    private void renderActiveNowStrip(@NonNull List<ConversationSummary> rows) {
        if (binding == null) {
            return;
        }
        binding.activeNowStrip.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        // 1. First item: "Your status" (+) item that opens R.id.profileFragment
        View yourStatusView = inflater.inflate(R.layout.item_active_now_peer, binding.activeNowStrip, false);
        AvatarView yourAvatar = yourStatusView.findViewById(R.id.activePeerAvatar);
        TextView yourName = yourStatusView.findViewById(R.id.activePeerName);
        View yourBadge = yourStatusView.findViewById(R.id.activePeerBadge);
        yourAvatar.setName("+");
        yourName.setText(R.string.inbox_your_status);
        if (yourBadge != null) {
            yourBadge.setVisibility(View.VISIBLE);
        }
        yourStatusView.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.profileFragment));
        binding.activeNowStrip.addView(yourStatusView);

        // 2. Next items (up to 8 peers), prioritizing direct conversations and deduplicating titles
        List<ConversationSummary> candidates = new ArrayList<>(rows.size());
        for (ConversationSummary row : rows) {
            if (!row.isGroup()) {
                candidates.add(row);
            }
        }
        for (ConversationSummary row : rows) {
            if (row.isGroup()) {
                candidates.add(row);
            }
        }

        Set<String> seenTitles = new HashSet<>();
        int count = 0;
        for (ConversationSummary row : candidates) {
            if (count >= 8) {
                break;
            }
            String displayTitle = ConversationPreviewHelper.displayTitle(requireContext(), row);
            String normalizedTitle = displayTitle.trim().toLowerCase(Locale.ROOT);
            if (!seenTitles.add(normalizedTitle)) {
                continue;
            }

            View peerView = inflater.inflate(R.layout.item_active_now_peer, binding.activeNowStrip, false);
            AvatarView peerAvatar = peerView.findViewById(R.id.activePeerAvatar);
            TextView peerName = peerView.findViewById(R.id.activePeerName);
            View peerBadge = peerView.findViewById(R.id.activePeerBadge);
            if (peerBadge != null) {
                peerBadge.setVisibility(View.GONE);
            }

            peerAvatar.setName(displayTitle);
            peerAvatar.setAvatarUrl(row.getDisplayAvatar());
            Boolean isOnline = null;
            if (row.getPeerPresence() != null) {
                isOnline = row.getPeerPresence().getOnline();
            } else if (!row.isGroup()) {
                isOnline = Boolean.TRUE;
            }
            peerAvatar.setOnlineStatus(isOnline);
            peerAvatar.setShowStoryRing(!row.isGroup() || Boolean.TRUE.equals(isOnline));

            String shortName = displayTitle.contains(" ")
                    ? displayTitle.substring(0, displayTitle.indexOf(" "))
                    : displayTitle;
            peerName.setText(shortName);

            peerView.setOnClickListener(v -> openConversation(row.getId()));
            binding.activeNowStrip.addView(peerView);
            count++;
        }
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
        return InboxFilter.ALL;
    }

    private static int emptyMessageFor(@NonNull InboxFilter filter) {
        if (filter == InboxFilter.UNREAD) {
            return R.string.inbox_empty_unread;
        }
        if (filter == InboxFilter.GROUPS) {
            return R.string.inbox_empty_groups;
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
        lastState = null;
        lastLabelState = null;
        renderedLabels = List.of();
        labelFiltersInitialized = false;
        lastPaginationErrorMessageId = 0;
        super.onDestroyView();
        binding = null;
    }
}
