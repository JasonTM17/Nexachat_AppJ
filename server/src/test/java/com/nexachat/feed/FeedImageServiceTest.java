package com.nexachat.feed;

import com.nexachat.attachment.StorageService;
import com.nexachat.attachment.UploadAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedImageServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-28T09:00:00Z");

    @Test
    void openAndOpenVideoAndOpenVideoRangeReleaseDbTransactionBeforeStorageIo() throws Exception {
        FeedStore store = mock(FeedStore.class);
        FeedRateLimits rateLimits = mock(FeedRateLimits.class);
        StorageService storageService = mock(StorageService.class);
        UploadAdmission uploadAdmission = new UploadAdmission(2, 100);
        PlatformTransactionManager txManager = new StubTransactionManager();
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(txManager);

        FeedImageService service = new FeedImageService(
                store, rateLimits, storageService, uploadAdmission,
                Clock.fixed(NOW, ZoneOffset.UTC), provider);

        UUID ownerId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        AtomicBoolean imageLookupTxActive = new AtomicBoolean(false);
        AtomicBoolean imageLookupTxReadOnly = new AtomicBoolean(false);
        AtomicBoolean imageStorageTxActive = new AtomicBoolean(true);

        when(store.findImage(imageId)).thenAnswer(inv -> {
            imageLookupTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            imageLookupTxReadOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return Optional.of(new FeedStore.ImageRow(
                    imageId, ownerId, "feed/img.png", "image/png", 4L, 10, 10, null));
        });
        when(storageService.open("feed/img.png")).thenAnswer(inv -> {
            imageStorageTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new StorageService.StreamHandle(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 4L);
        });

        try (FeedImageService.ImageContent img = service.open(ownerId, imageId)) {
            assertThat(img.image().id()).isEqualTo(imageId);
        }
        assertThat(imageLookupTxActive).isTrue();
        assertThat(imageLookupTxReadOnly).isTrue();
        assertThat(imageStorageTxActive).isFalse();

        AtomicBoolean videoLookupTxActive = new AtomicBoolean(false);
        AtomicBoolean videoLookupTxReadOnly = new AtomicBoolean(false);
        AtomicBoolean videoStorageOpenTxActive = new AtomicBoolean(true);
        AtomicBoolean videoStorageRangeTxActive = new AtomicBoolean(true);

        when(store.findImage(videoId)).thenAnswer(inv -> {
            videoLookupTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            videoLookupTxReadOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return Optional.of(new FeedStore.ImageRow(
                    videoId, ownerId, "feed/vid.mp4", FeedImageService.VIDEO_MIME, 10L, null, null, null));
        });
        when(storageService.open("feed/vid.mp4")).thenAnswer(inv -> {
            videoStorageOpenTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new StorageService.StreamHandle(new ByteArrayInputStream(new byte[10]), 10L);
        });
        when(storageService.openRange("feed/vid.mp4", 2L, 4L)).thenAnswer(inv -> {
            videoStorageRangeTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new StorageService.StreamHandle(new ByteArrayInputStream(new byte[4]), 10L);
        });

        try (FeedImageService.ImageContent vid = service.openVideo(ownerId, videoId)) {
            assertThat(vid.image().id()).isEqualTo(videoId);
        }
        assertThat(videoLookupTxActive).isTrue();
        assertThat(videoLookupTxReadOnly).isTrue();
        assertThat(videoStorageOpenTxActive).isFalse();

        try (FeedImageService.VideoRangeContent slice = service.openVideoRange(ownerId, videoId, "bytes=2-5")) {
            assertThat(slice.count()).isEqualTo(4L);
        }
        assertThat(videoStorageRangeTxActive).isFalse();
    }

    private static final class StubTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
