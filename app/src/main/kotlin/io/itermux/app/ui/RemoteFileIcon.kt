package io.itermux.app.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.ssh.RemoteFileKind
import io.itermux.ssh.remoteFileKind

@Composable
internal fun RemoteFileIcon(name: String, directory: Boolean = false, permissions: String = "") {
    val kind = remoteFileKind(name, directory, permissions)
    val (icon, label) = when (kind) {
        RemoteFileKind.DIRECTORY -> R.drawable.ic_folder to "目录"
        RemoteFileKind.TEXT -> R.drawable.ic_file_text to "文本文件"
        RemoteFileKind.CODE -> R.drawable.ic_file_code to "代码文件"
        RemoteFileKind.CONFIG -> R.drawable.ic_file_config to "配置文件"
        RemoteFileKind.IMAGE -> R.drawable.ic_image to "图片"
        RemoteFileKind.AUDIO -> R.drawable.ic_file_audio to "音频"
        RemoteFileKind.VIDEO -> R.drawable.ic_file_video to "视频"
        RemoteFileKind.ARCHIVE -> R.drawable.ic_file_archive to "压缩包"
        RemoteFileKind.PDF -> R.drawable.ic_file_pdf to "PDF"
        RemoteFileKind.DOCUMENT -> R.drawable.ic_file_document to "文档"
        RemoteFileKind.SPREADSHEET -> R.drawable.ic_file_spreadsheet to "表格"
        RemoteFileKind.PRESENTATION -> R.drawable.ic_file_presentation to "演示文稿"
        RemoteFileKind.EXECUTABLE -> R.drawable.ic_file_executable to "可执行文件"
        RemoteFileKind.FILE -> R.drawable.ic_file to "文件"
    }
    Icon(painterResource(icon), tr(label), tint = if (kind == RemoteFileKind.DIRECTORY)
        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}
