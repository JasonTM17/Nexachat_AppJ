package com.nexachat.feature.auth;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import com.nexachat.R;
import com.nexachat.feature.invite.InviteLinkParser;

/** Replaces the auth root so completed forms never remain as Back destinations. */
public final class AuthNav {
    private AuthNav() {}

    @Nullable
    static String validatedInviteToken(@Nullable String token) {
        return InviteLinkParser.shareUrl(token, null).isPresent() ? token : null;
    }

    @Nullable
    static String returnInviteToken(@Nullable Bundle arguments) {
        return validatedInviteToken(arguments == null
                ? null : arguments.getString("returnInviteToken"));
    }

    @Nullable
    static Bundle inviteReturnArgs(@Nullable String token) {
        String validated = validatedInviteToken(token);
        if (validated == null) return null;
        Bundle arguments = new Bundle();
        arguments.putString("returnInviteToken", validated);
        return arguments;
    }

    static void openSignedIn(NavController controller, @Nullable String token) {
        String validated = validatedInviteToken(token);
        Bundle arguments = null;
        if (validated != null) {
            arguments = new Bundle();
            arguments.putString("token", validated);
        }
        controller.navigate(validated == null ? R.id.inboxFragment : R.id.inviteJoinFragment,
                arguments, signedInOptions());
    }

    public static NavOptions signedInOptions() {
        return new NavOptions.Builder().setPopUpTo(R.id.nav_main, true).build();
    }

    public static NavOptions authTabSwitchOptions() {
        return new NavOptions.Builder()
                .setPopUpTo(R.id.nav_main, true)
                .setLaunchSingleTop(true)
                .build();
    }
}
