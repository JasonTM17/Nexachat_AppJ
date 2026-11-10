package com.nexachat.feature.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import io.reactivex.rxjava3.core.Single;
import org.junit.Test;

public final class SplashSessionTest {

    @Test
    public void sessionExpiredRefreshNavigatesToLoginWithMessage() {
        SplashNavEvent event = SplashSession.restoreDestination(
                Single.just(true),
                Single.just(true),
                Single.just(Result.failure(ApiError.SessionExpired.INSTANCE))).blockingGet();
        assertTrue(event instanceof SplashNavEvent.ToLogin);
        assertTrue(((SplashNavEvent.ToLogin) event).showSessionExpiredMessage());
    }

    @Test
    public void networkFailureStillNavigatesToInbox() {
        SplashNavEvent event = SplashSession.restoreDestination(
                Single.just(true),
                Single.just(true),
                Single.just(Result.failure(new ApiError.Network()))).blockingGet();
        assertEquals(SplashNavEvent.ToInbox.INSTANCE, event);
    }

    @Test
    public void closedSessionWithStoredCredentialsNavigatesToLoginWithMessage() {
        SplashNavEvent event = SplashSession.restoreDestination(
                Single.just(false),
                Single.just(true),
                Single.error(new AssertionError("refresh must not run"))).blockingGet();
        SplashNavEvent.ToLogin login = (SplashNavEvent.ToLogin) event;
        assertTrue(login.showSessionExpiredMessage());
    }

    @Test
    public void freshInstallNavigatesToLoginWithoutMessage() {
        SplashNavEvent event = SplashSession.restoreDestination(
                Single.just(false),
                Single.just(false),
                Single.error(new AssertionError("refresh must not run"))).blockingGet();
        assertFalse(((SplashNavEvent.ToLogin) event).showSessionExpiredMessage());
    }
}
