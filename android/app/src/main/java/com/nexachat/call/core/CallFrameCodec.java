package com.nexachat.call.core;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON codec for the call signaling contract carried inside STOMP frames. */
final class CallFrameCodec {
    private static final int MAX_SDP_BYTES = 16 * 1024;
    private static final int MAX_CANDIDATE_BYTES = 1024;
    private static final Type MAP_TYPE = Types.newParameterizedType(
            Map.class, String.class, Object.class);

    private final JsonAdapter<Map<String, Object>> mapAdapter;

    @SuppressWarnings("unchecked")
    CallFrameCodec(Moshi moshi) {
        mapAdapter = (JsonAdapter<Map<String, Object>>) (JsonAdapter<?>) moshi
                .adapter(MAP_TYPE)
                .serializeNulls();
    }

    String encodeSignal(CallSignal signal) {
        if (signal == null || signal.getType() == null || blank(signal.getCallId())
                || !withinLimits(signal)) return null;
        try {
            return mapAdapter.toJson(toWireMap(signal));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    CallEvent decodeEvent(String json) {
        Map<String, Object> root = parseMap(json);
        if (root == null) return null;
        String wireType = string(root.get("eventType"));
        CallEvent.Type eventType = eventType(wireType);
        Map<?, ?> payload = map(root.get("payload"));
        String callId = first(
                string(root.get("callId")),
                string(payload == null ? null : payload.get("callId")));
        CallSignal signal = parseSignal(
                string(payload == null ? null : payload.get("type")),
                callId,
                payload);
        return new CallEvent(
                eventType,
                wireType,
                callId,
                string(root.get("conversationId")),
                string(root.get("fromUserId")),
                string(root.get("serverTs")),
                signal,
                first(
                        string(payload == null ? null : payload.get("reason")),
                        string(root.get("reason"))),
                integer(payload == null ? null : payload.get("durationSeconds")),
                null);
    }

    CallEvent decodeError(String json) {
        Map<String, Object> root = parseMap(json);
        if (root == null) return null;
        Map<?, ?> nestedError = map(root.get("error"));
        String code = first(
                string(root.get("code")),
                string(nestedError == null ? null : nestedError.get("code")));
        String callId = string(root.get("callId"));
        String conversationId = string(root.get("conversationId"));
        // The queue is shared with chat SEND failures. Ignore uncorrelated
        // generic errors so an unrelated message failure cannot end a call.
        if (code == null || (callId == null && conversationId == null
                && !code.startsWith("CALL_"))) return null;
        return new CallEvent(
                CallEvent.Type.CALL_ERROR,
                "CALL_ERROR",
                callId,
                conversationId,
                null,
                string(root.get("serverTs")),
                null,
                null,
                null,
                code);
    }

    private Map<String, Object> parseMap(String json) {
        try {
            return mapAdapter.fromJson(json);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static Map<String, Object> toWireMap(CallSignal signal) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", signal.getType().name());
        frame.put("callId", signal.getCallId());
        frame.put("sdp", null);
        frame.put("candidate", null);
        if (signal.getSdp() != null) {
            Map<String, Object> sdp = new LinkedHashMap<>();
            sdp.put("type", signal.getSdp().getType());
            sdp.put("description", signal.getSdp().getDescription());
            frame.put("sdp", sdp);
        }
        if (signal.getCandidate() != null) {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("sdpMid", signal.getCandidate().getSdpMid());
            candidate.put("sdpMLineIndex", signal.getCandidate().getSdpMLineIndex());
            candidate.put("candidate", signal.getCandidate().getCandidate());
            frame.put("candidate", candidate);
        }
        return frame;
    }

    private static boolean withinLimits(CallSignal signal) {
        if (signal.getType() == CallSignal.Type.SDP) {
            if (signal.getSdp() == null || signal.getCandidate() != null) return false;
            String type = signal.getSdp().getType();
            String description = signal.getSdp().getDescription();
            return ("offer".equals(type) || "answer".equals(type))
                    && !blank(description)
                    && description.getBytes(StandardCharsets.UTF_8).length <= MAX_SDP_BYTES;
        }
        if (signal.getType() == CallSignal.Type.ICE) {
            if (signal.getCandidate() == null || signal.getSdp() != null) return false;
            String sdpMid = signal.getCandidate().getSdpMid();
            Integer lineIndex = signal.getCandidate().getSdpMLineIndex();
            String candidate = signal.getCandidate().getCandidate();
            return (sdpMid == null || sdpMid.length() <= 256)
                    && (lineIndex == null || lineIndex >= 0)
                    && !blank(candidate)
                    && candidate.getBytes(StandardCharsets.UTF_8).length <= MAX_CANDIDATE_BYTES;
        }
        return signal.getSdp() == null && signal.getCandidate() == null;
    }

    private static CallSignal parseSignal(String wireType, String callId, Map<?, ?> payload) {
        if (payload == null) return null;
        CallSignal.Type type = signalType(wireType);
        if (type == null) return null;
        Map<?, ?> sdpMap = map(payload.get("sdp"));
        CallSignal.Sdp sdp = sdpMap == null ? null : new CallSignal.Sdp(
                string(sdpMap.get("type")), string(sdpMap.get("description")));
        Map<?, ?> candidateMap = map(payload.get("candidate"));
        CallSignal.IceCandidate candidate = candidateMap == null ? null : new CallSignal.IceCandidate(
                string(candidateMap.get("sdpMid")),
                integer(candidateMap.get("sdpMLineIndex")),
                string(candidateMap.get("candidate")));
        return new CallSignal(type, callId, sdp, candidate);
    }

    private static CallEvent.Type eventType(String value) {
        if (value == null) return CallEvent.Type.UNKNOWN;
        try {
            return CallEvent.Type.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return CallEvent.Type.UNKNOWN;
        }
    }

    private static CallSignal.Type signalType(String value) {
        if (value == null) return null;
        try {
            return CallSignal.Type.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static String first(String preferred, String fallback) {
        return preferred == null ? fallback : preferred;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
