package com.nexachat.testutil;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared, non-overlapping phone ranges for server integration tests. */
public final class ItPhoneRanges {

    private static final Map<String, String> PREFIXES = Map.ofEntries(
            Map.entry("com.nexachat.archive.ArchiveFlowIT", "+84810"),
            Map.entry("com.nexachat.attachment.AttachmentFlowIT", "+84811"),
            Map.entry("com.nexachat.attachment.MultipartDefaultTempFileIT", "+84812"),
            Map.entry("com.nexachat.attachment.MultipartTempFileIT", "+84813"),
            Map.entry("com.nexachat.attachment.UploadAdmissionFlowIT", "+84814"),
            Map.entry("com.nexachat.auth.AuthFlowIT", "+84815"),
            Map.entry("com.nexachat.call.CallFlowIT", "+84816"),
            Map.entry("com.nexachat.contact.ContactConversationIT", "+84817"),
            Map.entry("com.nexachat.conversation.ConversationMuteArchiveFlowIT", "+84818"),
            Map.entry("com.nexachat.conversation.ConversationPinSeenFlowIT", "+84819"),
            Map.entry("com.nexachat.conversation.GroupFlowIT", "+84820"),
            Map.entry("com.nexachat.invite.GroupInviteFlowIT", "+84821"),
            Map.entry("com.nexachat.message.InteractionFlowIT", "+84822"),
            Map.entry("com.nexachat.message.MessageFlowIT", "+84823"),
            Map.entry("com.nexachat.message.ReactionDeltaIdempotencyIT", "+84824"),
            Map.entry("com.nexachat.reminder.ReminderFlowIT", "+84825"),
            Map.entry("com.nexachat.reminder.ReminderSchedulerIT", "+84826"),
            Map.entry("com.nexachat.invite.P1InviteApprovalMaxUsesIT", "+84827"),
            Map.entry("com.nexachat.invite.P1InviteIpRateLimitIT", "+84828"),
            Map.entry("com.nexachat.invite.P1InviteMaxUsesRaceIT", "+84829"),
            Map.entry("com.nexachat.label.LabelFlowIT", "+84840"),
            Map.entry("com.nexachat.message.MessageSequenceGuaranteeIT", "+84841"),
            Map.entry("com.nexachat.message.P1MentionRetryIdempotencyIT", "+84842"),
            Map.entry("com.nexachat.message.RagLexicalIndexIT", "+84843"),
            Map.entry("com.nexachat.perf.ServerHotPathPerfIT", "+84844"),
            Map.entry("com.nexachat.robustness.StompInputRobustnessIT", "+84845"),
            Map.entry("com.nexachat.ws.WsPayloadSecurityIT", "+84846"),
            Map.entry("com.nexachat.firebase.auth.FirebaseLeaseRevocationRaceIT", "+84847"),
            Map.entry("com.nexachat.config.RequestBodySizeLimitChunkedIT", "+84848"),
            Map.entry("com.nexachat.auth.AuthorizationSqlBudgetIT", "+84849"),
            Map.entry("com.nexachat.message.IdempotencyConcurrencyIT", "+84850"),
            Map.entry("com.nexachat.message.MessageFeatureFlowIT", "+84851"),
            Map.entry("com.nexachat.message.MessageMentionFlowIT", "+84852"),
            Map.entry("com.nexachat.message.MessageRateLimitFlowIT", "+84853"),
            Map.entry("com.nexachat.message.MessageRecallBoundaryIT", "+84854"),
            Map.entry("com.nexachat.message.SyncDeltaMessageGapIT", "+84855"),
            Map.entry("com.nexachat.conversation.UnreadCountConcurrencyIT", "+84856"),
            Map.entry("com.nexachat.presence.PresenceFlowIT", "+84857"),
            Map.entry("com.nexachat.auth.AuthRateLimitIT", "+84858"),
            Map.entry("com.nexachat.rag.AskOptOutUncitedCacheIT", "+84859"),
            Map.entry("com.nexachat.summary.ConversationAiOptOutIT", "+84860"),
            Map.entry("com.nexachat.summary.SummaryOptOutIT", "+84861"),
            Map.entry("com.nexachat.summary.SummaryApiIT", "+84862"),
            Map.entry("com.nexachat.rag.P1AskEditUncitedCacheIT", "+84863"),
            Map.entry("com.nexachat.rag.P1AskRecallUncitedCacheIT", "+84864"),
            Map.entry("com.nexachat.attachment.AvatarOrphanSweepIT", "+84865"),
            Map.entry("com.nexachat.retention.RetentionJobsIT", "+84866"),
            Map.entry("com.nexachat.user.UserSettingsFlowIT", "+84867"),
            Map.entry("com.nexachat.auth.AccessTokenRevocationIT", "+84868"),
            Map.entry("com.nexachat.auth.AccountDeletionD4RegressionIT", "+84869"),
            Map.entry("com.nexachat.auth.AccountDeletionFlowIT", "+84870"),
            Map.entry("com.nexachat.auth.AccountDeletionFirebaseProjectionIT", "+84871"),
            Map.entry("com.nexachat.auth.AuthorizationRouteMatrixIT", "+84872"),
            Map.entry("com.nexachat.auth.AuthorizationFieldAllowlistIT", "+84873"),
            Map.entry("com.nexachat.auth.ChangePasswordBcryptBoundIT", "+84874"),
            Map.entry("com.nexachat.auth.CorsAndHeadersIT", "+84875"),
            Map.entry("com.nexachat.auth.GoogleSignInDisabledIT", "+84876"),
            Map.entry("com.nexachat.auth.GoogleSignInIT", "+84877"),
            Map.entry("com.nexachat.auth.RefreshRevocationRaceIT", "+84878"),
            Map.entry("com.nexachat.auth.RagStatsAuthorizationIT", "+84879"),
            Map.entry("com.nexachat.auth.RefreshRotationReplayIT", "+84880"),
            Map.entry("com.nexachat.auth.SecurityAbuseIT", "+84881"),
            Map.entry("com.nexachat.auth.SensitiveRequestLoggingIT", "+84882"),
            Map.entry("com.nexachat.auth.SecurityRound3IT", "+84883"),
            Map.entry("com.nexachat.robustness.MultipartInputRobustnessIT", "+84884"),
            Map.entry("com.nexachat.robustness.RangeAndNestedBodyRobustnessIT", "+84885"),
            Map.entry("com.nexachat.robustness.RestInputRobustnessIT", "+84886"),
            Map.entry("com.nexachat.robustness.ExtendedRouteRobustnessIT", "+84887"),
            Map.entry("com.nexachat.user.AccountDeletionAttachmentLockOrderIT", "+84888"),
            // Brief 375 owns these two suites. AskApiIT inserts "+849" + 9 digits, and
            // SearchTrigramIndexIT keeps "+8493". Neither file is edited here, so every
            // other suite uses a +848xx range that overlaps neither stem.
            Map.entry("com.nexachat.message.SearchTrigramIndexIT", "+8493"));

