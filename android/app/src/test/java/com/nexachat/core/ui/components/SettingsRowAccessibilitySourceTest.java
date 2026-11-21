package com.nexachat.core.ui.components;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Pins the SettingsRowView accessibility contract behind issue #254's
 * unlabeled_interactive findings (profile {@code languageRow}/{@code themeRow},
 * contacts {@code addContactEntry}/{@code createGroupEntry}): the clickable
 * row composes its own description from title, subtitle and trailing value —
 * the same merged-row pattern the inbox conversation rows use since #260 — so
 * a flat uiautomator dump reads one label per row instead of a click target
 * whose text only lives in child views.
 */
public final class SettingsRowAccessibilitySourceTest {

    @Test
    public void settingsRow_composesRowContentDescription() throws Exception {
        String source = read("src/main/java/com/nexachat/core/ui/components/SettingsRowView.java");
        int compose = source.indexOf("private void updateRowAccessibilityDescription()");
        assertTrue("SettingsRowView must compose a row description", compose >= 0);
        String body = source.substring(compose);
        assertTrue(body.contains("String.join(\", \", parts)"));
        assertTrue("the compose folds the row's own text children",
                body.contains("addVisibleText(parts, titleView)"));
    }

    @Test
    public void settingsRow_settersRefreshTheRowDescription() throws Exception {
        String source = read("src/main/java/com/nexachat/core/ui/components/SettingsRowView.java");
        for (String setter : new String[] {
                "public void setTitle(CharSequence title)",
                "public void setSubtitle(@Nullable CharSequence subtitle)",
                "public void setTrailingValue(@Nullable CharSequence value)"}) {
            int start = source.indexOf(setter);
            assertTrue("expected " + setter, start >= 0);
            int bodyStart = source.indexOf("{", start);
            int nextMethod = source.indexOf("\n    public ", bodyStart);
            String method = source.substring(bodyStart,
                    nextMethod < 0 ? source.length() : nextMethod);
            assertTrue(setter + " must refresh the row accessibility description",
                    method.contains("updateRowAccessibilityDescription()"));
        }
    }

    @Test
    public void profileRows_andContactsEntries_areComposedThroughTheSetters() throws Exception {
        String profile = read("src/main/java/com/nexachat/feature/profile/ProfileFragment.java");
        assertTrue("the language row title reaches the compose via setTitle",
                profile.contains("binding.languageRow.setTitle(getString(R.string.settings_language))"));
        assertTrue("the language row's trailing value stays part of the description",
                profile.contains("binding.languageRow.setTrailingValue("));
        assertTrue("the theme row title reaches the compose via setTitle",
                profile.contains("binding.themeRow.setTitle(getString(R.string.settings_theme_title))"));
        String contacts = read("src/main/java/com/nexachat/feature/contacts/ContactsFragment.java");
        assertTrue("the add-contact entry title reaches the compose via setTitle",
                contacts.contains("binding.addContactEntry.setTitle(getString(R.string.add_contact_title))"));
        assertTrue("the create-group entry title reaches the compose via setTitle",
                contacts.contains("binding.createGroupEntry.setTitle(getString(R.string.group_create_title))"));
    }

    private static String read(String relative) throws Exception {
        for (String prefix : new String[] {"", "android/app/", "app/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate " + relative);
    }
}
