package dev.youtubeaa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.youtubeaa.auth.AuthManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = AuthManager(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthScreen(authManager)
                }
            }
        }
    }
}

@Composable
fun AuthScreen(authManager: AuthManager) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AuthState>(AuthState.Checking) }

    LaunchedEffect(Unit) {
        state = if (authManager.isLoggedIn()) AuthState.LoggedIn else AuthState.LoggedOut
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val s = state) {
            AuthState.Checking -> CircularProgressIndicator()

            AuthState.LoggedIn -> {
                Text("Аккаунт подключён", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Откройте Android Auto — приложение появится в разделе медиа.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        authManager.logout()
                        state = AuthState.LoggedOut
                    }
                }) { Text("Выйти из аккаунта") }
            }

            AuthState.LoggedOut -> {
                Text("YouTube для Android Auto", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Button(onClick = {
                    scope.launch {
                        state = AuthState.Loading
                        try {
                            val dc = authManager.requestDeviceCode()
                            state = AuthState.AwaitingCode(
                                userCode = dc.userCode,
                                url = dc.verificationUrl,
                                interval = dc.interval,
                                deviceCode = dc.deviceCode
                            )

                            // Poll until user approves or timeout
                            repeat(60) {
                                delay(dc.interval * 1000L)
                                val token = authManager.pollForToken(dc.deviceCode)
                                if (token.accessToken != null && token.refreshToken != null) {
                                    authManager.saveTokens(token.accessToken, token.refreshToken, token.expiresIn ?: 3600)
                                    state = AuthState.LoggedIn
                                    return@repeat
                                }
                                if (token.error != null && token.error != "authorization_pending") {
                                    state = AuthState.Error("Ошибка: ${token.error}")
                                    return@repeat
                                }
                            }
                            if (state !is AuthState.LoggedIn) state = AuthState.Error("Время ожидания вышло")
                        } catch (e: Exception) {
                            state = AuthState.Error(e.message ?: "Неизвестная ошибка")
                        }
                    }
                }) { Text("Войти через Google") }
            }

            AuthState.Loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Получение кода...")
            }

            is AuthState.AwaitingCode -> {
                Text("Перейдите на:", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    s.url,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(24.dp))
                Text("и введите код:", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    s.userCode,
                    fontSize = 48.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 8.sp
                )
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Ожидание подтверждения...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is AuthState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { state = AuthState.LoggedOut }) { Text("Попробовать снова") }
            }
        }
    }
}

sealed class AuthState {
    object Checking : AuthState()
    object LoggedIn : AuthState()
    object LoggedOut : AuthState()
    object Loading : AuthState()
    data class AwaitingCode(val userCode: String, val url: String, val interval: Int, val deviceCode: String) : AuthState()
    data class Error(val message: String) : AuthState()
}
