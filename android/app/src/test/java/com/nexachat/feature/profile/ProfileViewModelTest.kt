package com.nexachat.feature.profile

import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.UserDto
import com.nexachat.data.AuthRepository
import com.nexachat.data.ContactsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val contactsRepository: ContactsRepository = mockk()
    private val authRepository: AuthRepository = mockk()

    private val me = UserDto(
        id = "me-1",
        phone = "+84900000000",
        displayName = "Son",
        avatarUrl = null,
    )

    /** Builds the VM after stubbing the init-block getMe() it always runs. */
    private fun createViewModel(
        meResult: Result<UserDto> = Result.Success(me),
    ): ProfileViewModel {
        coEvery { contactsRepository.getMe() } returns meResult
        return ProfileViewModel(contactsRepository, authRepository)
    }

    @Test
    fun `init loads the own user record`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertEquals(me, state.user)
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test
    fun `load failure surfaces the error and reload retries`() = runTest {
        val viewModel = createViewModel(Result.Failure(ApiError.Network("down")))
        assertEquals(ApiError.Network("down"), viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.user)

        coEvery { contactsRepository.getMe() } returns Result.Success(me)
        viewModel.load()

        val state = viewModel.uiState.value
        assertEquals(me, state.user)
        assertNull(state.error)
        coVerify(exactly = 2) { contactsRepository.getMe() }
    }

    @Test
    fun `startEditName prefills the field and cancel clears it`() = runTest {
        val viewModel = createViewModel()

        viewModel.startEditName()
        assertTrue(viewModel.uiState.value.editingName)
        assertEquals("Son", viewModel.uiState.value.nameInput)

        viewModel.cancelEditName()
        assertFalse(viewModel.uiState.value.editingName)
        assertEquals("", viewModel.uiState.value.nameInput)
    }

    @Test
    fun `saveDisplayName posts the trimmed name and updates the user`() = runTest {
        val renamed = me.copy(displayName = "Tien Son")
        coEvery { contactsRepository.updateDisplayName(any()) } returns
            Result.Success(renamed)

        val viewModel = createViewModel()
        viewModel.startEditName()
        viewModel.onNameInputChange("  Tien Son  ")
        viewModel.saveDisplayName()

        val state = viewModel.uiState.value
        assertEquals(renamed, state.user)
        assertFalse(state.editingName)
        assertFalse(state.saving)
        assertNull(state.saveError)
        coVerify(exactly = 1) {
            contactsRepository.updateDisplayName("Tien Son")
        }
    }

    @Test
    fun `blank name input is blocked before hitting the repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.startEditName()
        viewModel.onNameInputChange("   ")
        viewModel.saveDisplayName()

        assertTrue(viewModel.uiState.value.editingName)
        coVerify(exactly = 0) { contactsRepository.updateDisplayName(any()) }
    }

    @Test
    fun `save failure surfaces saveError and keeps the editor open`() = runTest {
        coEvery { contactsRepository.updateDisplayName(any()) } returns
            Result.Failure(ApiError.Network("down"))

        val viewModel = createViewModel()
        viewModel.startEditName()
        viewModel.onNameInputChange("New name")
        viewModel.saveDisplayName()

        val state = viewModel.uiState.value
        assertEquals(ApiError.Network("down"), state.saveError)
        assertTrue(state.editingName)
        assertFalse(state.saving)
    }

    @Test
    fun `logout delegates to the auth repository — nav reacts globally`() = runTest {
        coEvery { authRepository.logout() } returns Result.Success(Unit)

        val viewModel = createViewModel()
        viewModel.logout()

        coVerify(exactly = 1) { authRepository.logout() }
    }

    private fun ProfileViewModel.openAndFillPassword(
        old: String = "oldpass123",
        new: String = "newpass456",
        confirm: String = new,
    ) {
        openPasswordDialog()
        onOldPasswordChange(old)
        onNewPasswordChange(new)
        onConfirmPasswordChange(confirm)
    }

    @Test
    fun `password change success closes the dialog and raises passwordChanged`() = runTest {
        coEvery { authRepository.changePassword(any(), any()) } returns Result.Success(Unit)

        val viewModel = createViewModel()
        viewModel.openAndFillPassword()
        viewModel.submitPasswordChange()

        val state = viewModel.uiState.value
        assertFalse(state.showPasswordDialog)
        assertTrue(state.passwordChanged)
        assertFalse(state.passwordSaving)
        assertNull(state.passwordError)
        coVerify(exactly = 1) { authRepository.changePassword("oldpass123", "newpass456") }
    }

    @Test
    fun `mismatched confirm is blocked locally before the repository`() = runTest {
        val viewModel = createViewModel()
        viewModel.openAndFillPassword(confirm = "different1")
        viewModel.submitPasswordChange()

        val state = viewModel.uiState.value
        assertTrue(state.confirmMismatch)
        assertTrue(state.showPasswordDialog)
        coVerify(exactly = 0) { authRepository.changePassword(any(), any()) }
    }

    @Test
    fun `short new password and empty old are flagged per field`() = runTest {
        val viewModel = createViewModel()
        viewModel.openAndFillPassword(old = "", new = "short")
        viewModel.submitPasswordChange()

        val state = viewModel.uiState.value
        assertTrue(state.oldPasswordError)
        assertTrue(state.newPasswordError)
        coVerify(exactly = 0) { authRepository.changePassword(any(), any()) }
    }

    @Test
    fun `server failure keeps the dialog open with the error`() = runTest {
        coEvery { authRepository.changePassword(any(), any()) } returns
            Result.Failure(ApiError.InvalidCredentials)

        val viewModel = createViewModel()
        viewModel.openAndFillPassword()
        viewModel.submitPasswordChange()

        val state = viewModel.uiState.value
        assertTrue(state.showPasswordDialog)
        assertEquals(ApiError.InvalidCredentials, state.passwordError)
        assertFalse(state.passwordSaving)
    }
}
