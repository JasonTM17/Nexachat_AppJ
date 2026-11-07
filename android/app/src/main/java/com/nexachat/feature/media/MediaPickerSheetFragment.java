package com.nexachat.feature.media;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.nexachat.databinding.SheetAttachmentPickerBinding;

/**
 * Attach bottom sheet: Photo Picker for visual media and SAF for documents/audio.
 * The hosting screen owns upload; this sheet only returns the picked {@link Uri}.
 */
public final class MediaPickerSheetFragment extends BottomSheetDialogFragment {

    public interface Listener {
        void onAttachmentPicked(@NonNull Uri uri, @Nullable String mimeType);
    }

    @Nullable
    private Listener listener;

    @Nullable
    private SheetAttachmentPickerBinding binding;

    private final ActivityResultLauncher<PickVisualMediaRequest> visualPicker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(),
            this::deliverVisual);

    private final ActivityResultLauncher<String[]> documentPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::deliverDocument);

    public void setListener(@NonNull Listener value) {
        listener = value;
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
        SheetAttachmentPickerBinding sheet = binding;
        if (sheet == null) {
            return;
        }
        sheet.pickVisualRow.setOnClickListener(v ->
                visualPicker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                        .build()));
        sheet.pickDocumentRow.setOnClickListener(v ->
                documentPicker.launch(new String[]{"*/*"}));
    }

    private void deliverVisual(@Nullable Uri uri) {
        if (uri != null && isAdded()) {
            deliver(uri);
        }
        if (isAdded()) {
            dismissAllowingStateLoss();
        }
    }

    private void deliverDocument(@Nullable Uri uri) {
        if (uri != null && isAdded()) {
            try {
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // PickVisualMedia URIs are not persistable; OpenDocument may already grant access.
            }
            deliver(uri);
        }
        if (isAdded()) {
            dismissAllowingStateLoss();
        }
    }

    private void deliver(@NonNull Uri uri) {
        if (listener == null || !isAdded()) {
            return;
        }
        String mime = requireContext().getContentResolver().getType(uri);
        listener.onAttachmentPicked(uri, mime);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
