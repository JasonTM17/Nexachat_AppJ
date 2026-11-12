package com.nexachat.feature.contacts;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.Contact;
import org.junit.Test;

/**
 * Rotation keeps the selected contact in the ViewModel while the dialog shell
 * is torn down; a user dismiss clears it so it does not return after recreation.
 */
public final class ContactDetailBinderRotationTest {

    private static final String USER_ID = "u-1";
    private static final String PHONE = "+84912345678";

    @Test
    public void configurationChangeLeavesDetailReadyForOneDialog() {
        Contact contact = new Contact(USER_ID, PHONE, "Lan", "Be Na", null, null);
        UserDto user = new UserDto(USER_ID, PHONE, "Lan", null);
        ContactsViewModel.UiState state = new ContactsViewModel.UiState()
                .withDetailUser(user)
                .withDetailContact(contact)
                .withAliasInput("Be Na");

        assertNotNull(ContactDetailBinder.contentFor(state));
    }

    @Test
    public void userClosedDetailStaysClosedAfterRecreation() {
        assertNull(ContactDetailBinder.contentFor(new ContactsViewModel.UiState()));
    }
}
