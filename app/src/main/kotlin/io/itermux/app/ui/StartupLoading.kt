package io.itermux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import io.itermux.app.tr

/** Same small mark as the system splash, shown only while the first local query is pending. */
@Composable
internal fun StartupLoading() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        ItermuxLogo(
            modifier = Modifier.size(64.dp).testTag("startup-loading").semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
            contentDescription = tr("正在加载…"),
            animated = true,
        )
    }
}
