package com.nexachat.recovery;

import com.nexachat.auth.AuthService;
import com.nexachat.auth.RateLimitService;
import com.nexachat.mail.MailSender;
import com.nexachat.user.AccountDeletionService;
import com.nexachat.user.PhoneService;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayDeque;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                mock(AuthService.class));

        service.requestAccountDeletion(phone);

        verify(userRepository, never()).findByPhoneAndDeletedAtIsNull(phone);
        assertThat(executor.size()).isEqualTo(1);

        executor.runNext();

        verify(userRepository).findByPhoneAndDeletedAtIsNull(phone);
        verify(otpService).performDummyWork(OtpPurpose.DELETE_ACCOUNT);
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
