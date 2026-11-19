package com.nexachat.recovery;

import com.nexachat.auth.AuthService;
import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.mail.MailSender;
import com.nexachat.user.AccountDeletionService;
import com.nexachat.user.PhoneService;
import com.nexachat.user.UserRepository;
import com.nexachat.ws.StompRevocationFence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the public-OTP budget key layout in {@link AccountRecoveryService}:
 * every purpose gets its own per-phone bucket, while the tighter
 * account-level bucket is shared across purposes on purpose — it caps the
 * total recovery work one phone number can trigger per window no matter
 * which flow is abused. The end-to-end consequence is covered by
 * {@code AccountRecoverySecurityTest#publicOtpRequestsShareAnAccountBudgetAcrossPurposes}
 * and {@code #publicOtpConfirmationsShareAnAccountBudgetAcrossPurposes}.
 */
@ExtendWith(MockitoExtension.class)
class AccountRecoveryRateLimitKeyTest {

    private static final String PHONE = "+84912345678";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PhoneService phoneService;
    @Mock
    private EmailOtpService otpService;
    @Mock
    private OtpMailDispatcher mailDispatcher;
    @Mock
    private MailSender mailSender;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private AccountDeletionService accountDeletionService;
    @Mock
    private AuthService authService;
    @Mock
    private StompRevocationFence revocationFence;

    private AccountRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new AccountRecoveryService(
                userRepository, phoneService, otpService, mailDispatcher, mailSender,
                rateLimitService, accountDeletionService, authService, revocationFence);
        when(phoneService.toE164(PHONE)).thenReturn(PHONE);
        when(rateLimitService.tryConsume(any(), anyLong(), any()))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        // Only the confirm paths reach the user lookup; request and
        // budget-denied cases stop before it.
        lenient().when(userRepository.findByPhoneAndDeletedAtIsNull(PHONE))
                .thenReturn(Optional.empty());
    }

    @Test
    void confirmAccountDeletion_usesPurposeScopedPhoneKeyAndSharedAccountKey() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.confirmAccountDeletion(PHONE, "123456"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INVALID_OR_EXPIRED_CODE);
        List<ConsumedKey> keys = consumedKeys();
        assertThat(keys).extracting(ConsumedKey::key).containsExactlyInAnyOrder(
                "public-otp|DELETE_ACCOUNT|confirm|phone|" + PHONE,
                "public-otp|confirm|account|phone|" + PHONE);
        assertThat(keys).extracting(ConsumedKey::capacity).containsExactlyInAnyOrder(20L, 10L);
    }

    @Test
    void confirmPasswordReset_usesItsOwnPhoneKeyAndTheSameSharedAccountKey() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.confirmPasswordReset(PHONE, null, "123456", "new-secret-1"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INVALID_OR_EXPIRED_CODE);
        List<ConsumedKey> keys = consumedKeys();
        assertThat(keys).extracting(ConsumedKey::key).containsExactlyInAnyOrder(
                "public-otp|RESET_PASSWORD|confirm|phone|" + PHONE,
                // Identical to the deletion account key: one shared confirm
                // budget per phone across both recovery purposes.
                "public-otp|confirm|account|phone|" + PHONE);
        assertThat(keys).extracting(ConsumedKey::capacity).containsExactlyInAnyOrder(20L, 10L);
    }

    @Test
    void publicRequests_shareOneAccountKeyAcrossPurposes() {
        service.requestAccountDeletion(PHONE);
        service.requestPasswordReset(PHONE);

        verify(mailDispatcher).dispatchPublicRequest(eq(OtpPurpose.DELETE_ACCOUNT), any(), any());
        verify(mailDispatcher).dispatchPublicRequest(eq(OtpPurpose.RESET_PASSWORD), any(), any());
        List<ConsumedKey> keys = consumedKeys();
        assertThat(keys).extracting(ConsumedKey::key).containsExactlyInAnyOrder(
                "public-otp|DELETE_ACCOUNT|request|phone|" + PHONE,
                "public-otp|RESET_PASSWORD|request|phone|" + PHONE,
                // One request-side account bucket serves both purposes.
                "public-otp|request|account|phone|" + PHONE,
                "public-otp|request|account|phone|" + PHONE);
        assertThat(keys).extracting(ConsumedKey::capacity).containsOnly(10L);
    }

    @Test
    void exhaustedSharedAccountBucket_stillRejectsWithTheGenericCode() {
        when(rateLimitService.tryConsume(
                eq("public-otp|confirm|account|phone|" + PHONE), eq(10L), any()))
                .thenReturn(new RateLimitService.Consumption(false, 42));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.confirmAccountDeletion(PHONE, "123456"));

        // Budget denial collapses into the same generic invalid-code response
        // as a wrong OTP, so the limit cannot be probed for account existence.
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INVALID_OR_EXPIRED_CODE);
        verify(userRepository, never()).findByPhoneAndDeletedAtIsNull(PHONE);
        verify(otpService).dummyCompare(OtpPurpose.DELETE_ACCOUNT, "123456");
    }

    private List<ConsumedKey> consumedKeys() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> capacityCaptor = ArgumentCaptor.forClass(Long.class);
        verify(rateLimitService, atLeastOnce())
                .tryConsume(keyCaptor.capture(), capacityCaptor.capture(), any());
        List<ConsumedKey> keys = new ArrayList<>();
        List<String> capturedKeys = keyCaptor.getAllValues();
        List<Long> capturedCapacities = capacityCaptor.getAllValues();
        for (int i = 0; i < capturedKeys.size(); i++) {
            keys.add(new ConsumedKey(capturedKeys.get(i), capturedCapacities.get(i)));
        }
        return keys;
    }

    private record ConsumedKey(String key, long capacity) {
    }
}
