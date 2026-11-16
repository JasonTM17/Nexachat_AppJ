package com.nexachat.feature.splash;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.databinding.FragmentSplashBinding;
import com.nexachat.feature.auth.SplashNavEvent;
import com.nexachat.feature.auth.SplashViewModel;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class SplashFragment extends Fragment {
    private FragmentSplashBinding binding;
    private SplashViewModel viewModel;
    private long introEndUptime;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentSplashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        playIntro(state == null);
        viewModel = new ViewModelProvider(this).get(SplashViewModel.class);
        viewModel.getServerStarting().observe(getViewLifecycleOwner(), starting -> {
            if (binding == null) return;
            boolean visible = Boolean.TRUE.equals(starting);
            binding.splashStatus.setVisibility(visible ? View.VISIBLE : View.GONE);
        });
        viewModel.getNavEvents().observe(getViewLifecycleOwner(), event -> {
            // Let the short intro finish so a fast session check does not cut it off.
            long wait = introEndUptime - android.os.SystemClock.uptimeMillis();
            if (wait > 0 && binding != null && event != null) {
                binding.getRoot().postDelayed(() -> {
                    if (binding != null && isAdded()) handleNav(event);
                }, wait);
                return;
            }
            handleNav(event);
        });
    }

    private void handleNav(SplashNavEvent event) {
        if (event instanceof SplashNavEvent.ToInbox) {
            NavHostFragment.findNavController(this).navigate(R.id.inboxFragment, null,
                    new androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.splashFragment, true).build());
        } else if (event instanceof SplashNavEvent.ToLogin login) {
            Bundle args = null;
            if (login.showSessionExpiredMessage()) {
                args = new Bundle();
                args.putBoolean("showSessionExpired", true);
            }
            NavHostFragment.findNavController(this).navigate(
                    R.id.loginFragment,
                    args,
                    new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.splashFragment, true)
                            .build());
        }
    }

    /**
     * The system launch screen shows the full-size mark. It keeps that size and place on its first frame, then
     * shrinks and lifts while the wordmark rises beneath it and the loader fades in.
     */
    private void playIntro(boolean animate) {
        binding.splashLogo.animate().cancel();
        if (!animate) return;
        float scale = android.provider.Settings.Global.getFloat(requireContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale <= 0f) return;
        introEndUptime = android.os.SystemClock.uptimeMillis() + (long) (900 * scale);
        float lift = getResources().getDimension(R.dimen.space_xl);
        float rise = getResources().getDimension(R.dimen.space_md);
        binding.splashTitle.setAlpha(0f);
        binding.splashTitle.setTranslationY(rise);
        binding.splashProgress.setAlpha(0f);
        binding.splashProgress.setTranslationY(rise);
        androidx.interpolator.view.animation.FastOutSlowInInterpolator ease =
                new androidx.interpolator.view.animation.FastOutSlowInInterpolator();
        binding.splashLogo.animate().scaleX(0.7f).scaleY(0.7f).translationY(-lift)
                .setStartDelay(160).setDuration(560).setInterpolator(ease).start();
        binding.splashTitle.animate().alpha(1f).translationY(-lift * 1.1f)
                .setStartDelay(300).setDuration(480).setInterpolator(ease).start();
        binding.splashProgress.animate().alpha(1f).translationY(-lift * 1.1f)
                .setStartDelay(520).setDuration(360).setInterpolator(ease).start();
    }

    @Override public void onDestroyView() {
        if (binding != null) {
            binding.splashLogo.animate().cancel();
            binding.splashTitle.animate().cancel();
            binding.splashProgress.animate().cancel();
        }
        super.onDestroyView();
        binding = null;
    }
}
