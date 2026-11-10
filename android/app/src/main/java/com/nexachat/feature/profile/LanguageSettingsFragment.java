package com.nexachat.feature.profile;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.core.common.AppLocaleManager;
import com.nexachat.core.common.LanguageOption;
import com.nexachat.databinding.FragmentLanguageSettingsBinding;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class LanguageSettingsFragment extends Fragment {
    private FragmentLanguageSettingsBinding binding;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentLanguageSettingsBinding.inflate(inflater, container, false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        binding.languageToolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        LanguageOption selected = AppLocaleManager.current(requireContext());
        LanguageOption[] options = {LanguageOption.SYSTEM, LanguageOption.VIETNAMESE, LanguageOption.ENGLISH};
        int[] labels = {R.string.language_system_default, R.string.language_name_vietnamese, R.string.language_name_english};
        int padH = getResources().getDimensionPixelSize(R.dimen.space_md);
        int padV = getResources().getDimensionPixelSize(R.dimen.space_sm);
        for (int i = 0; i < options.length; i++) {
            RadioButton button = new RadioButton(requireContext());
            button.setId(View.generateViewId());
            button.setText(labels[i]);
            button.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            button.setMinHeight(getResources().getDimensionPixelSize(R.dimen.min_touch_target));
            button.setPadding(padH, padV, padH, padV);
            button.setChecked(selected == options[i]);
            LanguageOption option = options[i];
            binding.languageOptions.addView(
                    button,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            button.setOnClickListener(v -> AppLocaleManager.apply(requireContext(), option));
        }
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
