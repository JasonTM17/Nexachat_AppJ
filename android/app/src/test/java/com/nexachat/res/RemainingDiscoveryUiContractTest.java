package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class RemainingDiscoveryUiContractTest {
    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

    private File appDir;

    @Before
    public void setUp() {
        File[] candidates = {
            new File("."), new File("app"), new File("android/app"), new File("../app")
        };
        for (File candidate : candidates) {
            if (new File(candidate, "src/main/res/values/colors_nexachat.xml").isFile()) {
                appDir = candidate;
                return;
            }
        }
        throw new IllegalStateException("Could not locate Android app directory");
    }

    @Test
    public void cachedContactFailureKeepsRowsAndOffersOneRetryAction() throws Exception {
        String contacts = readMainJava("com/nexachat/feature/contacts/ContactsFragment.java");

        assertContains(contacts, "boolean showError = error != null && contacts.isEmpty();");
        assertContains(contacts, "lastCachedRefreshErrorId");
        assertContains(contacts, "if (lastCachedRefreshErrorId != errorMessageId)");
        assertContains(contacts, "Snackbar.make(binding.getRoot(),");
        assertContains(contacts, "ContactErrorIds.messageId(error)");
        assertContains(contacts, ".setAction(R.string.action_retry,");
        assertContains(contacts, "viewModel.loadContacts()");
    }

    @Test
    public void contactDialogsUseTheDefinedTypeRoles() throws Exception {
        String contacts = readMainJava("com/nexachat/feature/contacts/ContactsFragment.java");
        String detail = readMainJava("com/nexachat/feature/contacts/ContactDetailBinder.java");

        assertContains(contacts, "R.style.TextAppearance_NexaChat_TitleLarge");
        assertContains(contacts, "R.style.TextAppearance_NexaChat_BodyLarge");
        assertContains(contacts, "R.style.TextAppearance_NexaChat_BodyMedium");
        assertFalse(contacts.contains("TextAppearance_Material3_"));
        assertContains(detail, "R.style.TextAppearance_NexaChat_TitleLarge");
        assertContains(detail, "R.style.TextAppearance_NexaChat_BodyMedium");
        assertFalse(detail.contains("TextAppearance_Material3_"));
    }

    @Test
    public void addContactDialogScrollsAndAnnouncesChangingFeedback() throws Exception {
        String contacts = readMainJava("com/nexachat/feature/contacts/ContactsFragment.java");

        assertContains(contacts, "new ScrollView(requireContext())");
        assertContains(contacts, "scroll.setFillViewport(true)");
        assertContains(contacts, "scroll.addView(root)");
        assertContains(contacts, ".setView(scroll)");
        assertContains(contacts,
                "addErrorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)");
        assertContains(contacts,
                "addResultText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)");
        assertContains(contacts,
                "MaterialColors.getColor(addErrorText, com.google.android.material.R.attr.colorError)");
    }

    @Test
    public void groupRenameIconUsesTheCurrentThemeInk() throws Exception {
        String group = readMainJava("com/nexachat/feature/group/GroupDetailFragment.java");

        assertContains(group, "renameItem.getIcon().mutate().setTint(");
        assertContains(group,
                "MaterialColors.getColor(binding.toolbar, com.google.android.material.R.attr.colorOnSurfaceVariant)");
    }

    @Test
    public void groupEditActionsAreAdminOnlyAndRemainNamedAndReachable() throws Exception {
        String group = readMainJava("com/nexachat/feature/group/GroupDetailFragment.java");
        String members = readMainJava("com/nexachat/feature/group/adapter/GroupMemberAdapter.java");
        Element title = findElementById(readLayout("fragment_group_detail.xml"), "groupTitle");

        assertNotNull(title);
        assertEquals("@dimen/min_touch_target", getAttr(title, "minHeight"));
        assertEquals("@dimen/min_touch_target", getAttr(title, "minWidth"));
        assertContains(group, "binding.groupAvatar.setClickable(state.isIAmAdmin())");
        assertContains(group, "binding.groupTitle.setClickable(state.isIAmAdmin())");
        assertContains(group, "R.string.group_change_avatar");
        assertContains(group, "R.string.action_rename_group");
        int adminStart = group.indexOf("if (isAdmin) {");
        int adminEnd = group.indexOf("} else {", adminStart);
        int avatarName = group.indexOf("binding.groupAvatar.setName(title)");
        int adminAvatarDescription = group.indexOf(
                "binding.groupAvatar.setContentDescription(getString(R.string.group_change_avatar))");
        int adminAvatarClick = group.indexOf(
                "binding.groupAvatar.setOnClickListener(v -> avatarPicker.launch(\"image/*\"))");
        assertTrue(adminStart >= 0 && adminEnd > adminStart);
        assertTrue(adminAvatarClick > adminStart && adminAvatarClick < adminEnd);
        assertTrue(avatarName >= 0 && adminAvatarDescription > avatarName);
        assertFalse(members.contains("itemView.setOnClickListener("));
        assertContains(members, "removeMemberContentDescriptionRes()");
    }

    @Test
    public void inviteManagerWaitsForInitialDataAndRetriesTheCompleteLoad() throws Exception {
        String fragment = readMainJava("com/nexachat/feature/invite/InviteManageFragment.java");
        String viewModel = readMainJava("com/nexachat/feature/invite/InviteManageViewModel.java");

        assertContains(fragment,
                "binding.linkStatus.setVisibility(state.isLoaded() ? View.VISIBLE : View.GONE)");
        assertContains(fragment, "state.isLoaded() && requests.isEmpty()");
        assertContains(fragment, "binding.createLinkButton.setEnabled(state.isLoaded() && !state.isBusy())");
        assertContains(fragment,
                "binding.refreshRequestsButton.setText(retryInitialLoad ? R.string.action_retry : R.string.invite_refresh_requests)");
        assertContains(fragment, "viewModel.load(conversationId)");
        assertContains(fragment, "viewModel.refreshRequests()");
        assertTrue(occurrences(viewModel, "!current().isLoaded()") >= 3);
    }

    private String readMainJava(String relativePath) throws Exception {
        File source = new File(appDir, "src/main/java/" + relativePath);
        StringBuilder content = new StringBuilder();
        try (Reader reader = new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
        }
        return content.toString().replaceAll("\\s+", " ").replaceAll("\\( ", "(");
    }

    private Document readLayout(String name) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(appDir, "src/main/res/layout/" + name));
    }

    private static Element findElementById(Document document, String suffix) {
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String id = getAttr(element, "id");
            if (id.endsWith("/" + suffix)) {
                return element;
            }
        }
        return null;
    }

    private static String getAttr(Element element, String name) {
        String value = element.getAttributeNS(NS_ANDROID, name);
        if (value == null || value.isEmpty()) {
            value = element.getAttribute("android:" + name);
        }
        return value == null ? "" : value;
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(value, from)) >= 0) {
            count++;
            from += value.length();
        }
        return count;
    }

    private static void assertContains(String text, String value) {
        assertTrue("Missing source contract: " + value, text.contains(value));
    }
}
