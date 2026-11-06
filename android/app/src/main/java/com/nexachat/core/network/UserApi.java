package com.nexachat.core.network;

import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.AddContactRequest;
import com.nexachat.core.network.dto.AvatarResponse;
import com.nexachat.core.network.dto.ContactDto;
import com.nexachat.core.network.dto.ContactEnvelopeDto;
import com.nexachat.core.network.dto.DirectConversationRequest;
import com.nexachat.core.network.dto.DirectConversationResponse;
import com.nexachat.core.network.dto.UpdateContactRequest;
import com.nexachat.core.network.dto.UpdateProfileRequest;
import com.nexachat.core.network.dto.UpdateUserSettingsRequest;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.network.dto.UserSettingsDto;
import java.util.List;
import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.PATCH;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * '/api/v1/users', '/api/v1/contacts' and '/api/v1/conversations/direct'
 * endpoints. Returns {@link Call}; repositories pass each call to
 * {@code safeApiCall(Call)} (see {@code ApiResult.kt}). All routes require the Bearer token
 * added by AuthInterceptor.
 */
public interface UserApi {

    /**
     * User discovery (rate-limited on the server). Exactly one of phone/q is
     * sent — Retrofit drops null query params.
     */
    @GET("api/v1/users/search")
    Call<List<UserDto>> searchUsers(@Nullable @Query("phone") String phone, @Nullable @Query("q") String q);

    @GET("api/v1/users/me")
    Call<UserDto> getMe();

    @GET("api/v1/users/me/settings")
    Call<UserSettingsDto> getSettings();

    @PATCH("api/v1/users/me/settings")
    Call<UserSettingsDto> updateSettings(@Body UpdateUserSettingsRequest body);

    @PATCH("api/v1/users/me")
    Call<UserDto> updateMe(@Body UpdateProfileRequest body);

    @Multipart
    @POST("api/v1/users/me/avatar")
    Call<AvatarResponse> uploadAvatar(@Part MultipartBody.Part file);

    @GET("api/v1/contacts")
    Call<List<ContactDto>> getContacts();

    @POST("api/v1/contacts")
    Call<ContactEnvelopeDto> addContact(@Body AddContactRequest body);

    @PATCH("api/v1/contacts/{userId}")
    Call<ContactEnvelopeDto> updateContact(@Path("userId") String userId, @Body UpdateContactRequest body);

    @DELETE("api/v1/contacts/{userId}")
    Call<Void> removeContact(@Path("userId") String userId);

    @POST("api/v1/conversations/direct")
    Call<DirectConversationResponse> openDirectConversation(@Body DirectConversationRequest body);
}
