package com.nexachat.feature.safety;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BlockedUsersViewModelTest {

    @Rule public final InstantTaskExecutorRule instantExecutor = new InstantTaskExecutorRule();

    private FakeRepository repository;
    private BlockedUsersViewModel viewModel;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = new FakeRepository();
        viewModel = new BlockedUsersViewModel(repository);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void firstPageLoadsOnInit() {
        BlockedUsersViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(2, state.getItems().size());
        assertTrue(state.hasMore());
    }

    @Test
    public void unblockRemovesRowAndCallsRepository() {
        viewModel.unblock("u1");

        BlockedUsersViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(1, state.getItems().size());
        assertEquals("u2", state.getItems().get(0).userId());
        assertEquals("u1", repository.unblockedId);
    }

    @Test
    public void loadMoreAppendsNextPage() {
        viewModel.loadMore();

        BlockedUsersViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(3, state.getItems().size());
        assertFalse(state.hasMore());
    }

    private static final class FakeRepository implements SafetyRepository {
        String unblockedId;

        @Override public Completable blockUser(String userId) {
            return Completable.complete();
        }

        @Override public Completable unblockUser(String userId) {
            unblockedId = userId;
            return Completable.complete();
        }

        @Override public Single<Boolean> blockStatus(String userId) {
            return Single.just(false);
        }

        @Override public Single<BlockedPage> listBlocked(int limit, String cursor) {
            if (cursor == null) {
                return Single.just(new BlockedPage(List.of(
                        new BlockedUser("u1", "One", null, null, null),
                        new BlockedUser("u2", "Two", null, null, null)), "next"));
            }
            return Single.just(new BlockedPage(List.of(
                    new BlockedUser("u3", "Three", null, null, null)), null));
        }

        @Override public Completable report(
                String targetType,
                String targetId,
                ReportReason reason,
                String details,
                String messageSnapshot) {
            return Completable.complete();
        }

        @Override public Completable starMessage(String messageId, String conversationId) {
            return Completable.complete();
        }

        @Override public Completable unstarMessage(String messageId, String conversationId) {
            return Completable.complete();
        }

        @Override public Single<StarredPage> listStarred(int limit, String cursor) {
            return Single.just(new StarredPage(List.of(), null));
        }

        @Override public Flowable<Set<String>> starredIds(String conversationId) {
            return Flowable.just(Set.of());
        }
    }
}
