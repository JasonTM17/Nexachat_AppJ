package com.nexachat.feature.contacts;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.Contact;
import com.nexachat.data.ContactsRepository;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class ContactsViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private ContactsRepository repository;
    private BehaviorProcessor<List<Contact>> contactsFlow;
    private ContactsViewModel viewModel;
    private final UserDto user = new UserDto("u-1", "+84912345678", "Lan", null);
    private final Contact contact = new Contact(
            "u-1", "+84912345678", "Lan", null, null, null);

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        repository = mock(ContactsRepository.class);
        contactsFlow = BehaviorProcessor.createDefault(Collections.emptyList());
        when(repository.getContactsFlow()).thenReturn(contactsFlow);
        when(repository.contacts()).thenReturn(Single.just(
                new Result.Success<>(Collections.emptyList())));
        when(repository.getMe()).thenReturn(Single.just(new Result.Success<>(
                new UserDto("me", "+84900000001", "Me", null))));
        viewModel = new ContactsViewModel(new SavedStateHandle(), repository);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void searchQueryDraftRestoresFromSavedStateHandle() {
        ContactsViewModel restored = new ContactsViewModel(
                new SavedStateHandle(Map.of("search_query", "+849")), repository);

        assertEquals("+849", restored.getUiState().getValue().getQuery());
    }

    @Test
    public void phoneShapedQueryRunsPhoneSearchAndShowsResults() {
        when(repository.searchByPhone(anyString()))
                .thenReturn(Single.just(new Result.Success<>(List.of(user))));
        viewModel.onQueryChange("+84912345678");

        viewModel.search();

        ContactsViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(List.of(user), state.getSearchResults());
        assertTrue(state.isSearched());
        assertFalse(state.isLoading());
        verify(repository, times(1)).searchByPhone("+84912345678");
        verify(repository, never()).searchByName(anyString());
    }

    @Test
    public void nameShapedQueryRunsPrefixSearch() {
        when(repository.searchByName(anyString()))
                .thenReturn(Single.just(new Result.Success<>(List.of(user))));
        viewModel.onQueryChange("Lan");

        viewModel.search();

        assertEquals(List.of(user), viewModel.getUiState().getValue().getSearchResults());
        verify(repository, times(1)).searchByName("Lan");
        verify(repository, never()).searchByPhone(anyString());
    }

    @Test
    public void blankQueryIsBlockedBeforeRepository() {
        viewModel.onQueryChange("   ");

        viewModel.search();

        assertFalse(viewModel.getUiState().getValue().isSearched());
        assertFalse(viewModel.getUiState().getValue().isLoading());
        verify(repository, never()).searchByPhone(anyString());
        verify(repository, never()).searchByName(anyString());
    }

    @Test
    public void searchWithNoMatchesKeepsSearchedEmptyState() {
        when(repository.searchByName(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Collections.emptyList())));
        viewModel.onQueryChange("Nobody");

        viewModel.search();

        ContactsViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isSearched());
        assertTrue(state.getSearchResults().isEmpty());
        assertNull(state.getError());
    }

    @Test
    public void searchFailureSurfacesErrorAndClearsResults() {
        when(repository.searchByPhone(anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.RateLimited.INSTANCE)));
        viewModel.onQueryChange("0912345678");

        viewModel.search();

        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.getUiState().getValue().getError());
        assertTrue(viewModel.getUiState().getValue().getSearchResults().isEmpty());
    }

    @Test
    public void findUserForAddWithNoMatchMapsToUserNotFound() {
        when(repository.searchByPhone(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Collections.emptyList())));
        viewModel.onAddPhoneChange("0900000000");

        viewModel.findUserForAdd();

        assertEquals(new ApiError.Unknown("USER_NOT_FOUND"),
                viewModel.getUiState().getValue().getAddError());
        assertNull(viewModel.getUiState().getValue().getAddResult());
    }

    @Test
    public void duplicateAddMapsToAlreadyContactState() {
        when(repository.addContact(anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.PhoneExists.INSTANCE)));
        viewModel.onAddPhoneChange("0912345678");

        viewModel.confirmAdd();

        assertEquals(ApiError.PhoneExists.INSTANCE, viewModel.getUiState().getValue().getAddError());
        assertFalse(viewModel.getUiState().getValue().isAddDone());
        verify(repository, times(1)).addContact("0912345678");
    }

    @Test
    public void successfulAddSetsAddDoneAndSharedCacheEmitsContact() {
        when(repository.addContact(anyString())).thenAnswer(invocation -> {
            contactsFlow.onNext(List.of(contact));
            return Single.just(new Result.Success<>(contact));
        });
        viewModel.onAddPhoneChange("0912345678");

        viewModel.confirmAdd();

        ContactsViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isAddDone());
        assertNull(state.getAddError());
        assertEquals(List.of(contact), state.getContacts());
    }

    @Test
    public void contactAddedByAnotherViewModelAppearsInThisList() {
        new ContactsViewModel(new SavedStateHandle(), repository);

        contactsFlow.onNext(List.of(contact));

        assertEquals(List.of(contact), viewModel.getUiState().getValue().getContacts());
    }

    @Test
    public void contactRemovedByAnotherViewModelDisappearsFromThisList() {
        new ContactsViewModel(new SavedStateHandle(), repository);
        contactsFlow.onNext(List.of(contact));
        assertEquals(List.of(contact), viewModel.getUiState().getValue().getContacts());

        contactsFlow.onNext(Collections.emptyList());

        assertTrue(viewModel.getUiState().getValue().getContacts().isEmpty());
    }

    @Test
    public void searchResultForExistingContactOpensContactActions() {
        contactsFlow.onNext(List.of(contact));

        viewModel.openUserDetail(user);

        assertEquals(contact.getUserId(), viewModel.getUiState().getValue()
                .getDetailContact().getUserId());
        assertEquals(user.getId(), viewModel.getUiState().getValue().getDetailUser().getId());
    }

    @Test
    public void messageSelectedEmitsOpenConversationWithReturnedId() {
        when(repository.openDirectConversation(any(), any()))
                .thenReturn(Single.just(new Result.Success<>("conv-42")));
        List<ContactsViewModel.Event> events = observeEvents();
        viewModel.openContactDetail(contact);

        viewModel.messageSelected();

        assertEquals(List.of(new ContactsViewModel.Event.OpenConversation("conv-42")), events);
        verify(repository, times(1)).openDirectConversation("u-1", "+84912345678");
    }

    @Test
    public void messageFailureSurfacesErrorAndEmitsNoEvent() {
        when(repository.openDirectConversation(any(), any()))
                .thenReturn(Single.just(Result.failure(ApiError.Network(null))));
        List<ContactsViewModel.Event> events = observeEvents();
        viewModel.openContactDetail(contact);

        viewModel.messageSelected();

        assertTrue(events.isEmpty());
        assertEquals(ApiError.Network(null), viewModel.getUiState().getValue().getDetailError());
    }

    @Test
    public void addingSignedInPhoneUsesDedicatedErrorWithoutCallingServer() {
        when(repository.getMe()).thenReturn(Single.just(new Result.Success<>(
                new UserDto("me", "+84912345678", "Me", null))));
        viewModel.onAddPhoneChange("0912 345 678");

        viewModel.confirmAdd();

        assertEquals(new ApiError.Unknown(SelfContactGuard.ERROR_CODE),
                viewModel.getUiState().getValue().getAddError());
        assertFalse(viewModel.getUiState().getValue().isAdding());
        verify(repository, never()).addContact(anyString());
    }

    private List<ContactsViewModel.Event> observeEvents() {
        List<ContactsViewModel.Event> events = new ArrayList<>();
        Observer<ContactsViewModel.Event> observer = events::add;
        viewModel.getEvents().observeForever(observer);
        return events;
    }
}
