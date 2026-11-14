package com.nexachat.auth.google;

import com.nexachat.auth.AuthResponseFactory;
import com.nexachat.auth.RateLimitService;
import com.nexachat.auth.TokenService;
import com.nexachat.auth.google.dto.GoogleSignInRequest;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.MessageRecallProperties;
import com.nexachat.auth.JwtService;
import com.nexachat.user.User;
import com.nexachat.user.UserAccountCreatedEvent;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleSignInServiceTest {

    private static final String SUBJECT = "google-sub-new";
    private static final String EMAIL = "new.user@example.com";

    @Mock private GoogleIdTokenVerifier verifier;
    @Mock private GoogleNonceService nonceService;
    @Mock private UserIdentityRepository identityRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenService tokenService;
    @Mock private JwtService jwtService;
    @Mock private RateLimitService rateLimitService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    @Mock private ApplicationEventPublisher eventPublisher;

    private GoogleSignInService service;

    @BeforeEach
    void setUp() {
        GoogleSignInProperties properties = new GoogleSignInProperties(
                true, GoogleTestKeys.AUDIENCE, "http://test/certs", 300,
                java.util.List.of(GoogleTestKeys.ANDROID_CLIENT_ID), true, null);
        AuthResponseFactory responseFactory = new AuthResponseFactory(
                jwtService, new MessageRecallProperties());
        lenient().when(jwtService.generateAccessToken(any())).thenReturn("access");
        lenient().when(jwtService.accessTokenExpiresIn()).thenReturn(900L);
        lenient().when(tokenService.issue(any(), any())).thenReturn("refresh");
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(transactionManagerProvider.getObject()).thenReturn(transactionManager);
        lenient().when(nonceService.consume(any(), any(), any(), any())).thenReturn(true);
        service = new GoogleSignInService(properties, verifier, nonceService, identityRepository,
                userRepository, passwordEncoder, tokenService, responseFactory, rateLimitService,
                transactionManagerProvider, eventPublisher);
    }

    @Test
    void signUpDisabled_returnsNotLinked() {
        GoogleSignInProperties off = new GoogleSignInProperties(
                true, GoogleTestKeys.AUDIENCE, "http://test/certs", 300,
                java.util.List.of(GoogleTestKeys.ANDROID_CLIENT_ID), false, null);
        service = new GoogleSignInService(off, verifier, nonceService, identityRepository,
                userRepository, passwordEncoder, tokenService,
                new AuthResponseFactory(jwtService, new MessageRecallProperties()),
                rateLimitService, transactionManagerProvider, eventPublisher);
        when(verifier.verify("token", "nonce")).thenReturn(
                new GoogleIdTokenClaims(SUBJECT, EMAIL, true, "New User"));
        when(identityRepository.findByProviderAndSubject(UserIdentity.PROVIDER_GOOGLE, SUBJECT))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.signIn(new GoogleSignInRequest("token", "nonce"), "device"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.GOOGLE_ACCOUNT_NOT_LINKED);
    }

    @Test
    void createAccount_returns201Payload() {
        when(verifier.verify("token", "nonce")).thenReturn(
                new GoogleIdTokenClaims(SUBJECT, EMAIL, true, "New User"));
        when(identityRepository.findByProviderAndSubject(UserIdentity.PROVIDER_GOOGLE, SUBJECT))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmailAndEmailVerifiedAtIsNotNullAndDeletedAtIsNull(EMAIL))
                .thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            return user;
        });

        GoogleSignInResult result = service.signIn(new GoogleSignInRequest("token", "nonce"), "device");

        assertThat(result.created()).isTrue();
        assertThat(result.response().newAccount()).isTrue();
        assertThat(result.response().user().phone()).isNull();
        verify(eventPublisher).publishEvent(any(UserAccountCreatedEvent.class));
    }

    @Test
    void unverifiedEmail_returnsSignInFailed() {
        when(verifier.verify("token", "nonce")).thenReturn(
                new GoogleIdTokenClaims(SUBJECT, EMAIL, false, null));
        when(identityRepository.findByProviderAndSubject(UserIdentity.PROVIDER_GOOGLE, SUBJECT))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.signIn(new GoogleSignInRequest("token", "nonce"), "device"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.GOOGLE_SIGN_IN_FAILED);
    }

    @Test
    void emailConflict_returnsEmailInUse() {
        when(verifier.verify("token", "nonce")).thenReturn(
                new GoogleIdTokenClaims(SUBJECT, EMAIL, true, "New User"));
        when(identityRepository.findByProviderAndSubject(UserIdentity.PROVIDER_GOOGLE, SUBJECT))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmailAndEmailVerifiedAtIsNotNullAndDeletedAtIsNull(EMAIL))
                .thenReturn(true);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.signIn(new GoogleSignInRequest("token", "nonce"), "device"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.GOOGLE_EMAIL_IN_USE);
    }

    @Test
    void concurrentWinner_returnsExistingSession() {
        UUID userId = UUID.randomUUID();
        User user = User.createGoogleOnly("Peer", EMAIL, Instant.now());
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        UserIdentity identity = UserIdentity.link(user, UserIdentity.PROVIDER_GOOGLE, SUBJECT, EMAIL);

        when(verifier.verify("token", "nonce")).thenReturn(
                new GoogleIdTokenClaims(SUBJECT, EMAIL, true, "New User"));
        when(identityRepository.findByProviderAndSubject(UserIdentity.PROVIDER_GOOGLE, SUBJECT))
                .thenReturn(Optional.empty(), Optional.of(identity));
        when(userRepository.existsByEmailAndEmailVerifiedAtIsNotNullAndDeletedAtIsNull(EMAIL))
                .thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        GoogleSignInResult result = service.signIn(new GoogleSignInRequest("token", "nonce"), "device");

        assertThat(result.created()).isFalse();
        assertThat(result.response().newAccount()).isNull();
    }
}
