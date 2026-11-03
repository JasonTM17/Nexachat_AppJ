package com.nexachat.feature.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import com.nexachat.core.ui.components.EmptyState
import com.nexachat.core.ui.components.ErrorState
import com.nexachat.core.ui.components.LoadingState
import kotlinx.coroutines.flow.collectLatest

/**
 * User discovery: one field, auto-detect mode — input starting with '+' or a
 * digit runs the exact phone lookup, anything else the name-prefix search
 * (plan §10.2 `GET /users/search?phone=|q=`). Results open the same detail
 * sheet as ContactsScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSearchScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ContactsViewModel.Event.OpenConversation ->
                    onOpenConversation(event.conversationId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(R.string.search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                trailingIcon = {
                    IconButton(
                        onClick = viewModel::search,
                        enabled = state.query.isNotBlank() && !state.loading,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.action_search),
                        )
                    }
                },
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loading -> LoadingState()
                    state.error != null -> ErrorState(
                        message = contactErrorMessage(state.error!!),
                        onRetry = viewModel::search,
                    )
                    state.searched && state.searchResults.isEmpty() -> EmptyState(
                        message = stringResource(R.string.search_no_results),
                        icon = Icons.Outlined.Search,
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.searchResults, key = { it.id }) { user ->
                            UserRow(
                                title = user.displayName,
                                subtitle = user.phone,
                                onClick = { viewModel.openUserDetail(user) },
                            )
                        }
                    }
                }
            }
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
