package io.itermux.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.ssh.RemoteFileKind
import io.itermux.ssh.remoteFileKind

@Composable
internal fun RemoteFileIcon(name: String, directory: Boolean = false, permissions: String = "",
    modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val spec = remember(name, directory, permissions) { fileIconSpec(name, directory, permissions) }
    val description = tr(kindLabel(remoteFileKind(name, directory, if (permissions.startsWith('l')) "" else permissions)))
    val linkBackground = MaterialTheme.colorScheme.surface
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        when (spec) {
            is FileIconSpec.Folder -> Canvas(Modifier.size(size)) { drawFolder(Color(spec.color)) }
            is FileIconSpec.Badge -> {
                val measurer = rememberTextMeasurer()
                // The label is part of the glyph, so it must not grow with the system font scale.
                val fontSize = with(LocalDensity.current) {
                    (size * when (spec.label.length) { 1 -> 0.5f; 2 -> 0.4f; else -> 0.31f }).toSp()
                }
                val style = TextStyle(color = Color(spec.labelColor), fontSize = fontSize, fontWeight = FontWeight.Bold,
                    letterSpacing = fontSize * -0.04f)
                Canvas(Modifier.size(size)) {
                    val inset = this.size.width * 0.08f
                    drawRoundRect(Color(spec.color), Offset(inset, inset),
                        Size(this.size.width - inset * 2, this.size.height - inset * 2), CornerRadius(this.size.width * 0.18f))
                    val layout = measurer.measure(spec.label, style, softWrap = false, maxLines = 1)
                    drawText(layout, topLeft = Offset((this.size.width - layout.size.width) / 2f,
                        (this.size.height - layout.size.height) / 2f))
                }
            }
            is FileIconSpec.Glyph -> Icon(painterResource(glyphIcon(spec.kind)), contentDescription = null,
                tint = Color(spec.color), modifier = Modifier.size(size))
        }
        if (permissions.startsWith('l')) Canvas(Modifier.size(size)) { drawLinkMark(linkBackground) }
    }
}

private fun DrawScope.drawFolder(color: Color) {
    val w = size.width
    val h = size.height
    val tab = Path().apply {
        moveTo(w * 0.06f, h * 0.27f)
        lineTo(w * 0.06f, h * 0.24f)
        quadraticTo(w * 0.06f, h * 0.16f, w * 0.14f, h * 0.16f)
        lineTo(w * 0.38f, h * 0.16f)
        lineTo(w * 0.48f, h * 0.27f)
        close()
    }
    drawPath(tab, color.copy(alpha = 0.75f))
    drawRoundRect(color, Offset(w * 0.06f, h * 0.27f), Size(w * 0.88f, h * 0.57f), CornerRadius(w * 0.08f))
}

/** Small arrow badge, like the symlink decoration in editor file trees. */
private fun DrawScope.drawLinkMark(background: Color) {
    val r = size.width * 0.21f
    val center = Offset(size.width - r, size.height - r)
    drawCircle(background, r + size.width * 0.05f, center)
    drawCircle(Color(0xFF42A5F5), r, center)
    val arrow = Path().apply {
        moveTo(center.x - r * 0.45f, center.y + r * 0.45f)
        lineTo(center.x + r * 0.45f, center.y - r * 0.45f)
        moveTo(center.x - r * 0.1f, center.y - r * 0.45f)
        lineTo(center.x + r * 0.45f, center.y - r * 0.45f)
        lineTo(center.x + r * 0.45f, center.y + r * 0.1f)
    }
    drawPath(arrow, Color.White, style = Stroke(width = size.width * 0.07f, cap = StrokeCap.Round))
}

private fun glyphIcon(kind: RemoteFileKind): Int = when (kind) {
    RemoteFileKind.DIRECTORY -> R.drawable.ic_folder
    RemoteFileKind.TEXT -> R.drawable.ic_file_text
    RemoteFileKind.CODE -> R.drawable.ic_file_code
    RemoteFileKind.CONFIG -> R.drawable.ic_file_config
    RemoteFileKind.IMAGE -> R.drawable.ic_image
    RemoteFileKind.AUDIO -> R.drawable.ic_file_audio
    RemoteFileKind.VIDEO -> R.drawable.ic_file_video
    RemoteFileKind.ARCHIVE -> R.drawable.ic_file_archive
    RemoteFileKind.PDF -> R.drawable.ic_file_pdf
    RemoteFileKind.DOCUMENT -> R.drawable.ic_file_document
    RemoteFileKind.SPREADSHEET -> R.drawable.ic_file_spreadsheet
    RemoteFileKind.PRESENTATION -> R.drawable.ic_file_presentation
    RemoteFileKind.EXECUTABLE -> R.drawable.ic_file_executable
    RemoteFileKind.FILE -> R.drawable.ic_file
}

private fun kindLabel(kind: RemoteFileKind): String = when (kind) {
    RemoteFileKind.DIRECTORY -> "目录"
    RemoteFileKind.TEXT -> "文本文件"
    RemoteFileKind.CODE -> "代码文件"
    RemoteFileKind.CONFIG -> "配置文件"
    RemoteFileKind.IMAGE -> "图片"
    RemoteFileKind.AUDIO -> "音频"
    RemoteFileKind.VIDEO -> "视频"
    RemoteFileKind.ARCHIVE -> "压缩包"
    RemoteFileKind.PDF -> "PDF"
    RemoteFileKind.DOCUMENT -> "文档"
    RemoteFileKind.SPREADSHEET -> "表格"
    RemoteFileKind.PRESENTATION -> "演示文稿"
    RemoteFileKind.EXECUTABLE -> "可执行文件"
    RemoteFileKind.FILE -> "文件"
}
