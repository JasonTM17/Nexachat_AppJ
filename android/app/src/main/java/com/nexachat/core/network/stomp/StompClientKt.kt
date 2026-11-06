@file:JvmName("StompClientKt")

package com.nexachat.core.network.stomp

import okhttp3.HttpUrl

/** Kotlin and Java-test facade retained for the existing endpoint call sites. */
internal fun wsEndpointUrl(baseUrl: String): HttpUrl = WsEndpoint.fromBaseUrl(baseUrl)
