package com.nexachat.feature.inbox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.ThemeMode;
import com.nexachat.databinding.FragmentInboxBinding;
import com.nexachat.feature.chat.ChatErrorIds;
import com.nexachat.feature.inbox.adapter.ConversationListAdapter;
import com.nexachat.feature.inbox.adapter.ConversationPreviewHelper;
import com.nexachat.feature.profile.AppSettingsViewModel;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Inbox conversation list backed by {@link InboxViewModel}. */
@AndroidEntryPoint
public final class InboxFragment extends Fragment {

    private FragmentInboxBinding binding;
    private InboxViewModel viewModel;
    private AppSettingsViewModel settingsViewModel;
    private ConversationListAdapter adapter;
    private int selectedFilterId = R.id.filterAllChip;
    @Nullable private InboxUiState lastState;
    @Nullable private String boundUserId;

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
        settingsViewModel = new ViewModelProvider(requireActivity()).get(AppSettingsViewModel.class);

        binding.inboxUserAvatar.setName("Nexa");
        binding.inboxUserAvatar.setOnlineStatus(true);
        binding.inboxUserAvatar.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.profileFragment));
        binding.conversationList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.swipeRefresh.setOnRefreshListener(viewModel::refresh);
        binding.themeButton.setOnClickListener(v -> showThemeDialog());
        binding.contactsButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.contactsFragment));
        binding.searchButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
        binding.inboxSearchPill.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
        binding.profileButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.profileFragment));

        binding.activeNowViewAll.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.contactsFragment));
        binding.navTabChats.setOnClickListener(
                v -> binding.conversationList.smoothScrollToPosition(0));
        binding.navTabCalls.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.contactsFragment));
        binding.navTabContacts.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.contactsFragment));
        binding.navTabSettings.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.profileFragment));

        binding.inboxFilterChips.check(selectedFilterId);
        updateFilterChipsUi(0);
        binding.inboxFilterChips.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    if (checkedIds.isEmpty()) {
                        return;
                    }
                    selectedFilterId = checkedIds.get(0);
                    updateFilterChipsUi(lastState != null ? lastState.getTotalUnread() : 0);
                    if (lastState != null) {
                        render(lastState);
                    }
                });
        binding.createGroupFab.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.groupCreateFragment));
        binding.errorState.setOnRetry(viewModel::refresh);
        binding.emptyState.setMessage(getString(R.string.inbox_empty));
        binding.emptyState.setIcon(R.drawable.ic_empty_chat);
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
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
                currentUserId,
                getString(R.string.time_just_now),
                getString(R.string.time_yesterday));
        binding.conversationList.setAdapter(adapter);
    }

    private void render(@NonNull InboxUiState state) {
        if (binding == null) {
            return;
        }
        lastState = state;
        ensureAdapter(state.getCurrentUserId());
        List<ConversationSummary> rows = state.getConversations();
        List<ConversationSummary> visibleRows = filterRows(rows);
        adapter.submitList(visibleRows);
        updateFilterChipsUi(state.getTotalUnread());
        renderActiveNowStrip(rows);
        binding.swipeRefresh.setRefreshing(state.isLoading() && !rows.isEmpty());

        boolean showLoading = state.isLoading() && rows.isEmpty();
        ApiError error = state.getError();
        boolean showError = error != null && rows.isEmpty();
        boolean showEmpty = !showLoading && !showError && visibleRows.isEmpty();
        boolean showList = !visibleRows.isEmpty();

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.conversationList.setVisibility(showList ? View.VISIBLE : View.GONE);
        binding.swipeRefresh.setVisibility(showList ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(showError ? View.VISIBLE : View.GONE);
        if (showError && error != null) {
            binding.errorState.setMessage(getString(ChatErrorIds.messageId(error)));
        }
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
        int surfaceVariantColor = resolveThemeColor(requireContext(), com.google.android.material.R.attr.colorSurfaceVariant);
        int onSurfaceVariantColor = resolveThemeColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant);
        int outlineColor = resolveThemeColor(requireContext(), com.google.android.material.R.attr.colorOutlineVariant);

        Chip[] chips = new Chip[] {
                binding.filterAllChip,
                binding.filterUnreadChip,
                binding.filterDirectChip,
                binding.filterGroupsChip
        };

        for (Chip chip : chips) {
            boolean isSelected = chip.getId() == selectedFilterId;
            if (isSelected) {
                chip.setChipBackgroundColor(ColorStateList.valueOf(primaryColor));
                chip.setTextColor(onPrimaryColor);
                chip.setChipStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
                chip.setChipStrokeWidth(0f);
            } else {
                chip.setChipBackgroundColor(ColorStateList.valueOf(surfaceVariantColor));
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

        // 2. Next items (up to 8 peers)
        int count = 0;
        for (ConversationSummary row : rows) {
            if (count >= 8) {
                break;
            }
            View peerView = inflater.inflate(R.layout.item_active_now_peer, binding.activeNowStrip, false);
            AvatarView peerAvatar = peerView.findViewById(R.id.activePeerAvatar);
            TextView peerName = peerView.findViewById(R.id.activePeerName);
            View peerBadge = peerView.findViewById(R.id.activePeerBadge);
            if (peerBadge != null) {
                peerBadge.setVisibility(View.GONE);
            }

            String displayTitle = ConversationPreviewHelper.displayTitle(requireContext(), row);
            peerAvatar.setName(displayTitle);
            peerAvatar.setAvatarUrl(row.getDisplayAvatar());
            peerAvatar.setShowStoryRing(true);
            Boolean isOnline = row.getPeerPresence() != null ? row.getPeerPresence().getOnline() : Boolean.TRUE;
            peerAvatar.setOnlineStatus(isOnline);

            String shortName = displayTitle.contains(" ")
                    ? displayTitle.substring(0, displayTitle.indexOf(" "))
                    : displayTitle;
            peerName.setText(shortName);

            peerView.setOnClickListener(v -> openConversation(row.getId()));
            binding.activeNowStrip.addView(peerView);
            count++;
        }
    }

    @NonNull
    private List<ConversationSummary> filterRows(@NonNull List<ConversationSummary> rows) {
        if (selectedFilterId == R.id.filterAllChip) {
            return rows;
        }
        List<ConversationSummary> filtered = new ArrayList<>();
        for (ConversationSummary row : rows) {
            boolean include;
            if (selectedFilterId == R.id.filterUnreadChip) {
                include = row.getUnreadCount() > 0;
            } else if (selectedFilterId == R.id.filterDirectChip) {
                include = !row.isGroup();
            } else if (selectedFilterId == R.id.filterGroupsChip) {
                include = row.isGroup();
            } else {
                include = true;
            }
            if (include) {
                filtered.add(row);
            }
        }
        return filtered;
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
            binding.conversationList.setAdapter(null);
        }
        adapter = null;
        boundUserId = null;
        lastState = null;
        super.onDestroyView();
        binding = null;
    }
}
