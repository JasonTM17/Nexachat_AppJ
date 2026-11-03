package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.PhoneNormalizer
import com.nexachat.core.common.Result
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.UserApi
import com.nexachat.core.network.dto.AddContactRequest
import com.nexachat.core.network.dto.ContactDto
import com.nexachat.core.network.dto.DirectConversationRequest
import com.nexachat.core.network.dto.UpdateContactRequest
import com.nexachat.core.network.dto.UpdateProfileRequest
import com.nexachat.core.network.dto.UserDto
import com.nexachat.core.network.safeApiCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Contact as the feature layer sees it — the wire [ContactDto] tolerates two
 * id spellings (`contactUserId`/`userId`) and fully-nullable fields, so this
 * domain shape is what UI code should depend on.
 */
data class Contact(
    val userId: String,
    val phone: String,
    val displayName: String,
    val aliasName: String? = null,
    val createdAt: String? = null,
) {
    /** Label shown in lists: the owner-chosen alias beats the profile name. */
    val primaryLabel: String get() = aliasName?.takeIf { it.isNotBlank() } ?: displayName
}

/**
 * Contacts + user-discovery boundary: speaks Retrofit to '/api/v1/users',
 * '/api/v1/contacts' and '/api/v1/conversations/direct' via [UserApi], and
 * translates the server error envelope into [ApiError].
 *
 * Phones are normalized to E.164 (region VN) best-effort before hitting the
 * wire — the server re-validates, so a parse failure sends the raw input.
 */
@Singleton
class ContactsRepository @Inject constructor(
    private val userApi: UserApi,
) {

    /**
     * Shared, mutation-aware contact cache. Every nav destination gets its
     * own ViewModel, so per-screen snapshots drifted — a contact added from
     * the search screen never reached the contacts list until re-entry.
     * All screens now observe this single source; every successful
     * fetch/add/alias/remove keeps it current. `null` = not yet loaded.
     */
    private val _contacts = MutableStateFlow<List<Contact>?>(null)
    val contactsFlow: StateFlow<List<Contact>?> = _contacts.asStateFlow()

    suspend fun getMe(): Result<UserDto> =
        call { userApi.getMe() }

    suspend fun updateDisplayName(displayName: String): Result<UserDto> =
        call { userApi.updateMe(UpdateProfileRequest(displayName = displayName.trim())) }

    suspend fun searchByPhone(rawPhone: String): Result<List<UserDto>> =
        call { userApi.searchUsers(phone = PhoneNormalizer.toE164(rawPhone)) }

    suspend fun searchByName(prefix: String): Result<List<UserDto>> =
        call { userApi.searchUsers(q = prefix.trim()) }

    suspend fun contacts(): Result<List<Contact>> =
        when (val result = call { userApi.getContacts() }) {
            is Result.Success ->
                Result.Success(result.value.mapNotNull { it.toContact() })
                    .also { _contacts.value = it.value }
            is Result.Failure -> result
        }

    /**
     * Adds a contact by phone. A 409 `ALREADY_CONTACT`/`PHONE_EXISTS` maps to
     * [ApiError.PhoneExists] — the contacts UI renders that variant as the
     * "already in your contacts" state.
     */
    suspend fun addContact(rawPhone: String): Result<Contact> =
        when (
            val result = call(
                mapError = ::contactWriteError,
            ) { userApi.addContact(AddContactRequest(phone = PhoneNormalizer.toE164(rawPhone))) }
        ) {
            is Result.Success ->
                result.value.unwrap().toContact()
                    ?.let { contact ->
                        _contacts.update {
                            (listOf(contact) + (it ?: emptyList()))
                                .distinctBy(Contact::userId)
                        }
                        Result.Success(contact)
                    }
                    ?: Result.Failure(ApiError.Unknown("UNEXPECTED_SHAPE"))
            is Result.Failure -> result
        }

    suspend fun setAlias(userId: String, aliasName: String?): Result<Contact> =
        when (
            val result = call {
                userApi.updateContact(userId, UpdateContactRequest(aliasName = aliasName))
            }
        ) {
            is Result.Success ->
                result.value.unwrap().toContact()
                    ?.let { updated ->
                        _contacts.update { list ->
                            list?.map { if (it.userId == updated.userId) updated else it }
                        }
                        Result.Success(updated)
                    }
                    ?: Result.Failure(ApiError.Unknown("UNEXPECTED_SHAPE"))
            is Result.Failure -> result
        }

    suspend fun removeContact(userId: String): Result<Unit> =
        call { userApi.removeContact(userId) }
            .also { result ->
                if (result is Result.Success) {
                    _contacts.update { list -> list?.filterNot { it.userId == userId } }
                }
            }

    /**
     * Opens (or creates) the direct conversation with [userId] — falling back
     * to [phone] when no id is known — and returns the conversation id. The
     * server enforces idempotency (`direct_key`), so repeats return the same id.
     *
     * The request is XOR: [userId] wins when present and [phone] is omitted —
     * sending both is a 400 on the server side.
     */
    suspend fun openDirectConversation(userId: String? = null, phone: String? = null): Result<String> =
        when (
            val result = call {
                userApi.openDirectConversation(
                    DirectConversationRequest(
                        userId = userId,
                        phone = if (userId == null) phone?.let { PhoneNormalizer.toE164(it) } else null,
                    ),
                )
            }
        ) {
            is Result.Success -> Result.Success(result.value.id)
            is Result.Failure -> result
        }

    // ---- internals ----

    private fun ContactDto.toContact(): Contact? {
        val id = resolvedUserId ?: return null
        return Contact(
            userId = id,
            phone = phone.orEmpty(),
            displayName = displayName ?: phone.orEmpty(),
            aliasName = aliasName,
            createdAt = createdAt,
        )
    }

    /**
     * Contact-write errors where the server's code needs remapping before the
     * generic [ApiError.from] table applies.
     */
    private fun contactWriteError(httpStatus: Int, serverCode: String?): ApiError = when {
        serverCode == "ALREADY_CONTACT" || serverCode == "PHONE_EXISTS" -> ApiError.PhoneExists
        else -> ApiError.from(httpStatus, serverCode)
    }

    private suspend fun <T> call(
        mapError: (Int, String?) -> ApiError = ApiError.Companion::from,
        block: suspend () -> retrofit2.Response<T>,
    ): Result<T> =
        when (val result = safeApiCallCatching(block)) {
            is ApiResult.Success -> Result.Success(result.body)
            is ApiResult.HttpError ->
                Result.Failure(mapError(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.Failure(ApiError.Network(result.cause.message))
        }

    /**
     * safeApiCall casts an empty 2xx body to `Unit as T` — an erased cast that
     * only blows up when `Success.body` is later read as a non-Unit type.
     * Convert that ClassCastException into a domain failure instead of a crash
     * (same guard as AuthRepository).
     */
    private suspend fun <T> safeApiCallCatching(
        block: suspend () -> retrofit2.Response<T>,
    ): ApiResult<T> =
        try {
            safeApiCall(block)
        } catch (_: ClassCastException) {
            ApiResult.HttpError(status = 200, errorCode = "UNEXPECTED_EMPTY_BODY")
        }
}
