package com.nexachat.feature.chat.voice;

import android.content.Context;
import android.media.MediaRecorder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VoiceRecorderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Context mockContext;
    private MediaRecorder mockMediaRecorder;
    private VoiceRecorder.RecorderFactory mockFactory;
    private File cacheDir;

    @Before
    public void setUp() throws IOException {
        cacheDir = temporaryFolder.newFolder("cache");
        mockContext = Mockito.mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        when(mockContext.getCacheDir()).thenReturn(cacheDir);

        mockMediaRecorder = Mockito.mock(MediaRecorder.class);
        mockFactory = ctx -> mockMediaRecorder;
    }

    @Test
    public void startAndStopSuccess() throws IOException {
        VoiceRecorder recorder = new VoiceRecorder(mockContext, mockFactory);

        assertFalse(recorder.isRecording());
        boolean started = recorder.start();
        assertTrue(started);
        assertTrue(recorder.isRecording());

        verify(mockMediaRecorder).setAudioSource(MediaRecorder.AudioSource.MIC);
        verify(mockMediaRecorder).setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        verify(mockMediaRecorder).setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        verify(mockMediaRecorder).prepare();
        verify(mockMediaRecorder).start();

        File outputFile = recorder.getOutputFile();
        assertNotNull(outputFile);
        assertTrue(outputFile.getName().startsWith("voice_"));
        assertTrue(outputFile.getName().endsWith(".m4a"));

        File result = recorder.stop();
        assertFalse(recorder.isRecording());
        assertEquals(outputFile, result);
        verify(mockMediaRecorder).stop();
        verify(mockMediaRecorder).release();
    }

    @Test
    public void cancelDeletesOutputFile() throws IOException {
        VoiceRecorder recorder = new VoiceRecorder(mockContext, mockFactory);
        recorder.start();

        File outputFile = recorder.getOutputFile();
        assertNotNull(outputFile);
        // Create the file on disk to test deletion
        assertTrue(outputFile.createNewFile());
        assertTrue(outputFile.exists());

        recorder.cancel();
        assertFalse(recorder.isRecording());
        assertFalse(outputFile.exists());
        assertNull(recorder.getOutputFile());
        verify(mockMediaRecorder).release();
    }

    @Test
    public void doubleStartFails() {
        VoiceRecorder recorder = new VoiceRecorder(mockContext, mockFactory);
        assertTrue(recorder.start());
        assertFalse(recorder.start());
    }

    @Test
    public void stopWhenNotRecordingReturnsNull() {
        VoiceRecorder recorder = new VoiceRecorder(mockContext, mockFactory);
        assertNull(recorder.stop());
    }

    @Test
    public void prepareFailureCleansUpAndReturnsFalse() throws IOException {
        doThrow(new IOException("MediaRecorder prepare failed")).when(mockMediaRecorder).prepare();

        VoiceRecorder recorder = new VoiceRecorder(mockContext, mockFactory);
        assertFalse(recorder.start());
        assertFalse(recorder.isRecording());
        assertNull(recorder.getOutputFile());
    }

    @Test
    public void stopWithRuntimeExceptionCleansUpAndReturnsNull() throws IOException {
        doThrow(new RuntimeException("stop failed: no audio data")).when(mockMediaRecorder).stop();

        VoiceRecorder recorder = new VoiceRecorder(mockContext, mockFactory);
        recorder.start();

        File outputFile = recorder.getOutputFile();
        assertNotNull(outputFile);
        assertTrue(outputFile.createNewFile());

        File result = recorder.stop();
        assertNull(result);
        assertFalse(recorder.isRecording());
        assertFalse(outputFile.exists());
    }
}
