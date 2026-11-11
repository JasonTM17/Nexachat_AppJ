package com.nexachat.feature.auth;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.nexachat.R;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;

/** One-shot login screen banner consumed when the login UI becomes visible. */
@Singleton
public final class LoginNotice {
    private final AtomicReference<Integer> pendingMessageResId = new AtomicReference<>();

    @Inject
    public LoginNotice() {}

    public void setAccountDeleted() {
        pendingMessageResId.set(R.string.login_account_deleted_notice);
    }

    @StringRes
    @Nullable
    public Integer consume() {
        return pendingMessageResId.getAndSet(null);
    }
}
