package com.nexachat.feature.auth

import app.cash.turbine.test
import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.AuthResponse
import com.nexachat.core.network.dto.UserDto
import com.nexachat.data.AuthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: AuthRepository = mockk()
    private lateinit var viewModel: AuthViewModel

    private val authResponse = AuthResponse(
        UserDto("u-1", "+84912345678", "Minh", null),
        "access-token",
        900,
        "refresh-token",
    )

    @Before
    fun setUp() {
        viewModel = AuthViewModel(repository)
    }

    @Test
    fun `login success emits LoggedIn and clears loading`() = runTest {
        coEvery { repository.login(any(), any()) } returns Result.Success(authResponse)
        viewModel.onPhoneChange("0912345678")
        viewModel.onPasswordChange("secret123")

        viewModel.events.test {
            viewModel.login()
            assertEquals(AuthViewModel.Event.LoggedIn, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(null, viewModel.uiState.value.error)
        coVerify(exactly = 1) { repository.login("0912345678", "secret123") }
    }

    @Test
    fun `login 401 shows InvalidCredentials and emits no event`() = runTest {
        coEvery { repository.login(any(), any()) } returns
            Result.failure(ApiError.InvalidCredentials.INSTANCE)
        viewModel.onPhoneChange("0912345678")
        viewModel.onPasswordChange("wrong-pass")

        viewModel.events.test {
            viewModel.login()
            expectNoEvents()
            cancel()
        }
        assertEquals(ApiError.InvalidCredentials.INSTANCE, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `login with blank fields is blocked before hitting the repository`() = runTest {
        viewModel.login()

        val state = viewModel.uiState.value
        assertTrue(state.phoneError)
        assertTrue(state.passwordError)
        assertFalse(state.loading)
        coVerify(exactly = 0) { repository.login(any(), any()) }
    }

    @Test
    fun `login with short password is blocked`() = runTest {
        viewModel.onPhoneChange("0912345678")
        viewModel.onPasswordChange("short")

        viewModel.login()

        assertTrue(viewModel.uiState.value.passwordError)
        coVerify(exactly = 0) { repository.login(any(), any()) }
    }

    @Test
    fun `register with blank displayName is blocked`() = runTest {
        viewModel.onPhoneChange("0912345678")
        viewModel.onPasswordChange("secret123")

        viewModel.register()

        assertTrue(viewModel.uiState.value.displayNameError)
        coVerify(exactly = 0) { repository.register(any(), any(), any()) }
    }

    @Test
    fun `register success emits LoggedIn`() = runTest {
        coEvery { repository.register(any(), any(), any()) } returns Result.Success(authResponse)
        viewModel.onDisplayNameChange("Minh")
        viewModel.onPhoneChange("0912345678")
        viewModel.onPasswordChange("secret123")

        viewModel.events.test {
            viewModel.register()
            assertEquals(AuthViewModel.Event.LoggedIn, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { repository.register("0912345678", "secret123", "Minh") }
    }

    @Test
    fun `register phone-exists error maps to PhoneExists`() = runTest {
        coEvery { repository.register(any(), any(), any()) } returns
            Result.failure(ApiError.PhoneExists.INSTANCE)
        viewModel.onDisplayNameChange("Minh")
        viewModel.onPhoneChange("0912345678")
        viewModel.onPasswordChange("secret123")

        viewModel.register()

        assertEquals(ApiError.PhoneExists.INSTANCE, viewModel.uiState.value.error)
    }
}
