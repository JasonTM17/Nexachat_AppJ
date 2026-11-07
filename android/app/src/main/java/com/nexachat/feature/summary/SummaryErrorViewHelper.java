package com.nexachat.feature.summary;

import android.view.View; import android.widget.TextView; import androidx.annotation.NonNull; import androidx.annotation.Nullable; import com.nexachat.R; import com.nexachat.summary.SummaryError;

/** Binds a summary error and retry action to the shared error include. */
public final class SummaryErrorViewHelper {
    private SummaryErrorViewHelper() {}
    public static void bind(@NonNull View root,@Nullable SummaryError error,@NonNull Runnable retry){TextView message=root.findViewById(R.id.summaryErrorText);if(message!=null)message.setText(R.string.error_generic);View button=root.findViewById(R.id.summaryRetry);if(button!=null)button.setOnClickListener(v->retry.run());}
}
