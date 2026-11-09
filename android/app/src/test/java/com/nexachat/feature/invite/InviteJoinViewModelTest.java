package com.nexachat.feature.invite;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    private InviteJoinUiState state() {
        return viewModel.getState().getValue();
    }
}
