package com.nexachat.data

import android.content.Context
import android.net.Uri
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
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Suspend bodies for [ContactsRepository]. */
internal object ContactsRepositoryFlow {

    private const val MAX_AVATAR_BYTES = 25 * 1024 * 1024

    @JvmStatic
    fun newContactsCache(): MutableStateFlow<List<Contact>?> = MutableStateFlow(null)

    @JvmStatic
    suspend fun getMe(userApi: UserApi): Result<UserDto> = call(userApi.getMe())

    @JvmStatic
    suspend fun uploadAvatar(
        userApi: UserApi,
        context: Context,
        uri: Uri,
    ): Result<UserDto> = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(MAX_AVATAR_BYTES + 1)
                var read = 0
                var total = 0
                while (total <= MAX_AVATAR_BYTES &&
                    input.read(buffer, total, MAX_AVATAR_BYTES + 1 - total).also { read = it } > 0
                ) {
                    total += read
                }
                if (total > MAX_AVATAR_BYTES) null else buffer.copyOf(total)
            }
        } catch (e: IOException) {
            null
        } ?: return@withContext Result.failure(ApiError.Network("avatar read failed"))
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "image/jpeg"
        val part = MultipartBody.Part.createFormData(
            "file",
            uri.lastPathSegment ?: "avatar",
            bytes.toRequestBody(mime.toMediaTypeOrNull()),
        )
        when (val uploaded = call(userApi.uploadAvatar(part))) {
            is Result.Success -> call(userApi.getMe())
            is Result.Failure -> Result.cast(uploaded)
        }
    }

    @JvmStatic
    suspend fun updateDisplayName(userApi: UserApi, displayName: String): Result<UserDto> =
        call(userApi.updateMe(UpdateProfileRequest(displayName.trim())))

    @JvmStatic
    suspend fun searchByPhone(userApi: UserApi, rawPhone: String): Result<List<UserDto>> =
        call(userApi.searchUsers(PhoneNormalizer.toE164(rawPhone), null))

    @JvmStatic
    suspend fun searchByName(userApi: UserApi, prefix: String): Result<List<UserDto>> =
        call(userApi.searchUsers(null, prefix.trim()))

    @JvmStatic
    suspend fun contacts(
        userApi: UserApi,
        cache: MutableStateFlow<List<Contact>?>,
    ): Result<List<Contact>> =
        when (val result = call(userApi.getContacts())) {
            is Result.Success ->
                Result.Success(result.value.mapNotNull { it.toContact() })
                    .also { cache.value = it.value }
            is Result.Failure -> Result.cast(result)
        }

    @JvmStatic
    suspend fun addContact(
        userApi: UserApi,
        cache: MutableStateFlow<List<Contact>?>,
        rawPhone: String,
    ): Result<Contact> =
        when (
            val result = call(
                userApi.addContact(AddContactRequest(PhoneNormalizer.toE164(rawPhone))),
                mapError = ::contactWriteError,
            )
        ) {
            is Result.Success ->
                result.value.unwrap().toContact()
                    ?.let { contact ->
                        cache.update {
                            (listOf(contact) + (it ?: emptyList()))
                                .distinctBy(Contact::userId)
                        }
                        Result.Success(contact)
                    }
                    ?: Result.failure(ApiError.Unknown("UNEXPECTED_SHAPE"))
            is Result.Failure -> Result.cast(result)
        }

    @JvmStatic
    suspend fun setAlias(
        userApi: UserApi,
        cache: MutableStateFlow<List<Contact>?>,
        userId: String,
        aliasName: String?,
    ): Result<Contact> =
        when (val result = call(userApi.updateContact(userId, UpdateContactRequest(aliasName)))) {
            is Result.Success ->
                result.value.unwrap().toContact()
                    ?.let { updated ->
                        cache.update { list ->
                            list?.map { if (it.userId == updated.userId) updated else it }
                        }
                        Result.Success(updated)
                    }
                    ?: Result.failure(ApiError.Unknown("UNEXPECTED_SHAPE"))
            is Result.Failure -> Result.cast(result)
        }

    @JvmStatic
    suspend fun removeContact(
        userApi: UserApi,
        cache: MutableStateFlow<List<Contact>?>,
        userId: String,
    ): Result<Unit> {
        val result = call(userApi.removeContact(userId))
        return when (result) {
            is Result.Success -> {
                cache.update { list -> list?.filterNot { it.userId == userId } }
                Result.Success(Unit)
            }
            is Result.Failure -> Result.cast(result)
        }
    }

    @JvmStatic
    suspend fun openDirectConversation(
        userApi: UserApi,
        userId: String?,
        phone: String?,
    ): Result<String> =
        when (
            val result = call(
                userApi.openDirectConversation(
                    DirectConversationRequest(
                        userId,
                        if (userId == null) phone?.let { PhoneNormalizer.toE164(it) } else null,
                    ),
                ),
            )
        ) {
            is Result.Success -> Result.Success(result.value.id)
            is Result.Failure -> Result.cast(result)
        }

    private fun ContactDto.toContact(): Contact? {
        val id = resolvedUserId ?: return null
        return Contact(
            userId = id,
            phone = phone.orEmpty(),
            displayName = displayName ?: phone.orEmpty(),
            aliasName = aliasName,
            avatarUrl = avatarUrl,
            createdAt = createdAt,
        )
    }

    private fun contactWriteError(httpStatus: Int, serverCode: String?): ApiError = when {
        serverCode == "ALREADY_CONTACT" || serverCode == "PHONE_EXISTS" -> ApiError.PhoneExists.INSTANCE
        else -> ApiError.from(httpStatus, serverCode)
    }

    private suspend fun <T> call(
        retrofitCall: retrofit2.Call<T>,
        mapError: (Int, String?) -> ApiError = ApiError::from,
    ): Result<T> =
        when (val result = safeApiCallCatching(retrofitCall)) {
            is ApiResult.Success -> Result.Success(result.body)
            is ApiResult.HttpError -> Result.failure(mapError(result.status, result.errorCode))
            is ApiResult.NetworkError -> Result.failure(ApiError.Network(result.cause.message))
        }

    private suspend fun <T> safeApiCallCatching(
        retrofitCall: retrofit2.Call<T>,
    ): ApiResult<T> =
        try {
            safeApiCall(retrofitCall)
        } catch (_: ClassCastException) {
            ApiResult.HttpError(200, "UNEXPECTED_EMPTY_BODY")
        }
}
