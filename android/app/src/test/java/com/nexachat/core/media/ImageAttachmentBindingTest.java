package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.junit.Test;

public final class ImageAttachmentBindingTest {

    @Test
    public void portableBlankCheckHandlesWhitespaceAndText() {
        assertTrue(MediaText.isBlank(" \n\u2003"));
        assertFalse(MediaText.isBlank(" image "));
        assertTrue(MediaText.isBlank(null));
    }

    @Test
    public void validHashShowsPlaceholderBeforeTheRemoteLoadStarts() {
        BlurHashPlaceholderCache<String> cache = new BlurHashPlaceholderCache<>();
        String placeholder = cache.getOrCreate("000000", hash -> "bitmap:" + hash);
        FakeTarget target = new FakeTarget();
        ImageAttachmentBinding<String> binding = new ImageAttachmentBinding<>();

        binding.bind("https://media.test/image", placeholder, target,
                (url, value, token, currentTarget) -> {
                    assertEquals("https://media.test/image", url);
                    assertEquals("bitmap:000000", target.placeholder);
                    target.loaderStarted = true;
                    target.loadedToken = token;
                });

        assertTrue(target.cleared);
        assertTrue(target.loaderStarted);
        assertEquals("bitmap:000000", target.placeholder);
        assertTrue(ImageAttachmentBinding.isCurrent(target, target.loadedToken));
    }

    @Test
    public void invalidHashIsSafeAndDoesNotCreateAPlaceholder() {
        BlurHashPlaceholderCache<String> cache = new BlurHashPlaceholderCache<>();
        String placeholder = cache.getOrCreate("not-a-hash!", hash -> "should-not-run");
        FakeTarget target = new FakeTarget();

        new ImageAttachmentBinding<String>().bind(null, placeholder, target,
                (url, value, token, currentTarget) -> {
                    throw new AssertionError("no remote URL was supplied");
                });

        assertNull(placeholder);
        assertNull(target.placeholder);
        assertTrue(target.cleared);
    }

    @Test
    public void rebindingMakesOldRequestCallbacksStale() {
        ImageAttachmentBinding<String> binding = new ImageAttachmentBinding<>();
        FakeTarget target = new FakeTarget();
        Object first = binding.bind("https://media.test/one", "one", target,
                (url, placeholder, token, currentTarget) -> { });
        Object second = binding.bind("https://media.test/two", "two", target,
                (url, placeholder, token, currentTarget) -> { });

        assertNotSame(first, second);
        assertFalse(ImageAttachmentBinding.isCurrent(target, first));
        assertTrue(ImageAttachmentBinding.isCurrent(target, second));
    }

    private static final class FakeTarget implements ImageAttachmentBinding.Target<String> {
        private boolean cleared;
        private boolean loaderStarted;
        @Nullable private String placeholder;
        @Nullable private Object token;
        @Nullable private Object loadedToken;

        @Override public void clearRequest() { cleared = true; }
        @Override public void setRequestToken(@NonNull Object requestToken) { token = requestToken; }
        @Nullable @Override public Object requestToken() { return token; }
        @Override public void showPlaceholder(@Nullable String value) { placeholder = value; }
    }
}
