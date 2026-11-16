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

/** Composer attach sheet: visual media or document. Restored through the child FragmentManager. */
public final class AttachmentPickerSheet extends NexaBottomSheetFragment {

    public static final String TAG = "attachment_picker_sheet";

    /** Launches the host's pickers; results land on the host's launchers. */
    public interface Host {
        void onPickVisual();
        void onPickDocument();
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
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
