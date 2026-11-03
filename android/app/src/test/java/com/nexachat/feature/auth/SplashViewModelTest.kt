package com.nexachat.feature.auth

import app.cash.turbine.test
import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.storage.TokenStore
import com.nexachat.data.AuthRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tokenStore: TokenStore = mockk()
    private val repository: AuthRepository = mockk()

    @Test
    fun `no stored refresh token navigates to login without calling refresh`() = runTest {
        every { tokenStore.hasValidRefresh } returns flowOf(false)

        val viewModel = SplashViewModel(tokenStore, repository)

        viewModel.navEvents.test {
            assertEquals(SplashViewModel.NavEvent.ToLogin, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        io.mockk.coVerify(exactly = 0) { repository.refresh() }
    }

    @Test
    fun `stored token plus successful refresh navigates to inbox`() = runTest {
        every { tokenStore.hasValidRefresh } returns flowOf(true)
        coEvery { repository.refresh() } returns Result.Success(true)

        val viewModel = SplashViewModel(tokenStore, repository)

        viewModel.navEvents.test {
            assertEquals(SplashViewModel.NavEvent.ToInbox, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stored token plus failed refresh navigates to login`() = runTest {
        every { tokenStore.hasValidRefresh } returns flowOf(true)
        coEvery { repository.refresh() } returns Result.Failure(ApiError.InvalidCredentials)

        val viewModel = SplashViewModel(tokenStore, repository)

        viewModel.navEvents.test {
            assertEquals(SplashViewModel.NavEvent.ToLogin, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
