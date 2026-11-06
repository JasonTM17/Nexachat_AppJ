package com.nexachat.feature.contacts

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.UserDto
import com.nexachat.data.Contact
import com.nexachat.data.ContactsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: ContactsRepository = mockk()

    /** Real flow standing in for the repository's shared contact cache. */
    private val contactsFlow = MutableStateFlow<List<Contact>?>(null)
    private lateinit var viewModel: ContactsViewModel

    private val user = UserDto(
        id = "u-1",
        phone = "+84912345678",
        displayName = "Lan",
        avatarUrl = null,
    )
    private val contact = Contact(
        userId = "u-1",
        phone = "+84912345678",
        displayName = "Lan",
        aliasName = null,
    )

    @Before
    fun setUp() {
        // The VM's init block calls loadContacts() → repository.contacts()
        // immediately; both it and the shared cache flow must be stubbed
        // before construction.
        every { repository.contactsFlow } returns contactsFlow
        coEvery { repository.contacts() } returns Result.Success(emptyList())
        viewModel = ContactsViewModel(SavedStateHandle(), repository)
    }

    @Test
    fun `search query draft restores from SavedStateHandle`() = runTest {
        val restored = ContactsViewModel(
            SavedStateHandle(mapOf("search_query" to "+849")),
            repository,
        )

        assertEquals("+849", restored.uiState.value.query)
    }

    @Test
    fun `phone-shaped query runs the phone search and shows results`() = runTest {
        coEvery { repository.searchByPhone(any()) } returns Result.Success(listOf(user))
        viewModel.onQueryChange("+84912345678")

        viewModel.search()

        assertEquals(listOf(user), viewModel.uiState.value.searchResults)
        assertTrue(viewModel.uiState.value.searched)
        assertFalse(viewModel.uiState.value.loading)
        coVerify(exactly = 1) { repository.searchByPhone("+84912345678") }
        coVerify(exactly = 0) { repository.searchByName(any()) }
    }

    @Test
    fun `name-shaped query runs the prefix search`() = runTest {
        coEvery { repository.searchByName(any()) } returns Result.Success(listOf(user))
        viewModel.onQueryChange("Lan")

        viewModel.search()

        assertEquals(listOf(user), viewModel.uiState.value.searchResults)
        coVerify(exactly = 1) { repository.searchByName("Lan") }
        coVerify(exactly = 0) { repository.searchByPhone(any()) }
    }

    @Test
    fun `blank query is blocked before hitting the repository`() = runTest {
        viewModel.onQueryChange("   ")

        viewModel.search()

        assertFalse(viewModel.uiState.value.searched)
        assertFalse(viewModel.uiState.value.loading)
        coVerify(exactly = 0) { repository.searchByPhone(any()) }
        coVerify(exactly = 0) { repository.searchByName(any()) }
    }

    @Test
    fun `search with no matches keeps the searched empty state`() = runTest {
        coEvery { repository.searchByName(any()) } returns Result.Success(emptyList())
        viewModel.onQueryChange("Nobody")

        viewModel.search()

        val state = viewModel.uiState.value
        assertTrue(state.searched)
        assertTrue(state.searchResults.isEmpty())
        assertNull(state.error)
    }

    @Test
    fun `search failure surfaces the error and clears results`() = runTest {
        coEvery { repository.searchByPhone(any()) } returns
            Result.Failure(ApiError.RateLimited)
        viewModel.onQueryChange("0912345678")

        viewModel.search()

        assertEquals(ApiError.RateLimited, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
    }

    @Test
    fun `findUserForAdd with no match maps to user-not-found`() = runTest {
        coEvery { repository.searchByPhone(any()) } returns Result.Success(emptyList())
        viewModel.onAddPhoneChange("0900000000")

        viewModel.findUserForAdd()

        assertEquals(
            ApiError.Unknown("USER_NOT_FOUND"),
            viewModel.uiState.value.addError,
        )
        assertNull(viewModel.uiState.value.addResult)
    }

    @Test
    fun `duplicate add maps to the already-contact state`() = runTest {
        coEvery { repository.addContact(any()) } returns
            Result.Failure(ApiError.PhoneExists)
        viewModel.onAddPhoneChange("0912345678")

        viewModel.confirmAdd()

        assertEquals(ApiError.PhoneExists, viewModel.uiState.value.addError)
        assertFalse(viewModel.uiState.value.addDone)
        coVerify(exactly = 1) { repository.addContact("0912345678") }
    }

    @Test
    fun `successful add sets addDone and the shared cache emits the contact`() = runTest {
        // Mirror the repository contract: a successful add prepends into the
        // shared cache, which every VM instance observes.
        coEvery { repository.addContact(any()) } answers {
            contactsFlow.value = listOf(contact)
            Result.Success(contact)
        }
        viewModel.onAddPhoneChange("0912345678")

        viewModel.confirmAdd()

        val state = viewModel.uiState.value
        assertTrue(state.addDone)
        assertNull(state.addError)
        assertEquals(listOf(contact), state.contacts)
    }

    @Test
    fun `contact added by another VM instance appears in this list`() = runTest {
        // Regression: each nav destination gets its own ViewModel — an add
        // performed by the search screen's instance previously never reached
        // the contacts screen until re-entry.
        ContactsViewModel(SavedStateHandle(), repository)

        contactsFlow.value = listOf(contact)

        assertEquals(listOf(contact), viewModel.uiState.value.contacts)
    }

    @Test
    fun `contact removed by another VM instance disappears from this list`() = runTest {
        ContactsViewModel(SavedStateHandle(), repository)
        contactsFlow.value = listOf(contact)
        assertEquals(listOf(contact), viewModel.uiState.value.contacts)

        contactsFlow.value = emptyList()

        assertTrue(viewModel.uiState.value.contacts.isEmpty())
    }

    @Test
    fun `search result for an existing contact opens the contact actions`() = runTest {
        contactsFlow.value = listOf(contact)

        viewModel.openUserDetail(user)

        assertEquals(contact.userId, viewModel.uiState.value.detailContact?.userId)
        assertEquals(user.id, viewModel.uiState.value.detailUser?.id)
    }

    @Test
    fun `messageSelected emits OpenConversation with the returned id`() = runTest {
        coEvery { repository.openDirectConversation(any(), any()) } returns
            Result.Success("conv-42")
        viewModel.openContactDetail(contact)

        viewModel.events.test {
            viewModel.messageSelected()
            assertEquals(
                ContactsViewModel.Event.OpenConversation("conv-42"),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) {
            repository.openDirectConversation(userId = "u-1", phone = "+84912345678")
        }
    }

    @Test
    fun `message failure surfaces the error and emits no event`() = runTest {
        coEvery { repository.openDirectConversation(any(), any()) } returns
            Result.Failure(ApiError.Network())
        viewModel.openContactDetail(contact)

        viewModel.events.test {
            viewModel.messageSelected()
            expectNoEvents()
            cancel()
        }
        assertEquals(ApiError.Network(), viewModel.uiState.value.detailError)
    }
}
