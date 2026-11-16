package com.nexachat.feature.chat;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.nexachat.data.ConversationSummary;
import com.nexachat.databinding.DialogForwardBinding;
import com.nexachat.feature.chat.adapter.ForwardTargetAdapter;
import com.nexachat.feature.ui.NexaBottomSheetFragment;
import java.util.ArrayList;
import java.util.List;

/** Conversation picker for forwarding a message; restored through the child FragmentManager. */
public final class ForwardPickerSheet extends NexaBottomSheetFragment {

    public static final String TAG = "forward_picker_sheet";

    private static final String ARG_MESSAGE_ID = "messageId";
    private static final String STATE_SELECTED_ID = "forward.selectedId";

    /** Supplies fresh inbox rows and receives the chosen target. */
    public interface Host {
        @NonNull List<ConversationSummary> forwardTargets();
        void onForwardTarget(@NonNull String messageId, @NonNull String targetId);
    }

    private DialogForwardBinding binding;
    private ForwardTargetAdapter adapter;
    @Nullable private String selectedId;

    @NonNull
    public static ForwardPickerSheet newInstance(@NonNull String messageId) {
        ForwardPickerSheet sheet = new ForwardPickerSheet();
        Bundle args = new Bundle();
        args.putString(ARG_MESSAGE_ID, messageId);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    private Host host() {
        Fragment parent = getParentFragment();
        return parent instanceof Host ? (Host) parent : null;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = DialogForwardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Host host = host();
        if (host == null) {
            dismissAllowingStateLoss();
            return;
        }
        if (savedInstanceState != null) {
            selectedId = savedInstanceState.getString(STATE_SELECTED_ID);
        }
        List<ConversationSummary> conversations = host.forwardTargets();
        binding.forwardList.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ForwardTargetAdapter(id -> {
            selectedId = id;
            binding.forwardSend.setEnabled(true);
        });
        binding.forwardList.setAdapter(adapter);
        adapter.setSelectedId(selectedId);
        adapter.submitList(new ArrayList<>(conversations));
        binding.forwardSend.setEnabled(selectedId != null);
        binding.forwardEmpty.setVisibility(conversations.isEmpty() ? View.VISIBLE : View.GONE);
        binding.forwardSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                List<ConversationSummary> filtered = new ArrayList<>();
                String needle = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (ConversationSummary item : conversations) {
                    if (item.getDisplayName() != null && item.getDisplayName().toLowerCase(java.util.Locale.ROOT).contains(needle)) filtered.add(item);
                }
                // A filtered-out recipient must not remain a hidden send target.
                if (filtered.stream().noneMatch(item -> item.getId().equals(selectedId))) {
                    selectedId = null;
                    adapter.setSelectedId(null);
                    binding.forwardSend.setEnabled(false);
                }
                binding.forwardEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.submitList(filtered);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        binding.forwardSend.setOnClickListener(v -> {
            String target = selectedId;
            Host callback = host();
            if (target != null && binding.forwardSend.isEnabled() && callback != null) {
                binding.forwardSend.setEnabled(false);
                dismiss();
                String messageId = getArguments() == null
                        ? "" : getArguments().getString(ARG_MESSAGE_ID, "");
                callback.onForwardTarget(messageId, target);
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog bottomSheetDialog) {
            FrameLayout bottomSheet = bottomSheetDialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                ViewGroup.LayoutParams params = bottomSheet.getLayoutParams();
                params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.8f);
                bottomSheet.setLayoutParams(params);
                bottomSheet.setBackgroundResource(android.R.color.transparent);
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SELECTED_ID, selectedId);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        adapter = null;
        super.onDestroyView();
    }
}
