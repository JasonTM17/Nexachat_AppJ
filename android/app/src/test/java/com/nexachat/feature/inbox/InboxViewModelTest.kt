package com.nexachat.feature.inbox

import app.cash.turbine.test
import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.data.ConversationRepository
import com.nexachat.data.ConversationSummary
import io.mockk.coEvery
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
class InboxViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: ConversationRepository = mockk()
    private val inboxFlow = MutableStateFlow<List<ConversationSummary>>(emptyList())
    private lateinit var viewModel: InboxViewModel

    private fun conversation(
        id: String,
        unread: Int,
        name: String = "Peer $id",
    ) = ConversationSummary(
        id = id,
        type = "DIRECT",
        title = null,
        lastMessageContent = "last $id",
        lastMessageSenderName = name,
        lastMessageAtEpochMs = 1_700_000_000_000L,
        lastMessageSeq = 42,
        unreadCount = unread,
        otherUserName = name,
        otherUserAvatar = null,
        avatarUrl = null,
    )

    @Before
    fun setUp() {
        every { repository.inboxFlow() } returns inboxFlow
        coEvery { repository.refreshInbox() } returns Result.Success(Unit)
        viewModel = InboxViewModel(repository)
    }

    @Test
    fun `room stream maps conversations and clears loading`() = runTest {
        inboxFlow.value = listOf(conversation("c-1", unread = 2))

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.conversations.size)
            assertEquals("c-1", state.conversations.single().id)
            assertFalse(state.loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unread counts map into rows and sum to the total badge`() = runTest {
        inboxFlow.value = listOf(
            conversation("c-1", unread = 2),
            conversation("c-2", unread = 3),
            conversation("c-3", unread = 0),
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(listOf(2, 3, 0), state.conversations.map { it.unreadCount })
            assertEquals(5, state.totalUnread)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `display name prefers group title then peer name`() = runTest {
        val group = conversation("g-1", unread = 0, name = "Team").copy(title = "Team Chat")
        inboxFlow.value = listOf(group, conversation("c-1", unread = 0))

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Team Chat", state.conversations[0].displayName)
            assertEquals("Peer c-1", state.conversations[1].displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh failure surfaces the error but keeps the list`() = runTest {
        inboxFlow.value = listOf(conversation("c-1", unread = 1))
        coEvery { repository.refreshInbox() } returns
            Result.Failure(ApiError.Network("down"))

        viewModel.refresh()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(ApiError.Network("down"), state.error)
            assertEquals(1, state.conversations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `successful refresh clears a previous error`() = runTest {
        coEvery { repository.refreshInbox() } returns
            Result.Failure(ApiError.RateLimited)
        viewModel.refresh()
        viewModel.uiState.test {
            assertEquals(ApiError.RateLimited, awaitItem().error)
            cancelAndIgnoreRemainingEvents()
        }

        coEvery { repository.refreshInbox() } returns Result.Success(Unit)
        viewModel.refresh()

        viewModel.uiState.test {
            assertNull(awaitItem().error)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
