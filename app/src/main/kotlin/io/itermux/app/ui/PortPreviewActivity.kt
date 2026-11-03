package io.itermux.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import io.itermux.app.appSettings
import io.itermux.ssh.LoopbackUrl
import io.itermux.ssh.RemoteSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a [PortPreviewActivity] shows: a page on a server and the login that reaches it. */
internal class PortPreviewRequest(val url: LoopbackUrl, val hostName: String, val open: suspend (host: String, port: Int) -> RemoteSocket)

/** Shows [url] on [hostName] in the app, through a tunnel over [open] (a session's or the host page's SSH login). */
internal fun openPortPreview(context: Context, url: LoopbackUrl, hostName: String, open: suspend (host: String, port: Int) -> RemoteSocket) {
    val id = UUID.randomUUID().toString()
    PortPreviewActivity.requests[id] = PortPreviewRequest(url, hostName, open)
    context.startActivity(Intent(context, PortPreviewActivity::class.java).putExtra(PortPreviewActivity.EXTRA_REQUEST, id))
}

/**
 * The port preview, in an activity of its own: in a dialog window WebView painted over the dialog's own toolbar, while
 * an ordinary activity window keeps it in its bounds. It keeps itself across rotation, so the page is not reloaded.
 * The request lives in memory only: after the process is gone there is no login to tunnel over, so it just closes.
 */
class PortPreviewActivity : ComponentActivity() {
    private var id: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra(EXTRA_REQUEST)
        val request = id?.let(requests::get) ?: return finish()
        enableEdgeToEdge()
        setContent {
            val theme = appSettings().appTheme()
            val light = theme == AppTheme.LIGHT || (theme == AppTheme.SYSTEM &&
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
            MaterialTheme(colorScheme = if (light) LightColors else DarkColors, shapes = AppShapes, typography = AppTypography) {
                PortPreviewPage(request.url, request.hostName, request.open, onDismiss = ::finish)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) id?.let(requests::remove)
        super.onDestroy()
    }

    internal companion object {
        const val EXTRA_REQUEST = "io.itermux.app.PORT_PREVIEW"
        val requests = ConcurrentHashMap<String, PortPreviewRequest>()
    }
}
