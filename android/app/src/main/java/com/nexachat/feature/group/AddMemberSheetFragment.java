package com.nexachat.feature.group;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.nexachat.core.ui.GroupDetailListRules;
import com.nexachat.databinding.BottomSheetGroupAddMemberBinding;
import com.nexachat.feature.group.adapter.SelectableContactAdapter;
import com.nexachat.feature.ui.NexaBottomSheetFragment;

/** Add-member picker sheet; restored through the host fragment's child FragmentManager. */
public final class AddMemberSheetFragment extends NexaBottomSheetFragment {

    public static final String TAG = "add_member_sheet";

    private BottomSheetGroupAddMemberBinding binding;
    private GroupDetailViewModel viewModel;
    private SelectableContactAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = BottomSheetGroupAddMemberBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Fragment parent = getParentFragment();
        if (!(parent instanceof GroupDetailFragment)) {
            dismissAllowingStateLoss();
            return;
        }
        viewModel = new ViewModelProvider(parent).get(GroupDetailViewModel.class);
        adapter = new SelectableContactAdapter(viewModel::toggleAddCandidate);
        binding.addMemberList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.addMemberList.setAdapter(adapter);
        binding.addMemberConfirm.setOnClickListener(v -> viewModel.addSelectedMembers());
        binding.addMemberInviteLink.setOnClickListener(v -> {
            dismiss();
            ((GroupDetailFragment) parent).openInviteManager();
        });
        applySheetInsets(binding.getRoot());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    /**
     * An empty addable list means every contact is already a member: the
     * disabled confirm is swapped for an explanation and an invite-link
     * shortcut. Only applied once the contacts result has arrived; while the
     * fetch is in flight the sheet shows an empty list with a disabled
     * confirm instead of a false empty state.
     */
    private void render(@NonNull GroupDetailUiState state) {
        if (binding == null) {
            return;
        }
        if (!state.isShowAddPicker()) {
            dismiss();
            return;
        }
        adapter.submit(state.getAddableContacts(), state.getSelectedAddIds());
        boolean noAddable = GroupDetailListRules.showAddMemberEmptyState(
                state.isAddableContactsLoaded(), state.getAddableContacts().isEmpty());
        binding.addMemberList.setVisibility(noAddable ? View.GONE : View.VISIBLE);
        binding.addMemberEmptyState.setVisibility(noAddable ? View.VISIBLE : View.GONE);
        binding.addMemberEmpty.setVisibility(noAddable ? View.VISIBLE : View.GONE);
        binding.addMemberInviteLink.setVisibility(noAddable ? View.VISIBLE : View.GONE);
        binding.addMemberConfirm.setVisibility(noAddable ? View.GONE : View.VISIBLE);
        binding.addMemberConfirm.setEnabled(!state.getSelectedAddIds().isEmpty() && !state.isBusy());
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        Activity activity = getActivity();
        if (viewModel != null && (activity == null || !activity.isChangingConfigurations())) {
            viewModel.dismissAddMember();
        }
        super.onDismiss(dialog);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        adapter = null;
        super.onDestroyView();
    }
}
