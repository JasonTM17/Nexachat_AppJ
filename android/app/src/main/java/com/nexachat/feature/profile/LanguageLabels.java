package com.nexachat.feature.profile;

import androidx.annotation.StringRes;

import com.nexachat.R;
import com.nexachat.core.common.LanguageOption;

/** Resource labels for the language options shown in profile settings. */
public final class LanguageLabels {

    private LanguageLabels() {}

    @StringRes
    public static int label(LanguageOption option) {
        switch (option) {
            case SYSTEM:
                return R.string.language_system_default;
            case VIETNAMESE:
                return R.string.language_name_vietnamese;
            case ENGLISH:
                return R.string.language_name_english;
            default:
                throw new IllegalArgumentException("Unsupported language option: " + option);
        }
    }
}
