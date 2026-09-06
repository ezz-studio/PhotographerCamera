/**
 * DebugConnectDialog — 运行时配置远程调试日志服务器地址。
 *
 * 这样真机调试时不必重新编译 APK：打开相机页右下角「调试日志」→ 填入
 * `http://<服务器IP>:<端口>/log` → 保存。App 的所有日志（DebugLog 已同步
 * 转发给 RemoteLog）即实时上送，开发者在服务端 Web 面板查看。
 *
 * 默认关闭（assets/remote_log_endpoint.txt 留空 + SharedPreferences 为空），
 * 不影响任何现有功能；地址持久化到 SharedPreferences，重启后自动重连。
 */
package com.photographercamera.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photographercamera.core.debug.RemoteLog

@Composable
fun DebugConnectDialog(onDismiss: () -> Unit) {
    val context: Context = androidx.compose.ui.platform.LocalContext.current
    var url by remember { mutableStateOf(RemoteLog.storedEndpoint(context)) }
    var status by remember { mutableStateOf(RemoteLog.status()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                RemoteLog.saveEndpoint(context, url)
                status = RemoteLog.status()
            }) { Text("保存并连接") }
        },
        dismissButton = {
            TextButton(onClick = {
                RemoteLog.saveEndpoint(context, "")
                status = RemoteLog.status()
                url = ""
            }) { Text("断开") }
        },
        title = { Text("远程调试日志") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "把 App 调试日志实时发送到服务器 Web 面板。不填则关闭，不影响任何功能。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器地址  http://IP:端口/log") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "状态: $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
