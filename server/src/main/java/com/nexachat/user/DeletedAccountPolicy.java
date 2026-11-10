package com.nexachat.user;

/**
 * Server-side presentation for accounts the owner has deleted. The display name
 * is fixed so clients can localize around it if needed; phone is cleared in the
 * database and masked on every public profile surface.
 */
public final class DeletedAccountPolicy {

    public static final String DISPLAY_NAME = "Deleted account";

    /** bcrypt-shaped placeholder; never matches a real password. */
    public static final String TOMBSTONE_PASSWORD_HASH =
            "$2a$12$nxacctdelnxacctdelnxacuKxacctdelnxacctdelnxacctdelxxx";

    private DeletedAccountPolicy() {
    }

    public static boolean isDeleted(User user) {
        return user != null && user.getDeletedAt() != null;
    }
}