    private static final Map<String, AtomicInteger> SEQUENCES = new ConcurrentHashMap<>();
    private static final Map<String, String> FIXTURE_PHONES = new ConcurrentHashMap<>();

    private ItPhoneRanges() {
    }

    public static String prefixFor(Class<?> integrationTest) {
        String prefix = PREFIXES.get(integrationTest.getName());
        if (prefix == null) {
            throw new IllegalArgumentException("No integration-test phone range registered for "
                    + integrationTest.getName());
        }
        return prefix;
    }

    public static String nextPhone(Class<?> integrationTest) {
        String prefix = prefixFor(integrationTest);
        AtomicInteger sequence = SEQUENCES.computeIfAbsent(integrationTest.getName(),
                ignored -> new AtomicInteger(ThreadLocalRandom.current().nextInt(900_000)));
        int suffix = sequence.getAndIncrement();
        if (suffix > 999_999) {
            throw new IllegalStateException("Integration-test phone range exhausted for "
                    + integrationTest.getName());
        }
        return prefix + String.format(Locale.ROOT, "%06d", suffix);
    }

    /** Returns one stable, class-scoped phone for each legacy fixture key. */
    public static String phoneFor(Class<?> integrationTest, String fixturePhone) {
        if (fixturePhone == null || fixturePhone.isBlank()) {
            throw new IllegalArgumentException("Fixture phone key must not be blank");
        }
        String key = integrationTest.getName() + "\u0000" + fixturePhone;
        return FIXTURE_PHONES.computeIfAbsent(key, ignored -> nextPhone(integrationTest));
    }

    public static Map<String, String> registeredPrefixes() {
        return Map.copyOf(PREFIXES);
    }

}
