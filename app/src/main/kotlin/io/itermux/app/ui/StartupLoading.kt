package io.itermux.app.ui

import android.graphics.drawable.Animatable
import android.widget.ImageView
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
import androidx.compose.ui.viewinterop.AndroidView
import io.itermux.app.R
import io.itermux.app.tr

/** Same small mark as the system splash, shown only while the first local query is pending. */
@Composable
internal fun StartupLoading() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context -> ImageView(context).apply {
                setImageResource(R.drawable.startup_animated)
                contentDescription = tr("正在加载…")
                (drawable as? Animatable)?.start()
            } },
            onRelease = { (it.drawable as? Animatable)?.stop() },
            modifier = Modifier.size(288.dp).testTag("startup-loading")
        )
    }
}
