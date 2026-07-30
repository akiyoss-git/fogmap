package dev.fogmap.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.fogmap.data.api.SyncRepository
import kotlinx.coroutines.launch

/**
 * Минимальный экран входа: логин и регистрация в одном окне.
 *
 * Ровно столько, сколько нужно, чтобы получить токен и проверить синхронизацию. Нормальный
 * онбординг — отдельная задача.
 */
@Composable
fun AuthDialog(
    syncRepository: SyncRepository,
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                action()
                onAuthenticated()
            } catch (e: Exception) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вход") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Имя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Почта (только для регистрации)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !busy,
                    onClick = { run { syncRepository.register(username, email, password) } },
                ) {
                    Text("Регистрация")
                }
                Button(
                    enabled = !busy,
                    onClick = { run { syncRepository.login(username, password) } },
                ) {
                    Text("Войти")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
