package io.itermux.app.ui

import io.itermux.app.tr
import io.itermux.app.trf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.itermux.app.login.PendingPrompt
import io.itermux.ssh.Prompt

@Composable internal fun PromptDialog(request: PendingPrompt) {
    val prompt = request.prompt
    var answers by remember(request) { mutableStateOf<List<String>>(if (prompt is Prompt.Keyboard) List(prompt.labels.size) { "" } else emptyList()) }
    AlertDialog(onDismissRequest = { request.answer.complete(null) }, title = { Text(if (prompt is Prompt.VerifyHost) tr("核对主机指纹") else tr("交互式认证")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (prompt) {
                is Prompt.VerifyHost -> {
                    Text(tr("首次连接此地址。请通过可信渠道核对服务器的 SHA-256 指纹，再决定是否信任。"))
                    SelectionContainer { Text("${prompt.pin.endpoint}\n${prompt.pin.algorithm}\n\n${prompt.pin.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                }
                is Prompt.Keyboard -> {
                    if (prompt.instruction.isNotBlank()) Text(prompt.instruction)
                    prompt.labels.forEachIndexed { i, label -> OutlinedTextField(answers[i], { value -> answers = answers.toMutableList().also { it[i] = value } }, label = { Text(label.ifBlank { trf("认证输入 %d", i + 1) }) }, visualTransformation = if (prompt.echo.getOrElse(i) { false }) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { request.answer.complete(if (prompt is Prompt.VerifyHost) listOf("trust") else answers) }) { Text(if (prompt is Prompt.VerifyHost) tr("信任并连接") else tr("提交")) } }, dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text(tr("取消")) } })
}
