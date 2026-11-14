package com.nexachat.e2ee;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.e2ee.dto.E2eeDeviceResponse;
import com.nexachat.e2ee.dto.E2eeDeviceView;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Device identity keys for E2EE direct chats. The server stores public keys
 * only; correctness rules enforced here:
 *
 * <ul>
 *   <li>{@code PUT} is idempotent for the same id+owner+key while active; any
 *       other reuse of the id is a {@code E2EE_DEVICE_CONFLICT}.</li>
 *   <li>A user keeps at most {@value #MAX_ACTIVE_DEVICES} active devices.</li>
 *   <li>Revocation is soft ({@code revoked_at}); logout-all and account
 *       deletion revoke everything the user owns.</li>
 * </ul>
 */
@Service
@Profile("!test")
public class E2eeDeviceService {

    static final int MAX_ACTIVE_DEVICES = 10;
    private static final long REGISTER_RATE_LIMIT = 20;
    private static final Duration REGISTER_RATE_PERIOD = Duration.ofHours(1);

    private final E2eeDeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final RateLimitService rateLimitService;
    private final Clock clock;

    @Autowired
    public E2eeDeviceService(E2eeDeviceRepository deviceRepository,
                             UserRepository userRepository,
                             RateLimitService rateLimitService) {
        this(deviceRepository, userRepository, rateLimitService, Clock.systemUTC());
    }

    E2eeDeviceService(E2eeDeviceRepository deviceRepository,
                      UserRepository userRepository,
                      RateLimitService rateLimitService,
                      Clock clock) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.rateLimitService = rateLimitService;
        this.clock = clock;
    }

    @Transactional
    public E2eeDeviceResponse register(UUID userId, UUID deviceId, String publicKey) {
        consumeRegisterBudget(userId);
        byte[] key = decodePublicKey(publicKey);
        Optional<E2eeDevice> existing = deviceRepository.findById(deviceId);
        if (existing.isPresent()) {
            E2eeDevice device = existing.get();
            boolean same = device.getRevokedAt() == null
                    && device.getUser().getId().equals(userId)
                    && Arrays.equals(device.getPublicKey(), key);
            if (!same) {
                throw ApiException.of(ApiErrorCode.E2EE_DEVICE_CONFLICT,
                        "Device id is already registered");
            }
            return toResponse(device);
        }
        if (deviceRepository.countByUser_IdAndRevokedAtIsNull(userId) >= MAX_ACTIVE_DEVICES) {
            throw ApiException.of(ApiErrorCode.E2EE_DEVICE_LIMIT,
                    "At most " + MAX_ACTIVE_DEVICES + " active devices per user");
        }
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        E2eeDevice saved = deviceRepository.save(E2eeDevice.register(deviceId, owner, key));
        return toResponse(saved);
    }

    @Transactional
    public void revoke(UUID userId, UUID deviceId) {
        deviceRepository.findById(deviceId)
                .filter(device -> device.getUser().getId().equals(userId))
                .filter(device -> device.getRevokedAt() == null)
                .ifPresent(device -> deviceRepository.save(
                        revokeNow(device)));
    }

    /** Revokes every active device the user owns (logout-all, account deletion). */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        deviceRepository.revokeAllForUser(userId, Instant.now(clock));
    }

    /** Active devices of every listed member, in user/registration order. */
    @Transactional(readOnly = true)
    public List<E2eeDeviceView> activeDevicesForMembers(Collection<UUID> memberUserIds) {
        if (memberUserIds == null || memberUserIds.isEmpty()) {
            return List.of();
        }
        return deviceRepository
                .findByUser_IdInAndRevokedAtIsNullOrderByUser_IdAscCreatedAtAsc(memberUserIds)
                .stream()
                .map(device -> new E2eeDeviceView(
                        device.getUser().getId(), device.getId(), encodePublicKey(device)))
                .toList();
    }

    /** Ids from {@code deviceIds} that are currently active and owned by one of {@code userIds}. */
    @Transactional(readOnly = true)
    public List<UUID> activeDeviceIdsForUsers(Collection<UUID> userIds, Collection<UUID> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        return deviceRepository.findActiveIdsForUsers(userIds, deviceIds);
    }

    /** Whether every listed member owns at least one active device. */
    @Transactional(readOnly = true)
    public boolean allMembersHaveDevices(Collection<UUID> memberUserIds) {
        List<UUID> ids = memberUserIds == null ? List.of() : List.copyOf(memberUserIds);
        if (ids.isEmpty()) {
            return false;
        }
        var withDevices = deviceRepository
                .findByUser_IdInAndRevokedAtIsNullOrderByUser_IdAscCreatedAtAsc(ids)
                .stream()
                .map(device -> device.getUser().getId())
                .distinct()
                .count();
        return withDevices == ids.size();
    }

    private E2eeDevice revokeNow(E2eeDevice device) {
        device.revoke(Instant.now(clock));
        return device;
    }

    private void consumeRegisterBudget(UUID userId) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                "e2ee-device-register:" + userId, REGISTER_RATE_LIMIT, REGISTER_RATE_PERIOD);
        if (!consumption.allowed()) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED, "Too many device registrations",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }

    private static byte[] decodePublicKey(String publicKey) {
        if (publicKey == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "publicKey is required");
        }
        final byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(publicKey);
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "publicKey must be base64url encoded");
        }
        if (key.length != 32) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "publicKey must decode to exactly 32 bytes");
        }
        return key;
    }

    private static String encodePublicKey(E2eeDevice device) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(device.getPublicKey());
    }

    private static E2eeDeviceResponse toResponse(E2eeDevice device) {
        return new E2eeDeviceResponse(device.getId(), encodePublicKey(device), device.getCreatedAt());
    }
}
