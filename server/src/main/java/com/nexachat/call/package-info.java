/**
 * Voice/video calls: WebRTC signaling over STOMP ({@code CallController}, events on
 * {@code /user/queue/calls}), the single-node {@code CallRegistry} and
 * {@code CallStateMachine}, ring/disconnect timeouts, persisted call history, and the
 * REST ICE-config, offer and history endpoints in {@code CallRestController}.
 * {@code CallService} owns the call lifecycle.
 */
package com.nexachat.call;
