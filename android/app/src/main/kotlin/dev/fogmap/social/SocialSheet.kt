package dev.fogmap.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fogmap.data.api.LeaderboardScope
import dev.fogmap.data.api.SocialRepository
import dev.fogmap.data.api.SocialSnapshot
import kotlinx.coroutines.launch

/** Площадь в тех же единицах, что и бейдж на карте. */
private fun formatArea(areaM2: Long): String =
    if (areaM2 < 1_000_000) "%,d м²".format(areaM2) else "%.2f км²".format(areaM2 / 1_000_000.0)

@Composable
fun SocialSheet(
    repository: SocialRepository,
    onDeleteAccount: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    var scope by remember { mutableStateOf(LeaderboardScope.GLOBAL) }
    var snapshot by remember { mutableStateOf<SocialSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var friendName by remember { mutableStateOf("") }
    var reloadKey by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(scope, reloadKey) {
        error = null
        try {
            snapshot = repository.load(scope)
        } catch (e: Exception) {
            error = e.message ?: e::class.java.simpleName
        }
    }

    fun act(action: suspend () -> Unit) {
        coroutineScope.launch {
            try {
                action()
                reloadKey++
            } catch (e: Exception) {
                error = e.message ?: e::class.java.simpleName
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Друзья и рейтинг", fontWeight = FontWeight.Bold)
                        TextButton(onClick = onDismiss) { Text("Закрыть") }
                    }
                    error?.let { Text(it) }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = friendName,
                            onValueChange = { friendName = it },
                            label = { Text("Имя друга") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = friendName.isNotBlank(),
                            onClick = {
                                val name = friendName.trim()
                                friendName = ""
                                act { repository.requestFriend(name) }
                            },
                        ) {
                            Text("Позвать")
                        }
                    }
                }

                val current = snapshot
                if (current != null) {
                    if (current.incoming.isNotEmpty()) {
                        item { Section("Заявки") }
                        items(current.incoming, key = { it.userId }) { request ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(request.username)
                                Button(onClick = { act { repository.acceptFriend(request.username) } }) {
                                    Text("Принять")
                                }
                            }
                        }
                    }

                    item { Section("Друзья") }
                    if (current.friends.isEmpty()) {
                        item { Text("Пока никого") }
                    } else {
                        items(current.friends, key = { it.userId }) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(it.username)
                                Text(formatArea(it.areaM2))
                            }
                        }
                    }

                    item {
                        Section("Рейтинг")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = scope == LeaderboardScope.GLOBAL,
                                onClick = { scope = LeaderboardScope.GLOBAL },
                                label = { Text("Все") },
                            )
                            FilterChip(
                                selected = scope == LeaderboardScope.FRIENDS,
                                onClick = { scope = LeaderboardScope.FRIENDS },
                                label = { Text("Друзья") },
                            )
                        }
                    }
                    items(current.leaderboard, key = { it.userId }) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("${it.rank}. ${it.username}")
                            Text(formatArea(it.areaM2))
                        }
                    }

                    item { Section("Достижения") }
                    if (current.achievements.isEmpty()) {
                        item { Text("Пока ничего не открыто") }
                    } else {
                        items(current.achievements, key = { it.code }) { Text(it.title) }
                    }

                    item {
                        Section("Аккаунт")
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Удалить аккаунт и все данные")
                        }
                    }
                } else if (error == null) {
                    item { Text("Загрузка…") }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteAccountDialog(
            onConfirm = {
                confirmDelete = false
                coroutineScope.launch {
                    try {
                        onDeleteAccount()
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message ?: e::class.java.simpleName
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Удаление необратимо, поэтому спрашиваем отдельно и прямым текстом, что именно исчезнет. */
@Composable
private fun DeleteAccountDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить аккаунт?") },
        text = {
            Text(
                "Исчезнут аккаунт, вся открытая карта, статистика, друзья и достижения — " +
                    "и на сервере, и на этом устройстве. Отменить это нельзя.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = 12.dp)) {
        HorizontalDivider()
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }
}
