package com.nexachat.core.network.stomp

import com.nexachat.core.storage.TokenStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Coroutine boundary kept in Kotlin so suspend token reads and flow cleanup remain structured. */
object StompClientFlow {

    @JvmStatic
    fun connect(tokenStore: TokenStore, connector: Connector): Flow<StompClient.State> = callbackFlow {
        val sink = object : StompClient.StateSink {
            override fun trySend(state: StompClient.State): Boolean =
                this@callbackFlow.trySend(state).isSuccess

            override fun close() {
                this@callbackFlow.close()
            }
        }
        sink.trySend(StompClient.State.Connecting)

        val token = tokenStore.readTokens()?.accessToken
        if (token.isNullOrBlank()) {
            sink.trySend(StompClient.State.Failed(StompClient.REASON_NO_TOKEN))
            close()
            return@callbackFlow
        }

        val cleanup = connector.connect(token, sink)
        awaitClose { cleanup.close() }
    }

    @JvmStatic
    fun messages(frames: SharedFlow<StompFrame>, subscriptionId: String): Flow<String> =
        frames
            .filter {
                it.command == StompCommand.MESSAGE &&
                    it.headers["subscription"] == subscriptionId
            }
            .map { it.body }

    fun interface Connector {
        fun connect(token: String, sink: StompClient.StateSink): AutoCloseable
    }
}
