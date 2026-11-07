package com.nexachat.feature.group;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.ViewModelStore;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.Contact;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.ConversationRepository;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class CreateGroupViewModelTest {

    @Rule
    public final TestRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final ViewModelStore viewModelStore = new ViewModelStore();
    private int viewModelCount;
    private ContactsRepository contactsRepository;
    private ConversationRepository conversationRepository;
    private CreateGroupViewModel viewModel;
    private final Contact contactA = new Contact(
            "u-1", "+84911111111", "Lan", null, null, null);
    private final Contact contactB = new Contact(
            "u-2", "+84922222222", "Minh", null, null, null);

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        contactsRepository = Mockito.mock(ContactsRepository.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        when(contactsRepository.contacts()).thenReturn(
                Single.just(new Result.Success<>(List.of(contactA, contactB))));
        viewModel = newViewModel();
    }

    @After
    public void tearDown() {
        viewModelStore.clear();
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    private CreateGroupViewModel newViewModel() {
        CreateGroupViewModel created = new CreateGroupViewModel(
                contactsRepository, conversationRepository);
        viewModelStore.put("create-group-" + (++viewModelCount), created);
        return created;
    }

    private CreateGroupUiState state() {
        return viewModel.getUiState().getValue();
    }

    @Test
    public void contactsLoadIntoSelectableList() {
        CreateGroupUiState state = state();
        assertEquals(List.of(contactA, contactB), state.getContacts());
        assertTrue(state.isContactsLoaded());
        assertFalse(state.isLoading());
    }

    @Test
    public void toggleMemberAddsAndRemovesId() {
        viewModel.toggleMember("u-1");
        viewModel.toggleMember("u-2");
        assertEquals(java.util.Set.of("u-1", "u-2"), state().getSelectedIds());

        viewModel.toggleMember("u-1");
        assertEquals(java.util.Set.of("u-2"), state().getSelectedIds());
    }

    @Test
    public void createIsBlockedWhileTitleIsBlank() {
        viewModel.toggleMember("u-1");
        assertFalse(state().getCanCreate());
        viewModel.create();
        verify(conversationRepository, never()).createGroup(anyString(), any());
    }

    @Test
    public void createIsBlockedWithNoMembersSelected() {
        viewModel.onTitleChange("Weekend");
        assertFalse(state().getCanCreate());
        viewModel.create();
        verify(conversationRepository, never()).createGroup(anyString(), any());
    }

    @Test
    public void titlePlusSelectionEnablesCreate() {
        viewModel.onTitleChange("Weekend");
        viewModel.toggleMember("u-1");
        assertTrue(state().getCanCreate());
    }

    @Test
    public void successfulCreateEmitsCreatedWithNewConversationId() {
        when(conversationRepository.createGroup(anyString(), any()))
                .thenReturn(Single.just(new Result.Success<>("conv-9")));
        viewModel.onTitleChange("Weekend");
        viewModel.toggleMember("u-1");
        viewModel.toggleMember("u-2");
        AtomicReference<GroupSingleLiveEvent.Event<CreateGroupEvent>> event = new AtomicReference<>();
        androidx.lifecycle.Observer<GroupSingleLiveEvent.Event<CreateGroupEvent>> observer = event::set;
        viewModel.getEvents().observeForever(observer);

        viewModel.create();

        assertEquals(new CreateGroupEvent.Created("conv-9"), event.get().consume());
        verify(conversationRepository).createGroup("Weekend", List.of("u-1", "u-2"));
        assertFalse(state().isCreating());
        assertNull(state().getError());
        viewModel.getEvents().removeObserver(observer);
    }

    @Test
    public void createFailureSurfacesTypedErrorAndEmitsNoEvent() {
        when(conversationRepository.createGroup(anyString(), any()))
                .thenReturn(Single.just(Result.failure(new ApiError.Network())));
        viewModel.onTitleChange("Weekend");
        viewModel.toggleMember("u-1");
        AtomicReference<GroupSingleLiveEvent.Event<CreateGroupEvent>> event = new AtomicReference<>();
        androidx.lifecycle.Observer<GroupSingleLiveEvent.Event<CreateGroupEvent>> observer = event::set;
        viewModel.getEvents().observeForever(observer);

        viewModel.create();

        assertNull(event.get());
        assertEquals(new ApiError.Network(), state().getError());
        assertFalse(state().isCreating());
        viewModel.getEvents().removeObserver(observer);
    }

    @Test
    public void contactsLoadFailureSurfacesError() {
        when(contactsRepository.contacts()).thenReturn(
                Single.just(Result.failure(ApiError.RateLimited.INSTANCE)));
        CreateGroupViewModel failed = newViewModel();

        assertEquals(ApiError.RateLimited.INSTANCE, failed.getUiState().getValue().getError());
        assertTrue(failed.getUiState().getValue().isContactsLoaded());
    }

    @Test
    public void consumedCreateEventDoesNotReplayAfterObserverReattaches() {
        when(conversationRepository.createGroup(anyString(), any()))
                .thenReturn(Single.just(new Result.Success<>("conv-9")));
        viewModel.onTitleChange("Weekend");
        viewModel.toggleMember("u-1");
        AtomicReference<GroupSingleLiveEvent.Event<CreateGroupEvent>> first = new AtomicReference<>();
        androidx.lifecycle.Observer<GroupSingleLiveEvent.Event<CreateGroupEvent>> firstObserver = first::set;
        viewModel.getEvents().observeForever(firstObserver);
        viewModel.create();
        assertEquals(new CreateGroupEvent.Created("conv-9"), first.get().consume());
        viewModel.getEvents().removeObserver(firstObserver);

        AtomicReference<GroupSingleLiveEvent.Event<CreateGroupEvent>> afterRotation = new AtomicReference<>();
        androidx.lifecycle.Observer<GroupSingleLiveEvent.Event<CreateGroupEvent>> secondObserver = afterRotation::set;
        viewModel.getEvents().observeForever(secondObserver);
        assertNull(afterRotation.get().consume());
        viewModel.getEvents().removeObserver(secondObserver);
    }
}
