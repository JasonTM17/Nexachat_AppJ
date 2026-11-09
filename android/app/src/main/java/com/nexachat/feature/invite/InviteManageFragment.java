package com.nexachat.feature.invite;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.databinding.FragmentInviteManageBinding;
import dagger.hilt.android.AndroidEntryPoint;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Group administrators create, share, rotate, and revoke invite links. */
@AndroidEntryPoint
public final class InviteManageFragment extends Fragment {
    private FragmentInviteManageBinding binding;
    private InviteManageViewModel viewModel;
    private InviteRequestAdapter requestAdapter;
    private int expiryDays = 7;
    private int maxUses;
    private boolean approvalDraft;

    @Nullable @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentInviteManageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(InviteManageViewModel.class);
        requestAdapter = new InviteRequestAdapter((id, approve) -> {
            if (approve) viewModel.approve(id); else viewModel.reject(id);
        });
        binding.requestList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.requestList.setAdapter(requestAdapter);
        binding.toolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        binding.expiryButton.setOnClickListener(ignored -> showExpiryOptions());
        binding.maxUsesButton.setOnClickListener(ignored -> showMaxUsesOptions());
        updateOptionLabels();
        binding.createLinkButton.setOnClickListener(ignored -> createOrRotate());
        binding.approvalSwitch.setOnCheckedChangeListener((button, checked) -> {
            approvalDraft = checked;
            InviteManageUiState current = viewModel.getState().getValue();
            if (current != null && current.getLink() != null) {
                viewModel.setRequiresApproval(checked);
            }
        });
        binding.copyButton.setOnClickListener(ignored -> copyLink());
        binding.shareButton.setOnClickListener(ignored -> shareLink());
        binding.revokeButton.setOnClickListener(ignored -> confirmRevoke());
        binding.refreshRequestsButton.setOnClickListener(ignored -> viewModel.refreshRequests());
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);

        String conversationId = getArguments() == null
                ? null : getArguments().getString("conversationId");
        if (conversationId != null) {
            viewModel.load(conversationId);
        }
    }

    private void render(@NonNull InviteManageUiState state) {
        if (binding == null) return;
        binding.loading.setVisibility(state.isLoading() ? View.VISIBLE : View.GONE);
        InviteLinkResponse link = state.getLink();
        boolean hasLink = link != null;
        if (hasLink) {
            approvalDraft = link.requiresApproval;
        }
        binding.linkStatus.setText(hasLink ? R.string.invite_active_link : R.string.invite_no_link);
        binding.createLinkButton.setText(hasLink ? R.string.invite_rotate : R.string.invite_create);
        binding.createLinkButton.setEnabled(!state.isBusy());
        binding.expiryButton.setEnabled(!state.isBusy());
        binding.maxUsesButton.setEnabled(!state.isBusy());
        binding.approvalSwitch.setEnabled(!state.isBusy());
        binding.approvalSwitch.setOnCheckedChangeListener(null);
        binding.approvalSwitch.setChecked(hasLink ? link.requiresApproval : approvalDraft);
        binding.approvalSwitch.setOnCheckedChangeListener((button, checked) -> {
            approvalDraft = checked;
            InviteManageUiState current = viewModel.getState().getValue();
            if (current != null && current.getLink() != null) {
                viewModel.setRequiresApproval(checked);
            }
        });

        if (hasLink) {
            if (link.maxUses == null) {
                binding.linkUsage.setText(getString(R.string.invite_usage_unlimited, link.useCount));
            } else {
                binding.linkUsage.setText(getString(
                        R.string.invite_usage_limited, link.useCount, link.maxUses));
            }
            binding.linkUsage.setVisibility(View.VISIBLE);
            binding.linkExpiry.setVisibility(View.VISIBLE);
            binding.linkExpiry.setText(expiryText(link.expiresAt));
            binding.revokeButton.setVisibility(View.VISIBLE);
        } else {
            binding.linkUsage.setVisibility(View.GONE);
            binding.linkExpiry.setVisibility(View.GONE);
            binding.revokeButton.setVisibility(View.GONE);
        }

        String shareUrl = state.getShareUrl();
        boolean canShare = shareUrl != null;
        binding.shareUrl.setVisibility(canShare ? View.VISIBLE : View.GONE);
        binding.shareUrl.setText(canShare ? shareUrl : "");
        binding.shareActions.setVisibility(canShare ? View.VISIBLE : View.GONE);
        binding.shareHint.setVisibility(hasLink && !canShare ? View.VISIBLE : View.GONE);
        binding.copyButton.setEnabled(canShare && !state.isBusy());
        binding.shareButton.setEnabled(canShare && !state.isBusy());
        binding.revokeButton.setEnabled(hasLink && !state.isBusy());

        InviteFailure failure = state.getFailure();
        binding.errorText.setVisibility(failure == null ? View.GONE : View.VISIBLE);
        if (failure != null) {
            binding.errorText.setText(InviteFailureText.resource(failure));
        }
        List<JoinRequestResponse> requests = state.getRequests();
        requestAdapter.submitList(requests);
        requestAdapter.setActionsEnabled(!state.isBusy());
        binding.emptyRequests.setVisibility(requests.isEmpty() ? View.VISIBLE : View.GONE);
        binding.refreshRequestsButton.setEnabled(!state.isBusy());
    }

    private void showExpiryOptions() {
        String[] labels = {
                getString(R.string.invite_expiry_never),
                getString(R.string.invite_expiry_one_day),
                getString(R.string.invite_expiry_one_week),
                getString(R.string.invite_expiry_thirty_days)
        };
        int selected = switch (expiryDays) {
            case 0 -> 0;
            case 1 -> 1;
            case 30 -> 3;
            default -> 2;
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.invite_expiry)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    expiryDays = switch (which) {
                        case 0 -> 0;
                        case 1 -> 1;
                        case 3 -> 30;
                        default -> 7;
                    };
                    updateOptionLabels();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showMaxUsesOptions() {
        String[] labels = {
                getString(R.string.invite_uses_unlimited),
                getString(R.string.invite_uses_one),
                getString(R.string.invite_uses_ten),
                getString(R.string.invite_uses_fifty)
        };
        int selected = switch (maxUses) {
            case 1 -> 1;
            case 10 -> 2;
            case 50 -> 3;
            default -> 0;
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.invite_max_uses)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    maxUses = switch (which) {
                        case 1 -> 1;
                        case 2 -> 10;
                        case 3 -> 50;
                        default -> 0;
                    };
                    updateOptionLabels();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void createOrRotate() {
        String expiresAt = expiryDays == 0
                ? null : Instant.now().plus(Duration.ofDays(expiryDays)).toString();
        Integer max = maxUses == 0 ? null : maxUses;
        viewModel.createOrRotate(expiresAt, max, approvalDraft);
    }

    private void updateOptionLabels() {
        if (binding == null) return;
        String expiry = switch (expiryDays) {
            case 0 -> getString(R.string.invite_expiry_never);
            case 1 -> getString(R.string.invite_expiry_one_day);
            case 30 -> getString(R.string.invite_expiry_thirty_days);
            default -> getString(R.string.invite_expiry_one_week);
        };
        String uses = switch (maxUses) {
            case 1 -> getString(R.string.invite_uses_one);
            case 10 -> getString(R.string.invite_uses_ten);
            case 50 -> getString(R.string.invite_uses_fifty);
            default -> getString(R.string.invite_uses_unlimited);
        };
        binding.expiryButton.setText(getString(R.string.invite_expiry_value, expiry));
        binding.maxUsesButton.setText(getString(R.string.invite_max_uses_value, uses));
    }

    private void copyLink() {
        InviteManageUiState state = viewModel.getState().getValue();
        String url = state == null ? null : state.getShareUrl();
        if (url == null) return;
        ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(getString(R.string.invite_manage_title), url);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        Toast.makeText(requireContext(), R.string.invite_copy_done, Toast.LENGTH_SHORT).show();
    }

    private void shareLink() {
        InviteManageUiState state = viewModel.getState().getValue();
        String url = state == null ? null : state.getShareUrl();
        if (url == null) return;
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(share, getString(R.string.invite_share_chooser)));
    }

    private void confirmRevoke() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.invite_revoke_title)
                .setMessage(R.string.invite_revoke_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.invite_revoke, (dialog, which) -> viewModel.revoke())
                .show();
    }

    private String expiryText(@Nullable String value) {
        if (value == null) return getString(R.string.invite_expiry_never);
        try {
            java.util.Date date = java.util.Date.from(Instant.parse(value));
            String formatted = android.text.format.DateFormat
                    .getMediumDateFormat(requireContext()).format(date);
            return getString(R.string.invite_expiry_line, formatted);
        } catch (DateTimeParseException invalid) {
            return getString(R.string.invite_expiry_never);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        requestAdapter = null;
    }
}
