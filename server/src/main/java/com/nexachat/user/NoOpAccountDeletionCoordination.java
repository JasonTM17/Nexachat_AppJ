package com.nexachat.user;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class NoOpAccountDeletionCoordination implements AccountDeletionCoordination {
    @Override
    public void beforeTombstone(UUID userId) {
    }
}
