package com.nexachat.feature.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.nexachat.R;
import com.nexachat.databinding.SheetSharedContentBinding;
import com.nexachat.feature.ui.NexaBottomSheetFragment;

/**
 * Confirmation step for sharing location or contact: the payload is shown
 * before anything is sent, and only an explicit Send commits it. Location
 * offers an optional label field; contact sends name and phone as picked.
 */
public final class SharedContentSheet extends NexaBottomSheetFragment {

    public static final String TAG = "shared_content_sheet";

    private static final String ARG_KIND = "kind";
    private static final String ARG_LAT = "lat";
    private static final String ARG_LON = "lon";
    private static final String ARG_NAME = "name";
    private static final String ARG_PHONE = "phone";
    private static final int KIND_LOCATION = 1;
    private static final int KIND_CONTACT = 2;

    /** Receives the confirmed payload; the host owns the actual send. */
    public interface Host {
        void onShareLocationConfirmed(double latitude, double longitude, @Nullable String label);
        void onShareContactConfirmed(@NonNull String name, @NonNull String phone);
    }

    @NonNull
    public static SharedContentSheet forLocation(double latitude, double longitude) {
        Bundle args = new Bundle();
        args.putInt(ARG_KIND, KIND_LOCATION);
        args.putDouble(ARG_LAT, latitude);
        args.putDouble(ARG_LON, longitude);
        SharedContentSheet sheet = new SharedContentSheet();
        sheet.setArguments(args);
        return sheet;
    }

    @NonNull
    public static SharedContentSheet forContact(@NonNull String name, @NonNull String phone) {
        Bundle args = new Bundle();
        args.putInt(ARG_KIND, KIND_CONTACT);
        args.putString(ARG_NAME, name);
        args.putString(ARG_PHONE, phone);
        SharedContentSheet sheet = new SharedContentSheet();
        sheet.setArguments(args);
        return sheet;
    }

    private SheetSharedContentBinding binding;

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
        binding = SheetSharedContentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        int kind = args == null ? 0 : args.getInt(ARG_KIND, 0);
        if (kind == KIND_LOCATION) {
            bindLocation(args);
        } else if (kind == KIND_CONTACT) {
            bindContact(args);
        } else {
            dismiss();
            return;
        }
        binding.sharedCancel.setOnClickListener(v -> dismiss());
        applySheetInsets(view);
    }

    private void bindLocation(@NonNull Bundle args) {
        double latitude = args.getDouble(ARG_LAT);
        double longitude = args.getDouble(ARG_LON);
        binding.sharedTitle.setText(R.string.share_location_title);
        binding.sharedIcon.setImageResource(R.drawable.ic_location);
        binding.sharedPrimary.setText(R.string.shared_location_title);
        binding.sharedSecondary.setText(
                SharedContent.formatCoordinate(latitude)
                        + ", "
                        + SharedContent.formatCoordinate(longitude));
        binding.sharedSend.setOnClickListener(v -> {
            CharSequence label = binding.sharedLabelInput.getText();
            String clean = SharedContent.sanitizeText(
                    label == null ? null : label.toString());
            dismiss();
            Host host = host();
            if (host != null) {
                host.onShareLocationConfirmed(latitude, longitude, clean.isEmpty() ? null : clean);
            }
        });
    }

    private void bindContact(@NonNull Bundle args) {
        String name = args.getString(ARG_NAME, "");
        String phone = args.getString(ARG_PHONE, "");
        binding.sharedTitle.setText(R.string.share_contact_title);
        binding.sharedIcon.setImageResource(R.drawable.ic_person_outline);
        binding.sharedPrimary.setText(name);
        binding.sharedSecondary.setText(phone);
        binding.sharedLabelLayout.setVisibility(View.GONE);
        binding.sharedSend.setOnClickListener(v -> {
            dismiss();
            Host host = host();
            if (host != null) {
                host.onShareContactConfirmed(name, phone);
            }
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
