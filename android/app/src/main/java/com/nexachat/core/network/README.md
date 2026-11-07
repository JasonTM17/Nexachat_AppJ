# core.network

Network outcomes and authentication use Java 17 with RxJava 3. Retrofit calls
are normalized by `ApiResultKt.safeApiCall(Call)` into `Single<ApiResult<T>>`;
disposal cancels the cloned Retrofit call. Token rotation is single-flight and
exposes both RxJava methods and synchronous bridges for OkHttp's
`Authenticator` thread. Connectivity, foreground state, STOMP frames, and
realtime session events are exposed as `Flowable` streams.

Kotlin callers that still use coroutine APIs should bridge at the call site
with `kotlinx-coroutines-rx3`; the Java network APIs do not expose coroutines.
