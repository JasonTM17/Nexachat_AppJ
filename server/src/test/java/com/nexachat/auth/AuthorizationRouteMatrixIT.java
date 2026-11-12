package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallService;
import com.nexachat.call.CallSignal;
import com.nexachat.common.error.ApiErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.aop.support.AopUtils;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exact REST route inventory plus authentication-state and per-route actor
 * probes for the server's authorization matrix. Every catalog row is bound to
 * an exact request and expected outcome through its unique probe ID.
 */
class AuthorizationRouteMatrixIT extends SecurityITBase {

    private static final UUID PATH_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PROBE_REACTION_EMOJI = "👍";
    private static final byte[] PROBE_PNG = HexFormat.of().parseHex(
            "89504E470D0A1A0A0000000D4948445200000010000000100806000000");
    private static final String AUTHZ_MATRIX = "/authz-route-matrix.tsv";
    private static final String STOMP_MATRIX = "/stomp-authz-matrix.tsv";
    private static final Map<String, RoleScenario> REST_ROLE_SCENARIOS = Map.ofEntries(
            Map.entry("authenticated_self", roleScenario("AUTHENTICATED",
                    "anonymous=401; valid user reaches handler; self scope allowed; another user's scope denied",
                    "registered account and two contact owners",
                    test("com.nexachat.auth.AuthFlowIT", "protectedEndpointRequiresBearer"),
                    test("com.nexachat.contact.ContactConversationIT", "contactsLifecycle"),
                    test("com.nexachat.contact.ContactConversationIT", "nonOwnerCannotPatchAnotherUsersContactAlias"))),
            Map.entry("authenticated_invite", roleScenario("AUTHENTICATED_INVITE",
                    "anonymous=401; valid invitee accepted or pending; removed or previously rejected invitee denied",
                    "group owner, invitee, pending requester, and active invite link",
                    test("com.nexachat.invite.GroupInviteFlowIT", "endToEndInviteLinkAndApprovalFlow"),
                    test("com.nexachat.invite.InviteServiceTest", "join_removedByAdmin_forbiddenViaSameLink"),
                    test("com.nexachat.invite.InviteServiceTest", "join_previouslyRejected_failsForbidden"))),
            Map.entry("call_participant", roleScenario("CALL_PARTICIPANT",
                    "current conversation participant=allowed; unrelated user=403",
                    "two call participants and one unrelated user",
                    test("com.nexachat.call.CallFlowIT", "rejectsNonMembersGroupsAndOversizedSdp"))),
            Map.entry("conversation_member", roleScenario("CONVERSATION_MEMBER",
                    "owner and ordinary member=allowed; outsider and former member=403",
                    "group owner, member, outsider, and removed member",
                    test("com.nexachat.message.MessageFlowIT", "nonMemberCannotReadWriteOrWatermark"),
                    test("com.nexachat.conversation.GroupFlowIT", "groupLifecycleRestFlow"),
                    test("com.nexachat.conversation.GroupFlowIT", "adminRemovalStopsLaterMessagesOnExistingConversationSubscription"),
                    test("com.nexachat.poll.PollFlowTest", "removedMemberVotesStayCountedButTheyCannotVote"))),
            Map.entry("authenticated_direct_open", roleScenario("AUTHENTICATED",
                    "anonymous=401; any signed-in caller may open a direct conversation with another user; the two peers may read it; an unrelated third user=403",
                    "caller, target peer, unrelated member, and resulting direct conversation",
                    test("com.nexachat.auth.AuthorizationRouteMatrixIT", "roleMatrixSeparatesMembersAdminsFormerMembersBannedUsersAndDirectPeers"),
                    test("com.nexachat.contact.ContactConversationIT", "directConversationIsIdempotentBothDirections"))),
            Map.entry("file_owner_or_member", roleScenario("FILE_OWNER_OR_MEMBER",
                    "attachment owner or authorized conversation member=allowed; unrelated user=403",
                    "uploaded file, conversation owner, member, and outsider",
                    test("com.nexachat.attachment.AttachmentFlowIT", "uploadBindAndMemberCheckedDownload"))),
            Map.entry("group_admin", roleScenario("GROUP_ADMIN",
                    "owner or promoted admin=allowed; ordinary member, outsider, or former member=403",
                    "group owner, promoted admin, ordinary member, outsider, and removed member",
                    test("com.nexachat.invite.GroupInviteFlowIT", "endToEndInviteLinkAndApprovalFlow"),
                    test("com.nexachat.invite.GroupInviteFlowIT", "adminJoinRequestListHonorsLimitAndOrdering"),
                    test("com.nexachat.conversation.GroupFlowIT", "nonAdminMemberCannotUploadGroupAvatarOverHttp"))),
            Map.entry("group_admin_or_self", roleScenario("GROUP_ADMIN_OR_SELF",
                    "admin may remove another member; a user may remove self; another ordinary member=403",
                    "group with owner, target member, and unrelated member",
                    test("com.nexachat.conversation.GroupFlowIT", "groupLifecycleRestFlow"))),
            Map.entry("direct_member_or_group_admin", roleScenario("DIRECT_MEMBER_OR_GROUP_ADMIN",
                    "direct conversation member=allowed; group admin=allowed; ordinary group member=403",
                    "direct peers, group admin, ordinary group member, and pinned message",
                    test("com.nexachat.message.InteractionFlowIT", "nonAdminPinInGroupIsForbiddenButDirectMemberPins"),
                    test("com.nexachat.message.InteractionFlowIT", "fourthPinHitsPinLimitAndUnpinFreesTheSlot"),
                    test("com.nexachat.message.PinServiceTest", "pin_notMember_throwsNotMember"),
                    test("com.nexachat.message.PinServiceTest", "unpin_member_removesAndPublishesFalse"),
                    test("com.nexachat.message.PinServiceTest", "unpin_groupMember_throwsNotAdmin"))),
            Map.entry("message_author_or_admin", roleScenario("MESSAGE_AUTHOR_OR_ADMIN",
                    "author or group admin=allowed; different ordinary member=403",
                    "message author, group admin, and another ordinary member",
                    test("com.nexachat.message.MessageFeatureFlowIT", "editDeleteFlowEnforcesAuthorAndMemberAndPreservesTombstoneHistory"),
                    test("com.nexachat.message.MessageFeatureFlowIT", "groupAdminCanModerateOldMessagesAndRemovedMembersLoseMessageAccess"),
                    test("com.nexachat.message.MessageMutationServiceTest", "editByNonAuthorIsForbidden"),
                    test("com.nexachat.message.MessageMutationServiceTest", "deleteByNonAuthorIsForbidden"))),
            Map.entry("message_conversation_member", roleScenario("MESSAGE_CONVERSATION_MEMBER",
                    "current conversation member=allowed; outsider or former member=403",
                    "message, reaction, current member, outsider, and removed member",
                    test("com.nexachat.message.MessageFlowIT", "nonMemberCannotReadWriteOrWatermark"),
                    test("com.nexachat.message.ReactionServiceTest", "toggle_notMember_throwsNotMember"),
                    test("com.nexachat.message.ReactionDeltaIdempotencyIT", "nonMemberReadsNoReactionEvents"))),
            Map.entry("operator_admin", roleScenario("OPERATOR_ADMIN",
                    "configured operator=allowed; ordinary authenticated user=403",
                    "configured operator decision and ordinary user decision",
                    test("com.nexachat.config.ActuatorAuthorizationTest", "actuatorMetricsUsesTheAdminPhoneAuthorizationManager"),
                    test("com.nexachat.config.ActuatorAuthorizationTest", "actuatorMetricsAllowsTheConfiguredAdminDecision"),
                    test("com.nexachat.auth.AuthorizationRouteMatrixIT",
                            "configuredOperatorAndOrdinaryUserHaveDifferentMetricsOutcomes"),
                    test("com.nexachat.auth.RagStatsAuthorizationIT", "ragStatsIsForbiddenForANonAdminUser"),
                    test("com.nexachat.auth.RagStatsAuthorizationIT", "ragStatsIsAllowedForTheConfiguredAdmin"))),
            Map.entry("poll_creator_or_admin", roleScenario("POLL_CREATOR_OR_ADMIN",
                    "poll creator or group admin=allowed; another ordinary member=403",
                    "poll creator, promoted admin, and ordinary member",
                    test("com.nexachat.poll.PollFlowTest", "closeRequiresCreatorOrAdminAndBlocksVoting"),
                    test("com.nexachat.poll.PollFlowTest", "closeAndDeleteByNonCreatorAdminWorkAfterPromotion"))),
            Map.entry("public_auth", roleScenario("PUBLIC_AUTH",
                    "anonymous request reaches public auth or recovery handling; bearer-required error is not returned",
                    "anonymous registration, login, account deletion, and password reset requests",
                    test("com.nexachat.auth.AuthFlowIT", "wrongLoginIs401Not404"),
                    test("com.nexachat.auth.AuthFlowIT", "registerValidationErrors"),
                    test("com.nexachat.auth.AccountRecoveryFlowTest", "publicDeletionRequestAlwaysReturnsAnEmptyAcceptedResponse"),
                    test("com.nexachat.auth.AccountRecoveryFlowTest", "unknownDeletionConfirmationHasOneGenericCodeError"),
                    test("com.nexachat.auth.AccountRecoveryFlowTest", "publicPasswordResetRequestAlwaysReturnsAnEmptyAcceptedResponse"),
                    test("com.nexachat.auth.AccountRecoveryFlowTest", "unknownPasswordResetConfirmationHasOneGenericCodeError"))),
            Map.entry("reminder_creator_or_admin", roleScenario("REMINDER_CREATOR_OR_ADMIN",
                    "reminder creator or group admin=allowed; another member=403",
                    "reminder creator, promoted admin, and another member",
                    test("com.nexachat.reminder.ReminderFlowIT", "creatorOrGroupAdminCanUpdateAndDelete"),
                    test("com.nexachat.reminder.ReminderFlowIT", "directConversationOnlyTheCreatorCanEdit"))),
            Map.entry("self_group_request", roleScenario("SELF_GROUP_REQUEST",
                    "request owner sees their own request; another member cannot see it",
                    "pending group request and a different authenticated member",
                    test("com.nexachat.invite.GroupInviteFlowIT", "endToEndInviteLinkAndApprovalFlow"))));
    private static final Map<String, RoleScenario> STOMP_ROLE_SCENARIOS = Map.of(
            "stomp_conversation_member", roleScenario("CONVERSATION_MEMBER",
                    "current member=allowed; outsider, former member, expired or revoked session=denied",
                    "member, outsider, former member, and authenticated STOMP session",
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_conversationTopic_member_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_conversationTopic_nonMember_rejected"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_foreignTopic_rejected"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_conversationDestination_member_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_conversationDestination_nonMember_rejected"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_typingAndRead_member_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_conversationDelivered_member_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_conversationDelivered_nonMember_rejected"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_callSignal_member_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_callSignal_nonMember_rejected"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "expiredConnectedSession_cannotSubscribe"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "connectedSession_cannotSendAfterItsTokenIsRevoked"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_matrixDestinationsEnforceTheirDeclaredPolicy"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_matrixDestinationsEnforceTheirDeclaredPolicy")),
            "stomp_own_queue", roleScenario("OWN_QUEUE",
                    "authenticated principal may subscribe to own queues; another principal and unknown queue=denied",
                    "authenticated principal, own queue set, another user's queue, and unknown queue",
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_userQueue_authenticated_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_userErrors_authenticated_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_userPresence_authenticated_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_userCallQueue_authenticated_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_anotherUsersPresenceQueue_rejected"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_unknownUserQueue_rejected"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "subscribe_matrixDestinationsEnforceTheirDeclaredPolicy")),
            "stomp_payload_conversation_member", roleScenario("PAYLOAD_CONVERSATION_MEMBER",
                    "current member=allowed; nonmember=denied before dispatch; missing or malformed ID continues to payload conversion",
                    "ack payload with current conversation, nonmember, missing ID, or malformed ID",
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_chatDeliveryAck_member_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_chatReadAck_member_allowed"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_chatDeliveryAck_nonMember_rejectedBeforeHandlerDispatch"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_chatReadAck_nonMember_rejectedBeforeHandlerDispatch"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_chatAckWithoutConversationId_reachesHandlerValidation"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_chatAckMalformedConversationId_continuesToPayloadConversion"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_chatAckUsesAcksRateLimitForMembers"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_matrixDestinationsEnforceTheirDeclaredPolicy"),
                    test("com.nexachat.ws.StompAuthChannelInterceptorTest", "send_messagesRateLimit_isPerUserAndReportsCorrelatedErrorWithoutDisconnecting")));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private AuthService authService;
    @Autowired
    private CallService callService;
    @Autowired
    private CallRegistry callRegistry;

