package io.itermux.app.desktop

import io.itermux.app.tr
import io.itermux.app.trf
import io.itermux.desktop.PluginManifest
import io.itermux.ssh.DesktopPluginInstaller
import io.itermux.ssh.RemoteHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Puts the pinned desktop plugin on a host, saying each step to [onLine] in the user's language. */
internal class DesktopPluginSetup(private val manifest: PluginManifest, private val onLine: (String) -> Unit) {
    /** Installs or upgrades the plugin for [target]; null once it is in place, else why not. */
    suspend fun install(remote: RemoteHost, target: String, build: PluginManifest.Target): String? {
        onLine(trf("安装桌面插件 %s（%s）", manifest.version, target))
        val openh264 = PluginManifest.openh264File(target, manifest.openh264)?.let { "${PluginManifest.OPENH264_BASE}/$it" }
        val installer = DesktopPluginInstaller(remote, fetch = ::download, onStep = { step ->
            onLine(when (step) {
                DesktopPluginInstaller.Step.HOST_DOWNLOAD -> tr("主机直接下载…")
                DesktopPluginInstaller.Step.PHONE_DOWNLOAD -> tr("主机下载失败，改为手机下载后上传…")
                DesktopPluginInstaller.Step.UPLOAD -> tr("上传到主机…")
                DesktopPluginInstaller.Step.VERIFY -> tr("校验 SHA-256…")
                DesktopPluginInstaller.Step.ENCODER_HOST -> tr("获取 Cisco OpenH264 编码器…")
                DesktopPluginInstaller.Step.ENCODER_PHONE -> tr("主机获取编码器失败，改为手机下载后上传…")
                DesktopPluginInstaller.Step.REGISTER -> tr("注册插件…")
                DesktopPluginInstaller.Step.DONE -> tr("安装完成")
            })
        }, onLine = { if (!it.startsWith("__ROAMTTY_")) onLine(it) })
        return when (val result = installer.install(DesktopPluginInstaller.Build(manifest.version, manifest.url(build), build.sha256, openh264))) {
            DesktopPluginInstaller.Result.Installed -> null
            is DesktopPluginInstaller.Result.Failed -> when (result.reason) {
                DesktopPluginInstaller.Reason.DOWNLOAD -> tr("下载桌面插件失败")
                DesktopPluginInstaller.Reason.CHECKSUM -> tr("桌面插件校验失败")
                DesktopPluginInstaller.Reason.ENCODER -> tr("下载 OpenH264 编码器失败")
                DesktopPluginInstaller.Reason.HOST -> tr("无法在主机上写入插件")
            }
        }
    }

    /** Loads the macOS agent again after an upgrade that raced launchd unloaded it; true once it runs. */
    suspend fun registerAgent(remote: RemoteHost): Boolean =
        DesktopPluginInstaller(remote, fetch = ::download, onLine = onLine).registerAgent()

    /** The phone's own download, for a host that cannot reach the release; follows redirects across hosts. */
    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        var current = URL(url)
        repeat(5) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            try {
                when (c.responseCode) {
                    in 300..399 -> current = URL(current, c.getHeaderField("Location") ?: error("redirect"))
                    in 200..299 -> return@withContext c.inputStream.use { it.readBytes() }
                    else -> error("HTTP ${c.responseCode}")
                }
            } finally { c.disconnect() }
        }
        error("too many redirects")
    }
}
