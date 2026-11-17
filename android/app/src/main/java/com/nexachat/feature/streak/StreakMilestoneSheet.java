package com.nexachat.feature.streak;

import android.animation.ValueAnimator;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.nexachat.R;
import com.nexachat.databinding.SheetStreakMilestoneBinding;
import com.nexachat.feature.ui.NexaBottomSheetFragment;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

/** One-time celebration for a streak milestone: flame, title, one line and a Done button. */
@AndroidEntryPoint
public final class StreakMilestoneSheet extends NexaBottomSheetFragment {

    public static final String TAG = "streak_milestone_sheet";
    /** Fragment result sent to the parent when the sheet closes. */
    public static final String RESULT_DISMISSED = "streak_milestone_dismissed";

    private static final String ARG_MILESTONE = "milestone";
    private static final long ENTER_MS = 450L;

    @Inject MilestoneTracker tracker;

    private SheetStreakMilestoneBinding binding;

    /** Shows the sheet unless one is already up; returns whether it was shown. */
    public static boolean show(@NonNull FragmentManager manager, @NonNull MilestoneTracker.Celebration celebration) {
        if (manager.isStateSaved() || manager.findFragmentByTag(TAG) != null) {
            return false;
        }
        StreakMilestoneSheet sheet = new StreakMilestoneSheet();
        Bundle args = new Bundle();
        args.putInt(ARG_MILESTONE, celebration.getMilestone());
        sheet.setArguments(args);
        sheet.show(manager, TAG);
        return true;
    }

    /**
     * Shows queued milestones from {@code viewModel} on {@code host} and tells the model when the
     * sheet closes so the next one may follow.
     */
    public static void bind(@NonNull Fragment host, @NonNull StreakViewModel viewModel) {
        host.getChildFragmentManager().setFragmentResultListener(
                RESULT_DISMISSED, host.getViewLifecycleOwner(),
                (key, result) -> viewModel.onCelebrationDismissed());
        viewModel.getCelebration().observe(host.getViewLifecycleOwner(), celebration -> {
            if (celebration == null) {
                return;
            }
            boolean shown = show(host.getChildFragmentManager(), celebration);
            viewModel.onCelebrationShown();
            if (!shown) {
                viewModel.onCelebrationDismissed();
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SheetStreakMilestoneBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        int milestone = requireArguments().getInt(ARG_MILESTONE);
        binding.streakMilestoneTitle.setText(getResources()
                .getQuantityString(R.plurals.streak_milestone_title, milestone, milestone));
        binding.streakMilestoneDone.setOnClickListener(ignored -> dismissAllowingStateLoss());
        if (savedInstanceState == null) {
            playEntrance();
        }
    }

    /** A calm fade and settle; skipped when the user turned animations off. */
    private void playEntrance() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        binding.streakMilestoneFlame.setAlpha(0f);
        binding.streakMilestoneFlame.setScaleX(0.85f);
        binding.streakMilestoneFlame.setScaleY(0.85f);
        binding.streakMilestoneFlame.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ENTER_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        tracker.dismissed();
        getParentFragmentManager().setFragmentResult(RESULT_DISMISSED, new Bundle());
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.streakMilestoneFlame.animate().cancel();
        }
        binding = null;
        super.onDestroyView();
    }
}
