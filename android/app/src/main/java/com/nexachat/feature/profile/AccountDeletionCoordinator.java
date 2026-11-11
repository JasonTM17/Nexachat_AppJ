package com.nexachat.feature.profile;

import androidx.annotation.NonNull;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Holds in-flight account deletion work so ViewModel teardown cannot cancel the
 * post-204 local session wipe.
 */
@Singleton
public final class AccountDeletionCoordinator {

    private final CompositeDisposable retained = new CompositeDisposable();

    @Inject
    public AccountDeletionCoordinator() {}

    public void retain(@NonNull Disposable disposable) {
        Objects.requireNonNull(disposable, "disposable");
        retained.add(disposable);
    }
}
