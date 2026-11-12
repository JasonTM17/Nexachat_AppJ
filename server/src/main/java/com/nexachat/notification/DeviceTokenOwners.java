package com.nexachat.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assigns each physical device token to one account.
 *
 * <p>A device token can hold a row per account that signed in on that device.
 * Each registration refreshes {@code updatedAt}, so the most recently updated
 * row names the account currently signed in. A push goes to the device once,
 * addressed to that account; the client still drops a payload whose recipient
 * is not its active account.</p>
 */
public final class DeviceTokenOwners {

    private DeviceTokenOwners() {
    }

    /**
     * Takes the candidate rows of each account and returns, per account, only
     * the tokens it currently owns. Account order is kept; accounts that own no
     * token are omitted.
     */
    public static Map<UUID, List<UserDeviceToken>> byCurrentOwner(
            Map<UUID, List<UserDeviceToken>> rowsByAccount) {
        Map<String, Candidate> owners = new LinkedHashMap<>();
        if (rowsByAccount != null) {
            rowsByAccount.forEach((accountId, rows) -> {
                if (accountId == null || rows == null) {
                    return;
                }
                for (UserDeviceToken row : rows) {
                    if (row == null || row.getToken() == null) {
                        continue;
                    }
                    Candidate candidate = new Candidate(accountId, row);
                    owners.merge(row.getToken(), candidate,
                            (kept, next) -> isNewer(next.row(), kept.row()) ? next : kept);
                }
            });
        }
        Map<UUID, List<UserDeviceToken>> result = new LinkedHashMap<>();
        if (rowsByAccount != null) {
            rowsByAccount.keySet().forEach(accountId -> {
                if (accountId != null) {
                    result.put(accountId, new ArrayList<>());
                }
            });
        }
        owners.values().forEach(owner -> result.get(owner.accountId()).add(owner.row()));
        result.values().removeIf(List::isEmpty);
        return result;
    }

    private static boolean isNewer(UserDeviceToken candidate, UserDeviceToken kept) {
        int byUpdate = compare(candidate.getUpdatedAt(), kept.getUpdatedAt());
        if (byUpdate != 0) {
            return byUpdate > 0;
        }
        return compare(candidate.getLastUsedAt(), kept.getLastUsedAt()) > 0;
    }

    private static int compare(Instant left, Instant right) {
        if (left == null) {
            return right == null ? 0 : -1;
        }
        return right == null ? 1 : left.compareTo(right);
    }

    private record Candidate(UUID accountId, UserDeviceToken row) {
    }
}
