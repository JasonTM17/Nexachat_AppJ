package com.nexachat.core.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

/**
 * Session tokens. DataStore holds ciphertext only; a blob that no longer
 * decrypts reads as logged out.
 */
@Singleton
public final class TokenStore implements TokenStoreApi {

    private final DataStore<Preferences> dataStore;
    private final TokenCipher cipher;
    private final Flow<Boolean> hasValidRefresh;

    @Inject
    public TokenStore(
            @TokenPreferences @NonNull DataStore<Preferences> dataStore,
            @NonNull TokenCipher cipher) {
        this.dataStore = Objects.requireNonNull(dataStore, "dataStore");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.hasValidRefresh = TokenStoreFlow.hasValidRefresh(dataStore, cipher);
    }

    @NonNull
    @Override
    public Flow<Boolean> getHasValidRefresh() {
        return hasValidRefresh;
    }

    @Override
    public Object saveTokens(
            @NonNull String access,
            long accessExpiresAtEpochMs,
            @NonNull String refresh,
            @NonNull Continuation<? super Unit> completion) {
        return TokenStoreFlow.saveTokens(
                dataStore, cipher, access, accessExpiresAtEpochMs, refresh, completion);
    }

    @Nullable
    @Override
    public Object readTokens(@NonNull Continuation<? super Tokens> completion) {
        return TokenStoreFlow.readTokens(dataStore, cipher, completion);
    }

    @Override
    public Object clear(@NonNull Continuation<? super Unit> completion) {
        return TokenStoreFlow.clear(dataStore, completion);
    }
}
