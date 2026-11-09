package com.nexachat.feature.groupboard;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.tabs.TabLayout;
import com.nexachat.R;
import com.nexachat.databinding.FragmentGroupBoardBinding;
import com.nexachat.feature.poll.PollFragment;
import com.nexachat.feature.reminder.ReminderFragment;
import dagger.hilt.android.AndroidEntryPoint;

/**
 * One board destination for a conversation: the shared reminder list and, for
 * groups, the poll list. Both tabs stay alive but only the visible one is
 * resumed, so the poll change feed stops while the reminder tab is shown.
 */
@AndroidEntryPoint
public final class GroupBoardFragment extends Fragment {

    public static final String ARG_CONVERSATION_ID = "conversationId";
    public static final String ARG_IS_GROUP = "isGroup";
    private static final String TAG_REMINDERS = "board_reminders";
    private static final String TAG_POLLS = "board_polls";
    private static final String STATE_TAB = "board.tab";

    private FragmentGroupBoardBinding binding;
    private int selectedTab;

    public GroupBoardFragment() {
        super(R.layout.fragment_group_board);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentGroupBoardBinding.bind(view);
        binding.boardToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        binding.boardTabs.addTab(binding.boardTabs.newTab()
                .setText(R.string.group_board_tab_reminders), true);
        binding.boardTabs.addTab(binding.boardTabs.newTab()
                .setText(R.string.group_board_tab_polls));
        selectedTab = savedInstanceState == null
                ? 0
                : savedInstanceState.getInt(STATE_TAB, 0);
        if (savedInstanceState == null) {
            addTabs();
        }
        binding.boardTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                selectedTab = tab.getPosition();
                showTab(selectedTab);
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        TabLayout.Tab tab = binding.boardTabs.getTabAt(selectedTab);
        if (tab != null && !tab.isSelected()) {
            tab.select();
        } else {
            showTab(selectedTab);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_TAB, selectedTab);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void addTabs() {
        Bundle args = new Bundle();
        args.putString(ARG_CONVERSATION_ID, argumentsConversationId());
        args.putBoolean(ARG_IS_GROUP, argumentsIsGroup());
        FragmentManager manager = getChildFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();
        transaction.add(R.id.boardContent, ReminderFragment.class, args, TAG_REMINDERS);
        transaction.add(R.id.boardContent, PollFragment.class, args, TAG_POLLS);
        transaction.commitNow();
        Fragment reminders = manager.findFragmentByTag(TAG_REMINDERS);
        Fragment polls = manager.findFragmentByTag(TAG_POLLS);
        if (reminders != null && polls != null) {
            manager.beginTransaction()
                    .hide(polls)
                    .setMaxLifecycle(reminders, Lifecycle.State.RESUMED)
                    .setMaxLifecycle(polls, Lifecycle.State.STARTED)
                    .commitNow();
        }
    }

    private void showTab(int position) {
        FragmentManager manager = getChildFragmentManager();
        Fragment reminders = manager.findFragmentByTag(TAG_REMINDERS);
        Fragment polls = manager.findFragmentByTag(TAG_POLLS);
        if (reminders == null || polls == null) {
            return;
        }
        boolean showPolls = position == 1;
        Fragment visible = showPolls ? polls : reminders;
        Fragment hidden = showPolls ? reminders : polls;
        manager.beginTransaction()
                .show(visible)
                .hide(hidden)
                .setMaxLifecycle(visible, Lifecycle.State.RESUMED)
                .setMaxLifecycle(hidden, Lifecycle.State.STARTED)
                .commit();
    }

    @NonNull
    private String argumentsConversationId() {
        return getArguments() == null
                ? ""
                : getArguments().getString(ARG_CONVERSATION_ID, "");
    }

    private boolean argumentsIsGroup() {
        return getArguments() != null && getArguments().getBoolean(ARG_IS_GROUP, false);
    }
}
