package com.nexachat.feature.chat;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import com.nexachat.R;
import java.util.List;

/** Supported translation targets shown in the language picker. */
public final class TranslationLanguages {

    public record Entry(@NonNull String code, @StringRes int nameRes) {
    }

    public static final List<Entry> SUPPORTED = List.of(
            new Entry("vi", R.string.lang_vi),
            new Entry("en", R.string.lang_en),
            new Entry("zh", R.string.lang_zh),
            new Entry("ja", R.string.lang_ja),
            new Entry("ko", R.string.lang_ko),
            new Entry("fr", R.string.lang_fr),
            new Entry("de", R.string.lang_de),
            new Entry("es", R.string.lang_es),
            new Entry("th", R.string.lang_th),
            new Entry("id", R.string.lang_id),
            new Entry("ru", R.string.lang_ru),
            new Entry("ar", R.string.lang_ar),
            new Entry("pt", R.string.lang_pt),
            new Entry("hi", R.string.lang_hi));

    @StringRes
    public static int nameRes(@NonNull String code) {
        for (Entry entry : SUPPORTED) {
            if (entry.code().equals(code)) {
                return entry.nameRes();
            }
        }
        return 0;
    }

    /** True when the server accepts {@code code} as a translation target. */
    public static boolean isSupported(@androidx.annotation.Nullable String code) {
        return code != null && nameRes(code) != 0;
    }

    private TranslationLanguages() {
    }
}
