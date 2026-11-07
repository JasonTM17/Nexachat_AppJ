package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.dto.MessagePageDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Suspend message API. Declaring it in Kotlin lets MockK stub the Java
 * repository the same way it stubs other suspend ports.
 */
interface MessageRepositoryApi {
    val typingEvents: SharedFlow<TypingEvent>
    val readEvents: SharedFlow<ReadEvent>
    val interactionEvents: SharedFlow<ApiError>

    fun messagesFlow(conversationId: String): Flow<List<ChatMessage>>

    fun pinsFlow(conversationId: String): Flow<List<ChatMessage>>

    suspend fun openConversation(conversationId: String)

    fun closeConversation(conversationId: String)

    suspend fun refreshHistory(
        conversationId: String,
        beforeSeq: Long? = null,
    ): Result<MessagePageDto>

    suspend fun loadUntilLoaded(
        conversationId: String,
        messageId: String,
        maxPages: Int = 20,
    ): MessageJump

    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        newContent: String,
    ): Result<Unit>

    suspend fun deleteMessage(conversationId: String, messageId: String): Result<Unit>

    suspend fun searchMessages(
        conversationId: String,
        query: String,
        beforeSeq: Long? = null,
        limit: Int = MessageApi.DEFAULT_SEARCH_LIMIT,
    ): Result<MessageSearchResult>

    suspend fun send(
        conversationId: String,
        content: String,
        replyToId: String? = null,
        type: String = MessageDto.TYPE_TEXT,
        attachments: List<AttachmentDto> = emptyList(),
    )

    suspend fun retry(clientMessageId: String)

    suspend fun flushOutbox()

    suspend fun sendRead(conversationId: String, upToSeq: Long)

    suspend fun toggleReaction(messageId: String, conversationId: String, emoji: String)

    suspend fun refreshPins(conversationId: String): Result<Unit>

    suspend fun pinMessage(conversationId: String, messageId: String)

    suspend fun unpinMessage(conversationId: String, messageId: String)

    suspend fun resyncForward(conversationId: String)

    suspend fun currentUserId(): String?

    suspend fun isMine(senderId: String?): Boolean
}
