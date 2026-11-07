package com.nexachat.feature.group

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.data.Contact
import com.nexachat.data.ContactsRepository
import com.nexachat.data.ConversationRepository
import com.nexachat.data.ConversationSummary
import com.nexachat.data.GroupDetail
import com.nexachat.data.GroupMember
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val conversationRepository: ConversationRepository = mockk()
    private val contactsRepository: ContactsRepository = mockk()

    private val conversationFlow = MutableStateFlow<ConversationSummary?>(null)
    private val groupUpdateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private lateinit var viewModel: GroupDetailViewModel

    private companion object {
        const val CONVERSATION_ID = "conv-1"
        const val MY_ID = "me-1"

        /** Comfortably past the coalescing window (200 ms) in virtual time. */
        const val PUSH_WINDOW_MS = 400L
    }

    private val me = GroupMember(MY_ID, "Me", "+84900000000", null, GroupMember.ROLE_ADMIN)
    private val lan = GroupMember("u-lan", "Lan", "+84911111111", null, "MEMBER")
    private val minh = GroupMember("u-minh", "Minh", "+84922222222", null, null)

    /** Builds the VM after stubbing everything its init block touches. */
    private fun createViewModel(
        detail: Result<GroupDetail> = Result.Success(GroupDetail("Weekend", listOf(me, lan))),
    ): GroupDetailViewModel {
        every { conversationRepository.conversationFlow(CONVERSATION_ID) } returns
            conversationFlow
        every { conversationRepository.groupUpdates(CONVERSATION_ID) } returns groupUpdateFlow
        coEvery { conversationRepository.myUserId() } returns MY_ID
        coEvery { conversationRepository.groupDetail(CONVERSATION_ID) } returns detail
        return GroupDetailViewModel(
            SavedStateHandle(mapOf("conversationId" to CONVERSATION_ID)),
            conversationRepository,
            contactsRepository,
        )
    }

    @Before
    fun setUp() {
        viewModel = createViewModel()
    }

    @Test
    fun `member list maps members and detects my admin role`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(listOf(me, lan), state.members)
        assertEquals("Weekend", state.title)
        assertEquals(MY_ID, state.myUserId)
        assertTrue(state.iAmAdmin)
        assertFalse(state.loading)
    }

    @Test
    fun `remote group update refreshes the open member list`() = runTest {
        val updatedMembers = listOf(me, lan, minh)
        coEvery { conversationRepository.groupDetail(CONVERSATION_ID) } returns
            Result.Success(GroupDetail("Weekend", updatedMembers))

        groupUpdateFlow.emit(Unit)
        advanceTimeBy(PUSH_WINDOW_MS)
        runCurrent()

        assertEquals(updatedMembers, viewModel.uiState.value.members)
        coVerify(exactly = 2) { conversationRepository.groupDetail(CONVERSATION_ID) }
    }

    @Test
    fun `a burst of group updates coalesces into one refetch`() = runTest {
        repeat(5) { groupUpdateFlow.emit(Unit) }
        advanceTimeBy(PUSH_WINDOW_MS)
        runCurrent()

        // init load + a single push-triggered fetch, not one per push.
        coVerify(exactly = 2) { conversationRepository.groupDetail(CONVERSATION_ID) }
    }

    @Test
    fun `a later group update still triggers its own refetch`() = runTest {
        groupUpdateFlow.emit(Unit)
        advanceTimeBy(PUSH_WINDOW_MS)
        runCurrent()
        coVerify(exactly = 2) { conversationRepository.groupDetail(CONVERSATION_ID) }

        // A second, well-separated push is a new change — it must not be
        // swallowed by the previous window.
        groupUpdateFlow.emit(Unit)
        advanceTimeBy(PUSH_WINDOW_MS)
        runCurrent()
        coVerify(exactly = 3) { conversationRepository.groupDetail(CONVERSATION_ID) }
    }

    @Test
    fun `missing role on my member row hides admin affordances`() = runTest {
        val meNoRole = GroupMember(me.userId, me.displayName, me.phone, me.avatarUrl, null)
        val viewModel = createViewModel(
            Result.Success(GroupDetail("Weekend", listOf(meNoRole, lan))),
        )

        assertFalse(viewModel.uiState.value.iAmAdmin)
    }

    @Test
    fun `title falls back to the Room row when the fetch has none`() = runTest {
        val viewModel = createViewModel(
            Result.Success(GroupDetail(null, listOf(me, lan))),
        )
        conversationFlow.value = ConversationSummary(
            CONVERSATION_ID,
            "GROUP",
            "Room title",
            null,
            null,
            null,
            0L,
            0,
            null,
            null,
            null,
        )

        assertEquals("Room title", viewModel.uiState.value.title)
    }

    @Test
    fun `remove request sets the pending target and confirm calls the repository`() = runTest {
        coEvery { conversationRepository.removeGroupMember(any(), any()) } returns
            Result.Success(Unit)

        viewModel.requestRemoveMember(lan)
        assertEquals(lan, viewModel.uiState.value.pendingRemove)

        viewModel.confirmRemoveMember()

        coVerify(exactly = 1) {
            conversationRepository.removeGroupMember(CONVERSATION_ID, "u-lan")
        }
        assertNull(viewModel.uiState.value.pendingRemove)
        // init load + post-mutation refetch.
        coVerify(exactly = 2) { conversationRepository.groupDetail(CONVERSATION_ID) }
    }

    @Test
    fun `dismissing the remove confirm calls nothing`() = runTest {
        viewModel.requestRemoveMember(lan)
        viewModel.dismissRemoveMember()

        assertNull(viewModel.uiState.value.pendingRemove)
        coVerify(exactly = 0) { conversationRepository.removeGroupMember(any(), any()) }
    }

    @Test
    fun `remove failure surfaces the error`() = runTest {
        coEvery { conversationRepository.removeGroupMember(any(), any()) } returns
            Result.failure(ApiError.Unknown("NOT_ADMIN"))

        viewModel.requestRemoveMember(lan)
        viewModel.confirmRemoveMember()

        assertEquals(ApiError.Unknown("NOT_ADMIN"), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test
    fun `confirmed leave emits ExitToInbox`() = runTest {
        coEvery { conversationRepository.leaveGroup(any()) } returns Result.Success(Unit)

        viewModel.events.test {
            viewModel.requestLeave()
            assertTrue(viewModel.uiState.value.confirmLeave)
            viewModel.confirmLeave()
            assertEquals(GroupDetailEvent.ExitToInbox.INSTANCE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { conversationRepository.leaveGroup(CONVERSATION_ID) }
    }

    @Test
    fun `leave failure surfaces the error and emits no event`() = runTest {
        coEvery { conversationRepository.leaveGroup(any()) } returns
            Result.failure(ApiError.Network())

        viewModel.events.test {
            viewModel.confirmLeave()
            expectNoEvents()
            cancel()
        }
        assertEquals(ApiError.Network(), viewModel.uiState.value.error)
    }

    @Test
    fun `add-member picker excludes current members`() = runTest {
        coEvery { contactsRepository.contacts() } returns Result.Success(
            listOf(
                Contact("u-lan", "+84911111111", "Lan"),
                Contact("u-new", "+84933333333", "Hoa"),
            ),
        )

        viewModel.openAddMember()

        val state = viewModel.uiState.value
        assertTrue(state.showAddPicker)
        assertEquals(listOf("u-new"), state.addableContacts.map { it.userId })
    }

    @Test
    fun `addSelectedMembers posts ids, closes the picker and refetches`() = runTest {
        coEvery { contactsRepository.contacts() } returns Result.Success(
            listOf(
                Contact("u-new", "+84933333333", "Hoa"),
            ),
        )
        coEvery { conversationRepository.addGroupMembers(any(), any()) } returns
            Result.Success(Unit)

        viewModel.openAddMember()
        viewModel.toggleAddCandidate("u-new")
        viewModel.addSelectedMembers()

        coVerify(exactly = 1) {
            conversationRepository.addGroupMembers(CONVERSATION_ID, listOf("u-new"))
        }
        assertFalse(viewModel.uiState.value.showAddPicker)
        coVerify(exactly = 2) { conversationRepository.groupDetail(CONVERSATION_ID) }
    }

    @Test
    fun `rename posts the trimmed title`() = runTest {
        coEvery { conversationRepository.renameGroup(any(), any()) } returns
            Result.Success(Unit)

        viewModel.openRename()
        viewModel.onRenameInputChange("  New name  ")
        viewModel.confirmRename()

        coVerify(exactly = 1) {
            conversationRepository.renameGroup(CONVERSATION_ID, "New name")
        }
        assertFalse(viewModel.uiState.value.showRename)
        assertEquals("", viewModel.uiState.value.renameInput)
    }

    @Test
    fun `failed rename keeps the dialog and the typed title`() = runTest {
        coEvery { conversationRepository.renameGroup(any(), any()) } returns
            Result.failure(ApiError.Unknown("NETWORK"))

        viewModel.openRename()
        viewModel.onRenameInputChange("Kept title")
        viewModel.confirmRename()

        assertTrue(viewModel.uiState.value.showRename)
        assertEquals("Kept title", viewModel.uiState.value.renameInput)
        assertEquals(ApiError.Unknown("NETWORK"), viewModel.uiState.value.error)
    }

    @Test
    fun `blank rename input is blocked before hitting the repository`() = runTest {
        viewModel.openRename()
        viewModel.onRenameInputChange("   ")
        viewModel.confirmRename()

        coVerify(exactly = 0) { conversationRepository.renameGroup(any(), any()) }
    }

    @Test
    fun `member fetch failure surfaces the error`() = runTest {
        val viewModel = createViewModel(Result.failure(ApiError.Network()))

        assertEquals(ApiError.Network(), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }
}
