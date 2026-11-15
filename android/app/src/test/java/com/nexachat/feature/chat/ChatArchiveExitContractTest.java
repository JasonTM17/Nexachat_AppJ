package com.nexachat.feature.chat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class ChatArchiveExitContractTest {

    @Test
    public void archivedConversationLeavesTheChatForTheInbox() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/chat/ChatFragment.java"));
        assertTrue("archiving from the overflow must pop back to the inbox",
                source.contains("Kind.ARCHIVED") && source.contains("popBackStack"));
    }

    @Test
    public void archiveUndoGoesThroughTheSurvivingInboxViewModel() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/chat/ChatFragment.java"));
        assertTrue("the chat view model is cleared after popBackStack, so undo must run "
                        + "through the surviving activity-scoped inbox view model",
                source.contains("sharedInboxViewModel.setConversationArchived"));
    }

    @Test
    public void archiveUndoConfirmsTheRowReturnedToChats() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/chat/ChatFragment.java"));
        assertTrue("undo runs through the activity-scoped store, whose confirmation event "
                        + "no screen observes; the snackbar must confirm the restore itself",
                source.contains("DISMISS_EVENT_ACTION")
                        && source.contains("R.string.conversation_unarchived"));
    }

    @Test
    public void archiveFeedbackAnchorsToTheHostSoItSurvivesNavigation() throws Exception {
        String source = readUtf8(locateJavaFile(
                "com/nexachat/feature/chat/ChatFragment.java"));
        assertTrue("the snackbar must anchor to the activity content view, "
                        + "not the popped chat root",
                source.contains("android.R.id.content"));
    }

    private static File locateJavaFile(String relativePath) {
        File[] candidates = {
                new File("src/main/java/" + relativePath),
                new File("app/src/main/java/" + relativePath),
                new File("android/app/src/main/java/" + relativePath)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return file;
            }
        }
        throw new IllegalStateException("Missing " + relativePath);
    }

    private static String readUtf8(File file) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
            }
        }
        return body.toString();
    }
}
