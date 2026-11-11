package io.itermux.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.code.Languages
import io.itermux.code.SyntaxHighlighter
import io.itermux.app.ui.*
import io.itermux.ssh.RemoteEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders the file browser rows and code viewer from fixed data, without an SSH server. */
@RunWith(AndroidJUnit4::class)
class FileBrowserDesignTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(tag: String, name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val entries = listOf(
        RemoteEntry(".git", true, 0, 1790800000, "drwxr-xr-x"),
        RemoteEntry("src", true, 0, 1790800000, "drwxr-xr-x"),
        RemoteEntry("node_modules", true, 0, 1790700000, "drwxr-xr-x"),
        RemoteEntry("docs", true, 0, 1790600000, "drwxr-xr-x"),
        RemoteEntry("current", false, 0, 1790600000, "lrwxrwxrwx"),
        RemoteEntry("main.kt", false, 4820, 1790800000, "-rw-r--r--"),
        RemoteEntry("server.ts", false, 12_400, 1790800000, "-rw-r--r--"),
        RemoteEntry("deploy.py", false, 2048, 1790800000, "-rwxr-xr-x"),
        RemoteEntry("package.json", false, 812, 1790800000, "-rw-r--r--"),
        RemoteEntry("docker-compose.yml", false, 640, 1790800000, "-rw-r--r--"),
        RemoteEntry("README.md", false, 3200, 1790800000, "-rw-r--r--"),
        RemoteEntry("install.sh", false, 900, 1790800000, "-rwxr-xr-x"),
        RemoteEntry("main.go", false, 1500, 1790800000, "-rw-r--r--"),
        RemoteEntry("lib.rs", false, 1500, 1790800000, "-rw-r--r--"),
        RemoteEntry("photo.png", false, 2_400_000, 1790800000, "-rw-r--r--"),
        RemoteEntry("backup.tar.gz", false, 98_000_000, 1790800000, "-rw-r--r--"),
        RemoteEntry(".env", false, 120, 1790800000, "-rw-------")
    )

    private fun rows(colors: ColorScheme, name: String) {
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize().testTag("rows")) {
                    Column {
                        FileBrowserPathBar("/home/dev/project", "dev-server", true, {}, Modifier.padding(16.dp))
                        entries.forEach { RemoteFileRow(it, {}, {}) }
                    }
                }
            }
        }
        entries.forEach { compose.onNodeWithTag("files-entry-${it.name}").assertExists() }
        capture("rows", name)
    }

    @Test fun rowsDark() = rows(DarkColors, "file-rows-dark")
    @Test fun rowsLight() = rows(LightColors, "file-rows-light")

    private val kotlin = """
        package io.itermux.demo

        import kotlinx.coroutines.*

        /** Greets every host in parallel. */
        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun greet(hosts: List<String>, retries: Int = 3): Map<String, Boolean> = coroutineScope {
            val results = hosts.map { host ->
                async { host to runCatching { connect(host, port = 22) }.isSuccess }
            }
            println("checked ${'$'}{hosts.size} hosts") // log progress
            results.awaitAll().toMap()
        }

        data class Host(val name: String, val port: Int = 0x16, val tags: Set<String> = emptySet())
    """.trimIndent()

    private val markdown = """
        # iTermux

        A **mobile** SSH client with `tmux` support. See [docs](https://example.com).

        - [x] Syntax highlighting
        - [ ] Markdown preview

        ```bash
        ./gradlew assembleDebug  # build
        export HOST=${'$'}USER@server
        ```
        > Tip: long-press a file for actions.
    """.trimIndent()

    private val readme = """
        # iTermux

        A **mobile** SSH client with `tmux` support and [docs](https://example.com).

        ## Features

        - [x] Syntax highlighting
        - [ ] Markdown preview
          - nested item with *emphasis*
        1. Connect
        2. Browse files

        > **Tip:** long-press a file for actions.

        ```bash
        ./gradlew assembleDebug  # build
        export HOST=${'$'}USER@server
        ```

        | Key | Action | Default |
        |:----|:------:|-------:|
        | Tab | Complete | on |
        | Ctrl+C | Interrupt | — |

        ---

        ![screenshot](https://example.com/shot.png)
    """.trimIndent()

    @Test fun markdownRenderedDark() = markdown(DarkColors, "markdown-rendered-dark")
    @Test fun markdownRenderedLight() = markdown(LightColors, "markdown-rendered-light")

    private fun markdown(colors: ColorScheme, name: String) {
        val blocks = io.itermux.code.MarkdownParser.parse(readme)
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize().testTag("md")) { MarkdownView(blocks, null, Modifier.fillMaxSize()) }
            }
        }
        compose.onNodeWithText("Features").assertIsDisplayed()
        capture("md", name)
    }

    private fun transfer(status: io.itermux.app.session.TransferStatus, name: String, colors: ColorScheme = DarkColors) {
        compose.setContent { MaterialTheme(colorScheme = colors, shapes = AppShapes) { TransferStatusDialog(status, {}, {}) } }
        compose.onNodeWithTag("transfer-dialog").assertIsDisplayed()
        capture("transfer-dialog", name)
    }

    @Test fun downloadResultsDark() = transfer(io.itermux.app.session.TransferStatus("", 0, 0, result = "", downloads = listOf(
        io.itermux.app.session.DownloadItem("/srv/app/release.tar.gz", 921_600, 921_600, io.itermux.app.session.DownloadPhase.COMPLETE,
            localPath = "/storage/emulated/0/Download/iTermux/release.tar.gz"),
        io.itermux.app.session.DownloadItem("/srv/app/README.md", 22_630, 22_630, io.itermux.app.session.DownloadPhase.COMPLETE,
            localPath = "/storage/emulated/0/Download/iTermux/README.md"),
        io.itermux.app.session.DownloadItem("/srv/app/server.log", -1, 4096, io.itermux.app.session.DownloadPhase.FAILED,
            error = "Permission denied"))), "transfer-results-dark")

    @Test fun downloadResultsLight() = transfer(io.itermux.app.session.TransferStatus("", 0, 0, result = "", downloads = listOf(
        io.itermux.app.session.DownloadItem("/srv/app/release.tar.gz", 921_600, 921_600, io.itermux.app.session.DownloadPhase.COMPLETE,
            localPath = "/storage/emulated/0/Download/iTermux/release.tar.gz"),
        io.itermux.app.session.DownloadItem("/srv/app/server.log", -1, 4096, io.itermux.app.session.DownloadPhase.FAILED,
            error = "Permission denied"))), "transfer-results-light", LightColors)

    @Test fun downloadProgressDark() = transfer(io.itermux.app.session.TransferStatus("", 512 * 1024L, 1024 * 1024L, running = true,
        downloads = listOf(io.itermux.app.session.DownloadItem("/a/README.md", 200, 200, io.itermux.app.session.DownloadPhase.COMPLETE),
            io.itermux.app.session.DownloadItem("/a/quarterly-report.pdf", 1024 * 1024L, 512 * 1024L, io.itermux.app.session.DownloadPhase.RUNNING),
            io.itermux.app.session.DownloadItem("/a/photo.png", 4 * 1024 * 1024L)), bytesPerSecond = 256 * 1024.0), "transfer-progress-dark")

    private fun code(source: String, fileName: String, colors: ColorScheme, wrap: Boolean, name: String, query: String = "") {
        val document = SyntaxHighlighter.highlight(source, Languages.detect(fileName))
        val matches = findCodeMatches(document, query)
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize().testTag("code")) {
                    CodeViewer(document, wrap, 13f, rememberLazyListState(), rememberScrollState(), matches, 0, Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithTag("file-preview-text").assertIsDisplayed()
        capture("code", name)
    }

    @Test fun yamlLight() = code("services:\n  web:\n    image: nginx:1.27  # proxy\n    ports: [\"8080:80\"]\n    restart: always\n", "compose.yml", LightColors, false, "code-yaml-light", query = "web")
    @Test fun kotlinDark() = code(kotlin, "Main.kt", DarkColors, false, "code-kotlin-dark", query = "host")
    @Test fun kotlinLightWrapped() = code(kotlin, "Main.kt", LightColors, true, "code-kotlin-light-wrap")
    @Test fun markdownDark() = code(markdown, "README.md", DarkColors, true, "code-markdown-dark")
}
