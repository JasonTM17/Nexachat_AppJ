package com.nexachat.data;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.dto.PasswordResetConfirmRequest;
import com.nexachat.core.network.dto.PasswordResetRequest;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class PasswordResetRepositoryTest {

  @Before
  public void setUp() {
    RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
  }

  @After
  public void tearDown() {
    RxJavaPlugins.reset();
  }

  @Test
  public void requestResetMaps202ToSuccess() {
    TestAuthApi api = new TestAuthApi();
    api.request = FakeCall.successVoid(202);
    PasswordResetRepository repository = new PasswordResetRepository(api);
    Result<Unit> result = repository.requestResetCode("0912345678").blockingGet();
    assertTrue(result instanceof Result.Success<?>);
    assertEquals("+84912345678", api.lastRequestPhone);
  }

  @Test
  public void confirmResetMapsInvalidCode() {
    TestAuthApi api = new TestAuthApi();
    api.confirm = FakeCall.errorVoid(
            400, "{\"error\":{\"code\":\"INVALID_OR_EXPIRED_CODE\"}}");
    PasswordResetRepository repository = new PasswordResetRepository(api);
    Result<Unit> result = repository
            .confirmReset("+84912345678", "123456", "newpass12")
            .blockingGet();
    assertTrue(result instanceof Result.Failure<?>);
    assertEquals(
            ApiError.InvalidOrExpiredCode.INSTANCE,
            ((Result.Failure<?>) result).getError());
  }

  @Test
  public void passwordResetPathsArePublicOnAuthApi() throws Exception {
    retrofit2.http.POST request = AuthApi.class
            .getMethod("requestPasswordReset", PasswordResetRequest.class)
            .getAnnotation(retrofit2.http.POST.class);
    retrofit2.http.POST confirm = AuthApi.class
            .getMethod("confirmPasswordReset", PasswordResetConfirmRequest.class)
            .getAnnotation(retrofit2.http.POST.class);
    assertEquals("api/v1/auth/password-reset/request", request.value());
    assertEquals("api/v1/auth/password-reset/confirm", confirm.value());
    assertTrue(AuthApi.isPublicAuthPath("/api/v1/auth/password-reset/request"));
    assertTrue(AuthApi.isPublicAuthPath("/api/v1/auth/password-reset/confirm"));
  }

  private static final class TestAuthApi implements AuthApi {
    Call<Void> request = FakeCall.successVoid(202);
    Call<Void> confirm = FakeCall.successVoid(204);
    String lastRequestPhone;

    @Override
    public Call<com.nexachat.core.network.dto.AuthResponse> register(
            com.nexachat.core.network.dto.RegisterRequest body) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Call<com.nexachat.core.network.dto.AuthResponse> login(
            com.nexachat.core.network.dto.LoginRequest body) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Call<com.nexachat.core.network.dto.AuthResponse> refresh(
            com.nexachat.core.network.dto.RefreshRequest body) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Call<com.nexachat.core.network.dto.AuthResponse> refresh(
            String idempotencyKey, com.nexachat.core.network.dto.RefreshRequest body) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Call<Void> logout(com.nexachat.core.network.dto.LogoutRequest body) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Call<Void> logoutAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Call<Void> changePassword(
            com.nexachat.core.network.dto.ChangePasswordRequest body) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Call<Void> requestPasswordReset(PasswordResetRequest body) {
      lastRequestPhone = body.getPhone();
      return request;
    }

    @Override
    public Call<Void> confirmPasswordReset(PasswordResetConfirmRequest body) {
      return confirm;
    }
  }

  private static final class FakeCall<T> implements Call<T> {
    private final Response<T> response;
    private final RuntimeException failure;

    private FakeCall(Response<T> response, RuntimeException failure) {
      this.response = response;
      this.failure = failure;
    }

    static Call<Void> successVoid(int code) {
      return new FakeCall<>(Response.success(code, null), null);
    }

    static Call<Void> errorVoid(int code, String json) {
      return new FakeCall<>(
              Response.error(
                      code,
                      ResponseBody.create(json, MediaType.get("application/json"))),
              null);
    }

    @Override
    public Response<T> execute() throws IOException {
      if (failure != null) {
        throw failure;
      }
      return response;
    }

    @Override
    public void enqueue(Callback<T> callback) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isExecuted() {
      return false;
    }

    @Override
    public void cancel() {
    }

    @Override
    public boolean isCanceled() {
      return false;
    }

    @Override
    public Call<T> clone() {
      return this;
    }

    @Override
    public Request request() {
      return new Request.Builder().url("http://localhost/").build();
    }

    @Override
    public Timeout timeout() {
      return Timeout.NONE;
    }
  }
}
