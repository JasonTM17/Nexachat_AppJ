package com.nexachat.data

import android.net.Uri
import com.nexachat.core.common.Result
import kotlinx.coroutines.flow.Flow

/** Coroutine-facing inbox API implemented by the Java repository. */
interface ConversationRepositoryApi {
    fun inboxFlow(): Flow<List<ConversationSummary>>

    fun conversationFlow(conversationId: String): Flow<ConversationSummary?>

    fun groupUpdates(conversationId: String): Flow<Unit>

    suspend fun refreshInbox(): Result<Unit>

    suspend fun myUserId(): String?

    suspend fun createGroup(title: String, memberIds: List<String>): Result<String>

    suspend fun groupDetail(conversationId: String): Result<GroupDetail>

    suspend fun addGroupMembers(conversationId: String, userIds: List<String>): Result<Unit>

    suspend fun removeGroupMember(conversationId: String, userId: String): Result<Unit>

    suspend fun renameGroup(conversationId: String, title: String): Result<Unit>

    suspend fun setGroupAvatar(conversationId: String, uri: Uri): Result<Unit>

    suspend fun leaveGroup(conversationId: String): Result<Unit>
}
