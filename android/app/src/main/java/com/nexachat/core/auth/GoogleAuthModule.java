package com.nexachat.core.auth;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/** Binds the Credential Manager implementation behind the test seam. */
@Module
@InstallIn(SingletonComponent.class)
public abstract class GoogleAuthModule {

    @Binds
    @Singleton
    abstract GoogleSignInClient bindGoogleSignInClient(CredentialManagerGoogleSignIn impl);
}
