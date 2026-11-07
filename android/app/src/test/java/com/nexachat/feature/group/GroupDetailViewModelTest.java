package com.nexachat.feature.group;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelStore;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.Contact;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.GroupDetail;
import com.nexachat.data.GroupMember;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class GroupDetailViewModelTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String MY_ID = "me-1";
    private static final long PUSH_WINDOW_MS = 400L;

    @Rule
    public final TestRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final TestScheduler timerScheduler = new TestScheduler();
    private final ViewModelStore viewModelStore = new ViewModelStore();
    private int viewModelCount;
    private ConversationRepository conversationRepository;
    private ContactsRepository contactsRepository;
    private PublishProcessor<Optional<ConversationSummary>> conversationFlow;
    private PublishProcessor<Boolean> groupUpdateFlow;
    private GroupDetailViewModel viewModel;

    private final GroupMember me = new GroupMember(
            MY_ID, "Me", "+84900000000", null, GroupMember.ROLE_ADMIN);
    private final GroupMember lan = new GroupMember(
            "u-lan", "Lan", "+84911111111", null, "MEMBER");
    private final GroupMember minh = new GroupMember(
            "u-minh", "Minh", "+84922222222", null, null);

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(ignored -> timerScheduler);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        conversationRepository = Mockito.mock(ConversationRepository.class);
        contactsRepository = Mockito.mock(ContactsRepository.class);
        conversationFlow = PublishProcessor.create();
        groupUpdateFlow = PublishProcessor.create();
        viewModel = createViewModel(new Result.Success<>(new GroupDetail("Weekend", List.of(me, lan))));
    }

    @After
    public void tearDown() {
        viewModelStore.clear();
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    private GroupDetailViewModel createViewModel(Result<GroupDetail> detail) {
        when(conversationRepository.conversationFlow(CONVERSATION_ID)).thenReturn(conversationFlow);
        when(conversationRepository.groupUpdates(CONVERSATION_ID)).thenReturn(groupUpdateFlow);
        when(conversationRepository.myUserId()).thenReturn(Maybe.just(MY_ID));
        when(conversationRepository.groupDetail(CONVERSATION_ID)).thenReturn(Single.just(detail));
        GroupDetailViewModel created = new GroupDetailViewModel(
                new SavedStateHandle(Map.of("conversationId", CONVERSATION_ID)),
                conversationRepository,
                contactsRepository);
        viewModelStore.put("group-detail-" + (++viewModelCount), created);
        return created;
    }

    private GroupDetailUiState state() {
        return viewModel.getUiState().getValue();
    }

    private static Contact contact(String userId, String phone, String name) {
        return new Contact(userId, phone, name, null, null, null);
    }

    @Test
    public void memberListMapsMembersAndDetectsMyAdminRole() {
        GroupDetailUiState state = state();
        assertEquals(List.of(me, lan), state.getMembers());
        assertEquals("Weekend", state.getTitle());
        assertEquals(MY_ID, state.getMyUserId());
        assertTrue(state.isIAmAdmin());
        assertFalse(state.isLoading());
    }

    @Test
    public void remoteGroupUpdateRefreshesOpenMemberList() {
        List<GroupMember> updatedMembers = List.of(me, lan, minh);
        when(conversationRepository.groupDetail(CONVERSATION_ID)).thenReturn(
                Single.just(new Result.Success<>(new GroupDetail("Weekend", updatedMembers))));

        groupUpdateFlow.onNext(Boolean.TRUE);
        timerScheduler.advanceTimeBy(PUSH_WINDOW_MS, TimeUnit.MILLISECONDS);

        assertEquals(updatedMembers, state().getMembers());
        verify(conversationRepository, times(2)).groupDetail(CONVERSATION_ID);
    }

    @Test
    public void burstOfGroupUpdatesCoalescesIntoOneRefetch() {
        for (int index = 0; index < 5; index++) {
            groupUpdateFlow.onNext(Boolean.TRUE);
        }
        timerScheduler.advanceTimeBy(PUSH_WINDOW_MS, TimeUnit.MILLISECONDS);

        // Init load plus one push-triggered fetch, not one per push.
        verify(conversationRepository, times(2)).groupDetail(CONVERSATION_ID);
    }

    @Test
    public void laterGroupUpdateStillTriggersItsOwnRefetch() {
        groupUpdateFlow.onNext(Boolean.TRUE);
        timerScheduler.advanceTimeBy(PUSH_WINDOW_MS, TimeUnit.MILLISECONDS);
        verify(conversationRepository, times(2)).groupDetail(CONVERSATION_ID);

        // A separated push is a new change and must not be swallowed by the prior window.
        groupUpdateFlow.onNext(Boolean.TRUE);
        timerScheduler.advanceTimeBy(PUSH_WINDOW_MS, TimeUnit.MILLISECONDS);
        verify(conversationRepository, times(3)).groupDetail(CONVERSATION_ID);
    }

    @Test
    public void missingRoleOnMyMemberRowHidesAdminAffordances() {
        GroupMember meNoRole = new GroupMember(MY_ID, "Me", "+84900000000", null, null);
        GroupDetailViewModel noRole = createViewModel(
                new Result.Success<>(new GroupDetail("Weekend", List.of(meNoRole, lan))));
        assertFalse(noRole.getUiState().getValue().isIAmAdmin());
    }

    @Test
    public void titleFallsBackToRoomRowWhenFetchHasNone() {
        GroupDetailViewModel noTitle = createViewModel(
                new Result.Success<>(new GroupDetail(null, List.of(me, lan))));
        conversationFlow.onNext(Optional.of(new ConversationSummary(
                CONVERSATION_ID, "GROUP", "Room title", null, null, null,
                0L, 0, null, null, null, 0L, null, null, false, null)));

        assertEquals("Room title", noTitle.getUiState().getValue().getTitle());
    }

    @Test
    public void removeRequestSetsPendingTargetAndConfirmCallsRepository() {
        when(conversationRepository.removeGroupMember(anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<Void>(null)));
        viewModel.requestRemoveMember(lan);
        assertEquals(lan, state().getPendingRemove());

        viewModel.confirmRemoveMember();

        verify(conversationRepository).removeGroupMember(CONVERSATION_ID, "u-lan");
        assertNull(state().getPendingRemove());
        // Initial load plus post-mutation refetch.
        verify(conversationRepository, times(2)).groupDetail(CONVERSATION_ID);
    }

    @Test
    public void dismissingRemoveConfirmCallsNothing() {
        viewModel.requestRemoveMember(lan);
        viewModel.dismissRemoveMember();
        assertNull(state().getPendingRemove());
        verify(conversationRepository, never()).removeGroupMember(anyString(), anyString());
    }

    @Test
    public void removeFailureSurfacesError() {
        when(conversationRepository.removeGroupMember(anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(new ApiError.Unknown("NOT_ADMIN"))));
        viewModel.requestRemoveMember(lan);
        viewModel.confirmRemoveMember();

        assertEquals(new ApiError.Unknown("NOT_ADMIN"), state().getError());
        assertFalse(state().isBusy());
    }

    @Test
    public void confirmedLeaveEmitsExitToInbox() {
        when(conversationRepository.leaveGroup(anyString()))
                .thenReturn(Single.just(new Result.Success<Void>(null)));
        AtomicReference<GroupSingleLiveEvent.Event<GroupDetailEvent>> event = new AtomicReference<>();
        androidx.lifecycle.Observer<GroupSingleLiveEvent.Event<GroupDetailEvent>> observer = event::set;
        viewModel.getEvents().observeForever(observer);
        viewModel.requestLeave();
        assertTrue(state().isConfirmLeave());

        viewModel.confirmLeave();

        assertEquals(GroupDetailEvent.ExitToInbox.INSTANCE, event.get().consume());
        verify(conversationRepository).leaveGroup(CONVERSATION_ID);
        viewModel.getEvents().removeObserver(observer);
    }

    @Test
    public void leaveFailureSurfacesErrorAndEmitsNoEvent() {
        when(conversationRepository.leaveGroup(anyString()))
                .thenReturn(Single.just(Result.failure(new ApiError.Network())));
        AtomicReference<GroupSingleLiveEvent.Event<GroupDetailEvent>> event = new AtomicReference<>();
        androidx.lifecycle.Observer<GroupSingleLiveEvent.Event<GroupDetailEvent>> observer = event::set;
        viewModel.getEvents().observeForever(observer);

        viewModel.confirmLeave();

        assertNull(event.get());
        assertEquals(new ApiError.Network(), state().getError());
        viewModel.getEvents().removeObserver(observer);
    }

    @Test
    public void addMemberPickerExcludesCurrentMembers() {
        when(contactsRepository.contacts()).thenReturn(Single.just(new Result.Success<>(List.of(
                contact("u-lan", "+84911111111", "Lan"),
                contact("u-new", "+84933333333", "Hoa")))));

        viewModel.openAddMember();

        GroupDetailUiState state = state();
        assertTrue(state.isShowAddPicker());
        assertEquals(List.of("u-new"), state.getAddableContacts().stream()
                .map(Contact::getUserId).toList());
    }

    @Test
    public void addSelectedMembersPostsIdsClosesPickerAndRefetches() {
        when(contactsRepository.contacts()).thenReturn(Single.just(new Result.Success<>(List.of(
                contact("u-new", "+84933333333", "Hoa")))));
        when(conversationRepository.addGroupMembers(anyString(), any()))
                .thenReturn(Single.just(new Result.Success<Void>(null)));

        viewModel.openAddMember();
        viewModel.toggleAddCandidate("u-new");
        viewModel.addSelectedMembers();

        verify(conversationRepository).addGroupMembers(CONVERSATION_ID, List.of("u-new"));
        assertFalse(state().isShowAddPicker());
        verify(conversationRepository, times(2)).groupDetail(CONVERSATION_ID);
    }

    @Test
    public void renamePostsTrimmedTitle() {
        when(conversationRepository.renameGroup(anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<Void>(null)));
        viewModel.openRename();
        viewModel.onRenameInputChange("  New name  ");
        viewModel.confirmRename();

        verify(conversationRepository).renameGroup(CONVERSATION_ID, "New name");
        assertFalse(state().isShowRename());
        assertEquals("", state().getRenameInput());
    }

    @Test
    public void failedRenameKeepsDialogAndTypedTitle() {
        when(conversationRepository.renameGroup(anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(new ApiError.Unknown("NETWORK"))));
        viewModel.openRename();
        viewModel.onRenameInputChange("Kept title");
        viewModel.confirmRename();

        assertTrue(state().isShowRename());
        assertEquals("Kept title", state().getRenameInput());
        assertEquals(new ApiError.Unknown("NETWORK"), state().getError());
    }

    @Test
    public void blankRenameInputIsBlockedBeforeRepositoryCall() {
        viewModel.openRename();
        viewModel.onRenameInputChange("   ");
        viewModel.confirmRename();
        verify(conversationRepository, never()).renameGroup(anyString(), anyString());
    }

    @Test
    public void memberFetchFailureSurfacesError() {
        GroupDetailViewModel failed = createViewModel(Result.failure(new ApiError.Network()));
        assertEquals(new ApiError.Network(), failed.getUiState().getValue().getError());
        assertFalse(failed.getUiState().getValue().isLoading());
    }

    @Test
    public void consumedLeaveEventDoesNotReplayAfterObserverReattaches() {
        when(conversationRepository.leaveGroup(anyString()))
                .thenReturn(Single.just(new Result.Success<Void>(null)));
        AtomicReference<GroupSingleLiveEvent.Event<GroupDetailEvent>> first = new AtomicReference<>();
        androidx.lifecycle.Observer<GroupSingleLiveEvent.Event<GroupDetailEvent>> firstObserver = first::set;
        viewModel.getEvents().observeForever(firstObserver);
        viewModel.confirmLeave();
        assertEquals(GroupDetailEvent.ExitToInbox.INSTANCE, first.get().consume());
        viewModel.getEvents().removeObserver(firstObserver);

        AtomicReference<GroupSingleLiveEvent.Event<GroupDetailEvent>> afterRotation = new AtomicReference<>();
        androidx.lifecycle.Observer<GroupSingleLiveEvent.Event<GroupDetailEvent>> secondObserver = afterRotation::set;
        viewModel.getEvents().observeForever(secondObserver);
        assertNull(afterRotation.get().consume());
        viewModel.getEvents().removeObserver(secondObserver);
    }
}
