package com.nexachat.data

import android.net.Uri
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.UserDto
import kotlinx.coroutines.flow.StateFlow

/**
 * Contact as the feature layer sees it. The wire DTO tolerates two id spellings
 * and nullable fields; UI code depends on this shape.
 */
data class Contact(
    val userId: String,
    val phone: String,
    val displayName: String,
    val aliasName: String? = null,
    val avatarUrl: String? = null,
    val createdAt: String? = null,
) {
    /** Label shown in lists: the owner-chosen alias beats the profile name. */
    val primaryLabel: String get() = aliasName?.takeIf { it.isNotBlank() } ?: displayName
}

/** Coroutine-facing contacts API implemented by the Java repository. */
interface ContactsRepositoryApi {
    val contactsFlow: StateFlow<List<Contact>?>

    suspend fun getMe(): Result<UserDto>

    suspend fun uploadAvatar(uri: Uri): Result<UserDto>

    suspend fun updateDisplayName(displayName: String): Result<UserDto>

    suspend fun searchByPhone(rawPhone: String): Result<List<UserDto>>

    suspend fun searchByName(prefix: String): Result<List<UserDto>>

    suspend fun contacts(): Result<List<Contact>>

    suspend fun addContact(rawPhone: String): Result<Contact>

    suspend fun setAlias(userId: String, aliasName: String?): Result<Contact>

    suspend fun removeContact(userId: String): Result<Unit>

    suspend fun openDirectConversation(
        userId: String? = null,
        phone: String? = null,
    ): Result<String>
}
