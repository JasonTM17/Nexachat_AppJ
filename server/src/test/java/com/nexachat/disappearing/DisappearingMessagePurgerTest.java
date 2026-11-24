package com.nexachat.disappearing;

import com.nexachat.message.MessageRepository;
import com.nexachat.message.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Poison-row starvation of the bounded purge batch: a row whose expire
 * transaction keeps failing sits at the head of the expiry order, and once
 * poison rows fill a whole page the rows behind them are never scanned. The
 * park + skip-ahead logic keeps the batch moving.
 */
@ExtendWith(MockitoExtension.class)
class DisappearingMessagePurgerTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageService messageService;

    private final UUID poisonId = UUID.randomUUID();
    private final UUID healthyId = UUID.randomUUID();

    private DisappearingProperties properties;
    private SettableClock clock;
    private DisappearingMessagePurger purger;

    @BeforeEach
    void setUp() {
        properties = new DisappearingProperties();
        properties.setEnabled(true);
        // A batch of one: the healthy row sits on page one, so it is only
        // processed once the sweep stops burning the batch on the poison row.
        properties.setBatchSize(1);
        clock = new SettableClock(Instant.parse("2026-11-11T12:00:00Z"));
        purger = new DisappearingMessagePurger(messageRepository, messageService, properties, clock);
    }

    @Test
    void parkedPoisonRowNoLongerBlocksTheRowsBehindIt() {
        when(messageRepository.findExpiredIds(any(Instant.class), any(PageRequest.class)))
                .thenAnswer(inv -> ((PageRequest) inv.getArgument(1)).getPageNumber() == 0
                        ? List.of(poisonId)
                        : List.of(healthyId));
        when(messageService.expireMessage(poisonId))
                .thenThrow(new IllegalStateException("corrupted cascade"));
        when(messageService.expireMessage(healthyId)).thenReturn(true);

        // MAX_CONSECUTIVE_FAILURES honest attempts fail before the park holds.
        purger.purgeBatch();
        purger.purgeBatch();
        purger.purgeBatch();
        assertThat(purger.purgeBatch())
                .as("once the poison row is parked, the sweep scans past it and expires the next row")
                .isEqualTo(1);

        verify(messageService, times(DisappearingMessagePurger.MAX_CONSECUTIVE_FAILURES))
                .expireMessage(poisonId);
        verify(messageService).expireMessage(healthyId);
    }

    @Test
    void parkedPoisonRowGetsOneRetryAfterTheParkLapses() {
        when(messageRepository.findExpiredIds(any(Instant.class), any(PageRequest.class)))
                .thenAnswer(inv -> ((PageRequest) inv.getArgument(1)).getPageNumber() == 0
                        ? List.of(poisonId)
                        : List.of());
        when(messageService.expireMessage(poisonId))
                .thenThrow(new IllegalStateException("corrupted cascade"))
                .thenThrow(new IllegalStateException("corrupted cascade"))
                .thenThrow(new IllegalStateException("corrupted cascade"))
                .thenReturn(true);

        purger.purgeBatch();
        purger.purgeBatch();
        purger.purgeBatch();
        assertThat(purger.purgeBatch())
                .as("a parked row consumes no attempt while the park holds")
                .isZero();
        verify(messageService, times(DisappearingMessagePurger.MAX_CONSECUTIVE_FAILURES))
                .expireMessage(poisonId);

        clock.advanceBy(DisappearingMessagePurger.POISON_PARK.plusSeconds(1));
        assertThat(purger.purgeBatch())
                .as("once the park lapses the row earns one fresh attempt")
                .isEqualTo(1);
        verify(messageService, times(DisappearingMessagePurger.MAX_CONSECUTIVE_FAILURES + 1))
                .expireMessage(poisonId);
    }

    /** A clock the test moves by hand across the park horizon. */
    private static final class SettableClock extends Clock {

        private Instant instant;

        private SettableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceBy(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
