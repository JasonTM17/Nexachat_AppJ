# core.network — `package com.nexachat.core.network`

The Android network layer is Java 21 LTS and uses Retrofit, OkHttp, Moshi, and
RxJava 3. `ApiResult.safeApiCall(Call)` executes Retrofit calls on the RxJava
IO scheduler and returns `Single<ApiResult<T>>`; disposing the subscription
cancels the request. HTTP failures preserve the status and any parsed server
error code, while I/O failures become network results.

DTOs are Java beans bound by Moshi's reflective adapters. Authentication uses
an OkHttp interceptor and authenticator, and connectivity, STOMP frames, and
realtime session updates are exposed as RxJava `Flowable` streams.
