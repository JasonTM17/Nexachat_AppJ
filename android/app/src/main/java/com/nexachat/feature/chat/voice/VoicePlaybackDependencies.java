package com.nexachat.feature.chat.voice;

import com.nexachat.data.AttachmentRepository;
import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

@EntryPoint
@InstallIn(SingletonComponent.class)
public interface VoicePlaybackDependencies {
    AttachmentRepository attachmentRepository();
}
