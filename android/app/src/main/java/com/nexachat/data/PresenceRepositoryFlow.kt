package com.nexachat.data

import com.nexachat.data.isoToEpochMsOrNull as messageRepositoryIsoToEpochMsOrNull
import java.util.function.Consumer
import io.reactivex.rxjava3.core.Flowable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow

/** Kotlin-only Flow boundary for the Java [PresenceRepository]. */
internal object PresenceRepositoryFlow {

    @JvmStatic
    fun <T> stateView(state: MutableStateFlow<T>): StateFlow<T> = state.asStateFlow()

    @JvmStatic
    fun <T : Any> collect(
        scope: CoroutineScope,
        flow: Flow<T>,
        consumer: Consumer<T>,
    ): Job = scope.launch {
        flow.collect { value -> consumer.accept(value) }
    }

    @JvmStatic
    fun <T : Any> collect(
        scope: CoroutineScope,
        flow: Flowable<T>,
        consumer: Consumer<T>,
    ): Job = scope.launch {
        flow.toObservable().asFlow().collect { value -> consumer.accept(value) }
    }

    @JvmStatic
    fun isBlank(value: String): Boolean = value.isBlank()

    @JvmStatic
    fun isoToEpochMsOrNullFromMessageRepository(iso: String?): Long? =
        messageRepositoryIsoToEpochMsOrNull(iso)
}
