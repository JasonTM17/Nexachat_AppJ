package com.nexachat.feature.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.net.Uri;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class PostComposeViewModelTest {

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private FakeFeedRepository repository;
    private FeedSyncBus syncBus;
    private PostComposeViewModel viewModel;

    @Before public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = new FakeFeedRepository();
        syncBus = new FeedSyncBus();
        viewModel = new PostComposeViewModel(repository, syncBus);
    }

    @After public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test public void anEmptyDraftCannotBeSubmitted() {
        assertFalse(state().canSubmit());
        assertEquals(0, state().getLength());
        assertNull(state().getImage());
        assertNull(state().getVideo());
    }

    @Test public void textEnablesSubmitAndCountsTrimmedCharacters() {
        viewModel.setBody("  hello  ");

        assertTrue(state().canSubmit());
        assertEquals(5, state().getLength());
        assertEquals("  hello  ", state().getBody());
    }

    @Test public void aBodyOverTheLimitCannotBeSubmitted() {
        viewModel.setBody("a".repeat(FeedRules.MAX_POST_LENGTH + 1));

        assertFalse(state().canSubmit());
        assertEquals(FeedRules.MAX_POST_LENGTH + 1, state().getLength());
    }

    @Test public void anImageAloneEnablesSubmit() {
        viewModel.setImage(mock(Uri.class));

        assertTrue(state().canSubmit());
    }

    @Test public void aVideoRequiresANonBlankCaption() {
        Uri video = mock(Uri.class);
        viewModel.setVideo(video);

        assertFalse(state().canSubmit());

        viewModel.setBody("watch this");
        assertTrue(state().canSubmit());

        viewModel.setBody("   ");
        assertFalse(state().canSubmit());
    }

    @Test public void pickingVideoClearsImageAndViceVersa() {
        Uri image = mock(Uri.class);
        Uri video = mock(Uri.class);

        viewModel.setImage(image);
        assertEquals(image, state().getImage());

        viewModel.setVideo(video);
        assertNull(state().getImage());
        assertEquals(video, state().getVideo());

        viewModel.setImage(image);
        assertNull(state().getVideo());
        assertEquals(image, state().getImage());
    }

    @Test public void clearingTheBodyWithAnImageKeepsSubmitEnabled() {
        viewModel.setImage(mock(Uri.class));
        viewModel.setBody("hello");
        viewModel.setBody("   ");

        assertTrue(state().canSubmit());
        assertEquals(0, state().getLength());
    }

    @Test public void submitWithoutAnImagePostsTheTrimmedBody() {
        viewModel.setBody("  hello  ");

        viewModel.submit();

        assertTrue(state().isBusy());
        assertFalse(state().canSubmit());
        assertEquals(1, repository.createPostCalls.size());
        assertEquals("hello", repository.createPostBodies.get(0));
        assertNull(repository.createPostImageIds.get(0));
        assertNull(repository.createPostVideoIds.get(0));
        assertTrue(repository.uploadImageCalls.isEmpty());
    }

    @Test public void aSuccessfulPostReportsPostedAndClearsBusy() {
        viewModel.setBody("hello");
        viewModel.submit();

        repository.createPostCalls.get(0).onSuccess(post("post-1"));

        assertEquals(PostComposeViewModel.Outcome.POSTED, outcome());
        assertFalse(state().isBusy());
        assertTrue(state().canSubmit());
    }

    @Test public void aFailedPostReportsFailed() {
        viewModel.setBody("hello");
        viewModel.submit();

        repository.createPostCalls.get(0).onError(new IllegalStateException("offline"));

        assertEquals(PostComposeViewModel.Outcome.FAILED, outcome());
        assertFalse(state().isBusy());
    }

    @Test public void aRateLimitedPostIsReportedSeparately() {
        viewModel.setBody("hello");
        viewModel.submit();

        repository.createPostCalls.get(0).onError(new FeedApiException(429, null));

        assertEquals(PostComposeViewModel.Outcome.RATE_LIMITED, outcome());
    }

    @Test public void anImageIsUploadedBeforeThePostIsCreated() {
        viewModel.setImage(mock(Uri.class));
        viewModel.setBody("hello");

        viewModel.submit();

        assertEquals(1, repository.uploadImageCalls.size());
        assertTrue(repository.createPostCalls.isEmpty());

        repository.uploadImageCalls.get(0).onSuccess(
                new FeedRepository.UploadedImage("image-1", "https://cdn/1.jpg", 10, 10, "image/jpeg"));

        assertEquals(1, repository.createPostCalls.size());
        assertEquals("image-1", repository.createPostImageIds.get(0));
        assertEquals("hello", repository.createPostBodies.get(0));
    }

    @Test public void retryingAfterCreateFailureReusesCachedUploadedImageId() {
        viewModel.setImage(mock(Uri.class));
        viewModel.setBody("hello");

        viewModel.submit();
        repository.uploadImageCalls.get(0).onSuccess(
                new FeedRepository.UploadedImage("image-1", "https://cdn/1.jpg", 10, 10, "image/jpeg"));
        repository.createPostCalls.get(0).onError(new IllegalStateException("network"));

        viewModel.submit();

        assertEquals("should not re-upload cached image", 1, repository.uploadImageCalls.size());
        assertEquals(2, repository.createPostCalls.size());
        assertEquals("image-1", repository.createPostImageIds.get(1));
    }

    @Test public void retryingAfterCreateFailureReusesCachedUploadedVideoId() {
        viewModel.setVideo(mock(Uri.class));
        viewModel.setBody("video caption");

        viewModel.submit();
        assertEquals(1, repository.uploadVideoCalls.size());
        repository.uploadVideoCalls.get(0).onSuccess(
                new FeedRepository.UploadedVideo("video-1", "/api/v1/feed/videos/video-1", "video/mp4"));
        assertEquals(1, repository.createPostCalls.size());
        assertEquals("video-1", repository.createPostVideoIds.get(0));

        repository.createPostCalls.get(0).onError(new IllegalStateException("network"));
        viewModel.submit();

        assertEquals("should not re-upload cached video", 1, repository.uploadVideoCalls.size());
        assertEquals(2, repository.createPostCalls.size());
        assertEquals("video-1", repository.createPostVideoIds.get(1));
    }

    @Test public void videoUploadsDisabledFlagBlocksVideoSubmitCleanly() {
        repository.getSocialConfigCalls.get(0).onSuccess(new FeedRepository.SocialConfig(true, false));
        assertFalse(state().isVideoUploadsEnabled());

        viewModel.setVideo(mock(Uri.class));
        viewModel.setBody("clip");
        viewModel.submit();

        assertTrue(repository.uploadVideoCalls.isEmpty());
        assertEquals(PostComposeViewModel.Outcome.VIDEO_DISABLED, outcome());
    }

    @Test public void videoUploadErrorsMapToSpecificOutcomes() {
        viewModel.setVideo(mock(Uri.class));
        viewModel.setBody("clip");

        viewModel.submit();
        repository.uploadVideoCalls.get(0).onError(new FeedApiException(413, "FILE_TOO_LARGE"));
        assertEquals(PostComposeViewModel.Outcome.VIDEO_TOO_LARGE, outcome());

        viewModel.submit();
        repository.uploadVideoCalls.get(1).onError(new FeedApiException(415, "MIME_NOT_ALLOWED"));
        assertEquals(PostComposeViewModel.Outcome.VIDEO_INVALID_TYPE, outcome());
    }

    @Test public void editModeCallsEditPostAndPublishesToSyncBus() {
        List<FeedSyncBus.Event> events = new ArrayList<>();
        var sub = syncBus.observe().subscribe(events::add);

        viewModel.startEdit("post-1", "original");
        assertTrue(state().isEditMode());
        assertTrue(state().canSubmit());

        viewModel.setBody("  updated body  ");
        viewModel.submit();

        assertEquals(1, repository.editPostCalls.size());
        assertEquals("post-1", repository.editPostIds.get(0));
        assertEquals("updated body", repository.editPostBodies.get(0));

        FeedRepository.Post updated = post("post-1");
        repository.editPostCalls.get(0).onSuccess(updated);

        assertEquals(PostComposeViewModel.Outcome.EDITED, outcome());
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof FeedSyncBus.PostUpdated);
        sub.dispose();
    }

    @Test public void aFailedUploadNeverCreatesThePost() {
        viewModel.setImage(mock(Uri.class));

        viewModel.submit();
        repository.uploadImageCalls.get(0).onError(new IllegalStateException("unreadable"));

        assertEquals(PostComposeViewModel.Outcome.IMAGE_FAILED, outcome());
        assertTrue(repository.createPostCalls.isEmpty());
        assertFalse(state().isBusy());
    }

    @Test public void theClientPostIdSurvivesARetry() {
        viewModel.setBody("hello");
        viewModel.submit();
        repository.createPostCalls.get(0).onError(new IllegalStateException("offline"));

        viewModel.submit();

        assertEquals(2, repository.createPostCalls.size());
        assertEquals(repository.createPostClientIds.get(0), repository.createPostClientIds.get(1));
    }

    @Test public void aSecondSubmitWhileBusyIsIgnored() {
        viewModel.setBody("hello");
        viewModel.submit();

        viewModel.submit();

        assertEquals(1, repository.createPostCalls.size());
    }

    @Test public void anEmptyDraftIsNeverSubmitted() {
        viewModel.submit();

        assertTrue(repository.createPostCalls.isEmpty());
        assertTrue(repository.uploadImageCalls.isEmpty());
    }

    @Test public void theOutcomeIsConsumedOnce() {
        viewModel.setBody("hello");
        viewModel.submit();
        repository.createPostCalls.get(0).onSuccess(post("post-1"));

        viewModel.onOutcomeShown();

        assertNull(outcome());
    }

    private PostComposeViewModel.UiState state() {
        return viewModel.getUiState().getValue();
    }

    private PostComposeViewModel.Outcome outcome() {
        return viewModel.getOutcome().getValue();
    }

    private static FeedRepository.Post post(String id) {
        return new FeedRepository.Post(
                id,
                new FeedRepository.Author("author-1", "An", null),
                "hello",
                null,
                "2026-02-25T05:33:20Z",
                0,
                java.util.List.of(),
                true);
    }
}
