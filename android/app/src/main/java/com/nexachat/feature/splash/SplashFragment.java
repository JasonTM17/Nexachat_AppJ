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

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentSplashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(SplashViewModel.class);
        viewModel.getNavEvents().observe(getViewLifecycleOwner(), event -> {
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
        });
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
