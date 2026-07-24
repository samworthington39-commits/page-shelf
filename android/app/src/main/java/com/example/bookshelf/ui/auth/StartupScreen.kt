package com.example.bookshelf.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bookshelf.ui.StartupState

@Composable
fun StartupScreen(state: StartupState, onRetry: () -> Unit, onEditServer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("页架", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        when (state) {
            StartupState.Checking, StartupState.Connected -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在连接书架…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is StartupState.NeedsLogin -> {
                Text(
                    state.message ?: "需要连接服务器",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRetry) { Text("重试") }
                TextButton(onClick = onEditServer) { Text("修改服务器设置") }
            }
        }
    }
}
