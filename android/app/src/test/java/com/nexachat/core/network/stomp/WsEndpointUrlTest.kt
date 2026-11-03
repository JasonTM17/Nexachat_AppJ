package com.nexachat.core.network.stomp

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the WS endpoint derivation: the request URL must stay a legal OkHttp
 * `http(s)` URL (`ws`/`wss` schemes throw `IllegalArgumentException` in
 * `HttpUrl` — this regression crashed the app at DI construction).
 */
class WsEndpointUrlTest {

    @Test
    fun `appends ws path and keeps http scheme`() {
        val url = wsEndpointUrl("http://10.0.2.2:8080")
        assertEquals("http", url.scheme)
        assertEquals("http://10.0.2.2:8080/ws", url.toString())
    }

    @Test
    fun `keeps https scheme which implies wss during upgrade`() {
        val url = wsEndpointUrl("https://chat.example.com")
        assertEquals("https", url.scheme)
        assertEquals("https://chat.example.com/ws", url.toString())
    }

    @Test
    fun `result is accepted by Request Builder without throwing`() {
        // This is the exact call path that crashed with "unexpected scheme: ws".
        val request = Request.Builder().url(wsEndpointUrl("http://192.168.1.10:8080")).build()
        assertEquals("/ws", request.url.encodedPath)
    }

    @Test
    fun `preserves nested base paths`() {
        val url = wsEndpointUrl("http://example.com/api/v1")
        assertEquals("http://example.com/api/v1/ws", url.toString())
    }
}
