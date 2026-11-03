package com.nexachat.feature.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import com.nexachat.core.ui.components.EmptyState
import com.nexachat.core.ui.components.ErrorState
import com.nexachat.core.ui.components.LoadingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Contact list + add-by-phone + contact detail (plan §12.1 feature/contacts).
 * One-shot [ContactsViewModel.Event]s drive navigation; everything else
 * renders from [ContactsViewModel.UiState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ContactsViewModel.Event.OpenConversation ->
                    onOpenConversation(event.conversationId)
            }
        }
    }
    // Dismiss the add sheet shortly after a successful add (the "added"
    // confirmation flashes first); resetAddFlow restores it for next time.
    LaunchedEffect(state.addDone) {
        if (state.addDone) {
            delay(ADD_DONE_DISMISS_MS)
            showAddSheet = false
            viewModel.resetAddFlow()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search_title),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_add_contact),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.loading && !state.contactsLoaded -> LoadingState()
                state.error != null && state.contacts.isEmpty() -> ErrorState(
                    message = contactErrorMessage(state.error!!),
                    onRetry = viewModel::loadContacts,
                )
                state.contacts.isEmpty() -> EmptyState(
                    message = stringResource(R.string.contacts_empty),
                    icon = Icons.Outlined.Person,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.contacts, key = { it.userId }) { contact ->
                        UserRow(
                            title = contact.primaryLabel,
                            subtitle = contact.phone,
                            onClick = { viewModel.openContactDetail(contact) },
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = {
            showAddSheet = false
            viewModel.resetAddFlow()
        }) {
            AddByPhoneContent(
                state = state,
                onPhoneChange = viewModel::onAddPhoneChange,
                onFind = viewModel::findUserForAdd,
                onAdd = viewModel::confirmAdd,
            )
        }
    }

    if (state.detailUser != null) {
        ModalBottomSheet(onDismissRequest = viewModel::closeDetail) {
            ContactDetailContent(
                state = state,
                onAliasChange = viewModel::onAliasChange,
                onSaveAlias = viewModel::saveAlias,
                onRemove = viewModel::removeSelectedContact,
                onAddContact = viewModel::addDetailContact,
                onMessage = viewModel::messageSelected,
            )
        }
    }
}

private const val ADD_DONE_DISMISS_MS = 900L
