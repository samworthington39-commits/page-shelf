package com.example.bookshelf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookshelf.ui.ConnectionStatus
import com.example.bookshelf.ui.SettingsViewModel
import com.example.bookshelf.ui.glass.GlassButton
import com.example.bookshelf.ui.glass.GlassSurface
import com.example.bookshelf.ui.glass.GlassTextField
import com.example.bookshelf.ui.glass.GlassTopBar
import com.example.bookshelf.ui.glass.LiquidBackdrop
import com.example.bookshelf.ui.glass.StaggeredReveal

@Composable
fun ServerSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: (() -> Unit)?,
    onConnected: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.status) {
        if (state.status is ConnectionStatus.Success) onConnected()
    }
    LiquidBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassTopBar(
                title = if (state.configured) "服务器设置" else "连接服务器",
                subtitle = "连接你的私人书库",
                onBack = onBack,
            )
            StaggeredReveal(index = 0) {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    shadow = true,
                ) {
                    Column(
                        Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "页架",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "连接你的私人书库，然后专心阅读。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        GlassTextField(
                            value = state.address,
                            onValueChange = viewModel::setAddress,
                            label = "服务器地址",
                            placeholder = "192.168.1.10:8080",
                            supportingText = "支持 HTTP、HTTPS、IP、域名和端口",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "服务器地址" },
                        )
                        GlassTextField(
                            value = state.password,
                            onValueChange = viewModel::setPassword,
                            label = "管理密码",
                            placeholder = if (state.hasActiveSession) "当前登录有效，留空可继续使用" else "请输入管理密码",
                            supportingText = "密码只用于登录，不会长期保存在手机中",
                            singleLine = true,
                            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = viewModel::togglePasswordVisibility) {
                                    Text(if (state.passwordVisible) "隐藏" else "显示")
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (state.canConnect) viewModel.connect() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "管理密码" },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("同步阅读进度", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (state.progressSyncEnabled) "通过服务器在不同设备间同步" else "关闭后阅读与朗读进度仅保存在本机",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(
                                checked = state.progressSyncEnabled,
                                onCheckedChange = viewModel::setProgressSyncEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.semantics { contentDescription = "同步阅读进度" },
                            )
                        }
                        GlassButton(
                            onClick = viewModel::connect,
                            enabled = state.canConnect && state.status !is ConnectionStatus.Testing,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) {
                            if (state.status is ConnectionStatus.Testing) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.height(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else Text("连接服务器")
                        }
                        when (val status = state.status) {
                            ConnectionStatus.Idle -> Unit
                            ConnectionStatus.Testing -> Text(
                                "正在检查地址并验证密码…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                            is ConnectionStatus.Success -> Text(
                                "连接成功 · API ${status.apiVersion}",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                            is ConnectionStatus.Error -> Text(
                                status.message,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
