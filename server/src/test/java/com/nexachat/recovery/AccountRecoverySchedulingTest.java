package com.nexachat.recovery;

import com.nexachat.auth.AuthService;
import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.mail.MailSender;
import com.nexachat.user.AccountDeletionService;
import com.nexachat.user.EmailRecoveryService;
import com.nexachat.user.PhoneService;
import com.nexachat.user.UserRepository;
import com.nexachat.ws.StompRevocationFence;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountRecoverySchedulingTest {

    @Test
    void publicRequestDefersAccountLookupAndDummyWorkToTheQueuedTask() {
        String phone = "+84912345678";
        PhoneService phoneService = mock(PhoneService.class);
        UserRepository userRepository = mock(UserRepository.class);
        EmailOtpService otpService = mock(EmailOtpService.class);
        MailSender mailSender = mock(MailSender.class);
        QueuedTaskExecutor executor = new QueuedTaskExecutor();
        when(phoneService.toE164(phone)).thenReturn(phone);
        when(mailSender.isAvailable()).thenReturn(true);
        when(userRepository.findByPhoneAndDeletedAtIsNull(phone)).thenReturn(Optional.empty());

        OtpMailDispatcher dispatcher = new OtpMailDispatcher(mailSender, executor);
        AccountRecoveryService service = new AccountRecoveryService(
                userRepository,
                phoneService,
                otpService,
                dispatcher,
                mailSender,
                new RateLimitService(),
                mock(AccountDeletionService.class),
                mock(AuthService.class),
                mock(StompRevocationFence.class));

        service.requestAccountDeletion(phone);

        verify(userRepository, never()).findByPhoneAndDeletedAtIsNull(phone);
        assertThat(executor.size()).isEqualTo(1);

        executor.runNext();

        verify(userRepository).findByPhoneAndDeletedAtIsNull(phone);
        verify(otpService).performDummyWork(OtpPurpose.DELETE_ACCOUNT);
    }

    @Test
    void emailOwnershipCheckIsDeferredAndDoesNotSendOnConflict() {
        UUID userId = UUID.randomUUID();
        String email = "claimed@example.com";
        EmailOtpService otpService = mock(EmailOtpService.class);
        MailSender mailSender = mock(MailSender.class);
        QueuedTaskExecutor executor = new QueuedTaskExecutor();
        when(mailSender.isAvailable()).thenReturn(true);
        when(otpService.beginEmailVerification(userId, email)).thenThrow(
                ApiException.of(ApiErrorCode.EMAIL_ALREADY_IN_USE, "Recovery email is already in use"));

        OtpMailDispatcher dispatcher = new OtpMailDispatcher(mailSender, executor);
        EmailRecoveryService service = new EmailRecoveryService(
                otpService, mailSender, dispatcher, new RateLimitService());

        service.requestVerification(userId, email);

        verify(otpService, never()).beginEmailVerification(userId, email);
        assertThat(executor.size()).isEqualTo(1);
        executor.runNext();

        verify(otpService).beginEmailVerification(userId, email);
        verify(mailSender, never()).sendOtp(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void emailAddressRequestLimitRemainsNeutralAndSuppressesSixthDelivery() {
        String email = "shared@example.com";
        EmailOtpService otpService = mock(EmailOtpService.class);
        MailSender mailSender = mock(MailSender.class);
        QueuedTaskExecutor executor = new QueuedTaskExecutor();
        when(mailSender.isAvailable()).thenReturn(true);
        EmailRecoveryService service = new EmailRecoveryService(
                otpService, mailSender, new OtpMailDispatcher(mailSender, executor), new RateLimitService());

        UUID overLimitUser = null;
        for (int index = 0; index < 6; index++) {
            UUID userId = UUID.randomUUID();
            if (index < 5) {
                when(otpService.beginEmailVerification(userId, email)).thenReturn(
                        new OtpMail(UUID.randomUUID(), email, OtpPurpose.VERIFY_EMAIL, "123456"));
            } else {
                overLimitUser = userId;
            }
            service.requestVerification(userId, email);
            assertThat(executor.size()).isEqualTo(1);
            executor.runNext();
        }

        verify(otpService, times(5)).beginEmailVerification(
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(email));
        verify(otpService, never()).beginEmailVerification(overLimitUser, email);
        verify(mailSender, times(5)).sendOtp(email, OtpPurpose.VERIFY_EMAIL, "123456");
    }

    private static final class QueuedTaskExecutor implements TaskExecutor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
