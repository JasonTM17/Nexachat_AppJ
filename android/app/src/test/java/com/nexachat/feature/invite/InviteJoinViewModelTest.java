package com.nexachat.feature.invite;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.io.IOException;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class InviteJoinViewModelTest {
    private static final String URL = "nexachat://join/" + "A".repeat(43);

    @Rule public final InstantTaskExecutorRule instantExecutor = new InstantTaskExecutorRule();
    private FakeInviteRepository repository;
    private InviteJoinViewModel viewModel;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = new FakeInviteRepository();
        viewModel = new InviteJoinViewModel(repository);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void invalidHostNeverCallsInviteApi() {
        viewModel.open("https://evil.example/join/" + "A".repeat(43));

        assertEquals(InviteJoinUiState.Status.ERROR, state().getStatus());
        assertEquals(InviteFailure.UNAVAILABLE, state().getFailure());
        assertEquals(0, repository.previewCalls);
    }

    @Test
    public void validatedInviteTokenRemainsAvailableForSignInReturn() {
        viewModel.open(URL);

        assertEquals("A".repeat(43), viewModel.inviteTokenForSignIn());
    }

    @Test
    public void signInReturnTokenReopensPreviewInRecreatedViewModel() {
        repository.preview = Single.error(new InviteApiException(InviteFailure.SIGN_IN_REQUIRED));
        viewModel.open(URL);
        String returnedToken = viewModel.inviteTokenForSignIn();
        assertEquals(InviteFailure.SIGN_IN_REQUIRED, state().getFailure());

        repository.preview = Single.just(FakeInviteRepository.preview(false, false));
        InviteJoinViewModel recreated = new InviteJoinViewModel(repository);
        recreated.open(InviteLinkParser.shareUrl(returnedToken, null).orElseThrow());

        assertEquals("A".repeat(43), recreated.inviteTokenForSignIn());
        assertEquals(InviteJoinUiState.Status.PREVIEW, recreated.getState().getValue().getStatus());
        assertEquals(2, repository.previewCalls);
        assertEquals(0, repository.joinCalls);
    }

    @Test
    public void previewShowsOnlySafeGroupFieldsAndAlreadyMemberState() {
        repository.preview = Single.just(FakeInviteRepository.preview(true, false));

        viewModel.open(URL);

        assertEquals(InviteJoinUiState.Status.ALREADY_MEMBER, state().getStatus());
        assertEquals("Travel group", state().getPreview().title);
        assertEquals(8, state().getPreview().memberCount);
        assertEquals("A".repeat(43), repository.lastToken);
        assertEquals("group-1", state().getConversationId());
    }

    @Test
    public void directJoinOpensJoinedState() {
        repository.join = Single.just(FakeInviteRepository.joinResult("JOINED", "group-1"));
        viewModel.open(URL);

        viewModel.join();

        assertEquals(InviteJoinUiState.Status.JOINED, state().getStatus());
        assertEquals("group-1", state().getConversationId());
    }

    @Test
    public void approvalJoinShowsPendingAndRefreshesMemberStatus() {
        repository.join = Single.just(FakeInviteRepository.joinResult("PENDING", "group-1"));
        repository.myRequest = Single.just(Optional.of(FakeInviteRepository.request("PENDING")));
        viewModel.open(URL);

        viewModel.join();

        assertEquals(InviteJoinUiState.Status.PENDING, state().getStatus());
        assertEquals(1, repository.statusCalls);
        assertEquals("group-1", repository.lastConversationId);
    }

    @Test
    public void waitingMemberCanSeeApprovedAndRejectedStatuses() {
        repository.join = Single.just(FakeInviteRepository.joinResult("PENDING", "group-1"));
        repository.myRequest = Single.just(Optional.of(FakeInviteRepository.request("APPROVED")));
        viewModel.open(URL);
        viewModel.join();
        assertEquals(InviteJoinUiState.Status.APPROVED, state().getStatus());

        repository.myRequest = Single.just(Optional.of(FakeInviteRepository.request("REJECTED")));
        viewModel.refreshStatus();
        assertEquals(InviteJoinUiState.Status.REJECTED, state().getStatus());
    }

    @Test
    public void unavailableRevokedExpiredAndUnknownLinksShareOneState() {
        repository.preview = Single.error(new InviteApiException(InviteFailure.UNAVAILABLE));

        viewModel.open(URL);

        assertEquals(InviteJoinUiState.Status.ERROR, state().getStatus());
        assertEquals(InviteFailure.UNAVAILABLE, state().getFailure());
    }

    @Test
    public void rateLimitedAndOfflinePreviewHaveDistinctStates() {
        repository.preview = Single.error(new InviteApiException(InviteFailure.RATE_LIMITED));
        viewModel.open(URL);
        assertEquals(InviteFailure.RATE_LIMITED, state().getFailure());

        repository.preview = Single.error(new InviteApiException(InviteFailure.OFFLINE));
        viewModel.refresh();
        assertEquals(InviteFailure.OFFLINE, state().getFailure());
    }

    @Test
    public void forbiddenJoinDoesNotExposeGroupExistence() {
        repository.join = Single.error(new InviteApiException(InviteFailure.NOT_ALLOWED));
        viewModel.open(URL);

        viewModel.join();

        assertEquals(InviteJoinUiState.Status.ERROR, state().getStatus());
        assertEquals(InviteFailure.NOT_ALLOWED, state().getFailure());
        assertFalse(state().getFailure().toString().contains("group-1"));
    }

    @Test
    public void unknownTransportFailureMapsToGenericState() {
        repository.preview = Single.error(new IOException("offline"));

        viewModel.open(URL);

        assertEquals(InviteFailure.UNKNOWN, state().getFailure());
    }

    @Test
    public void sameTokenReopenPreservesPendingAndApprovedProgress() {
        for (String status : new String[] { "PENDING", "APPROVED", "REJECTED" }) {
            repository.myRequest = Single.just(Optional.of(FakeInviteRepository.request(status)));
            repository.join = Single.just(FakeInviteRepository.joinResult("PENDING", "group-1"));
            InviteJoinViewModel retained = new InviteJoinViewModel(repository);
            retained.open(URL);
            retained.join();
            InviteJoinUiState before = retained.getState().getValue();
            int previews = repository.previewCalls;
            int requests = repository.statusCalls;

            retained.open(URL);

            assertSame(before, retained.getState().getValue());
            assertEquals(InviteJoinUiState.Status.valueOf(status), retained.getState().getValue().getStatus());
            assertEquals(previews, repository.previewCalls);
            assertEquals(requests, repository.statusCalls);
        }
    }

    @Test
    public void sameTokenReopenPreservesJoinedAndAlreadyMemberProgress() {
        for (String status : new String[] { "JOINED", "ALREADY_MEMBER" }) {
            repository.join = Single.just(FakeInviteRepository.joinResult(status, "group-1"));
            InviteJoinViewModel retained = new InviteJoinViewModel(repository);
            retained.open(URL);
            retained.join();
            InviteJoinUiState before = retained.getState().getValue();
            retained.open(URL);
            assertSame(before, retained.getState().getValue());
            assertEquals(InviteJoinUiState.Status.valueOf(status), retained.getState().getValue().getStatus());
        }
    }

    @Test
    public void sameTokenReopenDoesNotRestartInFlightPreview() {
        SingleSubject<InvitePreviewResponse> preview = SingleSubject.create();
        repository.preview = preview;
        viewModel.open(URL);
        InviteJoinUiState loading = state();

        viewModel.open("NEXACHAT://JOIN/" + "A".repeat(43));

        assertSame(loading, state());
        assertEquals(1, repository.previewCalls);
        preview.onSuccess(FakeInviteRepository.preview(false, false));
        assertEquals(InviteJoinUiState.Status.PREVIEW, state().getStatus());
    }

    @Test
    public void sameTokenReopenCannotEnableJoinWhileSubmissionIsInFlight() {
        SingleSubject<JoinResponse> joining = SingleSubject.create();
        repository.join = joining;
        viewModel.open(URL);
        viewModel.join();
        InviteJoinUiState submitting = state();

        viewModel.open(URL);
        viewModel.join();

        assertSame(submitting, state());
        assertTrue(state().isBusy());
        assertEquals(1, repository.joinCalls);
        joining.onSuccess(FakeInviteRepository.joinResult("JOINED", "group-1"));
        assertEquals(InviteJoinUiState.Status.JOINED, state().getStatus());
    }

    @Test
    public void freshViewModelReconstructsPendingRequest() {
        reconstructRequest("PENDING", InviteJoinUiState.Status.PENDING);
    }

    @Test
    public void freshViewModelReconstructsApprovedRequest() {
        reconstructRequest("APPROVED", InviteJoinUiState.Status.APPROVED);
    }

    @Test
    public void freshViewModelReconstructsRejectedRequest() {
        reconstructRequest("REJECTED", InviteJoinUiState.Status.REJECTED);
    }

    private void reconstructRequest(String status, InviteJoinUiState.Status expected) {
        repository.preview = Single.just(FakeInviteRepository.preview(false, true));
        repository.myRequest = Single.just(Optional.of(FakeInviteRepository.request(status)));
        viewModel.open(URL);

        assertEquals(expected, state().getStatus());
        assertEquals("group-1", state().getConversationId());
        assertEquals(1, repository.statusCalls);
        assertEquals(0, repository.joinCalls);
    }

    @Test
    public void reconstructionWithoutExistingRequestLeavesPreviewJoinable() {
        repository.preview = Single.just(FakeInviteRepository.preview(false, true));
        viewModel.open(URL);

        assertEquals(1, repository.statusCalls);
        assertEquals(InviteJoinUiState.Status.PREVIEW, state().getStatus());
        assertFalse(state().isBusy());
    }

    @Test
    public void reconstructionChecksStatusBeforeEnablingJoin() {
        SingleSubject<Optional<JoinRequestResponse>> request = SingleSubject.create();
        repository.preview = Single.just(FakeInviteRepository.preview(false, true));
        repository.myRequest = request;
        viewModel.open(URL);

        assertTrue(state().isBusy());
        viewModel.join();
        assertEquals(0, repository.joinCalls);
        viewModel.open(URL);
        assertEquals(1, repository.statusCalls);

        request.onSuccess(Optional.of(FakeInviteRepository.request("PENDING")));
        assertEquals(InviteJoinUiState.Status.PENDING, state().getStatus());
        assertFalse(state().isBusy());
    }

    @Test
    public void reconstructionStatusFailureRequiresExplicitRetryBeforeJoin() {
        repository.preview = Single.just(FakeInviteRepository.preview(false, true));
        repository.myRequest = Single.error(new InviteApiException(InviteFailure.OFFLINE));
        viewModel.open(URL);

        assertEquals(InviteJoinUiState.Status.ERROR, state().getStatus());
        assertEquals(InviteFailure.OFFLINE, state().getFailure());
        viewModel.join();
        assertEquals(0, repository.joinCalls);

        repository.myRequest = Single.just(Optional.of(FakeInviteRepository.request("APPROVED")));
        viewModel.refresh();
        assertEquals(InviteJoinUiState.Status.APPROVED, state().getStatus());
    }

    @Test
    public void sameTokenErrorReopenWaitsForExplicitRetry() {
        repository.preview = Single.error(new InviteApiException(InviteFailure.OFFLINE));
        viewModel.open(URL);
        InviteJoinUiState failed = state();
        repository.preview = Single.just(FakeInviteRepository.preview(false, false));
        viewModel.open(URL);
        assertSame(failed, state());
        assertEquals(1, repository.previewCalls);

        viewModel.refresh();
        assertEquals(InviteJoinUiState.Status.PREVIEW, state().getStatus());
        assertEquals(2, repository.previewCalls);
    }

    @Test
    public void statusRefreshCannotOverlapAnExistingRequest() {
        SingleSubject<Optional<JoinRequestResponse>> request = SingleSubject.create();
        repository.join = Single.just(FakeInviteRepository.joinResult("PENDING", "group-1"));
        repository.myRequest = request;
        viewModel.open(URL);
        viewModel.join();
        viewModel.refreshStatus();
        assertEquals(1, repository.statusCalls);
        assertTrue(state().isBusy());
        request.onSuccess(Optional.of(FakeInviteRepository.request("APPROVED")));
        assertEquals(InviteJoinUiState.Status.APPROVED, state().getStatus());
    }

    @Test
    public void newTokenLoadsNewPreviewAndDisposesOldSubmission() {
        SingleSubject<JoinResponse> oldJoin = SingleSubject.create();
        repository.join = oldJoin;
        viewModel.open(URL);
        viewModel.join();
        assertTrue(oldJoin.hasObservers());

        viewModel.open("nexachat://join/" + "B".repeat(43));
        assertFalse(oldJoin.hasObservers());
        oldJoin.onSuccess(FakeInviteRepository.joinResult("JOINED", "old-group"));
        assertEquals(InviteJoinUiState.Status.PREVIEW, state().getStatus());
        assertEquals("B".repeat(43), viewModel.inviteTokenForSignIn());
        assertEquals(2, repository.previewCalls);
    }

    @Test
    public void invalidReopenClearsTokenAndDisposesOldPreview() {
        SingleSubject<InvitePreviewResponse> oldPreview = SingleSubject.create();
        repository.preview = oldPreview;
        viewModel.open(URL);
        assertTrue(oldPreview.hasObservers());

        viewModel.open("https://evil.example/join/" + "A".repeat(43));
        assertNull(viewModel.inviteTokenForSignIn());
        assertFalse(oldPreview.hasObservers());
        oldPreview.onSuccess(FakeInviteRepository.preview(false, false));
        assertEquals(InviteJoinUiState.Status.ERROR, state().getStatus());
        assertEquals(InviteFailure.UNAVAILABLE, state().getFailure());
        assertEquals(1, repository.previewCalls);
    }

    private InviteJoinUiState state() {
        return viewModel.getState().getValue();
    }
}
