package com.nexachat.conversation;

import com.nexachat.conversation.dto.InboxConversationRow;
import com.nexachat.conversation.dto.InboxEntry;
import com.nexachat.conversation.dto.InboxMemberView;
import com.nexachat.conversation.dto.UnreadCountRow;
import com.nexachat.message.Message;
import com.nexachat.message.MessageRepository;
import com.nexachat.message.dto.MessageRow;
import com.nexachat.presence.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private PresenceService presenceService;

    private InboxService inboxService;

    private final UUID userId = UUID.randomUUID();
    private final UUID partnerId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inboxService = new InboxService(memberRepository, messageRepository, presenceService,
                Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC));
        org.mockito.Mockito.lenient().when(presenceService.peerPresence(any(UUID.class), anyCollection()))
                .thenReturn(java.util.Map.of());
    }

    private static InboxConversationRow row(UUID id, Long lastSeq, boolean muted,
                                            Instant mutedUntil, boolean archived) {
        return new InboxConversationRow(
                id, Conversation.Type.DIRECT, null, null, null, lastSeq,
                Instant.parse("2026-01-02T03:04:05Z"), 0L, muted, mutedUntil, archived,
                false, null);
    }

    private static InboxConversationRow pinnedRow(UUID id, Long lastSeq, Instant pinnedAt) {
        return new InboxConversationRow(
                id, Conversation.Type.DIRECT, null, null, null, lastSeq,
                Instant.parse("2026-01-02T03:04:05Z"), 0L, false, null, false,
                true, pinnedAt);
    }

    @Test
    void listInbox_empty_returnsEmptyList() {
        when(memberRepository.findInboxConversations(userId, false)).thenReturn(List.of());

        assertThat(inboxService.listInbox(userId, null)).isEmpty();
    }

    @Test
    void listInbox_assemblesEntryWithMembersLastMessageAndUnread() {
        UUID lastMessageId = UUID.randomUUID();
        Instant now = Instant.now();
        InboxConversationRow row = new InboxConversationRow(
                conversationId, Conversation.Type.DIRECT, null, null,
                lastMessageId, 42L, now.minusSeconds(60), 40L, false, null, false,
                false, null);
        when(memberRepository.findInboxConversations(userId, false)).thenReturn(List.of(row));

        InboxMemberView me = new InboxMemberView(
                conversationId, userId, "Me", "+84901111111", null, ConversationMember.Role.ADMIN);
        InboxMemberView partner = new InboxMemberView(
                conversationId, partnerId, "Partner", "+84902222222", "/api/v1/files/avatars/x.png",
                ConversationMember.Role.MEMBER);
        when(memberRepository.findInboxMemberViews(List.of(conversationId)))
                .thenReturn(List.of(me, partner));

        MessageRow lastMessage = new MessageRow(
                lastMessageId, conversationId, 42L, partnerId, "Partner",
                Message.Type.TEXT, "latest message", UUID.randomUUID(), null, now, null, null);
        when(messageRepository.findRowsByIdIn(List.of(lastMessageId)))
                .thenReturn(List.of(lastMessage));

        when(memberRepository.countUnreadByConversation(userId, List.of(conversationId)))
                .thenReturn(List.of(new UnreadCountRow(conversationId, 2L)));

        List<InboxEntry> inbox = inboxService.listInbox(userId, null);

        assertThat(inbox).hasSize(1);
        InboxEntry entry = inbox.get(0);
        assertThat(entry.id()).isEqualTo(conversationId);
        assertThat(entry.type()).isEqualTo("DIRECT");
        assertThat(entry.title()).isNull();
        assertThat(entry.members()).hasSize(2);
        assertThat(entry.members().get(1).avatarUrl()).isEqualTo("/api/v1/files/avatars/x.png");
        assertThat(entry.lastMessage().seq()).isEqualTo(42);
        assertThat(entry.lastMessage().contentSnippet()).isEqualTo("latest message");
        assertThat(entry.lastMessage().senderId()).isEqualTo(partnerId);
        assertThat(entry.unreadCount()).isEqualTo(2);
        assertThat(entry.lastReadSeq()).isEqualTo(40);
        assertThat(entry.muted()).isFalse();
        assertThat(entry.mutedUntil()).isNull();
        assertThat(entry.archived()).isFalse();
    }

    @Test
    void listInbox_mapsMuteExpiryAndArchiveFlagFromTheMemberRow() {
        Instant mutedUntil = Instant.parse("2026-12-01T00:00:00Z");
        when(memberRepository.findInboxConversations(userId, false))
                .thenReturn(List.of(row(conversationId, 7L, true, mutedUntil, true)));
        when(memberRepository.findInboxMemberViews(List.of(conversationId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(conversationId)))
                .thenReturn(List.of());

        InboxEntry entry = inboxService.listInbox(userId, null).get(0);

        assertThat(entry.muted()).isTrue();
        assertThat(entry.mutedUntil()).isEqualTo(mutedUntil);
        assertThat(entry.archived()).isTrue();
    }

    @Test
    void listInbox_mapsTheCallersPinnedFlagAndTimestamp() {
        Instant pinnedAt = Instant.parse("2026-12-01T00:00:00Z");
        when(memberRepository.findInboxConversations(userId, false))
                .thenReturn(List.of(pinnedRow(conversationId, 7L, pinnedAt)));
        when(memberRepository.findInboxMemberViews(List.of(conversationId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(conversationId)))
                .thenReturn(List.of());

        InboxEntry entry = inboxService.listInbox(userId, null).get(0);

        assertThat(entry.pinned()).isTrue();
        assertThat(entry.pinnedAt()).isEqualTo(pinnedAt);
    }

    @Test
    void listInbox_mutedForeverMapsNullExpiry() {
        when(memberRepository.findInboxConversations(userId, false))
                .thenReturn(List.of(row(conversationId, 7L, true, null, false)));
        when(memberRepository.findInboxMemberViews(List.of(conversationId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(conversationId)))
                .thenReturn(List.of());

        InboxEntry entry = inboxService.listInbox(userId, null).get(0);

        assertThat(entry.muted()).isTrue();
        assertThat(entry.mutedUntil()).isNull();
    }

    @Test
    void listInbox_expiredMuteIsReportedAsNotMuted() {
        Instant past = Instant.parse("2026-01-01T00:00:00Z");
        when(memberRepository.findInboxConversations(userId, false))
                .thenReturn(List.of(row(conversationId, 7L, true, past, false)));
        when(memberRepository.findInboxMemberViews(List.of(conversationId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(conversationId)))
                .thenReturn(List.of());

        InboxEntry entry = inboxService.listInbox(userId, null).get(0);

        assertThat(entry.muted()).isFalse();
        assertThat(entry.mutedUntil()).isNull();
    }

    @Test
    void listInbox_conversationWithoutMessages_hasNullLastMessageAndZeroUnread() {
        when(memberRepository.findInboxConversations(userId, false))
                .thenReturn(List.of(row(conversationId, null, false, null, false)));
        when(memberRepository.findInboxMemberViews(any())).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(any(), any())).thenReturn(List.of());

        InboxEntry entry = inboxService.listInbox(userId, null).get(0);

        assertThat(entry.lastMessage()).isNull();
        assertThat(entry.unreadCount()).isZero();
    }

    @Test
    void listInbox_archivedFlagOverridesTheDefaultOnlyWhenExplicitlyRequested() {
        when(memberRepository.findInboxConversations(userId, true))
                .thenReturn(List.of(row(conversationId, 1L, false, null, true)));
        when(memberRepository.findInboxMemberViews(List.of(conversationId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(conversationId)))
                .thenReturn(List.of());

        assertThat(inboxService.listInbox(userId, true)).hasSize(1);

        verify(memberRepository).findInboxConversations(userId, true);
        verify(memberRepository, org.mockito.Mockito.never()).findInboxConversations(userId, false);
    }

    @Test
    void listInbox_paginationDefaultsToNonArchivedRowsAndKeepsTheCursor() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Instant firstCreatedAt = Instant.parse("2026-01-02T03:04:05Z");
        when(memberRepository.findInboxConversationsPage(
                userId, null, null, null, null, false, PageRequest.of(0, 2)))
                .thenReturn(List.of(
                        row(firstId, 42L, false, null, false),
                        row(secondId, 41L, false, null, false)));
        when(memberRepository.findInboxMemberViews(List.of(firstId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(firstId))).thenReturn(List.of());

        InboxService.InboxPage first = inboxService.listInbox(userId, 1, null, null);

        assertThat(first.items()).extracting(InboxEntry::id).containsExactly(firstId);
        assertThat(first.nextCursor()).isNotBlank();

        when(memberRepository.findInboxConversationsPage(
                userId, false, 42L, firstCreatedAt, firstId, false, PageRequest.of(0, 2)))
                .thenReturn(List.of(row(secondId, 41L, false, null, false)));
        when(memberRepository.findInboxMemberViews(List.of(secondId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(secondId))).thenReturn(List.of());

        InboxService.InboxPage second = inboxService.listInbox(userId, 1, first.nextCursor(), null);

        assertThat(second.items()).extracting(InboxEntry::id).containsExactly(secondId);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void listInbox_pinnedPageCursorCarriesThePinnedRank() {
        UUID pinnedId = UUID.randomUUID();
        UUID unpinnedId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-02T03:04:05Z");
        InboxConversationRow pinnedRow = new InboxConversationRow(
                pinnedId, Conversation.Type.DIRECT, null, null, null, 42L,
                createdAt, 0L, false, null, false, true, createdAt);
        when(memberRepository.findInboxConversationsPage(
                userId, null, null, null, null, false, PageRequest.of(0, 2)))
                .thenReturn(List.of(pinnedRow,
                        row(unpinnedId, 41L, false, null, false)));
        when(memberRepository.findInboxMemberViews(List.of(pinnedId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(pinnedId))).thenReturn(List.of());

        InboxService.InboxPage first = inboxService.listInbox(userId, 1, null, null);
        assertThat(first.items()).extracting(InboxEntry::id).containsExactly(pinnedId);
        assertThat(first.nextCursor()).isNotBlank();

        when(memberRepository.findInboxConversationsPage(
                userId, true, 42L, createdAt, pinnedId, false, PageRequest.of(0, 2)))
                .thenReturn(List.of(row(unpinnedId, 41L, false, null, false)));
        when(memberRepository.findInboxMemberViews(List.of(unpinnedId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(unpinnedId)))
                .thenReturn(List.of());

        InboxService.InboxPage second = inboxService.listInbox(userId, 1, first.nextCursor(), null);

        assertThat(second.items()).extracting(InboxEntry::id).containsExactly(unpinnedId);
    }

    @Test
    void listInbox_cursorWithoutLimitDefaultsToNonArchivedRows() {
        UUID nextId = UUID.randomUUID();
        when(memberRepository.findInboxConversationsAfterCursor(
                userId, false, 42L, Instant.parse("2026-01-02T00:00:00Z"), conversationId, false))
                .thenReturn(List.of(row(nextId, 40L, false, null, false)));
        when(memberRepository.findInboxMemberViews(List.of(nextId))).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(nextId))).thenReturn(List.of());
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1|42|2026-01-02T00:00:00Z|" + conversationId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        InboxService.InboxPage page = inboxService.listInbox(userId, null, cursor, null);

        assertThat(page.items()).extracting(InboxEntry::id).containsExactly(nextId);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void snippet_truncatesLongContent() {
        String longContent = "x".repeat(200);
        UUID lastMessageId = UUID.randomUUID();
        InboxConversationRow row = new InboxConversationRow(
                conversationId, Conversation.Type.DIRECT, null, null,
                lastMessageId, 5L, Instant.now(), 0L, false, null, false,
                false, null);
        when(memberRepository.findInboxConversations(userId, false)).thenReturn(List.of(row));
        when(memberRepository.findInboxMemberViews(any())).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(any(), any())).thenReturn(List.of());
        when(messageRepository.findRowsByIdIn(List.of(lastMessageId))).thenReturn(List.of(
                new MessageRow(lastMessageId, conversationId, 5L, partnerId, "P",
                        Message.Type.TEXT, longContent, UUID.randomUUID(), null,
                        Instant.now(), null, null)));

        InboxEntry entry = inboxService.listInbox(userId, null).get(0);

        assertThat(entry.lastMessage().contentSnippet())
                .hasSize(InboxService.SNIPPET_LENGTH + 1)
                .endsWith("…");
    }

    @Test
    void deletedLastMessageKeepsNeutralTombstonePreview() {
        UUID lastMessageId = UUID.randomUUID();
        Instant deletedAt = Instant.parse("2026-11-05T12:00:00Z");
        InboxConversationRow row = new InboxConversationRow(
                conversationId, Conversation.Type.DIRECT, null, null,
                lastMessageId, 5L, Instant.now(), 0L, false, null, false,
                false, null);
        when(memberRepository.findInboxConversations(userId, false)).thenReturn(List.of(row));
        when(memberRepository.findInboxMemberViews(any())).thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(any(), any())).thenReturn(List.of());
        when(messageRepository.findRowsByIdIn(List.of(lastMessageId))).thenReturn(List.of(
                new MessageRow(lastMessageId, conversationId, 5L, partnerId, "P",
                        Message.Type.TEXT, null, UUID.randomUUID(), null,
                        Instant.now(), null, deletedAt)));

        InboxEntry entry = inboxService.listInbox(userId, null).get(0);

        assertThat(entry.lastMessage().seq()).isEqualTo(5L);
        assertThat(entry.lastMessage().contentSnippet()).isNull();
        assertThat(entry.lastMessage().deletedAt()).isEqualTo(deletedAt);
    }

    // --------------------------------------------------------------- authZ

    @Test
    void listInbox_scopesEveryQueryToCallerUserId() {
        // The inbox's authorization boundary IS the userId argument: the
        // conversation rows and the unread counts must both be keyed by the
        // caller — there is no way to ask for someone else's inbox. (The
        // HTTP-level guarantee — a non-member's inbox omits the room — is
        // proven in MessageFlowIT/GroupFlowIT.)
        InboxConversationRow row = row(conversationId, null, false, null, false);
        when(memberRepository.findInboxConversations(userId, false)).thenReturn(List.of(row));
        when(memberRepository.findInboxMemberViews(List.of(conversationId)))
                .thenReturn(List.of());
        when(memberRepository.countUnreadByConversation(userId, List.of(conversationId)))
                .thenReturn(List.of());

        inboxService.listInbox(userId, null);

        verify(memberRepository).findInboxConversations(userId, false);
        verify(memberRepository).countUnreadByConversation(userId, List.of(conversationId));
    }
}
