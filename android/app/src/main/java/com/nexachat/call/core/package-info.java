/**
 * Call core: {@code CallManager} serializes call-state changes and publishes snapshots;
 * STOMP signaling ({@code CallSignalingClient}), REST ICE config and history
 * ({@code CallRepository}), call state/event/signal models, and re-admission of incoming
 * call pushes against the current account.
 */
package com.nexachat.call.core;
