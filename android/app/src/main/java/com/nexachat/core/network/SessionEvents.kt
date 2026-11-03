package com.nexachat.core.network

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide session signals. Emitted by `TokenAuthenticator`/`AuthRepository`
 * when the session is definitively dead (refresh rejected, manual logout);
 * collected once in `AppNavHost` to force-navigate to Login.
 *
 * `tryEmit`-based emit so callers on OkHttp threads / inside `runBlocking`
 * never suspend. `replay = 1` makes the signal sticky — a `LoggedOut` fired
 * before `AppNavHost`'s collector subscribes is still delivered, not dropped.
 */
@Singleton
class SessionEvents @Inject constructor() {

    sealed interface Event {
        data object LoggedOut : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun emitLoggedOut() {
        _events.tryEmit(Event.LoggedOut)
    }
}

/** Lets plain Compose code (AppNavHost) reach the singleton without a VM hop. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionEventsEntryPoint {
    fun sessionEvents(): SessionEvents
}
