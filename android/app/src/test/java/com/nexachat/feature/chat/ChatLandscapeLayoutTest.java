package com.nexachat.feature.chat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/** Landscape chat must declare every view the portrait chat binds unconditionally. */
public class ChatLandscapeLayoutTest {

    @Test
    public void landscapeChatDeclaresMentionPicker() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("src/main/res/layout-land/fragment_chat.xml"));
        String landscape = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(
                "landscape chat inflates mentionPicker as null and crashes on open",
                landscape.contains("android:id=\"@+id/mentionPicker\""));
    }
}
