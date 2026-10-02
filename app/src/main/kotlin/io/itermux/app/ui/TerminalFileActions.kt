package io.itermux.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.itermux.app.R
import io.itermux.app.tr

/** Shared by the collapsed action row and the expanded terminal dock. */
@Composable
internal fun TerminalFileActions(enabled: Boolean, onAction: (FileAction) -> Unit) {
    FileActionButton(R.drawable.ic_upload, tr("上传文件"), "upload-file", enabled) { onAction(FileAction.UPLOAD) }
    FileActionButton(R.drawable.ic_download, tr("下载文件"), "download-file", enabled) { onAction(FileAction.DOWNLOAD) }
    FileActionButton(R.drawable.ic_image, tr("插入图片"), "select-image", enabled) { onAction(FileAction.SELECT_IMAGE) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionButton(@DrawableRes icon: Int, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag)) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(22.dp))
        }
    }
}
