package com.nexachat.feature.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.Contact;
import org.junit.Test;

/**
 * Characterization of the contact-detail content shared by the contacts list
 * and user search. Both screens must render the same fields from the same
 * {@link ContactsViewModel.UiState}.
 */
public final class ContactDetailBinderTest {

    private static final String USER_ID = "u-1";
    private static final String PHONE = "+84912345678";
    private static final String PROFILE_AVATAR = "https://cdn.example/profile.png";
    private static final String CONTACT_AVATAR = "https://cdn.example/contact.png";

    @Test
    public void savedContactUsesAliasLabelPhoneAndContactAvatar() {
        Contact contact = new Contact(
                USER_ID, PHONE, "Lan", "Be Na", CONTACT_AVATAR, "2026-01-02T00:00:00Z");
        ContactsViewModel.UiState state = detailState(user(), contact, "Be Na");

        ContactDetailBinder.Content content = ContactDetailBinder.contentFor(state);

        assertEquals(USER_ID, content.getUserId());
        assertEquals("Be Na", content.getTitle());
        assertEquals(PHONE, content.getPhone());
        assertEquals(CONTACT_AVATAR, content.getAvatarUrl());
        assertTrue(content.isContact());
        assertTrue(content.showsAliasEditor());
        assertEquals(R.string.action_remove_contact, content.getSecondaryActionTextRes());
    }

    @Test
    public void savedContactWithoutAliasFallsBackToProfileName() {
        Contact contact = new Contact(USER_ID, PHONE, "Lan", "   ", CONTACT_AVATAR, null);
        ContactsViewModel.UiState state = detailState(user(), contact, "");

        ContactDetailBinder.Content content = ContactDetailBinder.contentFor(state);

        assertEquals("Lan", content.getTitle());
        assertEquals(CONTACT_AVATAR, content.getAvatarUrl());
    }

    @Test
    public void searchedUserUsesProfileNameAvatarAndAddAction() {
        ContactsViewModel.UiState state = detailState(user(), null, "");

        ContactDetailBinder.Content content = ContactDetailBinder.contentFor(state);

        assertEquals("Lan", content.getTitle());
        assertEquals(PROFILE_AVATAR, content.getAvatarUrl());
        assertFalse(content.isContact());
        assertFalse(content.showsAliasEditor());
        assertEquals(R.string.action_add_contact, content.getSecondaryActionTextRes());
    }

    @Test
    public void busyContentDisablesBothActionsAndShowsProgress() {
        ContactsViewModel.UiState state = detailState(user(), null, "").withDetailBusy(true);

        ContactDetailBinder.Content content = ContactDetailBinder.contentFor(state);

        assertTrue(content.isBusy());
        assertTrue(content.showsProgress());
        assertFalse(content.isMessageEnabled());
        assertFalse(content.isActionEnabled());
    }

    @Test
    public void idleContentEnablesBothActionsAndHidesProgress() {
        ContactsViewModel.UiState state = detailState(user(), null, "");

        ContactDetailBinder.Content content = ContactDetailBinder.contentFor(state);

        assertFalse(content.isBusy());
        assertFalse(content.showsProgress());
        assertTrue(content.isMessageEnabled());
        assertTrue(content.isActionEnabled());
    }

    @Test
    public void errorContentMapsThroughTheContactsCopy() {
        ContactsViewModel.UiState state = detailState(user(), null, "")
                .withDetailError(new ApiError.Network("offline"));

        ContactDetailBinder.Content content = ContactDetailBinder.contentFor(state);

        assertTrue(content.showsError());
        assertEquals(R.string.error_network, content.getErrorMessageId());
    }

    @Test
    public void cleanContentHasNoErrorCopy() {
        ContactDetailBinder.Content content =
                ContactDetailBinder.contentFor(detailState(user(), null, ""));

        assertFalse(content.showsError());
        assertEquals(0, content.getErrorMessageId());
        assertNull(content.getError());
    }

    @Test
    public void noContentWithoutSelectedUser() {
        assertNull(ContactDetailBinder.contentFor(new ContactsViewModel.UiState()));
    }

    @Test
    public void aliasFieldIsReusedOnlyForTheSameCleanIdleContact() {
        ContactDetailBinder.Content contact =
                ContactDetailBinder.contentFor(detailState(contactUser(), savedContact(), "Be Na"));
        ContactDetailBinder.Content searchUser =
                ContactDetailBinder.contentFor(detailState(user(), null, ""));

        assertTrue(ContactDetailBinder.canReuseAliasField(
                USER_ID, true, contact.getTitle(), contact, true));
        assertFalse("other user",
                ContactDetailBinder.canReuseAliasField("u-2", true, contact.getTitle(), contact, true));
        assertFalse("no field",
                ContactDetailBinder.canReuseAliasField(USER_ID, true, contact.getTitle(), contact, false));
        assertFalse("contact became a search result",
                ContactDetailBinder.canReuseAliasField(USER_ID, false, contact.getTitle(), contact, true));
        assertFalse("search result has no alias editor",
                ContactDetailBinder.canReuseAliasField(USER_ID, false, searchUser.getTitle(), searchUser, true));
    }

    @Test
    public void savedAliasUpdatesTitleSoDialogMustRebind() {
        Contact beforeSave = new Contact(USER_ID, PHONE, "Lan", "Old nick", CONTACT_AVATAR, null);
        Contact afterSave = new Contact(USER_ID, PHONE, "Lan", "New nick", CONTACT_AVATAR, null);
        ContactDetailBinder.Content beforeContent = ContactDetailBinder.contentFor(
                detailState(contactUser(), beforeSave, "Old nick"));
        ContactDetailBinder.Content afterContent = ContactDetailBinder.contentFor(
                detailState(contactUser(), afterSave, "New nick"));

        assertEquals("Old nick", beforeContent.getTitle());
        assertEquals("New nick", afterContent.getTitle());
        assertTrue(ContactDetailBinder.canReuseAliasField(
                USER_ID, true, beforeContent.getTitle(), beforeContent, true));
        assertFalse("title changed after alias save",
                ContactDetailBinder.canReuseAliasField(
                        USER_ID, true, beforeContent.getTitle(), afterContent, true));
    }

    @Test
    public void errorOrBusyStateRebuildsTheAliasField() {
        ContactDetailBinder.Content error =
                ContactDetailBinder.contentFor(detailState(contactUser(), savedContact(), "Be Na")
                        .withDetailError(ApiError.RateLimited.INSTANCE));
        ContactDetailBinder.Content busy =
                ContactDetailBinder.contentFor(detailState(contactUser(), savedContact(), "Be Na")
                        .withDetailBusy(true));

        assertFalse(ContactDetailBinder.canReuseAliasField(
                USER_ID, true, error.getTitle(), error, true));
        assertFalse(ContactDetailBinder.canReuseAliasField(
                USER_ID, true, busy.getTitle(), busy, true));
    }

    private static ContactsViewModel.UiState detailState(
            UserDto user, Contact contact, String alias) {
        return new ContactsViewModel.UiState()
                .withDetailUser(user)
                .withDetailContact(contact)
                .withAliasInput(alias);
    }

    private static UserDto user() {
        return new UserDto(USER_ID, PHONE, "Lan", PROFILE_AVATAR);
    }

    private static UserDto contactUser() {
        return new UserDto(USER_ID, PHONE, "Lan", null);
    }

    private static Contact savedContact() {
        return new Contact(USER_ID, PHONE, "Lan", "Be Na", CONTACT_AVATAR, null);
    }
}
