package com.nexachat.feature.feed;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

/**
 * The one-time public-feed notice. It is a fragment so it survives rotation, and the device flag
 * is set only once the user acknowledges it: the button, back or a tap outside all count.
 */
@AndroidEntryPoint
public final class FeedNoticeDialogFragment extends DialogFragment {

    public static final String TAG = "feed_notice_dialog";

    @Inject FeedNoticeStore noticeStore;

    /** Shows the notice unless it was acknowledged, is already attached or state is saved. */
    public static void showIfNeeded(
            @NonNull FragmentManager manager, @NonNull FeedNoticeStore store) {
        if (!FeedNoticeRules.shouldShow(
                store.isNoticeSeen(),
                manager.findFragmentByTag(TAG) != null,
                manager.isStateSaved())) {
            return;
        }
        new FeedNoticeDialogFragment().show(manager, TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.feed_notice_title)
                .setMessage(R.string.feed_notice_body)
                .setPositiveButton(R.string.feed_notice_ok,
                        (dialog, which) -> noticeStore.markNoticeSeen())
                .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        noticeStore.markNoticeSeen();
        super.onCancel(dialog);
    }
}
