package com.nexachat.feature.chat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatEmptyStateContractTest {

    @Test
    public void trueEmptyIsDistinctFromLoadingErrorAndCachedRefresh() {
        assertTrue(ChatFragment.ChatEmptyPolicy.showEmpty(false, false, false));
        assertFalse(ChatFragment.ChatEmptyPolicy.showEmpty(true, false, false));
        assertFalse(ChatFragment.ChatEmptyPolicy.showEmpty(false, true, false));
        assertFalse(ChatFragment.ChatEmptyPolicy.showEmpty(false, false, true));
        assertTrue(ChatFragment.ChatEmptyPolicy.showLoading(true, false, false));
        assertFalse(ChatFragment.ChatEmptyPolicy.showLoading(true, false, true));
        assertFalse(ChatFragment.ChatEmptyPolicy.showLoading(true, true, false));
        assertTrue(ChatFragment.ChatEmptyPolicy.showError(true, false));
        assertFalse(ChatFragment.ChatEmptyPolicy.showError(true, true));
        assertFalse(ChatFragment.ChatEmptyPolicy.showError(false, false));
    }
}