    @Test
    void everyRegisteredRestRouteHasAnExplicitPolicyEntry() throws Exception {
        List<Route> routes = readMatrix();
        Set<String> expected = routes.stream()
                .map(route -> route.method() + " " + route.path())
                .collect(Collectors.toSet());
        Set<String> actual = new HashSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Class<?> handlerType = entry.getValue().getBeanType();
            if (!handlerType.getPackageName().startsWith("com.nexachat")
                    || (!AnnotatedElementUtils.hasAnnotation(handlerType, RestController.class)
                    && !AnnotatedElementUtils.hasAnnotation(handlerType, Controller.class))) {
                continue;
            }
            Set<String> paths = entry.getKey().getPatternValues();
            Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                    entry.getKey().getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                paths.forEach(path -> actual.add("ANY " + path));
            } else {
                for (org.springframework.web.bind.annotation.RequestMethod method : methods) {
                    paths.forEach(path -> actual.add(method.name() + " " + path));
                }
            }
        }

        assertThat(actual).as("registered controller routes missing a policy entry")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).hasSize(routes.size());
        assertRoleScenarios(routes.stream().map(Route::roleScenario).collect(Collectors.toSet()),
                REST_ROLE_SCENARIOS, "REST");
    }

    @Test
    void everyRegisteredStompHandlerHasAnExplicitAuthorizationEntry() throws Exception {
        List<StompRoute> routes = readStompMatrix();
        Set<String> expected = routes.stream()
                .filter(route -> route.kind().equals("HANDLER"))
                .map(StompRoute::destination)
                .collect(Collectors.toSet());
        Set<String> actual = new HashSet<>();

        for (String beanName : applicationContext.getBeanNamesForAnnotation(Controller.class)) {
            Class<?> handlerType = AopUtils.getTargetClass(applicationContext.getBean(beanName));
            for (Method method : ReflectionUtils.getAllDeclaredMethods(handlerType)) {
                MessageMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, MessageMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (String destination : mapping.value()) {
                    String route = destination.startsWith("/") ? destination : "/" + destination;
                    actual.add(route.startsWith("/app/") ? route : "/app" + route);
                }
            }
        }

        assertThat(actual).as("registered STOMP handlers missing an authorization entry")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(routes.stream().filter(route -> route.kind().equals("SUBSCRIBE")))
                .as("STOMP subscription policy entries")
                .isNotEmpty();
        assertRoleScenarios(routes.stream().map(StompRoute::roleScenario).collect(Collectors.toSet()),
                STOMP_ROLE_SCENARIOS, "STOMP");
    }

    @Test
    void everyPrivateRouteRejectsAnonymousInvalidExpiredAndRevokedTokens() throws Exception {
        List<Route> routes = readMatrix();
        List<Route> protectedRoutes = routes.stream().filter(Route::requiresAuthentication).toList();

        for (Route route : routes) {
            var result = mockMvc.perform(requestFor(route));
            if (route.requiresAuthentication()) {
                assertResponse(result.andReturn(), route, 401, ApiErrorCode.UNAUTHORIZED.getCode());
            } else {
                String content = result.andReturn().getResponse().getContentAsString();
                JsonNode body = content.isBlank() ? null : objectMapper.readTree(content);
                String code = body == null ? "" : body.path("error").path("code").asText();
                assertThat(code)
                        .as("public route must not be rejected by the bearer-token entry point: " + route)
                        .isNotEqualTo(ApiErrorCode.UNAUTHORIZED.getCode());
            }
        }

        Registered registered = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551801"));
        String expiredToken = expiredAccessToken(registered.userId());
        for (Route route : protectedRoutes) {
            assertTokenRejected(route, "malformed.access.token", ApiErrorCode.TOKEN_INVALID.getCode());
            assertTokenRejected(route, expiredToken, ApiErrorCode.TOKEN_EXPIRED.getCode());
        }

        String issuedBeforeLogout = accessTokenIssuedBeforeRevocation(registered.userId());
        authService.logoutAll(registered.userId());
        for (Route route : protectedRoutes) {
            assertTokenRejected(route, issuedBeforeLogout, ApiErrorCode.TOKEN_INVALID.getCode());
        }
    }

    @Test
    void everyCatalogRouteIsDispatchedByItsExactProbeId() throws Exception {
        List<Route> routes = readMatrix();
        String userPhone = ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551821");
        Registered user = register(userPhone);
        Registered operator = register(ADMIN_PHONE);
        Registered peer = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551823"));
        var directResult = postJson("/api/v1/conversations/direct",
                        Map.of("userId", peer.userId().toString()), user.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        UUID directConversationId = UUID.fromString(objectMapper
                .readTree(directResult.getResponse().getContentAsString()).path("id").asText());
        UUID callId = UUID.randomUUID();
        callService.signal(directConversationId, user.userId(), new CallSignal(
                CallSignal.Type.INVITE, callId, null, null));
        callRegistry.saveOffer(callId,
                new CallSignal.Sdp("offer", "v=0\\r\\no=nexachat 1 1 IN IP4 127.0.0.1"));
        String activePassword = "secret123";
        String activeAccessToken = user.accessToken();
        String activeRefreshToken = user.refreshToken();
        Set<String> observedProbeIds = new HashSet<>();

        for (Route route : routes) {
            assertThat(observedProbeIds.add(route.probeId()))
                    .as("probe ID must dispatch at most once: " + route.probeId())
                    .isTrue();

            String bearer = null;
            if (route.requiresAuthentication()) {
                if (route.policy().equals("OPERATOR_ADMIN")) {
                    bearer = operator.accessToken();
                } else {
                    bearer = activeAccessToken;
                }
            }

            MockHttpServletRequestBuilder request = requestForProbe(route, directConversationId, callId);
            if (bearer != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
            }
            if (route.path().equals("/api/v1/auth/logout")) {
                request.content(objectMapper.writeValueAsString(Map.of("refreshToken", activeRefreshToken)));
            } else if (route.path().equals("/api/v1/auth/refresh")) {
                request.content(objectMapper.writeValueAsString(Map.of("refreshToken", activeRefreshToken)));
            } else if (route.path().equals("/api/v1/auth/password")) {
                request.content(objectMapper.writeValueAsString(Map.of(
                        "oldPassword", activePassword,
                        "newPassword", "matrixSecret456",
                        "refreshToken", activeRefreshToken)));
            } else if (route.path().equals("/api/v1/users/me") && route.method().equals("DELETE")) {
                request.contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "wrong-probe-password")));
            }

            var result = mockMvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("current-authentication probe " + route.probeId() + " for "
                            + route.method() + " " + route.path())
                    .isBetween(200, 499);
            if (route.policy().equals("OPERATOR_ADMIN")) {
                assertThat(result.getResponse().getStatus())
                        .as("configured operator probe " + route.probeId())
                        .isBetween(200, 299);
            }

            if (route.path().equals("/api/v1/auth/refresh")) {
                JsonNode auth = objectMapper.readTree(result.getResponse().getContentAsString());
                activeAccessToken = auth.path("accessToken").asText();
                activeRefreshToken = auth.path("refreshToken").asText();
                assertThat(activeAccessToken).isNotBlank();
                assertThat(activeRefreshToken).isNotBlank();
            } else if (route.path().equals("/api/v1/auth/logout")
                    || route.path().equals("/api/v1/auth/logout-all")) {
                Registered next = loginForProbe(userPhone, activePassword);
                activeAccessToken = next.accessToken();
                activeRefreshToken = next.refreshToken();
            } else if (route.path().equals("/api/v1/auth/password")) {
                activePassword = "matrixSecret456";
                Registered next = loginForProbe(userPhone, activePassword);
                activeAccessToken = next.accessToken();
                activeRefreshToken = next.refreshToken();
            }
        }

        assertThat(observedProbeIds)
                .as("every exact REST route probe was executed once")
                .containsExactlyInAnyOrderElementsOf(routes.stream().map(Route::probeId).toList());
    }

    @Test
    void configuredOperatorAndOrdinaryUserHaveDifferentMetricsOutcomes() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        Registered ordinary = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551822"));

        getWithAuth("/actuator/metrics", admin.accessToken()).andExpect(status().isOk());
        getWithAuth("/actuator/metrics", ordinary.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void everyScopedRouteRunsItsPolicyActorProbes() throws Exception {
        RoleFixtures fixtures = createRoleFixtures();
        List<Route> routes = readMatrix();
        Set<String> expectedProbeIds = routes.stream()
                .filter(route -> !Set.of("AUTHENTICATED", "PUBLIC_AUTH", "OPERATOR_ADMIN")
                        .contains(route.policy()))
                .map(Route::probeId)
                .collect(Collectors.toSet());
        Set<String> observedProbeIds = new HashSet<>();

        List<Route> orderedRoutes = new ArrayList<>(routes);
        orderedRoutes.sort((left, right) -> Boolean.compare(
                left.path().endsWith("/{conversationId}/leave"),
                right.path().endsWith("/{conversationId}/leave")));

        for (Route route : orderedRoutes) {
            if (!expectedProbeIds.contains(route.probeId())) {
                continue;
            }
            RoleFixtures routeFixtures = prepareRoleRoute(route, fixtures);
            assertThat(observedProbeIds.add(route.probeId()))
                    .as("scoped route role probe must execute once: " + route.probeId())
                    .isTrue();

            switch (route.policy()) {
                case "CONVERSATION_MEMBER" -> {
                    for (Registered denied : List.of(routeFixtures.outsider(), routeFixtures.former(),
                            routeFixtures.banned())) {
                        assertRoleStatus(route, routeFixtures, denied, 403, ApiErrorCode.NOT_MEMBER.getCode());
                    }
                    if (route.path().equals("/api/v1/conversations/{conversationId}/leave")) {
                        assertRoleStatus(route, routeFixtures, routeFixtures.member(), 204, null);
                    } else {
                        assertRoleSuccess(route, routeFixtures, actorForMemberRoute(route, routeFixtures));
                    }
                }
                case "GROUP_ADMIN" -> {
                    assertRoleStatus(route, routeFixtures, routeFixtures.member(), 403,
                            ApiErrorCode.NOT_ADMIN.getCode());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.owner());
                }
                case "GROUP_ADMIN_OR_SELF" -> {
                    UUID removalGroup = createGroup(routeFixtures.owner(), "Member removal role probe",
                            List.of(routeFixtures.member().userId(), routeFixtures.otherMember().userId()));
                    RoleFixtures removalFixtures = routeFixtures.withGroupId(removalGroup);
                    assertRoleStatus(route, removalFixtures, removalFixtures.member(), 403,
                            ApiErrorCode.NOT_ADMIN.getCode());
                    assertRoleStatus(route, removalFixtures, removalFixtures.owner(), 204, null);
                    UUID selfGroup = createGroup(routeFixtures.owner(), "Self removal probe",
                            List.of(routeFixtures.member().userId()));
                    String selfPath = "/api/v1/conversations/" + selfGroup + "/members/"
                            + routeFixtures.member().userId();
                    mockMvc.perform(MockMvcRequestBuilders.delete(selfPath)
                                    .header(HttpHeaders.AUTHORIZATION,
                                            "Bearer " + routeFixtures.member().accessToken()))
                            .andExpect(status().isNoContent());
                }
                case "MESSAGE_AUTHOR_OR_ADMIN" -> {
                    assertRoleStatus(route, routeFixtures, fixtures.otherMember(), 403,
                            ApiErrorCode.NOT_MESSAGE_AUTHOR.getCode());
                    Registered author = route.method().equals("DELETE")
                            ? routeFixtures.owner() : routeFixtures.member();
                    assertRoleSuccess(route, routeFixtures, author);
                }
                case "MESSAGE_CONVERSATION_MEMBER" -> {
                    assertRoleStatus(route, routeFixtures, routeFixtures.outsider(), 403,
                            ApiErrorCode.NOT_MEMBER.getCode());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.member());
                }
                case "DIRECT_MEMBER_OR_GROUP_ADMIN" -> {
                    assertRoleStatus(route, routeFixtures, routeFixtures.member(), 403,
                            ApiErrorCode.NOT_ADMIN.getCode());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.owner());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.directPeer());
                }
                case "CALL_PARTICIPANT" -> {
                    assertRoleStatus(route, routeFixtures, routeFixtures.outsider(), 403,
                            ApiErrorCode.NOT_MEMBER.getCode());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.owner());
                }
                case "FILE_OWNER_OR_MEMBER" -> {
                    assertRoleStatus(route, routeFixtures, routeFixtures.outsider(), 403,
                            ApiErrorCode.NOT_MEMBER.getCode());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.member());
                }
                case "POLL_CREATOR_OR_ADMIN" -> {
                    assertRoleStatus(route, routeFixtures, routeFixtures.otherMember(), 403,
                            ApiErrorCode.NOT_ADMIN.getCode());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.owner());
                }
                case "REMINDER_CREATOR_OR_ADMIN" -> {
                    assertRoleStatus(route, routeFixtures, routeFixtures.otherMember(), 403,
                            ApiErrorCode.NOT_ADMIN.getCode());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.owner());
                }
                case "AUTHENTICATED_INVITE" -> {
                    if (route.method().equals("POST")) {
                        assertRoleStatus(route, routeFixtures, routeFixtures.banned(), 403,
                                ApiErrorCode.FORBIDDEN.getCode());
                    }
                    assertRoleSuccess(route, routeFixtures, routeFixtures.invitee());
                }
                case "SELF_GROUP_REQUEST" -> {
                    assertRoleStatus(route, routeFixtures, routeFixtures.outsider(), 404,
                            ApiErrorCode.NOT_FOUND.getCode());
                    assertRoleSuccess(route, routeFixtures, routeFixtures.requester());
                }
                default -> throw new AssertionError("No scoped REST role probe for " + route.policy());
            }
        }

        assertThat(observedProbeIds)
                .as("every scoped REST catalog row has an actor-specific request outcome")
                .containsExactlyInAnyOrderElementsOf(expectedProbeIds);
    }

    private RoleFixtures createRoleFixtures() throws Exception {
        Registered owner = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551901"));
        Registered member = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551902"));
        Registered otherMember = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551903"));
        Registered outsider = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551904"));
        Registered former = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551905"));
        Registered banned = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551906"));
        Registered directPeer = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551907"));
        Registered requester = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551908"));
        Registered invitee = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551909"));

        UUID groupId = createGroup(owner, "Route role probe group",
                List.of(member.userId(), otherMember.userId(), former.userId(), banned.userId()));
        String inviteToken = createInviteLink(owner, groupId, true);
        removeMember(owner, groupId, former.userId());
        removeMember(owner, groupId, banned.userId());

        UUID directId = openDirect(owner, directPeer);
        UUID callId = UUID.randomUUID();
        callService.signal(directId, owner.userId(), new CallSignal(
                CallSignal.Type.INVITE, callId, null, null));
        callRegistry.saveOffer(callId,
                new CallSignal.Sdp("offer", "v=0\\r\\no=nexachat 1 1 IN IP4 127.0.0.1"));

        UUID groupMessageId = sendRoleMessage(groupId, member, "role fixture message");
        UUID directMessageId = sendRoleMessage(directId, owner, "direct role fixture message");
        UUID labelId = createLabel(member);
        UploadedFile uploaded = uploadAndBind(member, groupId);
        UUID requesterRequestId = createPendingRequest(requester, inviteToken);

        return new RoleFixtures(owner, member, otherMember, outsider, former, banned,
                directPeer, requester, invitee, groupId, directId, callId,
                groupMessageId, directMessageId, labelId, uploaded.id(), uploaded.url(),
                inviteToken, requesterRequestId, groupMessageId, PATH_ID, PATH_ID, PATH_ID,
                requesterRequestId);
    }

    private void removeMember(Registered owner, UUID conversationId, UUID userId) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/conversations/{conversationId}/members/{userId}",
                        conversationId, userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
    }

    private void addMember(Registered owner, UUID conversationId, UUID userId) throws Exception {
        postJson("/api/v1/conversations/" + conversationId + "/members",
                        Map.of("userIds", List.of(userId.toString())), owner.accessToken())
                .andExpect(status().isNoContent());
    }

    private UUID openDirect(Registered owner, Registered peer) throws Exception {
        var result = postJson("/api/v1/conversations/direct", Map.of("userId", peer.userId().toString()),
                        owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private UUID sendRoleMessage(UUID conversationId, Registered actor, String content) throws Exception {
        var result = postJson("/api/v1/conversations/" + conversationId + "/messages",
                        Map.of("clientMessageId", UUID.randomUUID().toString(),
                                "type", "TEXT", "content", content), actor.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private UUID createLabel(Registered actor) throws Exception {
        var result = postJson("/api/v1/labels", Map.of("name", "Role probe", "colorId", "INDIGO"),
                        actor.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private UploadedFile uploadAndBind(Registered actor, UUID conversationId) throws Exception {
        var upload = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "role-probe.png", "image/png", PROBE_PNG))
                        .param("width", "16")
                        .param("height", "16")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode uploaded = objectMapper.readTree(upload.getResponse().getContentAsString());
        UUID id = UUID.fromString(uploaded.path("id").asText());
        String url = uploaded.path("url").asText();
        postJson("/api/v1/conversations/" + conversationId + "/messages",
                        Map.of("clientMessageId", UUID.randomUUID().toString(),
                                "type", "IMAGE", "attachmentIds", List.of(id.toString())), actor.accessToken())
                .andExpect(status().isCreated());
        return new UploadedFile(id, url);
    }

    private String createInviteLink(Registered owner, UUID conversationId, boolean requiresApproval)
            throws Exception {
        var result = postJson("/api/v1/conversations/" + conversationId + "/invite-link",
                        Map.of("requiresApproval", requiresApproval), owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private UUID createPendingRequest(Registered invitee, String token) throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/invite-links/{token}/join", token)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + invitee.accessToken()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID requestId = UUID.fromString(body.path("requestId").asText());
        assertThat(requestId).isNotNull();
        return requestId;
    }

    private RoleFixtures prepareRoleRoute(Route route, RoleFixtures fixtures) throws Exception {
        RoleFixtures prepared = fixtures;
        if (route.policy().equals("GROUP_ADMIN")) {
            UUID isolatedGroup = createGroup(fixtures.owner(), "Admin role probe " + route.probeId(),
                    List.of(fixtures.member().userId(), fixtures.otherMember().userId()));
            prepared = prepared.withGroupId(isolatedGroup);
        }
        if (route.policy().equals("MESSAGE_AUTHOR_OR_ADMIN")) {
            prepared = prepared.withMessageId(sendRoleMessage(fixtures.groupId(), fixtures.member(),
                    "author policy " + route.probeId()));
        } else if (route.policy().equals("MESSAGE_CONVERSATION_MEMBER")
                && route.path().startsWith("/api/v1/messages/")) {
            prepared = prepared.withMessageId(sendRoleMessage(fixtures.groupId(), fixtures.member(),
                    "reaction policy " + route.probeId()));
        }
        if (route.path().contains("/{pollId}")) {
            prepared = prepared.withPoll(createPoll(fixtures.groupId(), fixtures.member()));
        }
        if (route.path().contains("/{reminderId}")) {
            prepared = prepared.withReminder(createReminder(fixtures.groupId(), fixtures.member()));
        }
        if (route.policy().equals("AUTHENTICATED_INVITE") && route.method().equals("POST")) {
            String token = createInviteLink(fixtures.owner(), fixtures.groupId(), true);
            addMember(fixtures.owner(), fixtures.groupId(), fixtures.banned().userId());
            removeMember(fixtures.owner(), fixtures.groupId(), fixtures.banned().userId());
            prepared = prepared.withInviteToken(token);
        } else if (route.policy().equals("GROUP_ADMIN")
                && route.path().contains("/invite-link") && !route.method().equals("POST")) {
            prepared = prepared.withInviteToken(createInviteLink(
                    prepared.owner(), prepared.groupId(), true));
        }
        if (route.policy().equals("GROUP_ADMIN")
                && (route.path().endsWith("/approve") || route.path().endsWith("/reject"))) {
            Registered requester = route.path().endsWith("/approve")
                    ? fixtures.requester() : fixtures.invitee();
            String token = createInviteLink(prepared.owner(), prepared.groupId(), true);
            UUID requestId = createPendingRequest(requester, token);
            prepared = prepared.withRequestId(requestId);
        }
        return prepared;
    }

    private PollFixture createPoll(UUID conversationId, Registered creator) throws Exception {
        var result = postJson("/api/v1/conversations/" + conversationId + "/polls",
                        Map.of("question", "Authorization probe", "options", List.of("One", "Two")),
                        creator.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID pollId = UUID.fromString(body.path("id").asText());
        UUID optionId = UUID.fromString(body.path("options").get(0).path("id").asText());
        return new PollFixture(pollId, optionId);
    }

    private UUID createReminder(UUID conversationId, Registered creator) throws Exception {
        var result = postJson("/api/v1/conversations/" + conversationId + "/reminders",
                        Map.of("title", "Authorization probe",
                                "remindAt", Instant.now().plus(2, ChronoUnit.DAYS).toString()),
                        creator.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private void assertRoleStatus(Route route, RoleFixtures fixtures, Registered actor,
                                 int expectedStatus, String expectedCode) throws Exception {
        var result = mockMvc.perform(roleRequest(route, fixtures, actor)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("actor outcome for " + route.probeId() + " " + route.method() + " " + route.path()
                        + " as " + actor.userId())
                .isEqualTo(expectedStatus);
        if (expectedCode != null) {
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("error").path("code").asText())
                    .as("authorization error for " + route.probeId())
                    .isEqualTo(expectedCode);
        }
    }

    private void assertRoleSuccess(Route route, RoleFixtures fixtures, Registered actor) throws Exception {
        var result = mockMvc.perform(roleRequest(route, fixtures, actor)).andReturn();
        int expectedStatus = expectedRoleSuccessStatus(route);
        if (expectedStatus == 0) {
            assertThat(result.getResponse().getStatus())
                    .as("invite join outcome for " + route.probeId())
                    .isIn(200, 201);
        } else {
            assertThat(result.getResponse().getStatus())
                    .as("allowed actor outcome for " + route.probeId() + " " + route.method()
                            + " " + route.path())
                    .isEqualTo(expectedStatus);
        }
    }

    private int expectedRoleSuccessStatus(Route route) {
        if (route.policy().equals("AUTHENTICATED_INVITE") && route.method().equals("POST")) {
            return 0;
        }
        if (route.policy().equals("CALL_PARTICIPANT") || route.policy().equals("FILE_OWNER_OR_MEMBER")
                || route.policy().equals("SELF_GROUP_REQUEST") || route.method().equals("GET")) {
            return 200;
        }
        if (route.policy().equals("GROUP_ADMIN") && route.method().equals("POST")) {
            if (route.path().endsWith("/invite-link")) return 201;
            if (route.path().endsWith("/approve") || route.path().endsWith("/reject")) return 200;
            if (route.path().endsWith("/avatar")) return 200;
            return 204;
        }
        if (route.policy().equals("MESSAGE_AUTHOR_OR_ADMIN")) {
            return route.method().equals("PATCH") ? 200 : 204;
        }
        if (route.policy().equals("POLL_CREATOR_OR_ADMIN")) {
            return route.method().equals("POST") ? 200 : 204;
        }
        if (route.policy().equals("REMINDER_CREATOR_OR_ADMIN")) {
            return route.method().equals("PATCH") ? 200 : 204;
        }
        if (route.policy().equals("CONVERSATION_MEMBER")) {
            if ((route.path().endsWith("/vote") && route.method().equals("DELETE"))
                    || (route.path().endsWith("/vote") && route.method().equals("PUT"))) return 200;
            if (route.path().endsWith("/messages") && route.method().equals("POST")) return 201;
            if (route.path().endsWith("/polls") && route.method().equals("POST")) return 201;
            if (route.path().endsWith("/reminders") && route.method().equals("POST")) return 201;
            if (route.method().equals("POST") && !route.path().endsWith("/ask")
                    && !route.path().endsWith("/summary")) return 204;
            if (route.path().endsWith("/ask")) return 200;
            if (route.path().endsWith("/summary")) return 400;
            if (route.path().endsWith("/ai-opt-out")) return 200;
        }
        if (route.method().equals("PATCH")) return 200;
        if (route.method().equals("DELETE") || route.method().equals("PUT")
                || route.method().equals("POST")) return 204;
        return 200;
    }

    private MockHttpServletRequestBuilder roleRequest(Route route, RoleFixtures fixtures, Registered actor)
            throws Exception {
        String path = rolePath(route, fixtures, actor);
        HttpMethod method = HttpMethod.valueOf(route.method());
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken());
        if (path.equals("/api/v1/users/search")) request.param("q", "probe");
        if (route.path().endsWith("/messages/search")) request.param("q", "role");
        if (route.path().endsWith("/archive") && route.method().equals("GET")) {
            request.param("type", "MEDIA");
        }
        if (route.path().endsWith("/conversations/{conversationId}/polls/changes")) {
            request.param("since", "0");
        }
        Object body = roleBody(route, fixtures, actor);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        if (route.policy().equals("GROUP_ADMIN") && route.path().endsWith("/avatar")) {
            return MockMvcRequestBuilders.multipart(path)
                    .file(new MockMultipartFile("file", "role-probe.png", "image/png", PROBE_PNG))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken());
        }
        return request;
    }

    private String rolePath(Route route, RoleFixtures fixtures, Registered actor) {
        if (route.path().equals("/api/v1/files/**")) return fixtures.fileUrl();
        boolean directPin = route.policy().equals("DIRECT_MEMBER_OR_GROUP_ADMIN")
                && actor.userId().equals(fixtures.directPeer().userId());
        UUID conversationId = route.path().equals("/api/v1/conversations/{id}/calls")
                || directPin ? fixtures.directId() : fixtures.groupId();
        UUID messageId = directPin ? fixtures.directMessageId() : fixtures.messageId();
        UUID pathId = route.path().startsWith("/api/v1/attachments/")
                ? fixtures.attachmentId() : conversationId;
        String path = route.path()
                .replace("/api/v1/files/**", fixtures.fileUrl())
                .replace("{conversationId}", conversationId.toString())
                .replace("{id}", pathId.toString())
                .replace("{userId}", fixtures.otherMember().userId().toString())
                .replace("{messageId}", messageId.toString())
                .replace("{labelId}", fixtures.labelId().toString())
                .replace("{callId}", fixtures.callId().toString())
                .replace("{pollId}", fixtures.pollId().toString())
                .replace("{reminderId}", fixtures.reminderId().toString())
                .replace("{requestId}", fixtures.requestId().toString())
                .replace("{token}", fixtures.inviteToken())
                .replace("{emoji}", PROBE_REACTION_EMOJI);
        return path;
    }

    private Object roleBody(Route route, RoleFixtures fixtures, Registered actor) {
        String path = route.path();
        if (route.method().equals("POST") && path.endsWith("/messages")) {
            return Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT",
                    "content", "per-route authorization probe");
        }
        if (route.method().equals("PATCH") && path.contains("/messages/")) {
            return Map.of("content", "updated authorization probe");
        }
        if ((route.method().equals("POST") && (path.endsWith("/read") || path.endsWith("/delivered")))) {
            return Map.of("upToSeq", 1);
        }
        if (path.endsWith("/mute") && route.method().equals("PUT")) return Map.of("forever", true);
        if (route.policy().equals("GROUP_ADMIN") && path.endsWith("/invite-link")
                && route.method().equals("PATCH")) return Map.of("requiresApproval", true);
        if (route.policy().equals("GROUP_ADMIN") && path.equals("/api/v1/conversations/{conversationId}")
                && route.method().equals("PATCH")) return Map.of("title", "Updated role probe group");
        if (route.policy().equals("GROUP_ADMIN") && path.endsWith("/members")) {
            return Map.of("userIds", List.of(fixtures.requester().userId().toString()));
        }
        if (route.method().equals("POST") && path.endsWith("/polls")) {
            return Map.of("question", "per-route authorization probe", "options", List.of("One", "Two"));
        }
        if (route.path().endsWith("/{pollId}/vote") && route.method().equals("PUT")) {
            return Map.of("optionIds", List.of(fixtures.pollOptionId().toString()));
        }
        if (route.method().equals("POST") && path.endsWith("/reminders")) {
            return Map.of("title", "per-route authorization probe",
                    "remindAt", Instant.now().plus(2, ChronoUnit.DAYS).toString());
        }
        if (route.method().equals("PATCH") && path.contains("/reminders/")) {
            return Map.of("title", "updated authorization probe");
        }
        if (route.policy().equals("DIRECT_MEMBER_OR_GROUP_ADMIN") && route.method().equals("POST")) {
            UUID messageId = actor.userId().equals(fixtures.directPeer().userId())
                    ? fixtures.directMessageId() : fixtures.messageId();
            return Map.of("messageId", messageId.toString());
        }
        if (route.policy().equals("MESSAGE_CONVERSATION_MEMBER") && route.method().equals("POST")) {
            return Map.of("emoji", "👍");
        }
        if (route.method().equals("POST") && path.endsWith("/ask")) return Map.of("question", "role probe");
        if (route.method().equals("POST") && path.endsWith("/summary")) {
            return Map.of("scope", "LAST_N", "limit", 5);
        }
        if (route.path().equals("/api/v1/users/me") && route.method().equals("DELETE")) {
            return Map.of("password", "wrong-role-probe");
        }
        return null;
    }

    private static Registered actorForMemberRoute(Route route, RoleFixtures fixtures) {
        return route.path().equals("/api/v1/conversations/{id}/calls")
                ? fixtures.directPeer() : fixtures.member();
    }

    private record UploadedFile(UUID id, String url) {}

    private record PollFixture(UUID id, UUID optionId) {}

    private record RoleFixtures(Registered owner, Registered member, Registered otherMember,
                                Registered outsider, Registered former, Registered banned,
                                Registered directPeer, Registered requester, Registered invitee,
                                UUID groupId, UUID directId, UUID callId,
                                UUID baseMessageId, UUID directMessageId, UUID labelId,
                                UUID attachmentId, String fileUrl, String inviteToken,
                                UUID baseRequestId, UUID messageId, UUID pollId,
                                UUID pollOptionId, UUID reminderId, UUID requestId) {
        private RoleFixtures withMessageId(UUID value) {
            return new RoleFixtures(owner, member, otherMember, outsider, former, banned, directPeer,
                    requester, invitee, groupId, directId, callId, baseMessageId, directMessageId,
                    labelId, attachmentId, fileUrl, inviteToken, baseRequestId, value, pollId,
                    pollOptionId, reminderId, requestId);
        }
        private RoleFixtures withPoll(PollFixture value) {
            return new RoleFixtures(owner, member, otherMember, outsider, former, banned, directPeer,
                    requester, invitee, groupId, directId, callId, baseMessageId, directMessageId,
                    labelId, attachmentId, fileUrl, inviteToken, baseRequestId, messageId, value.id(),
                    value.optionId(), reminderId, requestId);
        }
        private RoleFixtures withReminder(UUID value) {
            return new RoleFixtures(owner, member, otherMember, outsider, former, banned, directPeer,
                    requester, invitee, groupId, directId, callId, baseMessageId, directMessageId,
                    labelId, attachmentId, fileUrl, inviteToken, baseRequestId, messageId, pollId,
                    pollOptionId, value, requestId);
        }
        private RoleFixtures withInviteToken(String value) {
            return new RoleFixtures(owner, member, otherMember, outsider, former, banned, directPeer,
                    requester, invitee, groupId, directId, callId, baseMessageId, directMessageId,
                    labelId, attachmentId, fileUrl, value, baseRequestId, messageId, pollId,
                    pollOptionId, reminderId, requestId);
        }
        private RoleFixtures withRequestId(UUID value) {
            return new RoleFixtures(owner, member, otherMember, outsider, former, banned, directPeer,
                    requester, invitee, groupId, directId, callId, baseMessageId, directMessageId,
                    labelId, attachmentId, fileUrl, inviteToken, baseRequestId, messageId, pollId,
                    pollOptionId, reminderId, value);
        }
        private RoleFixtures withGroupId(UUID value) {
            return new RoleFixtures(owner, member, otherMember, outsider, former, banned, directPeer,
                    requester, invitee, value, directId, callId, baseMessageId, directMessageId,
                    labelId, attachmentId, fileUrl, inviteToken, baseRequestId, messageId, pollId,
                    pollOptionId, reminderId, requestId);
        }
    }

    @Test
    void roleMatrixSeparatesMembersAdminsFormerMembersBannedUsersAndDirectPeers() throws Exception {
        Registered owner = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551802"));
        Registered member = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551803"));
        Registered outsider = register(ItPhoneRanges.phoneFor(AuthorizationRouteMatrixIT.class, "+84905551804"));
        UUID groupId = createGroup(owner, "Authorization matrix group", List.of(member.userId()));

        getWithAuth(messagesPath(groupId), owner.accessToken()).andExpect(status().isOk());
        getWithAuth(messagesPath(groupId), member.accessToken()).andExpect(status().isOk());
        getWithAuth(messagesPath(groupId), outsider.accessToken()).andExpect(status().isForbidden());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/conversations/" + groupId + "/join-requests")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken()))
                .andExpect(status().isForbidden());

        var linkResult = postJson("/api/v1/conversations/" + groupId + "/invite-link", Map.of(),
                        owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode link = objectMapper.readTree(linkResult.getResponse().getContentAsString());
        String inviteToken = link.path("token").asText();
        assertThat(inviteToken).isNotBlank();

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/conversations/" + groupId
                                + "/members/" + member.userId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        getWithAuth(messagesPath(groupId), member.accessToken()).andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/invite-links/" + inviteToken + "/join")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken()))
                .andExpect(status().isForbidden());

        var directResult = postJson("/api/v1/conversations/direct",
                        Map.of("userId", outsider.userId().toString()), owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        UUID directId = UUID.fromString(objectMapper.readTree(directResult.getResponse().getContentAsString())
                .path("id").asText());
        getWithAuth(messagesPath(directId), outsider.accessToken()).andExpect(status().isOk());
        getWithAuth(messagesPath(directId), member.accessToken()).andExpect(status().isForbidden());
    }

    private UUID createGroup(Registered owner, String title, List<UUID> memberIds) throws Exception {
        List<String> ids = memberIds.stream().map(UUID::toString).toList();
        var result = postJson("/api/v1/conversations/group",
                        Map.of("title", title, "memberIds", ids), owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private static String messagesPath(UUID conversationId) {
        return "/api/v1/conversations/" + conversationId + "/messages?limit=10";
    }

    private String expiredAccessToken(UUID userId) {
        Instant now = Instant.now();
        byte[] secret = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        return Jwts.builder()
                .subject(userId.toString())
                .claim(JwtService.CLAIM_TYPE, JwtService.TYPE_ACCESS)
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret))
                .compact();
    }

    private Registered loginForProbe(String phone, String password) throws Exception {
        var result = postJson("/api/v1/auth/login", Map.of("phone", phone, "password", password))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registered(
                UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText(),
                body.get("refreshToken").asText());
    }

    private MockHttpServletRequestBuilder withBearer(Route route, String token) {
        return requestFor(route).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private void assertTokenRejected(Route route, String token, String expectedCode) throws Exception {
        assertResponse(mockMvc.perform(withBearer(route, token)).andReturn(), route, 401, expectedCode);
    }

    private void assertResponse(org.springframework.test.web.servlet.MvcResult result, Route route,
                                int expectedStatus, String expectedCode) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("response status for " + route.method() + " " + route.path())
                .isEqualTo(expectedStatus);
        String actualCode = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("error").path("code").asText();
        assertThat(actualCode)
                .as("error code for " + route.method() + " " + route.path())
                .isEqualTo(expectedCode);
    }

    private MockHttpServletRequestBuilder requestFor(Route route) {
        String path = route.path()
                .replace("/**", "/matrix-no-file")
                .replaceAll("\\{[^}]+}", PATH_ID.toString());
        HttpMethod method = route.method().equals("ANY") ? HttpMethod.GET : HttpMethod.valueOf(route.method());
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path);
        if (Set.of("POST", "PUT", "PATCH").contains(method.name())) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return request;
    }

    private MockHttpServletRequestBuilder requestForProbe(Route route,
                                                           UUID directConversationId,
                                                           UUID callId) {
        String path = route.path();
        if (path.contains("{callId}")) {
            path = path.replace("{callId}", callId.toString());
        }
        if (path.equals("/api/v1/conversations/{id}/calls")) {
            path = path.replace("{id}", directConversationId.toString());
        }
        if (route.method().equals("POST")
                && (path.equals("/api/v1/attachments") || path.endsWith("/avatar"))) {
            path = path.replace("/**", "/matrix-no-file")
                    .replaceAll("\\{[^}]+}", PATH_ID.toString());
            return MockMvcRequestBuilders.multipart(path)
                    .file(new MockMultipartFile("file", "matrix-probe.png", "image/png", PROBE_PNG));
        }
        return requestFor(new Route(route.method(), path, route.policy(),
                route.roleScenario(), route.probeId()));
    }

    private static List<Route> readMatrix() throws Exception {
        try (var stream = AuthorizationRouteMatrixIT.class.getResourceAsStream(AUTHZ_MATRIX)) {
            assertThat(stream).as("REST authorization matrix resource").isNotNull();
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                List<Route> routes = new ArrayList<>();
                String line = reader.readLine();
                assertThat(line).isEqualTo("method\tpath\tpolicy\trole_scenario\tprobe_id");
                while ((line = reader.readLine()) != null) {
                    String[] columns = line.split("\\t", -1);
                    assertThat(columns).hasSize(5);
                    RoleScenario scenario = REST_ROLE_SCENARIOS.get(columns[3]);
                    assertThat(scenario).as("REST role scenario " + columns[3]).isNotNull();
                    assertThat(scenario.policy()).as("policy bound to " + columns[3]).isEqualTo(columns[2]);
                    assertThat(columns[4]).as("route probe ID").matches("rest-[0-9]{3}");
                    routes.add(new Route(columns[0], columns[1], columns[2], columns[3], columns[4]));
                }
                assertThat(routes).isNotEmpty();
                return routes;
            }
        }
    }

    private static List<StompRoute> readStompMatrix() throws Exception {
        try (var stream = AuthorizationRouteMatrixIT.class.getResourceAsStream(STOMP_MATRIX)) {
            assertThat(stream).as("STOMP authorization matrix resource").isNotNull();
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                List<StompRoute> routes = new ArrayList<>();
                String line = reader.readLine();
                assertThat(line).isEqualTo("kind\tdestination\tpolicy\trole_scenario");
                while ((line = reader.readLine()) != null) {
                    String[] columns = line.split("\\t", -1);
                    assertThat(columns).hasSize(4);
                    assertThat(columns[0]).isIn("HANDLER", "SUBSCRIBE");
                    RoleScenario scenario = STOMP_ROLE_SCENARIOS.get(columns[3]);
                    assertThat(scenario).as("STOMP role scenario " + columns[3]).isNotNull();
                    assertThat(scenario.policy()).as("policy bound to " + columns[3]).isEqualTo(columns[2]);
                    routes.add(new StompRoute(columns[0], columns[1], columns[2], columns[3]));
                }
                assertThat(routes).isNotEmpty();
                return routes;
            }
        }
    }

    private record Route(String method, String path, String policy, String roleScenario, String probeId) {
        private boolean requiresAuthentication() {
            return !policy.equals("PUBLIC_AUTH");
        }
    }

    private record StompRoute(String kind, String destination, String policy, String roleScenario) {
    }

    private static RoleScenario roleScenario(String policy, String expectedOutcomes,
                                              String fixtureSetup, TestEvidence... evidence) {
        return new RoleScenario(policy, expectedOutcomes, fixtureSetup, List.of(evidence));
    }

    private static TestEvidence test(String className, String methodName) {
        return new TestEvidence(className, methodName);
    }

    private static void assertRoleScenarios(Set<String> referenced,
                                            Map<String, RoleScenario> scenarios,
                                            String catalogName) throws Exception {
        assertThat(scenarios.keySet()).as(catalogName + " role scenarios referenced by catalog")
                .containsExactlyInAnyOrderElementsOf(referenced);
        for (Map.Entry<String, RoleScenario> entry : scenarios.entrySet()) {
            RoleScenario scenario = entry.getValue();
            assertThat(scenario.expectedOutcomes()).as(entry.getKey() + " expected actor outcomes").isNotBlank();
            assertThat(scenario.fixtureSetup()).as(entry.getKey() + " fixture setup").isNotBlank();
            assertThat(scenario.evidence()).as(entry.getKey() + " executable test evidence").isNotEmpty();
            for (TestEvidence evidence : scenario.evidence()) {
                Class<?> type = Class.forName(evidence.className());
                Method method = type.getDeclaredMethod(evidence.methodName());
                assertThat(method.isAnnotationPresent(Test.class))
                        .as(catalogName + " executable evidence " + evidence.className()
                                + "#" + evidence.methodName())
                        .isTrue();
            }
        }
    }

    private record RoleScenario(String policy, String expectedOutcomes,
                                String fixtureSetup, List<TestEvidence> evidence) {
    }

    private record TestEvidence(String className, String methodName) {
    }
}
