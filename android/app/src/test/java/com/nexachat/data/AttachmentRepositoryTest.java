package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.AttachmentApi;
import com.nexachat.core.network.dto.AttachmentDto;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Upload/download transport coverage: progress is strictly non-decreasing
 * and clamps at 1f, the 25 MB guard fails typed before the wire, HTTP errors
 * map to ApiError, and the dto lands intact.
 *
 * <p>The resolver side is mocked Context/ContentResolver (JVM tests have no
 * Android runtime); the API side is Mockito {@link Response}s.
 */
public final class AttachmentRepositoryTest {

    private final AttachmentApi attachmentApi = mock(AttachmentApi.class);
    private final Context context = mock(Context.class);
    private final ContentResolver resolver = mock(ContentResolver.class);
    private final Uri uri = mock(Uri.class);

    private AttachmentRepository repository;

    private final AttachmentDto dto = new AttachmentDto("att-1", "pic.png", "image/png", 4L);

    @Before
    public void setUp() {
        repository = new AttachmentRepository(attachmentApi, context);
    }

    @Test
    public void uploadEmitsMonotonicProgressEndingAtOneThenTheDto() throws Exception {
        byte[] bytes = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        stubResolver(bytes, "image/png", bytes.length);
        Call<AttachmentDto> uploadCall = mock(Call.class);
        ArgumentCaptor<MultipartBody.Part> partCaptor =
                ArgumentCaptor.forClass(MultipartBody.Part.class);
        when(attachmentApi.upload(partCaptor.capture())).thenReturn(uploadCall);
        when(uploadCall.execute()).thenAnswer(invocation -> {
            // Drive the real ProgressRequestBody so intermediate fractions fire.
            partCaptor.getValue().body().writeTo(new Buffer());
            return Response.success(dto);
        });

        List<AttachmentUploadEvent> events = await(repository.upload(uri).test());

        java.util.ArrayList<AttachmentUploadEvent.Progress> progress = new java.util.ArrayList<>();
        for (AttachmentUploadEvent event : events) {
            if (event instanceof AttachmentUploadEvent.Progress item) {
                progress.add(item);
            }
        }
        assertTrue(!progress.isEmpty());
        assertEquals(0f, progress.get(0).getFraction(), 0.0001f);
        assertEquals(1f, progress.get(progress.size() - 1).getFraction(), 0.0001f);
        for (int index = 1; index < progress.size(); index++) {
            assertTrue(progress.get(index).getFraction() >= progress.get(index - 1).getFraction());
        }
        assertEquals(new AttachmentUploadEvent.Uploaded(dto), events.get(events.size() - 1));
    }

