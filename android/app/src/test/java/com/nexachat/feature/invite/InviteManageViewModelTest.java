package com.nexachat.feature.invite;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class InviteManageViewModelTest {
    @Rule public final InstantTaskExecutorRule instantExecutor = new InstantTaskExecutorRule();
    private FakeInviteRepository repository;
    private InviteManageViewModel viewModel;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = new FakeInviteRepository();
        viewModel = new InviteManageViewModel(repository);
        viewModel.load("group-1");
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void emptyLinkAndRequestQueueRenderAsReady() {
        assertFalse(state().isLoading());
        assertNull(state().getLink());
        assertTrue(state().getRequests().isEmpty());
    }

    @Test
    public void createOptionsProduceOneTimeShareUrl() {
        repository.createdLink = Single.just(
                FakeInviteRepository.link("A".repeat(43), true));

        viewModel.createOrRotate("2026-11-16T00:00:00Z", 10, true);

        assertEquals("2026-11-16T00:00:00Z", repository.lastCreateRequest.expiresAt);
        assertEquals(Integer.valueOf(10), repository.lastCreateRequest.maxUses);
        assertTrue(repository.lastCreateRequest.requiresApproval);
        assertEquals("nexachat://join/" + "A".repeat(43), state().getShareUrl());
        assertTrue(state().canShare());
    }

    @Test
    public void togglingApprovalAndRevokingLinkUpdateAdminState() {
        repository.createdLink = Single.just(
                FakeInviteRepository.link("A".repeat(43), false));
        viewModel.createOrRotate(null, null, false);
        repository.updatedLink = Single.just(FakeInviteRepository.link(null, true));

        viewModel.setRequiresApproval(true);

        assertTrue(repository.lastApprovalValue);
        assertTrue(state().getLink().requiresApproval);
        assertTrue(state().canShare());

        viewModel.revoke();
        assertNull(state().getLink());
        assertNull(state().getShareUrl());
        assertEquals(1, repository.revokeCalls);
    }

    @Test
    public void pendingRequestsCanBeApprovedAndRejectedThenRemoved() {
        JoinRequestResponse approved = FakeInviteRepository.request("PENDING");
        approved.id = "approve-1";
        JoinRequestResponse rejected = FakeInviteRepository.request("PENDING");
        rejected.id = "reject-1";
        repository.requests = new java.util.ArrayList<>(List.of(approved, rejected));
        viewModel.refreshRequests();
        assertEquals(2, state().getRequests().size());

        viewModel.approve("approve-1");
        assertEquals(1, state().getRequests().size());
        assertEquals(1, repository.approveCalls);

        viewModel.reject("reject-1");
        assertTrue(state().getRequests().isEmpty());
        assertEquals(1, repository.rejectCalls);
    }

    @Test
    public void apiFailureRemainsTypedForLocalizedFeedback() {
        repository.pendingRequests = Single.error(
                new InviteApiException(InviteFailure.OFFLINE));
        viewModel = new InviteManageViewModel(repository);

        viewModel.load("group-1");

        assertEquals(InviteFailure.OFFLINE, state().getFailure());
    }

    @Test
    public void creationIsBlockedUntilInitialDataCompletes() {
        SingleSubject<Optional<InviteLinkResponse>> activeLink = SingleSubject.create();
        SingleSubject<List<JoinRequestResponse>> pendingRequests = SingleSubject.create();
        repository.activeLink = activeLink;
        repository.pendingRequests = pendingRequests;
        InviteManageViewModel deferred = new InviteManageViewModel(repository);

        deferred.load("group-deferred");
        deferred.createOrRotate("2026-11-16T00:00:00Z", 10, false);

        assertTrue(deferred.getState().getValue().isLoading());
        assertNull(repository.lastCreateRequest);
    }

    @Test
    public void requestReviewIsBlockedUntilInitialDataCompletes() {
        SingleSubject<Optional<InviteLinkResponse>> activeLink = SingleSubject.create();
        SingleSubject<List<JoinRequestResponse>> pendingRequests = SingleSubject.create();
        repository.activeLink = activeLink;
        repository.pendingRequests = pendingRequests;
        InviteManageViewModel deferred = new InviteManageViewModel(repository);

        deferred.load("group-deferred");
        deferred.approve("request-1");
        deferred.reject("request-1");

        assertEquals(0, repository.approveCalls);
        assertEquals(0, repository.rejectCalls);
    }

    @Test
    public void retryAfterInitialFailureReloadsLinkAndRequestsOnSameViewModel() {
        AtomicInteger activeLinkCalls = new AtomicInteger();
        AtomicInteger pendingRequestCalls = new AtomicInteger();
        repository.activeLink = Single.defer(() -> {
            activeLinkCalls.incrementAndGet();
            return Single.just(Optional.of(FakeInviteRepository.link("B".repeat(43), false)));
        });
        repository.pendingRequests = Single.defer(() -> {
            int call = pendingRequestCalls.incrementAndGet();
            return call == 1
                    ? Single.error(new InviteApiException(InviteFailure.OFFLINE))
                    : Single.just(List.of(FakeInviteRepository.request("PENDING")));
        });
        InviteManageViewModel retryable = new InviteManageViewModel(repository);

        retryable.load("group-retry");
        assertEquals(InviteFailure.OFFLINE, retryable.getState().getValue().getFailure());

        retryable.load("group-retry");

        assertEquals(2, activeLinkCalls.get());
        assertEquals(2, pendingRequestCalls.get());
        assertNull(retryable.getState().getValue().getFailure());
        assertEquals("link-1", retryable.getState().getValue().getLink().id);
        assertEquals(1, retryable.getState().getValue().getRequests().size());
    }

    private InviteManageUiState state() {
        return viewModel.getState().getValue();
    }
}
