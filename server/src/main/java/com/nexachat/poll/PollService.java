package com.nexachat.poll;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.poll.dto.CreatePollRequest;
import com.nexachat.poll.dto.PollChangeItem;
import com.nexachat.poll.dto.PollChangesResponse;
import com.nexachat.poll.dto.PollOptionResponse;
import com.nexachat.poll.dto.PollPageResponse;
import com.nexachat.poll.dto.PollResponse;
import com.nexachat.poll.dto.PollVoteRow;
import com.nexachat.poll.dto.VoteRequest;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Group-poll use cases.
 *
 * <p><b>Authorization:</b> every operation first resolves the conversation and
 * requires a membership row (403 {@code NOT_MEMBER}), then rejects DIRECT
 * conversations with 400 {@code NOT_A_GROUP}. The type check comes after
 * membership so a non-member never learns the conversation type. Closing and
 * deleting additionally require the poll creator or a group ADMIN
 * (403 {@code NOT_ADMIN}).
 *
 * <p><b>Concurrency:</b> every write locks the conversation row before taking
 * the poll row, so revisions are assigned in commit order for the per-group
 * change feed. Votes, retract, close and delete then lock the poll row with
 * {@code SELECT ... FOR UPDATE}: the single-choice replacement and the
 * multiple-choice set replacement are one serialized delete-plus-insert, and
 * the {@code (poll, option, user)} primary key is the second guard against a
 * double count. {@code ON CONFLICT DO NOTHING} keeps a replay harmless.
 *
 * <p><b>Results:</b> counts are derived from the votes table on every read.
 * Removed members' votes stay counted because their rows are not deleted when
 * the membership row disappears. An anonymous poll never serializes
 * {@code voterIds}, including for its creator.
 *
 * <p><b>Realtime:</b> {@code ws/WsEventType} has no poll event, so mutations do
 * not emit a STOMP frame. Every visible change consumes a
 * {@code poll_version_seq} value and is exposed through
 * {@link #changes} instead; clients poll it when they need a live board.
 */
@Service
public class PollService {

    private static final Logger log = LoggerFactory.getLogger(PollService.class);

    static final int MAX_QUESTION_LENGTH = 200;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 10;
    static final int MAX_OPTION_LENGTH = 100;
    static final int MAX_EXPIRY_DAYS = 30;
    static final int DEFAULT_LIST_LIMIT = 50;
    static final int MAX_LIST_LIMIT = 100;
    static final int DEFAULT_CHANGES_LIMIT = 100;
    static final int MAX_CHANGES_LIMIT = 200;

    private final PollRepository pollRepository;
    private final PollOptionRepository optionRepository;
    private final PollVoteRepository voteRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final PollRateLimiter rateLimiter;
    private final Clock clock;

    public PollService(PollRepository pollRepository,
                       PollOptionRepository optionRepository,
                       PollVoteRepository voteRepository,
                       ConversationRepository conversationRepository,
                       ConversationMemberRepository memberRepository,
                       UserRepository userRepository,
                       PollRateLimiter rateLimiter,
                       Clock clock) {
        this.pollRepository = pollRepository;
        this.optionRepository = optionRepository;
        this.voteRepository = voteRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * Creates a poll in a GROUP the caller belongs to.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER},
     *                      {@code NOT_A_GROUP}, {@code VALIDATION_FAILED},
     *                      {@code RATE_LIMITED}
     */
    @Transactional
    public PollResponse create(UUID userId, UUID conversationId, CreatePollRequest request) {
        rateLimiter.checkCreate(userId);
        Conversation conversation = lockGroupConversation(conversationId, userId);

        String question = normalizeQuestion(request == null ? null : request.question());
        List<String> labels = normalizeOptions(request == null ? null : request.options());
        Instant now = clock.instant();
        Instant closesAt = normalizeClosesAt(request == null ? null : request.closesAt(), now);
        boolean multipleChoice = request != null && Boolean.TRUE.equals(request.multipleChoice());
        boolean anonymous = request != null && Boolean.TRUE.equals(request.anonymous());

        User creator = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        Poll poll = Poll.create(conversation, creator, question, multipleChoice, anonymous,
                closesAt, nextVersion());
        pollRepository.save(poll);

        List<PollOption> options = new ArrayList<>(labels.size());
        for (int position = 0; position < labels.size(); position++) {
            options.add(PollOption.create(poll, position, labels.get(position)));
        }
        optionRepository.saveAll(options);

        log.info("Poll {} created in {} by {}", poll.getId(), conversationId, userId);
        return single(poll, userId, now);
    }

    /**
     * Newest-first page of visible polls. The cursor is the id of the last item
     * of the previous page; an id from another conversation is invalid.
     */
    @Transactional(readOnly = true)
    public PollPageResponse list(UUID userId, UUID conversationId, int limit, UUID before) {
        requireGroupConversation(conversationId, userId);
        requireLimit(limit, MAX_LIST_LIMIT);

        PageRequest pageRequest = PageRequest.of(0, limit + 1);
        List<Poll> rows;
        if (before == null) {
            rows = pollRepository.findVisibleFirstPage(conversationId, pageRequest);
        } else {
            Poll cursor = pollRepository.findByIdAndConversation_Id(before, conversationId)
                    .orElseThrow(() -> ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                            "before is not a poll of this conversation"));
            rows = pollRepository.findVisiblePageBefore(conversationId,
                    cursor.getCreatedAt(), cursor.getId(), pageRequest);
        }
        boolean hasMore = rows.size() > limit;
        List<Poll> page = hasMore ? rows.subList(0, limit) : rows;
        UUID nextBefore = hasMore ? page.get(page.size() - 1).getId() : null;
        return new PollPageResponse(toResponses(page, userId, clock.instant()), nextBefore);
    }

    /**
     * One visible poll with counts and the caller's selection.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code NOT_MEMBER},
     *                      {@code NOT_A_GROUP}, {@code POLL_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public PollResponse get(UUID userId, UUID conversationId, UUID pollId) {
        requireGroupConversation(conversationId, userId);
        Poll poll = visiblePoll(pollId, conversationId);
        return single(poll, userId, clock.instant());
    }

    /**
     * Replaces the caller's complete selection. The same selection is a no-op
     * that keeps the version, including on a closed poll (safe outbox replay);
     * a different selection or a retract on a closed/expired poll is
     * {@code POLL_CLOSED}.
     */
    @Transactional
    public PollResponse vote(UUID userId, UUID conversationId, UUID pollId, VoteRequest request) {
        rateLimiter.checkVote(userId);
        lockGroupConversation(conversationId, userId);
        Poll poll = lockVisiblePoll(pollId, conversationId);

        LinkedHashSet<UUID> requested = normalizeSelection(request);
        List<PollOption> options = optionRepository.findByPoll_IdOrderByPositionAsc(pollId);
        validateSelection(poll, requested, options);

        Set<UUID> current = new HashSet<>(
                voteRepository.findOptionIdsByPollAndUser(pollId, userId));
        if (current.equals(requested)) {
            return single(poll, userId, clock.instant());
        }
        Instant now = clock.instant();
        if (poll.isClosedAt(now)) {
            throw ApiException.of(ApiErrorCode.POLL_CLOSED, "Poll is closed");
        }

        voteRepository.deleteByPollAndUser(pollId, userId);
        for (UUID optionId : requested) {
            voteRepository.insertVote(pollId, optionId, userId);
        }
        poll.bumpVersion(nextVersion());
        log.info("Vote updated on poll {} by {}", pollId, userId);
        return single(poll, userId, now);
    }

    /** Retracts the caller's vote; a replay with no vote left is a version-preserving no-op. */
    @Transactional
    public PollResponse retractVote(UUID userId, UUID conversationId, UUID pollId) {
        rateLimiter.checkVote(userId);
        lockGroupConversation(conversationId, userId);
        Poll poll = lockVisiblePoll(pollId, conversationId);

        List<UUID> current = voteRepository.findOptionIdsByPollAndUser(pollId, userId);
        if (current.isEmpty()) {
            return single(poll, userId, clock.instant());
        }
        Instant now = clock.instant();
        if (poll.isClosedAt(now)) {
            throw ApiException.of(ApiErrorCode.POLL_CLOSED, "Poll is closed");
        }

        voteRepository.deleteByPollAndUser(pollId, userId);
        poll.bumpVersion(nextVersion());
        log.info("Vote retracted on poll {} by {}", pollId, userId);
        return single(poll, userId, now);
    }

    /**
     * Explicitly closes a poll. The creator and group admins may close; a
     * repeated close is a no-op that keeps the version.
     */
    @Transactional
    public PollResponse close(UUID userId, UUID conversationId, UUID pollId) {
        rateLimiter.checkManage(userId);
        lockGroupConversation(conversationId, userId);
        Poll poll = lockVisiblePoll(pollId, conversationId);
        ConversationMember member = requireMember(conversationId, userId);
        requireManageRole(poll, member, userId);

        if (poll.getClosedAt() != null) {
            return single(poll, userId, clock.instant());
        }
        Instant now = clock.instant();
        poll.close(member.getUser(), now);
        poll.bumpVersion(nextVersion());
        log.info("Poll {} closed by {}", pollId, userId);
        return single(poll, userId, now);
    }

    /**
     * Soft-deletes a poll. The creator and group admins may delete; deleting an
     * already-deleted poll is an idempotent 204 without a version bump. The row
     * stays to serve the {@code deleted:true} change-feed tombstone. The role
     * check runs before the idempotent return, so a member who could never
     * delete the poll does not receive a 204 that implies success.
     */
    @Transactional
    public void delete(UUID userId, UUID conversationId, UUID pollId) {
        rateLimiter.checkManage(userId);
        lockGroupConversation(conversationId, userId);
        Poll poll = lockPoll(pollId, conversationId);
        ConversationMember member = requireMember(conversationId, userId);
        requireManageRole(poll, member, userId);
        if (poll.getDeletedAt() != null) {
            return;
        }

        poll.markDeleted(clock.instant());
        poll.bumpVersion(nextVersion());
        log.info("Poll {} deleted by {}", pollId, userId);
    }

    /**
     * Revision feed for clients without a poll WebSocket event. Rows are
     * ordered by {@code version} ascending; deleted polls are tombstones with
     * no body. {@code since} is the last {@code version} the client applied.
     */
    @Transactional(readOnly = true)
    public PollChangesResponse changes(UUID userId, UUID conversationId, long since, int limit) {
        requireGroupConversation(conversationId, userId);
        if (since < 0) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "since must be zero or greater");
        }
        requireLimit(limit, MAX_CHANGES_LIMIT);

        List<Poll> rows = pollRepository.findChanges(conversationId, since,
                PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        List<Poll> page = hasMore ? rows.subList(0, limit) : rows;
        long nextSince = page.isEmpty() ? since : page.get(page.size() - 1).getVersion();

        Instant now = clock.instant();
        List<Poll> live = page.stream().filter(poll -> poll.getDeletedAt() == null).toList();
        Map<UUID, PollResponse> liveById = toResponses(live, userId, now).stream()
                .collect(Collectors.toMap(PollResponse::id, response -> response));
        List<PollChangeItem> items = page.stream()
                .map(poll -> new PollChangeItem(
                        poll.getId(), poll.getVersion(), poll.getDeletedAt() != null,
                        liveById.get(poll.getId())))
                .toList();
        return new PollChangesResponse(items, nextSince, hasMore);
    }

    // ------------------------------------------------------------- internals

    private Conversation requireGroupConversation(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        requirePollAccess(conversation, userId);
        return conversation;
    }

    /**
     * Serializes poll writes per conversation. The conversation row lock is
     * held until commit, so a sequence value assigned by a later writer is
     * also committed later. Without it, two concurrent writes could commit in
     * the opposite order and a change-feed reader could set {@code nextSince}
     * past a lower version that had not committed yet.
     */
    private Conversation lockGroupConversation(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        requirePollAccess(conversation, userId);
        return conversation;
    }

    private void requirePollAccess(Conversation conversation, UUID userId) {
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversation.getId(), userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw ApiException.of(ApiErrorCode.NOT_A_GROUP,
                    "Polls are only available in GROUP conversations");
        }
    }

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
    }

    private static void requireManageRole(Poll poll, ConversationMember member, UUID userId) {
        boolean creator = poll.getCreatedBy().getId().equals(userId);
        if (!creator && member.getRole() != ConversationMember.Role.ADMIN) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN,
                    "Poll creator or group admin role required");
        }
    }

    private Poll lockVisiblePoll(UUID pollId, UUID conversationId) {
        Poll poll = lockPoll(pollId, conversationId);
        if (poll.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.POLL_NOT_FOUND, "Poll not found");
        }
        return poll;
    }

    private Poll lockPoll(UUID pollId, UUID conversationId) {
        Poll poll = pollRepository.findByIdForUpdate(pollId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.POLL_NOT_FOUND, "Poll not found"));
        if (!poll.getConversation().getId().equals(conversationId)) {
            throw ApiException.of(ApiErrorCode.POLL_NOT_FOUND, "Poll not found");
        }
        return poll;
    }

    private Poll visiblePoll(UUID pollId, UUID conversationId) {
        Poll poll = pollRepository.findByIdAndConversation_Id(pollId, conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.POLL_NOT_FOUND, "Poll not found"));
        if (poll.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.POLL_NOT_FOUND, "Poll not found");
        }
        return poll;
    }

    private long nextVersion() {
        Long version = pollRepository.nextVersion();
        if (version == null) {
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "Poll version sequence unavailable");
        }
        return version;
    }

    private static String normalizeQuestion(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "question is required");
        }
        String question = raw.trim();
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "question must be at most " + MAX_QUESTION_LENGTH + " characters");
        }
        return question;
    }

    private static List<String> normalizeOptions(List<String> raw) {
        if (raw == null || raw.size() < MIN_OPTIONS || raw.size() > MAX_OPTIONS) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Provide between " + MIN_OPTIONS + " and " + MAX_OPTIONS + " options");
        }
        List<String> labels = new ArrayList<>(raw.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String value : raw) {
            String label = value == null ? "" : value.trim();
            if (label.isEmpty()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "option labels must not be blank");
            }
            if (label.length() > MAX_OPTION_LENGTH) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "option labels must be at most " + MAX_OPTION_LENGTH + " characters");
            }
            if (!seen.add(label)) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "option labels must be unique");
            }
            labels.add(label);
        }
        return labels;
    }

    private static Instant normalizeClosesAt(Instant closesAt, Instant now) {
        if (closesAt == null) {
            return null;
        }
        if (!closesAt.isAfter(now)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "closesAt must be in the future");
        }
        if (closesAt.isAfter(now.plus(Duration.ofDays(MAX_EXPIRY_DAYS)))) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "closesAt must be at most " + MAX_EXPIRY_DAYS + " days from now");
        }
        return closesAt;
    }

    private static LinkedHashSet<UUID> normalizeSelection(VoteRequest request) {
        if (request == null || request.optionIds() == null || request.optionIds().isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "optionIds must not be empty");
        }
        LinkedHashSet<UUID> selection = new LinkedHashSet<>();
        for (UUID optionId : request.optionIds()) {
            if (optionId == null) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "optionIds must not contain null");
            }
            selection.add(optionId);
        }
        if (selection.size() > MAX_OPTIONS) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "At most " + MAX_OPTIONS + " options may be selected");
        }
        return selection;
    }

    private static void validateSelection(Poll poll, Set<UUID> requested,
                                          List<PollOption> options) {
        if (!poll.isMultipleChoice() && requested.size() != 1) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "A single-choice poll takes exactly one option");
        }
        Set<UUID> known = options.stream().map(PollOption::getId).collect(Collectors.toSet());
        if (!known.containsAll(requested)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Every optionId must belong to this poll");
        }
    }

    private static void requireLimit(int limit, int max) {
        // The controller applies the default; a directly-called service still
        // rejects an out-of-range value instead of silently clamping.
        if (limit < 1 || limit > max) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + max);
        }
    }

    private PollResponse single(Poll poll, UUID viewerId, Instant now) {
        return toResponses(List.of(poll), viewerId, now).get(0);
    }

    private List<PollResponse> toResponses(List<Poll> polls, UUID viewerId, Instant now) {
        if (polls.isEmpty()) {
            return List.of();
        }
        List<UUID> pollIds = polls.stream().map(Poll::getId).toList();
        Map<UUID, List<PollOption>> optionsByPoll =
                optionRepository.findByPoll_IdInOrderByPoll_IdAscPositionAsc(pollIds).stream()
                        .collect(Collectors.groupingBy(
                                option -> option.getPoll().getId(), Collectors.toList()));
        Map<UUID, List<PollVoteRow>> votesByPoll =
                voteRepository.findRowsByPollIds(pollIds).stream()
                        .collect(Collectors.groupingBy(PollVoteRow::pollId));

        List<PollResponse> responses = new ArrayList<>(polls.size());
        for (Poll poll : polls) {
            List<PollOption> options = optionsByPoll.getOrDefault(poll.getId(), List.of());
            List<PollVoteRow> votes = votesByPoll.getOrDefault(poll.getId(), List.of());

            Map<UUID, List<UUID>> votersByOption = new HashMap<>();
            Set<UUID> distinctVoters = new HashSet<>();
            Set<UUID> mine = new HashSet<>();
            for (PollVoteRow vote : votes) {
                votersByOption.computeIfAbsent(vote.optionId(), key -> new ArrayList<>())
                        .add(vote.userId());
                distinctVoters.add(vote.userId());
                if (vote.userId().equals(viewerId)) {
                    mine.add(vote.optionId());
                }
            }

            List<PollOptionResponse> optionResponses = new ArrayList<>(options.size());
            List<UUID> myOptionIds = new ArrayList<>();
            for (PollOption option : options) {
                List<UUID> voters = new ArrayList<>(
                        votersByOption.getOrDefault(option.getId(), List.of()));
                voters.sort(Comparator.comparing(UUID::toString));
                if (mine.contains(option.getId())) {
                    myOptionIds.add(option.getId());
                }
                optionResponses.add(new PollOptionResponse(
                        option.getId(), option.getPosition(), option.getLabel(), voters.size(),
                        poll.isAnonymous() ? null : List.copyOf(voters)));
            }

            responses.add(new PollResponse(
                    poll.getId(),
                    poll.getConversation().getId(),
                    poll.getCreatedBy().getId(),
                    poll.getQuestion(),
                    poll.isMultipleChoice(),
                    poll.isAnonymous(),
                    poll.isClosedAt(now) ? "CLOSED" : "OPEN",
                    poll.getClosesAt(),
                    poll.getClosedAt(),
                    poll.getClosedBy() == null ? null : poll.getClosedBy().getId(),
                    poll.getCreatedAt(),
                    poll.getVersion(),
                    distinctVoters.size(),
                    optionResponses,
                    myOptionIds));
        }
        return responses;
    }
}
