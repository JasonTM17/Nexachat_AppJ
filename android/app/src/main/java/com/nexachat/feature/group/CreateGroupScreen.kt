package com.nexachat.feature.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Observer
import com.nexachat.R
import com.nexachat.core.ui.CreateGroupListRules
import com.nexachat.core.ui.components.EmptyState
import com.nexachat.core.ui.components.ErrorState
import com.nexachat.core.ui.components.LoadingState
import com.nexachat.feature.contacts.InitialAvatar

/**
 * Create-group screen (phase-08 FR-D1): name field + contact multi-select
 * (checkbox rows: avatar letter, name, phone). The create button stays
 * disabled until [CreateGroupUiState.canCreate]; a successful
 * create emits [CreateGroupEvent.Created] → `chat/{id}`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel(),
) {
    val stateValue = remember(viewModel) {
        androidx.compose.runtime.mutableStateOf(
            viewModel.uiState.value ?: CreateGroupUiState())
    }
    val createEventValue = remember(viewModel) {
        androidx.compose.runtime.mutableStateOf<GroupSingleLiveEvent.Event<CreateGroupEvent>?>(null)
    }
    DisposableEffect(viewModel) {
        val stateObserver = Observer<CreateGroupUiState> { stateValue.value = it }
        val eventObserver = Observer<GroupSingleLiveEvent.Event<CreateGroupEvent>> {
            createEventValue.value = it
        }
        viewModel.uiState.observeForever(stateObserver)
        viewModel.events.observeForever(eventObserver)
        onDispose {
            viewModel.uiState.removeObserver(stateObserver)
            viewModel.events.removeObserver(eventObserver)
        }
    }
    val state by stateValue
    val createEvent by createEventValue
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { groupErrorMessage(it) }
    val showLoadError = CreateGroupListRules.showLoadError(
        state.loading,
        state.contacts.isEmpty(),
        state.error != null,
    )

    LaunchedEffect(createEvent?.sequence) {
        when (val event = createEvent?.consume()) {
            is CreateGroupEvent.Created -> onCreated(event.conversationId)
            else -> Unit
        }
    }
    LaunchedEffect(errorMessage, showLoadError) {
        if (errorMessage != null && !showLoadError) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(R.string.group_name_label)) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                enabled = !state.creating,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loading && !state.contactsLoaded -> LoadingState()
                    showLoadError -> ErrorState(
                        message = errorMessage ?: stringResource(R.string.error_generic),
                        onRetry = viewModel::loadContacts,
                    )
                    state.contacts.isEmpty() -> EmptyState(
                        message = stringResource(R.string.contacts_empty),
                        icon = Icons.Outlined.Person,
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.contacts, key = { it.userId }) { contact ->
                            SelectableContactRow(
                                name = contact.primaryLabel,
                                phone = contact.phone,
                                selected = contact.userId in state.selectedIds,
                                onToggle = { viewModel.toggleMember(contact.userId) },
                                avatarUrl = contact.avatarUrl,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = viewModel::create,
                enabled = state.canCreate,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 48.dp),
            ) {
                if (state.creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.action_create_group))
                }
            }
        }
    }
}

/** Contact row for member selection — whole row toggles the checkbox. */
@Composable
internal fun SelectableContactRow(
    name: String,
    phone: String,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialAvatar(name, avatarUrl)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = phone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}
