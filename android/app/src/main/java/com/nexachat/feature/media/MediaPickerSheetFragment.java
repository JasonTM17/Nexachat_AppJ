package com.nexachat.feature.media;

import android.app.Dialog; import android.os.Bundle; import android.view.View; import android.widget.LinearLayout; import android.widget.TextView; import androidx.activity.result.ActivityResultLauncher; import androidx.activity.result.contract.ActivityResultContracts; import androidx.annotation.NonNull; import androidx.annotation.Nullable; import com.google.android.material.bottomsheet.BottomSheetDialog; import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/** Document and visual attachment picker using the system document providers. */
public final class MediaPickerSheetFragment extends BottomSheetDialogFragment {
    public interface Listener { void onAttachmentPicked(@NonNull android.net.Uri uri, @Nullable String mimeType); }
    private Listener listener;
    private final ActivityResultLauncher<String[]> visualPicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->deliver(uri));
    private final ActivityResultLauncher<String> filePicker=registerForActivityResult(new ActivityResultContracts.GetContent(),uri->deliver(uri));
    public void setListener(@NonNull Listener value){listener=value;}
    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state){BottomSheetDialog dialog=new BottomSheetDialog(requireContext());LinearLayout root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,20,24,20);root.addView(option("Photo or video",v->{dismiss();visualPicker.launch(new String[]{"image/*","video/*"});}));root.addView(option("File or audio",v->{dismiss();filePicker.launch("*/*");}));dialog.setContentView(root);return dialog;}
    private View option(String text,View.OnClickListener click){TextView row=new TextView(requireContext());row.setText(text);row.setTextSize(16);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setMinHeight((int)(56*getResources().getDisplayMetrics().density));row.setOnClickListener(click);return row;}
    private void deliver(@Nullable android.net.Uri uri){if(uri!=null&&listener!=null)listener.onAttachmentPicked(uri,requireContext().getContentResolver().getType(uri));}
}
