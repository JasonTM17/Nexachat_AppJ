package com.nexachat.auth;

import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.message.MessageRecallProperties;
import com.nexachat.user.User;
import com.nexachat.user.dto.UserResponse;
import org.springframework.stereotype.Component;

/** Builds the unchanged public auth response shape from the locked user snapshot. */
@Component
public class AuthResponseFactory {

    private final JwtService jwtService;
    private final MessageRecallProperties messageRecallProperties;

    public AuthResponseFactory(JwtService jwtService, MessageRecallProperties messageRecallProperties) {
        this.jwtService = jwtService;
        this.messageRecallProperties = messageRecallProperties;
    }

    public AuthResponse create(User user, String refreshToken) {
        return create(user, refreshToken, null);
    }

    public AuthResponse create(User user, String refreshToken, Boolean newAccount) {
        return new AuthResponse(
                UserResponse.from(user),
                jwtService.generateAccessToken(user),
                jwtService.accessTokenExpiresIn(),
                refreshToken,
                messageRecallProperties.getRecallWindow().toSeconds(),
                MessageRecallProperties.GROUP_ADMIN_MAY_RECALL_AFTER_WINDOW,
                newAccount);
    }
}
