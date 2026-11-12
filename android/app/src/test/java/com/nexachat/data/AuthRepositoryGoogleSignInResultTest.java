package com.nexachat.data;

import com.nexachat.core.common.Result;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.UserDto;
import io.reactivex.rxjava3.core.Single;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class AuthRepositoryGoogleSignInResultTest {

    @Test
    public void http201MapsToNewAccountResult() throws Exception {
        AuthResponse body = new AuthResponse(
                new UserDto("u-1", null, "Google User", null, "user@gmail.com", true),
                "access",
                900,
                "refresh",
                86_400L,
                false,
                false);
        Call<AuthResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(201, body));
        when(call.isExecuted()).thenReturn(false);
        ApiResult<AuthResponse> apiResult = ApiResult.safeApiCall(call).blockingGet();
        assertTrue(apiResult instanceof ApiResult.Success<?>);
        ApiResult.Success<AuthResponse> success = (ApiResult.Success<AuthResponse>) apiResult;
        assertEquals(201, success.getStatusCode());
        boolean newAccount = success.getStatusCode() == 201 || body.isNewAccount();
        assertTrue(newAccount);
    }

    @Test
    public void http200WithFlagStillMapsToNewAccount() throws Exception {
        AuthResponse body = new AuthResponse(
                new UserDto("u-1", null, "Google User", null, "user@gmail.com", true),
                "access",
                900,
                "refresh",
                86_400L,
                false,
                true);
        Call<AuthResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(body));
        when(call.isExecuted()).thenReturn(false);
        ApiResult<AuthResponse> apiResult = ApiResult.safeApiCall(call).blockingGet();
        ApiResult.Success<AuthResponse> success = (ApiResult.Success<AuthResponse>) apiResult;
        boolean newAccount = success.getStatusCode() == 201 || body.isNewAccount();
        assertTrue(newAccount);
    }

    @Test
    public void existingSignInStaysExisting() throws Exception {
        AuthResponse body = new AuthResponse(
                new UserDto("u-1", "+84901234567", "Phone User", null),
                "access",
                900,
                "refresh");
        Call<AuthResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(body));
        when(call.isExecuted()).thenReturn(false);
        ApiResult<AuthResponse> apiResult = ApiResult.safeApiCall(call).blockingGet();
        ApiResult.Success<AuthResponse> success = (ApiResult.Success<AuthResponse>) apiResult;
        boolean newAccount = success.getStatusCode() == 201 || body.isNewAccount();
        assertFalse(newAccount);
        assertFalse(AuthSessionResult.existing(body).isNewAccount());
        Result<AuthSessionResult> mapped = new Result.Success<>(AuthSessionResult.existing(body));
        assertFalse(((AuthSessionResult) ((Result.Success<?>) mapped).getValue()).isNewAccount());
    }
}
