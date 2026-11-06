package com.nexachat.data

import android.content.Context
import android.content.SharedPreferences
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.UserApi
import com.nexachat.core.network.dto.UpdateUserSettingsRequest
import com.nexachat.core.network.dto.UserSettingsDto
import com.nexachat.core.storage.TokenStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Theme persistence (device-level) and the optimistic presence toggle
 * (account-level, reverted on failure, reset on logout).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    private val userApi = mockk<UserApi>()
    private val tokenStore = mockk<TokenStore>()
    private val sessionEvents = mockk<SessionEvents>()
    private val sessionSignals = MutableSharedFlow<SessionEvents.Event>(extraBufferCapacity = 4)
    private val hasSession = MutableStateFlow(false)

    private fun fakeContext(store: MutableMap<String, String?>): Context {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } just Runs
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } answers { store[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }

    private fun TestScope.build(store: MutableMap<String, String?> = mutableMapOf()) {
        every { tokenStore.hasValidRefresh } returns hasSession
        every { sessionEvents.events } returns sessionSignals
        repository = SettingsRepository(
            context = fakeContext(store),
            userApi = userApi,
            tokenStore = tokenStore,
            sessionEvents = sessionEvents,
            scope = backgroundScope,
        )
        runCurrent()
    }

    private lateinit var repository: SettingsRepository

    @Test
    fun `theme defaults to SYSTEM and persists the selected mode`() = runTest {
        val store = mutableMapOf<String, String?>()
        build(store)
        assertEquals(ThemeMode.SYSTEM, repository.themeMode.value)

        repository.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, repository.themeMode.value)
        assertEquals("DARK", store["theme_mode"])

        repository.setThemeMode(ThemeMode.LIGHT)
        assertEquals("LIGHT", store["theme_mode"])
    }

    @Test
    fun `theme is restored from the last stored value`() = runTest {
        build(mutableMapOf("theme_mode" to "DARK"))

        assertEquals(ThemeMode.DARK, repository.themeMode.value)
    }

    @Test
    fun `setShowPresence applies optimistically then accepts the server value`() = runTest {
        build()
        coEvery { userApi.updateSettings(UpdateUserSettingsRequest(false)) } returns
            Response.success(UserSettingsDto(false as Boolean?))

        val result = repository.setShowPresence(false)

        assertTrue(result is com.nexachat.core.common.Result.Success)
        assertEquals(false, repository.showPresence.value)
        coVerify(exactly = 1) { userApi.updateSettings(UpdateUserSettingsRequest(false)) }
    }

    @Test
    fun `rejected presence toggle reverts to the previous value`() = runTest {
        build()
        coEvery { userApi.updateSettings(any()) } returns
            Response.error(429, "".toResponseBody())

        val result = repository.setShowPresence(false)

        assertTrue(result is com.nexachat.core.common.Result.Failure)
        assertEquals(true, repository.showPresence.value)
    }

    @Test
    fun `refreshShowPresence loads the server flag once a session exists`() = runTest {
        build()
        coEvery { userApi.getSettings() } returns
            Response.success(UserSettingsDto(false as Boolean?))

        hasSession.value = true
        runCurrent()

        assertEquals(false, repository.showPresence.value)
    }

    @Test
    fun `logout resets the privacy flag to the permissive default`() = runTest {
        build()
        coEvery { userApi.getSettings() } returns
            Response.success(UserSettingsDto(false as Boolean?))
        repository.refreshShowPresence()
        assertEquals(false, repository.showPresence.value)

        sessionSignals.emit(SessionEvents.Event.LoggedOut.INSTANCE)
        runCurrent()

        assertEquals(true, repository.showPresence.value)
    }
}
