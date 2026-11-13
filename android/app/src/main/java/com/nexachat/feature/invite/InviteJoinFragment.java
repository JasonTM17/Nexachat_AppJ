package com.nexachat.feature.invite;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.databinding.FragmentInviteJoinBinding;
import dagger.hilt.android.AndroidEntryPoint;

/** Safe preview and join destination for supported invite links. */
@AndroidEntryPoint
public final class InviteJoinFragment extends Fragment {
    private FragmentInviteJoinBinding binding;
    private InviteJoinViewModel viewModel;

    @Nullable @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentInviteJoinBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(InviteJoinViewModel.class);
        binding.toolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        binding.joinButton.setOnClickListener(ignored -> viewModel.join());
        binding.checkStatusButton.setOnClickListener(ignored -> viewModel.refreshStatus());
        binding.retryButton.setOnClickListener(ignored -> viewModel.refresh());
        binding.signInButton.setOnClickListener(ignored -> {
            Bundle args = new Bundle();
            args.putString("returnInviteToken", viewModel.inviteTokenForSignIn());
            NavHostFragment.findNavController(this).navigate(R.id.action_global_login, args);
        });
        binding.openChatButton.setOnClickListener(ignored -> openChat());
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);

        String tokenArgument = getArguments() == null
                ? null : getArguments().getString("token");
        String rawUrl = tokenArgument == null
                ? requireActivity().getIntent().getDataString()
                : InviteLinkParser.shareUrl(tokenArgument, null).orElse(null);
        viewModel.open(rawUrl);
    }

    private void render(@NonNull InviteJoinUiState state) {
        if (binding == null) return;
        InvitePreviewResponse preview = state.getPreview();
        boolean hasPreview = preview != null;
        binding.groupAvatar.setVisibility(hasPreview ? View.VISIBLE : View.GONE);
        binding.groupTitle.setVisibility(hasPreview ? View.VISIBLE : View.GONE);
        binding.memberCount.setVisibility(hasPreview ? View.VISIBLE : View.GONE);
        binding.approvalNote.setVisibility(
                hasPreview && preview.requiresApproval ? View.VISIBLE : View.GONE);
        if (hasPreview) {
            binding.groupAvatar.setName(preview.title);
            binding.groupAvatar.setAvatarUrl(preview.avatarUrl);
            binding.groupTitle.setText(preview.title);
            binding.memberCount.setText(
                    com.nexachat.res.QuantityStrings.inviteMemberCount(
                            getResources(), preview.memberCount));
        }

        InviteJoinUiState.Status status = state.getStatus();
        boolean loadingVisible = state.isBusy() || status == InviteJoinUiState.Status.LOADING;
        binding.loading.setVisibility(loadingVisible ? View.VISIBLE : View.GONE);
        binding.loading.setContentDescription(
                loadingVisible ? getString(R.string.voice_status_loading) : null);
        binding.joinButton.setVisibility(status == InviteJoinUiState.Status.PREVIEW
                ? View.VISIBLE : View.GONE);
        binding.joinButton.setText(preview != null && preview.requiresApproval
                ? R.string.invite_request_button : R.string.invite_join_button);
        binding.joinButton.setEnabled(!state.isBusy());
        binding.checkStatusButton.setVisibility(status == InviteJoinUiState.Status.PENDING
                ? View.VISIBLE : View.GONE);
        binding.checkStatusButton.setEnabled(!state.isBusy());
        boolean canOpenChat = status == InviteJoinUiState.Status.ALREADY_MEMBER
                || status == InviteJoinUiState.Status.JOINED
                || status == InviteJoinUiState.Status.APPROVED;
        binding.openChatButton.setVisibility(canOpenChat ? View.VISIBLE : View.GONE);

        int statusResource = switch (status) {
            case ALREADY_MEMBER -> R.string.invite_status_already_member;
            case JOINED -> R.string.invite_status_joined;
            case PENDING -> R.string.invite_status_pending;
            case APPROVED -> R.string.invite_status_approved;
            case REJECTED -> R.string.invite_status_rejected;
            default -> 0;
        };
        InviteFailure failure = state.getFailure();
        if (failure != null) {
            binding.statusText.setText(InviteFailureText.resource(failure));
            binding.statusText.setVisibility(View.VISIBLE);
        } else if (statusResource != 0) {
            binding.statusText.setText(statusResource);
            binding.statusText.setVisibility(View.VISIBLE);
        } else {
            binding.statusText.setVisibility(View.GONE);
        }
        binding.retryButton.setVisibility(status == InviteJoinUiState.Status.ERROR
                && failure != InviteFailure.SIGN_IN_REQUIRED
                && failure != InviteFailure.NOT_ALLOWED ? View.VISIBLE : View.GONE);
        binding.signInButton.setVisibility(failure == InviteFailure.SIGN_IN_REQUIRED
                ? View.VISIBLE : View.GONE);
    }

    private void openChat() {
        InviteJoinUiState state = viewModel.getState().getValue();
        String conversationId = state == null ? null : state.getConversationId();
        if (conversationId == null) return;
        Bundle args = new Bundle();
        args.putString("conversationId", conversationId);
        NavHostFragment.findNavController(this).navigate(R.id.chatFragment, args);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
