package io.itermux.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.itermux.app.R
import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.ssh.ForwardingProhibitedException
import io.itermux.ssh.LoopbackUrl
import io.itermux.ssh.PortTunnel
import io.itermux.ssh.RemoteSocket
import io.itermux.ssh.SshUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PortStatus { CHECKING, READY, NOT_LISTENING, OFFLINE, PROHIBITED }

/**
 * The content of [PortPreviewActivity]: a page a server serves on its own loopback (a dev server's `http://localhost:5173/`), shown on the phone through a
 * tunnel over [open]: the server's SSH login connects to the port for each request. The tunnel lives as long as the
 * page. Only back, the address and refresh: back steps through the page's history, then closes it.
 */
@Composable
internal fun PortPreviewPage(url: LoopbackUrl, hostName: String, open: suspend (host: String, port: Int) -> RemoteSocket,
    onDismiss: () -> Unit) {
    val tunnel = remember(url) { PortTunnel(url.target, url.port, open) }
    // Closing ends JSch channels, which may wait on the network.
    DisposableEffect(tunnel) { onDispose { Thread({ tunnel.close() }, "port-tunnel-close").start() } }
    var status by remember(tunnel) { mutableStateOf(PortStatus.CHECKING) }
    /** Bumped by retry and refresh: check the port again, then reload the page (or load it, if it never was). */
    var attempt by remember(tunnel) { mutableIntStateOf(0) }
    /** Bumped when the page failed to load: check whether the server went away, without loading again. */
    var failures by remember(tunnel) { mutableIntStateOf(0) }
    /** [PortTunnel.dropped] when the page last loaded again: a failure past it was the login's. */
    var droppedSeen by remember(tunnel) { mutableIntStateOf(0) }
    var webView by remember(tunnel) { mutableStateOf<WebView?>(null) }
    var title by remember(tunnel) { mutableStateOf("") }
    var address by remember(tunnel) { mutableStateOf(url.display) }
    var canGoBack by remember(tunnel) { mutableStateOf(false) }
    var loaded by remember(tunnel) { mutableStateOf(false) }

    suspend fun check(): PortStatus = try { if (tunnel.listening()) PortStatus.READY else PortStatus.NOT_LISTENING }
        catch (e: CancellationException) { throw e }
        catch (_: SshUnavailableException) { PortStatus.OFFLINE }
        catch (_: ForwardingProhibitedException) { PortStatus.PROHIBITED }

    // WebView and the page's state belong to the main thread, whatever dispatcher runs effects.
    LaunchedEffect(tunnel, attempt) {
        withContext(Dispatchers.Main.immediate) {
            status = PortStatus.CHECKING
            status = check()
            // The first load happens as the page's WebView is made, once there is something to show.
            if (status == PortStatus.READY && attempt > 0) { droppedSeen = tunnel.dropped; webView?.reload() }
        }
    }
    LaunchedEffect(failures) {
        if (failures == 0) return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            val found = check()
            // A page that failed on a login that is back by now loads again; one that failed with the login up is the
            // page's own failure, which it shows, and reloading it would only repeat it.
            if (found != PortStatus.READY) status = found
            else if (tunnel.dropped > droppedSeen) { droppedSeen = tunnel.dropped; webView?.reload() }
        }
    }

    val back = {
        val view = webView
        if (status == PortStatus.READY && view != null && view.canGoBack()) view.goBack() else onDismiss()
    }
    BackHandler(onBack = back)
        Surface(Modifier.fillMaxSize().testTag("port-preview"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolbarIcon(R.drawable.ic_chevron_left, tr(if (canGoBack && status == PortStatus.READY) "上一页" else "关闭预览"),
                        Modifier.testTag("port-preview-back"), onClick = back)
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("port-preview-title"))
                        Text("$hostName · $address", style = if (title.isBlank()) MaterialTheme.typography.titleSmall
                            else MaterialTheme.typography.bodySmall, color = if (title.isBlank()) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("port-preview-address"))
                    }
                    ToolbarIcon(R.drawable.ic_refresh, tr("刷新"), Modifier.testTag("port-preview-refresh")) { attempt++ }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    // Made only once the port answers: an empty WebView paints black over the whole window.
                    if (status == PortStatus.READY || loaded) AndroidView(factory = { context ->
                        @SuppressLint("SetJavaScriptEnabled") // Dev servers are apps; their pages need scripts to show anything.
                        val view = WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { show(url) }
                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    show(url)
                                    canGoBack = view.canGoBack()
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) failures++
                                }
                                private fun show(page: String?) {
                                    if (page == null || page == "about:blank") return
                                    address = LoopbackUrl.fromTunnel(page, tunnel.localPort, url.port, url.host)?.display ?: page
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView, value: String?) {
                                    // Pages without a <title> report their address, which the second line already shows.
                                    title = value?.takeUnless { it.startsWith("http://") || it.startsWith("localhost:") || it.startsWith("127.") }.orEmpty()
                                }
                            }
                        }
                        webView = view
                        loaded = true
                        view.loadUrl(url.through(tunnel.localPort))
                        view
                    }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize().testTag("port-preview-web"))
                    if (status != PortStatus.READY) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        PortProblem(status, hostName, url.port) { attempt++ }
                    }
                }
            }
        }
}

@Composable
private fun PortProblem(status: PortStatus, hostName: String, port: Int, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.Page), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (status == PortStatus.CHECKING) { CircularProgressIndicator(Modifier.testTag("port-preview-checking")); return@Column }
        Text(when (status) {
                PortStatus.NOT_LISTENING -> trf("%s 上 :%d 没有在监听", hostName, port)
                PortStatus.PROHIBITED -> trf("%s 不允许端口转发", hostName)
                else -> tr("SSH 连接未就绪")
            },
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("port-preview-problem"))
        Spacer(Modifier.height(Spacing.Tight))
        Text(tr(when (status) {
                PortStatus.NOT_LISTENING -> "启动服务后点重试。"
                PortStatus.PROHIBITED -> "服务器的 sshd 关闭了 TCP 转发（AllowTcpForwarding），开启后点重试。"
                else -> "连接恢复后点重试。"
            }),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.Section))
        Button(onClick = onRetry, modifier = Modifier.testTag("port-preview-retry")) { Text(tr("重试")) }
    }
}
