package com.nexachat.auth;

import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.auth.dto.ChangePasswordRequest;
import com.nexachat.auth.dto.LoginRequest;
import com.nexachat.auth.dto.RefreshRequest;
import com.nexachat.auth.dto.RegisterRequest;
import com.nexachat.common.error.ApiException;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.message.MessageRecallProperties;
import com.nexachat.user.PhoneService;
import com.nexachat.user.User;
import com.nexachat.user.UserAccountCreatedEvent;
import com.nexachat.user.UserRepository;
import com.nexachat.ws.StompRevocationFence;
import com.nexachat.user.dto.UserResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private PhoneService phoneService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private TokenService tokenService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private AccessTokenRevocationService accessTokenRevocationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    @Mock
    private StompRevocationFence revocationFence;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // lenient: these stubs cover the happy paths of several tests and the
        // constructor-time dummy-hash encode.
        lenient().when(passwordEncoder.encode(anyString()))
                .thenAnswer(inv -> "hashed:" + inv.getArgument(0));
        lenient().when(jwtService.generateAccessToken(any())).thenReturn("access-jwt");
        lenient().when(jwtService.accessTokenExpiresIn()).thenReturn(900L);
        lenient().when(tokenService.issue(any(), any())).thenReturn("refresh-opaque");
        lenient().when(userRepository.saveAndFlush(any())).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            if (user == null) {
                return null;
            }
            if (user.getId() == null) {
                ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            }
            return user;
        });
        lenient().when(rateLimitService.tryConsume(anyString(), anyLong(), any()))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(transactionManagerProvider.getObject()).thenReturn(transactionManager);
        authService = new AuthService(userRepository, entityManager, phoneService, passwordEncoder,
                tokenService, accessTokenRevocationService, rateLimitService,
                new AuthResponseFactory(jwtService, new MessageRecallProperties()),
                (boundary, operation, userId, tokenId) -> { }, eventPublisher, transactionManagerProvider,
                revocationFence);
    }

    private static User userWithId(String phone, String passwordHash, String displayName) {
        User user = User.create(phone, passwordHash, displayName);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    // ------------------------------------------------------------- register

    @Test
    void register_success_normalizesHashesAndIssuesTokens() {
        when(phoneService.toE164("0912345678")).thenReturn("+84912345678");
        when(userRepository.existsByPhoneAndDeletedAtIsNull("+84912345678")).thenReturn(false);

        AuthResponse response = authService.register(
                new RegisterRequest("0912345678", "secret123", "  Minh Tran  "), "JUnit-Agent");

        assertThat(response.user().phone()).isEqualTo("+84912345678");
        assertThat(response.user().displayName()).isEqualTo("Minh Tran");
        assertThat(response.accessToken()).isEqualTo("access-jwt");
        assertThat(response.accessTokenExpiresIn()).isEqualTo(900);
        assertThat(response.refreshToken()).isEqualTo("refresh-opaque");
        assertThat(response.recallWindowSeconds()).isEqualTo(86_400);
        assertThat(response.groupAdminMayRecallAfterWindow()).isTrue();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        verify(eventPublisher).publishEvent(new UserAccountCreatedEvent(saved.getValue().getId()));
        assertThat(saved.getValue().getPhone()).isEqualTo("+84912345678");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed:secret123");
        verify(tokenService).issue(saved.getValue(), "JUnit-Agent");
    }

    @Test
    void register_duplicatePhone_throwsPhoneExists409() {
        when(phoneService.toE164(anyString())).thenReturn("+84912345678");
        when(userRepository.existsByPhoneAndDeletedAtIsNull("+84912345678")).thenReturn(true);

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.register(new RegisterRequest("0912345678", "secret123", "Minh"), null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.PHONE_EXISTS);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_uniqueConstraintRaceOnSave_mapsToPhoneExists() {
        when(phoneService.toE164(anyString())).thenReturn("+84912345678");
        when(userRepository.existsByPhoneAndDeletedAtIsNull("+84912345678")).thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("users_phone_active_key"));

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.register(new RegisterRequest("0912345678", "secret123", "Minh"), null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.PHONE_EXISTS);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void register_invalidPhone_throwsValidationFailed400() {
        when(phoneService.toE164(anyString())).thenThrow(new IllegalArgumentException("not a number"));

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.register(new RegisterRequest("abc", "secret123", "Minh"), null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        verify(userRepository, never()).existsByPhoneAndDeletedAtIsNull(anyString());
    }

    // ---------------------------------------------------------------- login

    @Test
    void login_success_issuesTokens() {
        User user = userWithId("+84912345678", "stored-hash", "Minh");
        when(phoneService.toE164("0912345678")).thenReturn("+84912345678");
        when(userRepository.findByPhoneAndDeletedAtIsNull("+84912345678")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "stored-hash")).thenReturn(true);

        AuthResponse response = authService.login(new LoginRequest("0912345678", "secret123"), "JUnit");

        assertThat(response.user().id()).isEqualTo(user.getId());
        assertThat(response.accessToken()).isEqualTo("access-jwt");
        assertThat(response.refreshToken()).isEqualTo("refresh-opaque");
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials401() {
        User user = userWithId("+84912345678", "stored-hash", "Minh");
        when(phoneService.toE164(anyString())).thenReturn("+84912345678");
        when(userRepository.findByPhoneAndDeletedAtIsNull("+84912345678")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("nope", "stored-hash")).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.login(new LoginRequest("0912345678", "nope"), null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INVALID_CREDENTIALS);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(tokenService, never()).issue(any(), any());
    }

    @Test
    void login_unknownUser_throwsIdenticalInvalidCredentials_andPaysBcryptCompare() {
        when(phoneService.toE164(anyString())).thenReturn("+84999999999");
        when(userRepository.findByPhoneAndDeletedAtIsNull("+84999999999")).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.login(new LoginRequest("0999999999", "whatever123"), null));

        // Same code/status/message as the wrong-password case → no account enumeration.
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INVALID_CREDENTIALS);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        // Timing equalization: one bcrypt compare still runs against the dummy hash.
        verify(passwordEncoder).matches("whatever123", "hashed:nexachat-timing-equalization");
    }

    @Test
    void login_unparseablePhone_throwsInvalidCredentialsNotValidation() {
        when(phoneService.toE164(anyString())).thenThrow(new IllegalArgumentException("bad phone"));

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.login(new LoginRequest("not-a-phone", "whatever123"), null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INVALID_CREDENTIALS);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(userRepository, never()).findByPhoneAndDeletedAtIsNull(anyString());
        verify(passwordEncoder).matches("whatever123", "hashed:nexachat-timing-equalization");
    }

    // -------------------------------------------------------------- refresh

    @Test
    void refresh_success_returnsRotatedTokenAndNewAccess() {
        User user = userWithId("+84912345678", "stored-hash", "Minh");
        AuthResponse original = new AuthResponse(UserResponse.from(user), "access-jwt", 900,
                "rt-2", 86_400, true);
        UUID predecessorId = UUID.randomUUID();
        when(tokenService.rotate("rt-1", null, "JUnit"))
                .thenReturn(new TokenService.RotationResult(user.getId(), predecessorId, original));
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));

        AuthResponse response = authService.refresh(new RefreshRequest("rt-1"), "JUnit");

        assertThat(response.refreshToken()).isEqualTo("rt-2");
        assertThat(response.accessToken()).isEqualTo("access-jwt");
        assertThat(response.user().id()).isEqualTo(user.getId());
    }

    @Test
    void refresh_reusedToken_revokesWholeFamilyAndThrows401() {
        UUID userId = UUID.randomUUID();
        when(tokenService.rotate("stolen", null, null)).thenThrow(RefreshTokenException.reused(userId));

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.refresh(new RefreshRequest("stolen"), null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.TOKEN_REUSED);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        // Both refresh and access tokens must be revoked in the separate
        // transaction that commits before the 401 is returned.
        verify(accessTokenRevocationService).revokeRefreshFamilyAndAccessTokens(
                userId, RefreshReplayConfiguration.Operation.REUSE_REVOKE);
        verify(tokenService, never()).revokeAllForUser(userId);
    }

    @Test
    void refresh_unknownToken_throwsInvalidWithoutFamilyRevoke() {
        when(tokenService.rotate("ghost", null, null)).thenThrow(RefreshTokenException.invalid(null));

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.refresh(new RefreshRequest("ghost"), null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.TOKEN_INVALID);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(tokenService, never()).revokeAllForUser(any());
    }

    // ------------------------------------------------------- change password

    @Test
    void changePassword_wrongOld_throwsWrongPasswordAndKeepsSessions() {
        User user = userWithId("+84912345678", "stored-hash", "Minh");
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("bad-old", "stored-hash")).thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> authService.changePassword(user.getId(), new ChangePasswordRequest("bad-old", "newpass123", null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.WRONG_PASSWORD);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(user.getPasswordHash()).isEqualTo("stored-hash");
        verify(tokenService, never()).revokeAllForUser(any());
    }

    @Test
    void changePassword_success_rehashesAndRevokesAllSessions() {
        User user = userWithId("+84912345678", "stored-hash", "Minh");
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-pass", "stored-hash")).thenReturn(true);

        authService.changePassword(user.getId(), new ChangePasswordRequest("old-pass", "newpass123", null));

        assertThat(user.getPasswordHash()).isEqualTo("hashed:newpass123");
        verify(userRepository).save(user);
        verify(tokenService).revokeAllForUser(user.getId());
        verify(accessTokenRevocationService).revoke(user.getId());
    }

    @Test
    void changePassword_withKeepToken_revokesOnlyOtherSessions() {
        User user = userWithId("+84912345678", "stored-hash", "Minh");
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-pass", "stored-hash")).thenReturn(true);

        authService.changePassword(user.getId(), new ChangePasswordRequest("old-pass", "newpass123", "rt-current"));

        verify(tokenService).revokeAllForUserExcept(user.getId(), "rt-current");
        verify(tokenService, never()).revokeAllForUser(any());
        verify(accessTokenRevocationService).revoke(user.getId());
    }

    @Test
    void changePassword_unknownUser_throwsUserNotFound404() {
        UUID ghost = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(ghost)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> authService.changePassword(ghost, new ChangePasswordRequest("old", "newpass123", null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(tokenService, never()).revokeAllForUser(any());
    }

    // --------------------------------------------------------------- logout

    @Test
    void logout_delegatesToOwnershipCheckedRevoke() {
        UUID userId = UUID.randomUUID();

        // The ownership filter lives in TokenService.revokeIfOwned — a
        // foreign refresh token must NOT be revoked on someone else's
        // logout (its negative case is proven in TokenServiceTest).
        authService.logout(userId, "rt-claimed-by-caller");

        verify(tokenService).revokeIfOwned("rt-claimed-by-caller", userId);
        verify(accessTokenRevocationService).revoke(userId);
    }

    @Test
    void logoutAll_revokesWholeFamily() {
        UUID userId = UUID.randomUUID();

        authService.logoutAll(userId);

        verify(tokenService).revokeAllForUser(userId);
        verify(accessTokenRevocationService).revoke(userId);
    }
}
