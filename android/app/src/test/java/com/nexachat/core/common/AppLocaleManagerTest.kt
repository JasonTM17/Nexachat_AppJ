package com.nexachat.core.common

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Covers the pure tag mapping and the SharedPreferences-backed apply/current
 * round-trip. Plain JVM tests have `Build.VERSION.SDK_INT == 0`, so
 * [AppLocaleManager] always takes its API<33 branch here — the 33+ framework
 * path is exercised on-device, not in this suite.
 */
class AppLocaleManagerTest {

    private lateinit var defaultLocale: Locale

    @Before
    fun keepLocale() {
        defaultLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    /** In-memory stand-in for the "nexachat_locale" SharedPreferences file. */
    private fun fakeContext(store: MutableMap<String, String?> = mutableMapOf()): Context {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } just Runs
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } answers { store[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        // apply(SYSTEM) consults the base configuration for the JVM-default
        // reset — report an empty locale list so that lookup yields null.
        val locales = mockk<LocaleList>()
        every { locales.isEmpty } returns true
        val configuration = mockk<Configuration>()
        every { configuration.locales } returns locales
        val resources = mockk<Resources>()
        every { resources.configuration } returns configuration
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.resources } returns resources
        return context
    }

    // ---- LanguageOption.fromTag ----

    @Test
    fun `fromTag maps en and vi to their options`() {
        assertEquals(LanguageOption.ENGLISH, LanguageOption.fromTag("en"))
        assertEquals(LanguageOption.VIETNAMESE, LanguageOption.fromTag("vi"))
    }

    @Test
    fun `fromTag maps null, blank and unknown tags to SYSTEM`() {
        assertEquals(LanguageOption.SYSTEM, LanguageOption.fromTag(null))
        assertEquals(LanguageOption.SYSTEM, LanguageOption.fromTag(""))
        assertEquals(LanguageOption.SYSTEM, LanguageOption.fromTag("fr"))
    }

    // ---- apply/current round-trip (<33 branch) ----

    @Test
    fun `current defaults to SYSTEM when nothing was stored`() {
        assertEquals(LanguageOption.SYSTEM, AppLocaleManager.current(fakeContext()))
    }

    @Test
    fun `apply persists the tag and current reads it back`() {
        val context = fakeContext()

        AppLocaleManager.apply(context, LanguageOption.VIETNAMESE)
        assertEquals(LanguageOption.VIETNAMESE, AppLocaleManager.current(context))

        AppLocaleManager.apply(context, LanguageOption.ENGLISH)
        assertEquals(LanguageOption.ENGLISH, AppLocaleManager.current(context))
    }

    @Test
    fun `apply SYSTEM clears back to the system option`() {
        val context = fakeContext()
        AppLocaleManager.apply(context, LanguageOption.VIETNAMESE)

        AppLocaleManager.apply(context, LanguageOption.SYSTEM)

        assertEquals(LanguageOption.SYSTEM, AppLocaleManager.current(context))
    }
}
