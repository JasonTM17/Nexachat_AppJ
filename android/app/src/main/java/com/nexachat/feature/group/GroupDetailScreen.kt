package com.nexachat.feature.group

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexachat.R
import com.nexachat.data.GroupMember
import com.nexachat.feature.contacts.InitialAvatar
import kotlinx.coroutines.flow.collectLatest

/**
 * Group detail (phase-08 FR-D2/D4): member list with ADMIN badges, add/remove
 * member (ADMIN-only affordances — hidden when the wire `role` is absent),
 * rename dialog, and self-leave. Everything renders from
 * [GroupDetailViewModel.UiState]; [GroupDetailViewModel.Event.ExitToInbox]
 * pops back to the inbox after a successful leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    onExitToInbox: () -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { groupErrorMessage(it) }

    // Image-only system picker for the group avatar (FR-D4, ADMIN-gated).
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: android.net.Uri? ->
        if (uri != null) viewModel.setGroupAvatar(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                GroupDetailViewModel.Event.ExitToInbox -> onExitToInbox()
            }
        }
    }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.title.ifBlank { stringResource(R.string.group_detail_title) },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.iAmAdmin) {
                        IconButton(onClick = viewModel::openRename) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.action_rename_group),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "group-avatar") {
                val avatarDescription = stringResource(R.string.group_change_avatar)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                        InitialAvatar(
                            name = state.title,
                            avatarUrl = state.groupAvatar,
                            size = 72.dp,
                            modifier = Modifier
                                .then(
                                    if (state.iAmAdmin) {
                                        Modifier
                                            .clickable {
                                                avatarPicker.launch(
                                                    PickVisualMediaRequest(
                                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                    ),
                                                )
                                            }
                                            .semantics { contentDescription = avatarDescription }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                        if (state.iAmAdmin) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
            items(state.members, key = { it.userId }) { member ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    MemberRow(
                        member = member,
                        isSelf = member.userId == state.myUserId,
                        canRemove = state.iAmAdmin && member.userId != state.myUserId,
                        onRemove = { viewModel.requestRemoveMember(member) },
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }
            }
            if (state.iAmAdmin) {
                item(key = "add-member") {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = 640.dp)
                                .fillMaxWidth()
                                .clickable(onClick = viewModel::openAddMember)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.action_add_member),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
            item(key = "leave") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(
                        onClick = viewModel::requestLeave,
                        enabled = !state.busy,
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.action_leave_group),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item(key = "loading") {
                if (state.loading || state.busy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }

    // Remove-member confirmation — the VM owns the pending target.
    state.pendingRemove?.let { member ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoveMember,
            title = { Text(stringResource(R.string.action_remove_member)) },
            text = {
                Text(
                    stringResource(
                        R.string.group_remove_member_confirm,
                        member.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemoveMember) {
                    Text(
                        stringResource(R.string.action_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemoveMember) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.confirmLeave) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLeave,
            title = { Text(stringResource(R.string.action_leave_group)) },
            text = { Text(stringResource(R.string.group_leave_confirm)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmLeave) {
                    Text(
                        stringResource(R.string.action_leave_group),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissLeave) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.showRename) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRename,
            title = { Text(stringResource(R.string.action_rename_group)) },
            text = {
                OutlinedTextField(
                    value = state.renameInput,
                    onValueChange = viewModel::onRenameInputChange,
                    label = { Text(stringResource(R.string.group_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmRename,
                    enabled = state.renameInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRename) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.showAddPicker) {
        ModalBottomSheet(onDismissRequest = viewModel::dismissAddMember) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Text(
                    text = stringResource(R.string.action_add_member),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.addableContacts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.contacts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                    ) {
                        items(state.addableContacts, key = { it.userId }) { contact ->
                            SelectableContactRow(
                                name = contact.primaryLabel,
                                phone = contact.phone,
                                selected = contact.userId in state.selectedAddIds,
                                onToggle = { viewModel.toggleAddCandidate(contact.userId) },
                            )
                        }
                    }
                }
                Button(
                    onClick = viewModel::addSelectedMembers,
                    enabled = state.selectedAddIds.isNotEmpty() && !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.action_add_member))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: GroupMember,
    isSelf: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialAvatar(member.displayName, member.avatarUrl)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isSelf) {
                        stringResource(R.string.group_member_self, member.displayName)
                    } else {
                        member.displayName
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (member.isAdmin) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.group_role_admin),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = member.phone.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_remove_member),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
