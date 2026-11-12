package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.common.error.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterization matrix for the conversation membership guard.
 *
 * <p>Every public conversation-scoped REST operation is probed with the six
 * caller situations the refactor track must preserve: unknown conversation,
 * outsider, removed member, direct versus group conversation, non-admin
 * member, and an unauthenticated caller. Each row freezes the current HTTP
 * status, the public error code, the error envelope, and (through the probes
 * that distinguish them) the order of the existence, membership, type and
 * admin checks.</p>
 *
 * <p>The tests must pass on the current code. They are a baseline for later
 * refactors, not a specification of what the behavior ought to be. Rows whose
 * current behavior looks surprising carry a comment naming the behavior; the
 * finding is recorded in the audit report instead of being fixed here.</p>
 *
 * <p>Admission is already frozen per route by
 * {@link AuthorizationRouteMatrixIT#everyScopedRouteRunsItsPolicyActorProbes};
 * this class freezes the denial side and adds a direct-conversation
 * admission control so a blanket denial cannot masquerade as a passing
 * matrix.</p>
 */
class MembershipGuardMatrixIT extends SecurityITBase {

    private static final String MATRIX = "/authz-route-matrix.tsv";
    private static final String PROBE_EMOJI = "👍";
    private static final byte[] PROBE_PNG = HexFormat.of().parseHex(
            "89504E470D0A1A0A0000000D4948445200000010000000100806000000");

    private static final Expectation CONVERSATION_NOT_FOUND =
            new Expectation(404, ApiErrorCode.CONVERSATION_NOT_FOUND);
    private static final Expectation NOT_MEMBER = new Expectation(403, ApiErrorCode.NOT_MEMBER);
    private static final Expectation NOT_ADMIN = new Expectation(403, ApiErrorCode.NOT_ADMIN);
    private static final Expectation NOT_MESSAGE_AUTHOR =
            new Expectation(403, ApiErrorCode.NOT_MESSAGE_AUTHOR);
    private static final Expectation NOT_FOUND = new Expectation(404, ApiErrorCode.NOT_FOUND);
    private static final Expectation FORBIDDEN = new Expectation(403, ApiErrorCode.FORBIDDEN);
    private static final Expectation MESSAGE_NOT_FOUND =
            new Expectation(404, ApiErrorCode.MESSAGE_NOT_FOUND);
    private static final Expectation ATTACHMENT_NOT_FOUND =
            new Expectation(404, ApiErrorCode.ATTACHMENT_NOT_FOUND);
    private static final Expectation FILE_NOT_FOUND = new Expectation(404, ApiErrorCode.FILE_NOT_FOUND);
    private static final Expectation VALIDATION_FAILED =
            new Expectation(400, ApiErrorCode.VALIDATION_FAILED);
    private static final Expectation NOT_A_GROUP = new Expectation(400, ApiErrorCode.NOT_A_GROUP);
    private static final Expectation UNAUTHORIZED = new Expectation(401, ApiErrorCode.UNAUTHORIZED);

    private static final Map<String, Op> OPERATIONS = buildOperations();

    @Test
    void everyConversationScopedRouteInTheAuthorizationMatrixHasOneCatalogRow() throws Exception {
        List<String> conversationScopedProbeIds = new ArrayList<>();
        for (String line : readMatrixLines()) {
            String[] columns = line.split("\t", -1);
            String path = columns[1];
            if (isConversationScoped(path)) {
                conversationScopedProbeIds.add(columns[4]);
            }
        }

        assertThat(conversationScopedProbeIds).as("conversation-scoped routes in the TSV")
                .isNotEmpty();
        assertThat(OPERATIONS.keySet())
                .as("membership catalog covers every conversation-scoped route exactly once")
                .containsExactlyInAnyOrderElementsOf(conversationScopedProbeIds);
    }

    @Test
    void membershipGuardMatrixMatchesCurrentBehavior() throws Exception {
        Fixtures f = fixtures();
        Probe group = new Probe(f.groupId(), f.groupMessageId(), f.otherMember().userId(),
                f.labelId(), f.pollId(), f.optionId(), f.reminderId(), f.requestId(),
                f.attachmentId(), f.attachmentUrl());
        Probe groupOtherAuthor = group.withMessageId(f.otherMemberMessageId());
        Probe direct = new Probe(f.directId(), f.directMessageId(), f.directPeer().userId(),
                f.labelId(), f.pollId(), f.optionId(), f.directReminderId(), f.requestId(),
                f.directAttachmentId(), f.directAttachmentUrl());
        Probe unknownConversation = group.withConversationId(UUID.randomUUID());
        Probe unknownTarget = new Probe(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "/api/v1/files/unknown-probe-key.bin");

        for (Op op : OPERATIONS.values()) {
            assertCase(op, "unauthenticated", null, group, UNAUTHORIZED);

            Probe unknownProbe = switch (op.probeId()) {
                // These routes resolve the target resource before any conversation
                // id exists, so their "unknown target" case is an unknown message or
                // attachment rather than an unknown conversation.
                case "rest-017", "rest-081", "rest-091", "rest-019", "rest-037" -> unknownTarget;
                default -> unknownConversation;
            };
            assertCase(op, "unknown", f.outsider(), unknownProbe, op.unknown());

            assertCase(op, "outsider", f.outsider(), group, op.outsider());
            assertCase(op, "removed member", f.removed(), group, op.removed());

            if (op.crossType() != null) {
                if (op.probeId().equals("rest-036")) {
                    // Direct-only route probed on a GROUP: membership is checked
                    // before the conversation type.
                    assertCase(op, "group on direct-only route", f.owner(), group, op.crossType());
                } else {
                    assertCase(op, "direct on group-only route", f.directPeer(), direct,
                            op.crossType());
                }
            }

            if (op.nonAdmin() != null) {
                Registered actor = switch (op.probeId()) {
                    case "rest-012", "rest-014", "rest-049", "rest-074" -> f.otherMember();
                    default -> f.member();
                };
                Probe probe = switch (op.probeId()) {
                    case "rest-008", "rest-048" -> groupOtherAuthor;
                    default -> group;
                };
                assertCase(op, "non-admin member", actor, probe, op.nonAdmin());
            }
        }
    }

    @Test
    void directConversationMembersKeepAccessToEveryDirectCapableOperation() throws Exception {
        Registered owner = register("+84905602101");
        Registered peer = register("+84905602102");
        Registered outsider = register("+84905602103");
        Registered groupOwner = register("+84905602104");
        UUID directId = openDirect(owner, peer.userId());
        UUID groupId = createGroup(groupOwner, List.of(outsider.userId()));
        UUID directMessageId = sendText(directId, owner, "direct admission control");
        UUID attachmentId;
        String attachmentUrl;
        var upload = upload(owner);
        attachmentId = upload.id();
        attachmentUrl = upload.url();
        sendMessage(directId, owner, "IMAGE", null, List.of(attachmentId));

        get("/api/v1/conversations/" + directId + "/messages?limit=10", peer)
                .andExpect(status().is(200));
        postJson("/api/v1/conversations/" + directId + "/messages",
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "direct control"), peer.accessToken())
                .andExpect(status().is(201));
        postJson("/api/v1/conversations/" + directId + "/read",
                Map.of("upToSeq", 0), peer.accessToken()).andExpect(status().is(204));
        postJson("/api/v1/conversations/" + directId + "/delivered",
                Map.of("upToSeq", 0), peer.accessToken()).andExpect(status().is(204));

        request(HttpMethod.PUT, "/api/v1/conversations/" + directId + "/mute", peer,
                Map.of("forever", true)).andExpect(status().is(204));
        request(HttpMethod.DELETE, "/api/v1/conversations/" + directId + "/mute", peer, null)
                .andExpect(status().is(204));
        request(HttpMethod.PUT, "/api/v1/conversations/" + directId + "/archive", peer, null)
                .andExpect(status().is(204));
        request(HttpMethod.DELETE, "/api/v1/conversations/" + directId + "/archive", peer, null)
                .andExpect(status().is(204));
        request(HttpMethod.PUT, "/api/v1/conversations/" + directId + "/pin", peer, null)
                .andExpect(status().is(204));
        request(HttpMethod.DELETE, "/api/v1/conversations/" + directId + "/pin", peer, null)
                .andExpect(status().is(204));

        get("/api/v1/conversations/" + directId + "/pins", peer).andExpect(status().is(200));
        postJson("/api/v1/conversations/" + directId + "/pins",
                Map.of("messageId", directMessageId.toString()), peer.accessToken())
                .andExpect(status().is(204));
        request(HttpMethod.DELETE,
                "/api/v1/conversations/" + directId + "/pins/" + directMessageId, peer, null)
                .andExpect(status().is(204));

        get("/api/v1/conversations/" + directId + "/reminders", peer).andExpect(status().is(200));
        postJson("/api/v1/conversations/" + directId + "/reminders",
                Map.of("title", "direct control",
                        "remindAt", Instant.now().plus(2, ChronoUnit.DAYS).toString()),
                peer.accessToken())
                .andExpect(status().is(201));

        get("/api/v1/conversations/" + directId + "/ai-opt-out", peer).andExpect(status().is(200));
        request(HttpMethod.PUT, "/api/v1/conversations/" + directId + "/ai-opt-out", peer, null)
                .andExpect(status().is(200));
        request(HttpMethod.DELETE, "/api/v1/conversations/" + directId + "/ai-opt-out", peer, null)
                .andExpect(status().is(200));

        get("/api/v1/conversations/" + directId + "/archive?type=MEDIA", peer).andExpect(status().is(200));
        get("/api/v1/conversations/" + directId + "/calls", peer).andExpect(status().is(200));
        get("/api/v1/attachments/" + attachmentId + "/content", peer).andExpect(status().is(200));
        get(attachmentUrl, peer).andExpect(status().is(200));

        // Polls are the one GROUP-only surface on this list: the member is
        // admitted first, then the type check rejects the DIRECT conversation.
        get("/api/v1/conversations/" + directId + "/polls", peer)
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));

        // A caller outside the DIRECT pair is denied on the same path.
        get("/api/v1/conversations/" + directId + "/messages", outsider)
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        get("/api/v1/conversations/" + groupId + "/messages", outsider).andExpect(status().is(200));
    }

    @Test
    void archiveBrowserValidatesItsQueryBeforeResolvingTheConversation() throws Exception {
        Registered caller = register("+84905602201");
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/conversations/" + unknown + "/archive?type=BOGUS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken()))
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/conversations/" + unknown + "/archive?type=MEDIA")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken()))
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void beanValidationPrecedesTheServiceMembershipPrecheck() throws Exception {
        Fixtures f = fixtures();

        // Spring validates @Valid @RequestBody before the controller invokes
        // the service, so a structurally invalid body is 400 even for an
        // outsider. The membership-first order above starts only after bean
        // validation passes.
        postJson("/api/v1/conversations/" + f.groupId() + "/messages", Map.of(),
                f.outsider().accessToken())
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postJson("/api/v1/conversations/" + f.groupId() + "/pins", Map.of(),
                f.outsider().accessToken())
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void messageSendChecksMembershipBeforeServicePayloadValidation() throws Exception {
        Fixtures f = fixtures();
        Map<String, Object> unknownTypePayload = Map.of(
                "clientMessageId", UUID.randomUUID().toString(),
                "type", "BOGUS", "content", "membership probe");

        // The body passes bean validation; the service rejects the type. An
        // outsider is stopped by the membership precheck before that parsing.
        postJson("/api/v1/conversations/" + f.groupId() + "/messages", unknownTypePayload,
                f.outsider().accessToken())
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        postJson("/api/v1/conversations/" + f.groupId() + "/messages", unknownTypePayload,
                f.member().accessToken())
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // Pins resolve the membership row and the pin role before the target
        // message; a random message id is MESSAGE_NOT_FOUND only for an admin.
        UUID unknownMessage = UUID.randomUUID();
        postJson("/api/v1/conversations/" + f.groupId() + "/pins",
                Map.of("messageId", unknownMessage.toString()), f.outsider().accessToken())
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        postJson("/api/v1/conversations/" + f.groupId() + "/pins",
                Map.of("messageId", unknownMessage.toString()), f.owner().accessToken())
                .andExpect(status().is(404))
                .andExpect(jsonPath("$.error.code").value("MESSAGE_NOT_FOUND"));
    }

    @Test
    void groupOnlyRoutesDisagreeAboutTypeVersusMembershipOrder() throws Exception {
        Registered owner = register("+84905602301");
        Registered peer = register("+84905602302");
        Registered outsider = register("+84905602303");
        UUID directId = openDirect(owner, peer.userId());

        // GroupService: existence, then type, then membership.
        patchWithAuth("/api/v1/conversations/" + directId, Map.of("title", "probe"),
                outsider.accessToken())
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // InviteService: existence, then type, then membership (NOT_A_GROUP).
        postJson("/api/v1/conversations/" + directId + "/invite-link",
                Map.of("requiresApproval", true), outsider.accessToken())
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));

        // PollService: existence, then membership, then type.
        postJson("/api/v1/conversations/" + directId + "/polls",
                Map.of("question", "probe", "options", List.of("One", "Two")),
                outsider.accessToken())
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    @Test
    void memberRemovalChecksAdminBeforeResolvingTheTarget() throws Exception {
        Fixtures f = fixtures();
        UUID unknownUser = UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/conversations/{conversationId}/members/{userId}",
                                f.groupId(), unknownUser)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.member().accessToken()))
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/conversations/{conversationId}/members/{userId}",
                                f.groupId(), unknownUser)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.owner().accessToken()))
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    // --------------------------------------------------------------- fixtures

    private Fixtures fixtures() throws Exception {
        Registered owner = register("+84905602001");
        Registered member = register("+84905602002");
        Registered otherMember = register("+84905602003");
        Registered outsider = register("+84905602004");
        Registered removed = register("+84905602005");
        Registered directPeer = register("+84905602006");
        Registered invitee = register("+84905602007");

        UUID groupId = createGroup(owner,
                List.of(member.userId(), otherMember.userId(), removed.userId()));
        removeMember(owner, groupId, removed.userId());

        UUID directId = openDirect(owner, directPeer.userId());
        UUID groupMessageId = sendText(groupId, member, "membership matrix message");
        UUID otherMemberMessageId = sendText(groupId, otherMember, "other author message");
        UUID directMessageId = sendText(directId, owner, "direct membership matrix");

        PollFixture poll = createPoll(groupId, member);
        UUID reminderId = createReminder(groupId, member);
        UUID directReminderId = createReminder(directId, owner);
        UUID labelId = createLabel(member);

        Uploaded groupAttachment = upload(member);
        sendMessage(groupId, member, "IMAGE", null, List.of(groupAttachment.id()));
        Uploaded directAttachment = upload(owner);
        sendMessage(directId, owner, "IMAGE", null, List.of(directAttachment.id()));

        String inviteToken = createInviteLink(owner, groupId);
        UUID requestId = pendingRequest(invitee, inviteToken);

        return new Fixtures(owner, member, otherMember, outsider, removed, directPeer, invitee,
                groupId, directId, groupMessageId, otherMemberMessageId, directMessageId,
                poll.id(), poll.optionId(), reminderId, directReminderId, labelId,
                groupAttachment.id(), groupAttachment.url(),
                directAttachment.id(), directAttachment.url(), requestId);
    }

    private UUID createGroup(Registered owner, List<UUID> memberIds) throws Exception {
        var result = postJson("/api/v1/conversations/group",
                Map.of("title", "Membership guard group",
                        "memberIds", memberIds.stream().map(UUID::toString).toList()),
                owner.accessToken())
                .andExpect(status().is(201))
                .andReturn();
        return UUID.fromString(body(result).path("id").asText());
    }

    private void removeMember(Registered owner, UUID conversationId, UUID userId) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.delete(
                        "/api/v1/conversations/{conversationId}/members/{userId}",
                        conversationId, userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().is(204));
    }

    private UUID openDirect(Registered owner, UUID peerId) throws Exception {
        var result = postJson("/api/v1/conversations/direct",
                Map.of("userId", peerId.toString()), owner.accessToken())
                .andExpect(status().is(201))
                .andReturn();
        return UUID.fromString(body(result).path("id").asText());
    }

    private UUID sendText(UUID conversationId, Registered actor, String content) throws Exception {
        return sendMessage(conversationId, actor, "TEXT", content, List.of());
    }

    private UUID sendMessage(UUID conversationId, Registered actor, String type, String content,
                             List<UUID> attachmentIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientMessageId", UUID.randomUUID().toString());
        payload.put("type", type);
        if (content != null) {
            payload.put("content", content);
        }
        if (!attachmentIds.isEmpty()) {
            payload.put("attachmentIds", attachmentIds.stream().map(UUID::toString).toList());
        }
        var result = postJson("/api/v1/conversations/" + conversationId + "/messages", payload,
                actor.accessToken())
                .andExpect(status().is(201))
                .andReturn();
        return UUID.fromString(body(result).path("id").asText());
    }

    private Uploaded upload(Registered actor) throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "membership-probe.png", "image/png",
                                PROBE_PNG))
                        .param("width", "16")
                        .param("height", "16")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken()))
                .andExpect(status().is(201))
                .andReturn();
        JsonNode uploaded = body(result);
        return new Uploaded(UUID.fromString(uploaded.path("id").asText()),
                uploaded.path("url").asText());
    }

    private PollFixture createPoll(UUID conversationId, Registered creator) throws Exception {
        var result = postJson("/api/v1/conversations/" + conversationId + "/polls",
                Map.of("question", "Membership guard poll", "options", List.of("One", "Two")),
                creator.accessToken())
                .andExpect(status().is(201))
                .andReturn();
        JsonNode poll = body(result);
        return new PollFixture(UUID.fromString(poll.path("id").asText()),
                UUID.fromString(poll.path("options").get(0).path("id").asText()));
    }

    private UUID createReminder(UUID conversationId, Registered creator) throws Exception {
        var result = postJson("/api/v1/conversations/" + conversationId + "/reminders",
                Map.of("title", "Membership guard reminder",
                        "remindAt", Instant.now().plus(2, ChronoUnit.DAYS).toString()),
                creator.accessToken())
                .andExpect(status().is(201))
                .andReturn();
        return UUID.fromString(body(result).path("id").asText());
    }

    private UUID createLabel(Registered owner) throws Exception {
        var result = postJson("/api/v1/labels",
                Map.of("name", "Membership guard", "colorId", "INDIGO"), owner.accessToken())
                .andExpect(status().is(201))
                .andReturn();
        return UUID.fromString(body(result).path("id").asText());
    }

    private String createInviteLink(Registered owner, UUID conversationId) throws Exception {
        var result = postJson("/api/v1/conversations/" + conversationId + "/invite-link",
                Map.of("requiresApproval", true), owner.accessToken())
                .andExpect(status().is(201))
                .andReturn();
        return body(result).path("token").asText();
    }

    private UUID pendingRequest(Registered invitee, String token) throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/invite-links/{token}/join", token)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + invitee.accessToken()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        return UUID.fromString(body(result).path("requestId").asText());
    }

    // --------------------------------------------------------------- requests

    private void assertCase(Op op, String caseName, Registered actor, Probe probe,
                            Expectation expected) throws Exception {
        var exchange = perform(op, probe, actor);
        var response = exchange.andReturn().getResponse();
        assertThat(response.getStatus())
                .as("%s %s %s: %s caller", op.probeId(), op.method(), op.path(), caseName)
                .isEqualTo(expected.status());
        JsonNode envelope = body(exchange.andReturn());
        assertThat(envelope.size())
                .as("error envelope has one root field: %s %s", op.probeId(), caseName)
                .isEqualTo(1);
        JsonNode error = envelope.path("error");
        assertThat(error.path("code").asText())
                .as("error code for %s %s %s: %s", op.probeId(), op.method(), op.path(), caseName)
                .isEqualTo(expected.code().getCode());
        assertThat(error.path("message").asText())
                .as("error message for %s %s", op.probeId(), caseName)
                .isNotBlank();
        assertThat(error.has("stackTrace")).isFalse();
        assertThat(error.has("trace")).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions perform(Op op, Probe probe,
                                                                       Registered actor)
            throws Exception {
        String path = path(op, probe);
        MockHttpServletRequestBuilder request;
        if (op.probeId().equals("rest-064")) {
            request = MockMvcRequestBuilders.multipart(path)
                    .file(new MockMultipartFile("file", "membership-probe.png", "image/png",
                            PROBE_PNG));
        } else {
            request = MockMvcRequestBuilders.request(HttpMethod.valueOf(op.method()), path);
            if (op.queryName() != null) {
                request.param(op.queryName(), op.queryValue());
            }
            Object payload = body(op, probe);
            if (payload != null) {
                request.contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload));
            }
        }
        if (actor != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken());
        }
        return mockMvc.perform(request);
    }

    private Object body(Op op, Probe probe) {
        return switch (op.probeId()) {
            case "rest-072" -> Map.of("messageId", probe.messageId().toString());
            case "rest-090" -> Map.of("optionIds", List.of(probe.optionId().toString()));
            default -> op.body();
        };
    }

    private String path(Op op, Probe probe) {
        if (op.path().contains("/files/**")) {
            return probe.filePath();
        }
        String path = op.path()
                .replace("{conversationId}", probe.conversationId().toString())
                .replace("{messageId}", probe.messageId().toString())
                .replace("{userId}", probe.targetUserId().toString())
                .replace("{labelId}", probe.labelId().toString())
                .replace("{pollId}", probe.pollId().toString())
                .replace("{reminderId}", probe.reminderId().toString())
                .replace("{requestId}", probe.requestId().toString())
                .replace("{emoji}", PROBE_EMOJI);
        if (path.contains("{id}")) {
            String id = op.probeId().equals("rest-019")
                    ? probe.attachmentId().toString()
                    : probe.conversationId().toString();
            path = path.replace("{id}", id);
        }
        return path;
    }

    private org.springframework.test.web.servlet.ResultActions get(String path, Registered actor)
            throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken()));
    }

    private org.springframework.test.web.servlet.ResultActions request(
            HttpMethod method, String path, Registered actor, Object payload) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken());
        if (payload != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload));
        }
        return mockMvc.perform(builder);
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("response body").isNotBlank();
        return objectMapper.readTree(content);
    }

    // --------------------------------------------------------------- catalog

    private static Map<String, Op> buildOperations() {
        Map<String, Op> ops = new LinkedHashMap<>();

        // AI opt-out, archive marker, mute, conversation pin and labels: the
        // conversation row is resolved before the membership row.
        ops.put("rest-003", member("rest-003", "DELETE",
                "/api/v1/conversations/{conversationId}/ai-opt-out"));
        ops.put("rest-004", member("rest-004", "DELETE",
                "/api/v1/conversations/{conversationId}/archive"));
        ops.put("rest-006", member("rest-006", "DELETE",
                "/api/v1/conversations/{conversationId}/labels/{labelId}"));
        ops.put("rest-009", member("rest-009", "DELETE",
                "/api/v1/conversations/{conversationId}/mute"));
        ops.put("rest-010", member("rest-010", "DELETE",
                "/api/v1/conversations/{conversationId}/pin"));
        ops.put("rest-024", member("rest-024", "GET",
                "/api/v1/conversations/{conversationId}/ai-opt-out"));
        ops.put("rest-025", member("rest-025", "GET",
                "/api/v1/conversations/{conversationId}/archive", null, "type", "MEDIA"));
        ops.put("rest-031", member("rest-031", "GET",
                "/api/v1/conversations/{conversationId}/pins"));
        ops.put("rest-035", member("rest-035", "GET",
                "/api/v1/conversations/{conversationId}/reminders"));
        ops.put("rest-063", member("rest-063", "POST",
                "/api/v1/conversations/{conversationId}/ask",
                Map.of("question", "membership probe")));
        ops.put("rest-065", member("rest-065", "POST",
                "/api/v1/conversations/{conversationId}/delivered",
                Map.of("upToSeq", 0)));
        ops.put("rest-075", member("rest-075", "POST",
                "/api/v1/conversations/{conversationId}/read",
                Map.of("upToSeq", 0)));
        ops.put("rest-076", member("rest-076", "POST",
                "/api/v1/conversations/{conversationId}/reminders",
                Map.of("title", "membership probe",
                        "remindAt", Instant.now().plus(2, ChronoUnit.DAYS).toString())));
        ops.put("rest-077", member("rest-077", "POST",
                "/api/v1/conversations/{conversationId}/summary",
                Map.of("scope", "LAST_N", "limit", 5)));
        ops.put("rest-085", member("rest-085", "PUT",
                "/api/v1/conversations/{conversationId}/ai-opt-out"));
        ops.put("rest-086", member("rest-086", "PUT",
                "/api/v1/conversations/{conversationId}/archive"));
        ops.put("rest-087", member("rest-087", "PUT",
                "/api/v1/conversations/{conversationId}/labels/{labelId}"));
        ops.put("rest-088", member("rest-088", "PUT",
                "/api/v1/conversations/{conversationId}/mute", Map.of("forever", true)));
        ops.put("rest-089", member("rest-089", "PUT",
                "/api/v1/conversations/{conversationId}/pin"));

        // Group administration: existence, then conversation type, then
        // membership, then the admin role.
        ops.put("rest-005", groupAdmin("rest-005", "DELETE",
                "/api/v1/conversations/{conversationId}/invite-link", null,
                ApiErrorCode.NOT_A_GROUP));
        ops.put("rest-007", groupAdmin("rest-007", "DELETE",
                "/api/v1/conversations/{conversationId}/members/{userId}", null,
                ApiErrorCode.VALIDATION_FAILED));
        ops.put("rest-026", groupAdmin("rest-026", "GET",
                "/api/v1/conversations/{conversationId}/invite-link", null,
                ApiErrorCode.NOT_A_GROUP));
        ops.put("rest-027", groupAdmin("rest-027", "GET",
                "/api/v1/conversations/{conversationId}/join-requests", null,
                ApiErrorCode.NOT_A_GROUP));
        ops.put("rest-046", groupAdmin("rest-046", "PATCH",
                "/api/v1/conversations/{conversationId}", Map.of("title", "updated probe"),
                ApiErrorCode.VALIDATION_FAILED));
        ops.put("rest-047", groupAdmin("rest-047", "PATCH",
                "/api/v1/conversations/{conversationId}/invite-link",
                Map.of("requiresApproval", true), ApiErrorCode.NOT_A_GROUP));
        ops.put("rest-064", groupAdmin("rest-064", "POST",
                "/api/v1/conversations/{conversationId}/avatar", null,
                ApiErrorCode.VALIDATION_FAILED));
        ops.put("rest-066", groupAdmin("rest-066", "POST",
                "/api/v1/conversations/{conversationId}/invite-link",
                Map.of("requiresApproval", true), ApiErrorCode.NOT_A_GROUP));
        ops.put("rest-067", groupAdmin("rest-067", "POST",
                "/api/v1/conversations/{conversationId}/join-requests/{requestId}/approve", null,
                ApiErrorCode.NOT_A_GROUP));
        ops.put("rest-068", groupAdmin("rest-068", "POST",
                "/api/v1/conversations/{conversationId}/join-requests/{requestId}/reject", null,
                ApiErrorCode.NOT_A_GROUP));
        ops.put("rest-069", groupAdmin("rest-069", "POST",
                "/api/v1/conversations/{conversationId}/leave", null,
                ApiErrorCode.VALIDATION_FAILED).withNonAdmin(null));
        ops.put("rest-070", groupAdmin("rest-070", "POST",
                "/api/v1/conversations/{conversationId}/members",
                Map.of("userIds", List.of(UUID.randomUUID().toString())),
                ApiErrorCode.VALIDATION_FAILED).withNonAdmin(NOT_ADMIN));

        // The "my join request" route deliberately has no membership check:
        // an outsider or removed member sees the group's existence/type but a
        // generic NOT_FOUND for their own request. Frozen as-is.
        ops.put("rest-028", new Op("rest-028", "GET",
                "/api/v1/conversations/{conversationId}/join-requests/me", null, null, null,
                CONVERSATION_NOT_FOUND, NOT_FOUND, NOT_FOUND, NOT_A_GROUP, NOT_FOUND));

        // Message reads resolve the conversation first; message sends and
        // mutations check the membership row first, so an unknown conversation
        // is indistinguishable from a foreign one on those routes.
        ops.put("rest-029", messageMember("rest-029", "GET",
                "/api/v1/conversations/{conversationId}/messages"));
        ops.put("rest-030", messageMember("rest-030", "GET",
                "/api/v1/conversations/{conversationId}/messages/search", null, "q", "role"));
        ops.put("rest-008", messageAuthor("rest-008", "DELETE",
                "/api/v1/conversations/{conversationId}/messages/{messageId}"));
        ops.put("rest-048", messageAuthor("rest-048", "PATCH",
                "/api/v1/conversations/{conversationId}/messages/{messageId}",
                Map.of("content", "updated probe")));
        ops.put("rest-071", messageSend("rest-071", "POST",
                "/api/v1/conversations/{conversationId}/messages"));

        // Pins: membership first, then the conversation row, then the pin role
        // (DIRECT any member, GROUP admin only).
        ops.put("rest-011", pinOp("rest-011", "DELETE",
                "/api/v1/conversations/{conversationId}/pins/{messageId}"));
        ops.put("rest-072", pinOp("rest-072", "POST",
                "/api/v1/conversations/{conversationId}/pins"));

        // Polls: membership before the GROUP-only type check.
        ops.put("rest-012", pollManage("rest-012", "DELETE",
                "/api/v1/conversations/{conversationId}/polls/{pollId}"));
        ops.put("rest-013", pollMember("rest-013", "DELETE",
                "/api/v1/conversations/{conversationId}/polls/{pollId}/vote"));
        ops.put("rest-032", pollMember("rest-032", "GET",
                "/api/v1/conversations/{conversationId}/polls"));
        ops.put("rest-033", pollMember("rest-033", "GET",
                "/api/v1/conversations/{conversationId}/polls/changes", null, "since", "0"));
        ops.put("rest-034", pollMember("rest-034", "GET",
                "/api/v1/conversations/{conversationId}/polls/{pollId}"));
        ops.put("rest-073", pollMember("rest-073", "POST",
                "/api/v1/conversations/{conversationId}/polls",
                Map.of("question", "membership probe", "options", List.of("One", "Two"))));
        ops.put("rest-074", pollManage("rest-074", "POST",
                "/api/v1/conversations/{conversationId}/polls/{pollId}/close"));
        ops.put("rest-090", pollMember("rest-090", "PUT",
                "/api/v1/conversations/{conversationId}/polls/{pollId}/vote"));

        // Reminders: creator or GROUP admin; a DIRECT non-creator gets
        // FORBIDDEN rather than NOT_ADMIN.
        ops.put("rest-014", reminderManage("rest-014", "DELETE",
                "/api/v1/conversations/{conversationId}/reminders/{reminderId}"));
        ops.put("rest-049", reminderManage("rest-049", "PATCH",
                "/api/v1/conversations/{conversationId}/reminders/{reminderId}",
                Map.of("title", "updated probe")));

        // Message-scoped reactions resolve the message first, then membership.
        ops.put("rest-017", messageReaction("rest-017", "DELETE",
                "/api/v1/messages/{messageId}/reactions/{emoji}"));
        ops.put("rest-081", messageReaction("rest-081", "POST",
                "/api/v1/messages/{messageId}/reactions", Map.of("emoji", PROBE_EMOJI)));
        ops.put("rest-091", messageReaction("rest-091", "PUT",
                "/api/v1/messages/{messageId}/reactions/{emoji}"));

        // Attachment and file reads resolve the stored object first, then the
        // owner-or-member rule of the bound message.
        ops.put("rest-019", attachmentContent("rest-019"));
        ops.put("rest-037", fileContent("rest-037"));

        // Call history is DIRECT-only: membership before the type check.
        ops.put("rest-036", new Op("rest-036", "GET",
                "/api/v1/conversations/{id}/calls", null, null, null,
                CONVERSATION_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, VALIDATION_FAILED, null));

        return Map.copyOf(ops);
    }

    private static Op member(String probeId, String method, String path) {
        return member(probeId, method, path, null, null, null);
    }

    private static Op member(String probeId, String method, String path, Object body) {
        return member(probeId, method, path, body, null, null);
    }

    private static Op member(String probeId, String method, String path, Object body,
                             String queryName, String queryValue) {
        return new Op(probeId, method, path, body, queryName, queryValue,
                CONVERSATION_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, null, null);
    }

    private static Op messageMember(String probeId, String method, String path) {
        return messageMember(probeId, method, path, null, null, null);
    }

    private static Op messageMember(String probeId, String method, String path, Object body,
                                    String queryName, String queryValue) {
        return new Op(probeId, method, path, body, queryName, queryValue,
                CONVERSATION_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, null, null);
    }

    private static Op messageSend(String probeId, String method, String path) {
        return new Op(probeId, method, path,
                Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT",
                        "content", "membership probe"),
                null, null, NOT_MEMBER, NOT_MEMBER, NOT_MEMBER, null, null);
    }

    private static Op messageAuthor(String probeId, String method, String path) {
        return messageAuthor(probeId, method, path, null);
    }

    private static Op messageAuthor(String probeId, String method, String path, Object body) {
        return new Op(probeId, method, path, body, null, null,
                NOT_MEMBER, NOT_MEMBER, NOT_MEMBER, null, NOT_MESSAGE_AUTHOR);
    }

    private static Op pinOp(String probeId, String method, String path) {
        return new Op(probeId, method, path, null, null, null,
                NOT_MEMBER, NOT_MEMBER, NOT_MEMBER, null, NOT_ADMIN);
    }

    private static Op groupAdmin(String probeId, String method, String path, Object body,
                                 ApiErrorCode typeError) {
        return new Op(probeId, method, path, body, null, null,
                CONVERSATION_NOT_FOUND, NOT_MEMBER, NOT_MEMBER,
                new Expectation(400, typeError), NOT_ADMIN);
    }

    private static Op pollMember(String probeId, String method, String path) {
        return pollMember(probeId, method, path, null, null, null);
    }

    private static Op pollMember(String probeId, String method, String path, Object body) {
        return pollMember(probeId, method, path, body, null, null);
    }

    private static Op pollMember(String probeId, String method, String path, Object body,
                                 String queryName, String queryValue) {
        return new Op(probeId, method, path, body, queryName, queryValue,
                CONVERSATION_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, NOT_A_GROUP, null);
    }

    private static Op pollManage(String probeId, String method, String path) {
        return new Op(probeId, method, path, null, null, null,
                CONVERSATION_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, NOT_A_GROUP, NOT_ADMIN);
    }

    private static Op reminderManage(String probeId, String method, String path) {
        return reminderManage(probeId, method, path, null);
    }

    private static Op reminderManage(String probeId, String method, String path, Object body) {
        return new Op(probeId, method, path, body, null, null,
                CONVERSATION_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, FORBIDDEN, NOT_ADMIN);
    }

    private static Op messageReaction(String probeId, String method, String path) {
        return messageReaction(probeId, method, path, null);
    }

    private static Op messageReaction(String probeId, String method, String path, Object body) {
        return new Op(probeId, method, path, body, null, null,
                MESSAGE_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, null, null);
    }

    private static Op attachmentContent(String probeId) {
        return new Op(probeId, "GET", "/api/v1/attachments/{id}/content", null, null, null,
                ATTACHMENT_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, null, null);
    }

    private static Op fileContent(String probeId) {
        return new Op(probeId, "GET", "/api/v1/files/**", null, null, null,
                FILE_NOT_FOUND, NOT_MEMBER, NOT_MEMBER, null, null);
    }

    private static boolean isConversationScoped(String path) {
        return path.startsWith("/api/v1/conversations/{")
                || path.startsWith("/api/v1/messages/")
                || path.equals("/api/v1/attachments/{id}/content")
                || path.equals("/api/v1/files/**");
    }

    private static List<String> readMatrixLines() throws Exception {
        try (var stream = MembershipGuardMatrixIT.class.getResourceAsStream(MATRIX)) {
            assertThat(stream).as("REST authorization matrix resource").isNotNull();
            try (var reader = new BufferedReader(new InputStreamReader(stream,
                    StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String header = reader.readLine();
                assertThat(header).isEqualTo("method\tpath\tpolicy\trole_scenario\tprobe_id");
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
                return lines;
            }
        }
    }

    // ---------------------------------------------------------------- records

    private record Expectation(int status, ApiErrorCode code) {
    }

    private record Op(String probeId, String method, String path, Object body,
                      String queryName, String queryValue,
                      Expectation unknown, Expectation outsider, Expectation removed,
                      Expectation crossType, Expectation nonAdmin) {
        private Op withNonAdmin(Expectation value) {
            return new Op(probeId, method, path, body, queryName, queryValue,
                    unknown, outsider, removed, crossType, value);
        }
    }

    private record Probe(UUID conversationId, UUID messageId, UUID targetUserId, UUID labelId,
                         UUID pollId, UUID optionId, UUID reminderId, UUID requestId,
                         UUID attachmentId, String filePath) {
        private Probe withConversationId(UUID value) {
            return new Probe(value, messageId, targetUserId, labelId, pollId, optionId,
                    reminderId, requestId, attachmentId, filePath);
        }

        private Probe withMessageId(UUID value) {
            return new Probe(conversationId, value, targetUserId, labelId, pollId, optionId,
                    reminderId, requestId, attachmentId, filePath);
        }
    }

    private record Uploaded(UUID id, String url) {
    }

    private record PollFixture(UUID id, UUID optionId) {
    }

    private record Fixtures(Registered owner, Registered member, Registered otherMember,
                            Registered outsider, Registered removed, Registered directPeer,
                            Registered invitee, UUID groupId, UUID directId,
                            UUID groupMessageId, UUID otherMemberMessageId, UUID directMessageId,
                            UUID pollId, UUID optionId, UUID reminderId, UUID directReminderId,
                            UUID labelId, UUID attachmentId, String attachmentUrl,
                            UUID directAttachmentId, String directAttachmentUrl, UUID requestId) {
    }
}