    @Test
    public void uploadReadsResolverStreamAwayFromCallerDispatcher() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3, 4};
        stubResolver(bytes, "video/mp4", bytes.length);
        Thread callerThread = Thread.currentThread();
        AtomicReference<Thread> readerThread = new AtomicReference<>();
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(bytes) {
            @Override
            public int read(byte[] buffer, int offset, int length) {
                readerThread.set(Thread.currentThread());
                return super.read(buffer, offset, length);
            }
        });
        Call<AttachmentDto> uploadCall = mock(Call.class);
        ArgumentCaptor<MultipartBody.Part> partCaptor =
                ArgumentCaptor.forClass(MultipartBody.Part.class);
        when(attachmentApi.upload(partCaptor.capture())).thenReturn(uploadCall);
        when(uploadCall.execute()).thenAnswer(invocation -> {
            partCaptor.getValue().body().writeTo(new Buffer());
            return Response.success(dto.withMimeType("video/mp4"));
        });

        await(repository.upload(uri).test());

        assertTrue(readerThread.get() != null);
        assertNotSame(callerThread, readerThread.get());
    }

    @Test
    public void oversizeFileFailsTypedBeforeAnyRequest() throws Exception {
        stubResolver(new byte[] {1, 2, 3, 4}, "image/png",
                AttachmentRepository.MAX_UPLOAD_BYTES + 1);

        List<AttachmentUploadEvent> events = await(repository.upload(uri).test());

        AttachmentUploadEvent.Failed failed = singleFailure(events);
        assertEquals(AttachmentRepository.ERROR_TOO_LARGE,
                ((ApiError.Unknown) failed.getError()).getCode());
        verify(attachmentApi, never()).upload(any(MultipartBody.Part.class));
    }

    @Test
    public void streamThatOutgrowsCapMidReadAlsoFailsTyped() throws Exception {
        // statSize under-reports; the bounded read must still stop it.
        byte[] bytes = new byte[(int) AttachmentRepository.MAX_UPLOAD_BYTES + 1];
        stubResolver(bytes, "image/png", 100L);

        List<AttachmentUploadEvent> events = await(repository.upload(uri).test());

        assertTrue(events.get(events.size() - 1) instanceof AttachmentUploadEvent.Failed);
        assertEquals(AttachmentRepository.ERROR_TOO_LARGE,
                ((ApiError.Unknown) ((AttachmentUploadEvent.Failed)
                        events.get(events.size() - 1)).getError()).getCode());
    }

    @Test
    public void httpErrorMapsToApiError() throws Exception {
        stubResolver(new byte[] {1, 2, 3, 4}, "image/png", 4);
        Call<AttachmentDto> uploadCall = mock(Call.class);
        when(attachmentApi.upload(any(MultipartBody.Part.class))).thenReturn(uploadCall);
        when(uploadCall.execute()).thenReturn(Response.error(
                413, ResponseBody.create(MediaType.parse("text/plain"), "")));

        List<AttachmentUploadEvent> events = await(repository.upload(uri).test());

        AttachmentUploadEvent.Failed failed = singleFailure(events);
        assertTrue(failed.getError() instanceof ApiError.Unknown);
    }

    @Test
    public void networkFailureMapsToApiErrorNetwork() throws Exception {
        stubResolver(new byte[] {1, 2, 3, 4}, "image/png", 4);
        Call<AttachmentDto> uploadCall = mock(Call.class);
        when(attachmentApi.upload(any(MultipartBody.Part.class))).thenReturn(uploadCall);
        when(uploadCall.execute()).thenThrow(new IOException("down"));

        List<AttachmentUploadEvent> events = await(repository.upload(uri).test());

        assertTrue(singleFailure(events).getError() instanceof ApiError.Network);
    }

    @Test
    public void specificPickerHintBeatsGenericResolverType() throws Exception {
        // A provider that cannot sniff HEIC answers application/octet-stream;
        // the picker knew it was an image. The old `meta.mimeType ?: hint`
        // shipped that upload as a FILE.
        stubResolver(new byte[] {1, 2, 3, 4}, "application/octet-stream", 4);
        AtomicReference<String> sentType = new AtomicReference<>();
        Call<AttachmentDto> uploadCall = mock(Call.class);
        ArgumentCaptor<MultipartBody.Part> partCaptor =
                ArgumentCaptor.forClass(MultipartBody.Part.class);
        when(attachmentApi.upload(partCaptor.capture())).thenReturn(uploadCall);
        when(uploadCall.execute()).thenAnswer(invocation -> {
            sentType.set(partCaptor.getValue().body().contentType().toString());
            return Response.success(dto);
        });

        await(repository.upload(uri, "image/heic").test());

        assertEquals("image/heic", sentType.get());
    }

    @Test
    public void specificResolverTypeBeatsGenericHint() throws Exception {
        stubResolver(new byte[] {1, 2, 3, 4}, "video/mp4", 4);
        AtomicReference<String> sentType = new AtomicReference<>();
        Call<AttachmentDto> uploadCall = mock(Call.class);
        ArgumentCaptor<MultipartBody.Part> partCaptor =
                ArgumentCaptor.forClass(MultipartBody.Part.class);
        when(attachmentApi.upload(partCaptor.capture())).thenReturn(uploadCall);
        when(uploadCall.execute()).thenAnswer(invocation -> {
            sentType.set(partCaptor.getValue().body().contentType().toString());
            return Response.success(dto);
        });

        await(repository.upload(uri, "application/octet-stream").test());

        assertEquals("video/mp4", sentType.get());
    }

    @Test
    public void noUsableTypeFallsBackToOctetStream() throws Exception {
        stubResolver(new byte[] {1, 2, 3, 4}, null, 4);
        AtomicReference<String> sentType = new AtomicReference<>();
        Call<AttachmentDto> uploadCall = mock(Call.class);
        ArgumentCaptor<MultipartBody.Part> partCaptor =
                ArgumentCaptor.forClass(MultipartBody.Part.class);
        when(attachmentApi.upload(partCaptor.capture())).thenReturn(uploadCall);
        when(uploadCall.execute()).thenAnswer(invocation -> {
            sentType.set(partCaptor.getValue().body().contentType().toString());
            return Response.success(dto);
        });

        await(repository.upload(uri, null).test());

        assertEquals("application/octet-stream", sentType.get());
    }

    @Test
    public void revokedUriPermissionFailsTypedInsteadOfEscapingTheStream() throws Exception {
        stubResolver(new byte[] {1, 2, 3, 4}, "image/png", 4);
        // The picker grant can be gone by upload time (activity recreated,
        // grant expired). An escaping SecurityException kills the caller's
        // ViewModel scope and takes the chat screen down with it.
        when(resolver.openInputStream(uri)).thenThrow(new SecurityException("PERMISSION_DENIED"));

        List<AttachmentUploadEvent> events = await(repository.upload(uri).test());

        AttachmentUploadEvent.Failed failed = singleFailure(events);
        assertEquals(AttachmentRepository.ERROR_UNREADABLE,
                ((ApiError.Unknown) failed.getError()).getCode());
        verify(attachmentApi, never()).upload(any(MultipartBody.Part.class));
    }

    @Test
    public void failedDownloadLeavesNoTruncatedFileInCache() throws Exception {
        File cacheDir = Files.createTempDirectory("nexa-attachments").toFile();
        when(context.getCacheDir()).thenReturn(cacheDir);
        Call<ResponseBody> contentCall = mock(Call.class);
        when(attachmentApi.content("att-1")).thenReturn(contentCall);
        when(contentCall.execute()).thenReturn(Response.success(cutBody()));

        io.reactivex.rxjava3.observers.TestObserver<Result<File>> observer =
                repository.downloadToCache("att-1", "pic.png").test();
        observer.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors();
        Result<File> result = observer.values().get(0);

        assertTrue(result instanceof Result.Failure<?>);
        File[] files = new File(cacheDir, AttachmentRepository.CACHE_DIR_NAME).listFiles();
        assertTrue("truncated download left behind: "
                + (files == null ? List.of() : java.util.Arrays.asList(files)),
                files == null || files.length == 0);
    }

    private void stubResolver(byte[] bytes, String mime, long size) throws Exception {
        when(context.getContentResolver()).thenReturn(resolver);
        when(resolver.query(uri, null, null, null, null)).thenReturn(null);
        if (size >= 0L) {
            ParcelFileDescriptor descriptor = mock(ParcelFileDescriptor.class);
            when(descriptor.getStatSize()).thenReturn(size);
            doNothing().when(descriptor).close();
            when(resolver.openFileDescriptor(uri, "r")).thenReturn(descriptor);
        } else {
            when(resolver.openFileDescriptor(uri, "r")).thenReturn(null);
        }
        when(resolver.getType(uri)).thenReturn(mime);
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(bytes));
        when(uri.getLastPathSegment()).thenReturn("pic.png");
    }

    private static List<AttachmentUploadEvent> await(
            TestSubscriber<AttachmentUploadEvent> observer) {
        observer.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors();
        return observer.values();
    }

    private static AttachmentUploadEvent.Failed singleFailure(
            List<AttachmentUploadEvent> events) {
        AttachmentUploadEvent.Failed failure = null;
        int failures = 0;
        for (AttachmentUploadEvent event : events) {
            if (event instanceof AttachmentUploadEvent.Failed failed) {
                failure = failed;
                failures++;
            }
        }
        assertEquals(1, failures);
        return failure;
    }

    /** 2xx body whose stream dies mid-transfer. */
    private static ResponseBody cutBody() {
        return new ResponseBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }

            @Override
            public long contentLength() {
                return -1L;
            }

            @Override
            public BufferedSource source() {
                Source source = new Source() {
                    @Override
                    public long read(Buffer sink, long byteCount) throws IOException {
                        throw new IOException("cut");
                    }

                    @Override
                    public Timeout timeout() {
                        return Timeout.NONE;
                    }

                    @Override
                    public void close() {
                    }
                };
                return Okio.buffer(source);
            }
        };
    }
}
