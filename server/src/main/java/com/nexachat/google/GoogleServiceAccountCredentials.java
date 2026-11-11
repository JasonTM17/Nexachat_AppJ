package com.nexachat.google;

import com.google.auth.oauth2.GoogleCredentials;

import java.security.PrivateKey;

/** Parsed service-account data shared by Google integrations; never logs key material. */
public final class GoogleServiceAccountCredentials {

    private final String clientEmail;
    private final String projectId;
    private final String tokenUri;
    private final PrivateKey privateKey;
    private final GoogleCredentials googleCredentials;

    GoogleServiceAccountCredentials(String clientEmail,
                                    String projectId,
                                    String tokenUri,
                                    PrivateKey privateKey,
                                    GoogleCredentials googleCredentials) {
        this.clientEmail = clientEmail;
        this.projectId = projectId;
        this.tokenUri = tokenUri;
        this.privateKey = privateKey;
        this.googleCredentials = googleCredentials;
    }

    public String clientEmail() {
        return clientEmail;
    }

    public String projectId() {
        return projectId;
    }

    public String tokenUri() {
        return tokenUri;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public GoogleCredentials googleCredentials() {
        return googleCredentials;
    }

    @Override
    public String toString() {
        return "GoogleServiceAccountCredentials{clientEmail=[REDACTED], projectId=[REDACTED], "
                + "tokenUri='" + tokenUri + "', privateKey=[REDACTED]}";
    }
}
