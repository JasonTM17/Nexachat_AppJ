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
class GroupDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val conversationRepository: ConversationRepository = mockk()
    private val contactsRepository: ContactsRepository = mockk()

    private val conversationFlow = MutableStateFlow<ConversationSummary?>(null)
    private lateinit var viewModel: GroupDetailViewModel

    private companion object {
        const val CONVERSATION_ID = "conv-1"
        const val MY_ID = "me-1"
    }

    private val me = GroupMember(
        userId = MY_ID,
        displayName = "Me",
        phone = "+84900000000",
        avatarUrl = null,
        role = GroupMember.ROLE_ADMIN,
    )
    private val lan = GroupMember(
        userId = "u-lan",
        displayName = "Lan",
        phone = "+84911111111",
        avatarUrl = null,
        role = "MEMBER",
    )
    private val minh = GroupMember(
        userId = "u-minh",
        displayName = "Minh",
        phone = "+84922222222",
        avatarUrl = null,
        role = null,
    )

    /** Builds the VM after stubbing everything its init block touches. */
    private fun createViewModel(
        detail: Result<GroupDetail> = Result.Success(GroupDetail("Weekend", listOf(me, lan))),
    ): GroupDetailViewModel {
        every { conversationRepository.conversationFlow(CONVERSATION_ID) } returns
            conversationFlow
        coEvery { conversationRepository.myUserId() } returns MY_ID
        coEvery { conversationRepository.groupDetail(CONVERSATION_ID) } returns detail
        return GroupDetailViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf("conversationId" to CONVERSATION_ID),
            ),
            conversationRepository = conversationRepository,
            contactsRepository = contactsRepository,
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
    fun `missing role on my member row hides admin affordances`() = runTest {
        val meNoRole = me.copy(role = null)
        val viewModel = createViewModel(
            Result.Success(GroupDetail("Weekend", listOf(meNoRole, lan))),
        )

        assertFalse(viewModel.uiState.value.iAmAdmin)
    }

    @Test
    fun `title falls back to the Room row when the fetch has none`() = runTest {
        val viewModel = createViewModel(
            Result.Success(GroupDetail(title = null, members = listOf(me, lan))),
        )
        conversationFlow.value = ConversationSummary(
            id = CONVERSATION_ID,
            type = "GROUP",
            title = "Room title",
            lastMessageContent = null,
            lastMessageSenderName = null,
            lastMessageAtEpochMs = null,
            lastMessageSeq = 0L,
            unreadCount = 0,
            otherUserName = null,
            otherUserAvatar = null,
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
            Result.Failure(ApiError.Unknown("NOT_ADMIN"))

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
            assertEquals(GroupDetailViewModel.Event.ExitToInbox, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { conversationRepository.leaveGroup(CONVERSATION_ID) }
    }

    @Test
    fun `leave failure surfaces the error and emits no event`() = runTest {
        coEvery { conversationRepository.leaveGroup(any()) } returns
            Result.Failure(ApiError.Network())

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
                Contact(userId = "u-lan", phone = "+84911111111", displayName = "Lan"),
                Contact(userId = "u-new", phone = "+84933333333", displayName = "Hoa"),
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
                Contact(userId = "u-new", phone = "+84933333333", displayName = "Hoa"),
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
        val viewModel = createViewModel(Result.Failure(ApiError.Network()))

        assertEquals(ApiError.Network(), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }
}
