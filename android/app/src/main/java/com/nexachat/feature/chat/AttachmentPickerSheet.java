package com.nexachat.feature.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.nexachat.databinding.SheetAttachmentPickerBinding;
import com.nexachat.feature.ui.NexaBottomSheetFragment;

/** Composer attach sheet: media, document, or a text-encoded shared item. Restored through the child FragmentManager. */
public final class AttachmentPickerSheet extends NexaBottomSheetFragment {

    public static final String TAG = "attachment_picker_sheet";
    private static final String ARG_MEDIA_ALLOWED = "media_allowed";

    /** Launches the host's pickers; results land on the host's launchers. */
    public interface Host {
        void onPickVisual();
        void onPickDocument();
        void onPickLocation();
        void onPickContact();
    }

    /**
     * Location and contact share as ordinary text, so they stay available in
     * E2EE conversations; media rows hide there because uploads are not yet
     * encrypted end to end.
     */
    @NonNull
    public static AttachmentPickerSheet newInstance(boolean mediaAllowed) {
        Bundle args = new Bundle();
        args.putBoolean(ARG_MEDIA_ALLOWED, mediaAllowed);
        AttachmentPickerSheet sheet = new AttachmentPickerSheet();
        sheet.setArguments(args);
        return sheet;
    }

    private SheetAttachmentPickerBinding binding;

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
        binding = SheetAttachmentPickerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        boolean mediaAllowed = args == null || args.getBoolean(ARG_MEDIA_ALLOWED, true);
        int mediaVisibility = mediaAllowed ? View.VISIBLE : View.GONE;
        binding.pickVisualRow.setVisibility(mediaVisibility);
        binding.pickDocumentRow.setVisibility(mediaVisibility);
        binding.pickVisualRow.setOnClickListener(v -> {
            dismiss();
            Host host = host();
            if (host != null) {
                host.onPickVisual();
            }
        });
        binding.pickDocumentRow.setOnClickListener(v -> {
            dismiss();
            Host host = host();
            if (host != null) {
                host.onPickDocument();
            }
        });
        binding.pickLocationRow.setOnClickListener(v -> {
            dismiss();
            Host host = host();
            if (host != null) {
                host.onPickLocation();
            }
        });
        binding.pickContactRow.setOnClickListener(v -> {
            dismiss();
            Host host = host();
            if (host != null) {
                host.onPickContact();
            }
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
