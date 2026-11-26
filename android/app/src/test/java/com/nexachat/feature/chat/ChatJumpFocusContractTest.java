package com.nexachat.feature.chat;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Guards Java focus wiring. The device journey independently checks the visible destination. */
public final class ChatJumpFocusContractTest {
    @Test
    public void chatResolvesTheCurrentSourceRowAfterScrollingAndBeforeFocusing() throws IOException {
        String fragment = read("src/main/java/com/nexachat/feature/chat/ChatFragment.java");
        assertTrue(fragment.contains("viewModel.getCitationJumps().observe(getViewLifecycleOwner()"));
        assertTrue(fragment.contains("OneShotPreDrawListener.add(list, () -> focusAttachedCitation(list, jump))"));
        String focus = fragment.substring(fragment.indexOf("private boolean focusAttachedCitation"),
                fragment.indexOf("private void clearPendingCitationFocus"));
        assertTrue(focus.contains("pendingCitationFocus != jump"));
        assertTrue(focus.contains("citationPosition(jump.messageId())"));
        assertTrue(focus.indexOf("finishIfCitationWasRecalled") < focus.indexOf("focusChatView"));
        assertTrue(focus.indexOf("findViewHolderForAdapterPosition") < focus.indexOf("focusChatView"));
        assertTrue(focus.contains("focusChatView(holder.itemView)"));
    }

    @Test
    public void sourceIsDispatchedAfterSheetDismissal() throws IOException {
        String sheet = read("src/main/java/com/nexachat/feature/ask/AskBottomSheetFragment.java");
        assertTrue(sheet.contains("selectedSource = target;"));
        assertTrue(sheet.contains("!requireActivity().isChangingConfigurations()"));
        int close = sheet.indexOf("viewModel.close()", sheet.indexOf("public void onDismiss"));
        int removed = sheet.indexOf("super.onDismiss(dialog)", close);
        int delivered = sheet.indexOf("listener.onDismissed(source)", removed);
        assertTrue(close >= 0 && removed > close && delivered > removed);
    }

    @Test
    public void normalDismissalTracksTheOpenerAndWaitsForWindowFocus() throws IOException {
        String fragment = read("src/main/java/com/nexachat/feature/chat/ChatFragment.java");
        assertTrue(fragment.contains("showAsk(v.getId())"));
        assertTrue(fragment.contains("showAsk(R.id.moreButton)"));
        assertTrue(fragment.contains("putInt(ARG_ASK_OPENER_ID, openerId)"));
        assertTrue(fragment.contains("observer.addOnWindowFocusChangeListener(listener)"));
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
    }
}
