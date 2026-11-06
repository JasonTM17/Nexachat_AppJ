package com.nexachat.feature.group

import app.cash.turbine.test
import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.data.Contact
import com.nexachat.data.ContactsRepository
import com.nexachat.data.ConversationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateGroupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val contactsRepository: ContactsRepository = mockk()
    private val conversationRepository: ConversationRepository = mockk()
    private lateinit var viewModel: CreateGroupViewModel

    private val contactA = Contact(
        userId = "u-1",
        phone = "+84911111111",
        displayName = "Lan",
    )
    private val contactB = Contact(
        userId = "u-2",
        phone = "+84922222222",
        displayName = "Minh",
    )

    @Before
    fun setUp() {
        // The VM loads contacts in init — the stub must exist before construction.
        coEvery { contactsRepository.contacts() } returns
            Result.Success(listOf(contactA, contactB))
        viewModel = CreateGroupViewModel(contactsRepository, conversationRepository)
    }

    @Test
    fun `contacts load into the selectable list`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(listOf(contactA, contactB), state.contacts)
        assertTrue(state.contactsLoaded)
        assertFalse(state.loading)
    }

    @Test
    fun `toggleMember adds and removes the id`() = runTest {
        viewModel.toggleMember("u-1")
        viewModel.toggleMember("u-2")
        assertEquals(setOf("u-1", "u-2"), viewModel.uiState.value.selectedIds)

        viewModel.toggleMember("u-1")
        assertEquals(setOf("u-2"), viewModel.uiState.value.selectedIds)
    }

    @Test
    fun `create is blocked while the title is blank`() = runTest {
        viewModel.toggleMember("u-1")
        assertFalse(viewModel.uiState.value.canCreate)

        viewModel.create()

        coVerify(exactly = 0) { conversationRepository.createGroup(any(), any()) }
    }

    @Test
    fun `create is blocked with no members selected`() = runTest {
        viewModel.onTitleChange("Weekend")
        assertFalse(viewModel.uiState.value.canCreate)

        viewModel.create()

        coVerify(exactly = 0) { conversationRepository.createGroup(any(), any()) }
    }

    @Test
    fun `title plus selection enables create`() = runTest {
        viewModel.onTitleChange("Weekend")
        viewModel.toggleMember("u-1")

        assertTrue(viewModel.uiState.value.canCreate)
    }

    @Test
    fun `successful create emits Created with the new conversation id`() = runTest {
        coEvery { conversationRepository.createGroup(any(), any()) } returns
            Result.Success("conv-9")
        viewModel.onTitleChange("Weekend")
        viewModel.toggleMember("u-1")
        viewModel.toggleMember("u-2")

        viewModel.events.test {
            viewModel.create()
            assertEquals(
                CreateGroupEvent.Created("conv-9"),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) {
            conversationRepository.createGroup("Weekend", listOf("u-1", "u-2"))
        }
        assertFalse(viewModel.uiState.value.creating)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `create failure surfaces a typed error and emits no event`() = runTest {
        coEvery { conversationRepository.createGroup(any(), any()) } returns
            Result.failure(ApiError.Network())
        viewModel.onTitleChange("Weekend")
        viewModel.toggleMember("u-1")

        viewModel.events.test {
            viewModel.create()
            expectNoEvents()
            cancel()
        }
        assertEquals(ApiError.Network(), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.creating)
    }

    @Test
    fun `contacts load failure surfaces the error`() = runTest {
        coEvery { contactsRepository.contacts() } returns
            Result.failure(ApiError.RateLimited.INSTANCE)

        val viewModel = CreateGroupViewModel(contactsRepository, conversationRepository)

        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.contactsLoaded)
    }
}
