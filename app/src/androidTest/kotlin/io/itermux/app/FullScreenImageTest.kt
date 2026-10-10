package io.itermux.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.SessionService
import io.itermux.app.session.TerminalConnection
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real SSH/SFTP + full-screen fixture; run only with the isolated loopback server. */
@RunWith(AndroidJUnit4::class)
class FullScreenImageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val serviceRule = ServiceTestRule()

    private fun screen(session: TerminalConnection): String = session.emulator.let {
        it.getSelectedText(0, 0, it.columns, it.rows)
    }

    private suspend fun awaitScreen(session: TerminalConnection, text: String) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { screen(session).contains(text) }) delay(50)
    }

    private suspend fun withFixture(name: String, test: suspend (TerminalConnection) -> Unit) = coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val key = File(context.filesDir, "tmux_client_key")
        val fixture = args.getString("historyFixture")
        val user = args.getString("tmuxUser")
        assumeTrue("Isolated SSH fixture required", key.exists() && fixture != null && user != null)
        val app = context.applicationContext as ItermuxApp
        app.database.hosts().forgetPin("[127.0.0.1]:22230")
        val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
        val host = Host(name = "Full-screen image QA", address = "127.0.0.1", port = 22230,
            username = requireNotNull(user), auth = AuthMethod.KEY.name, mode = SessionMode.TMUX.name)
        val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.TMUX, Credentials(privateKey = key.readBytes())) }
        val prompts = launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
        try {
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            compose.openSession(session.id)
            // exec keeps this fixture in the foreground process group, as a real CLI would be.
            withContext(Dispatchers.Main) { session.write("exec python3 '${requireNotNull(fixture).replace("'", "'\\''")}' $name\r") }
            awaitScreen(session, "CLI_LIVE")
            compose.onNodeWithTag("restore-dock").performClick()
            test(session)
        } finally {
            prompts.cancel()
            withContext(Dispatchers.Main) { session.write("\u0003"); service.close(session.id); service.forget(session.id) }
        }
    }

    @Test fun successiveSingleImageSelections() = runBlocking {
        withFixture("codex") { session ->
            awaitScreen(session, "SUBMITTED=0 NAVKEYS=0")

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "itermux-image-qa.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }))
            val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val paths = mutableListOf<String>()
            try {
                for (number in 1..3) {
                    if (number == 2) {
                        // The old screen-text heuristic no longer recognizes the banner here.
                        assertFalse(withContext(Dispatchers.Main) { screen(session).contains("Codex") })
                        compose.activityRule.scenario.recreate()
                        compose.waitUntil(8000) { compose.onAllNodesWithTag("terminal").fetchSemanticsNodes().isNotEmpty() }
                    }
                    withContext(Dispatchers.Main) { session.transfers.uploadSharedImages(context, session, listOf(uri)) }
                    val complete = withTimeout(15_000) { session.transfers.status.first { !it.running } }
                    assertEquals(complete.result, true, complete.succeeded)
                    paths += complete.imagePaths.single()
                    awaitScreen(session, "IMAGES=$number SUBMITTED=0")
                    withContext(Dispatchers.Main) { session.transfers.dismiss() }
                }
                assertEquals(3, paths.distinct().size)
                compose.waitForIdle()
                delay(250) // Allow the native TerminalView to draw the final attachment row.
                compose.activityRule.scenario.onActivity {
                    val root = it.window.decorView
                    val screenshot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(screenshot))
                    File(context.getExternalFilesDir(null), "full-screen-images.png").outputStream().use { out ->
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    screenshot.recycle()
                }
            } finally {
                resolver.delete(uri, null, null)
                paths.forEach { runCatching { session.deleteRemoteFile(it) } }
            }
        }
    }

    @Test fun fullScreenProgramsRejectShellHistoryCommands() = runBlocking {
        withFixture("grok") { session ->
            awaitScreen(session, "SUBMITTED=0 NAVKEYS=0")
            withContext(Dispatchers.Main) { session.executeHistoryCommand("SHOULD_NOT_SUBMIT") }.join()
            awaitScreen(session, "SUBMITTED=0 NAVKEYS=0")
            assertNotNull(session.inputWarning.value)
        }
    }

    @Test fun multiImageSharePickerAndQueuedUploadsSurviveRecreation() = runBlocking {
        withFixture("grok") { session ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
            val second = withContext(Dispatchers.Main) {
                service.open(session.host, SessionMode.TMUX,
                    Credentials(privateKey = File(context.filesDir, "tmux_client_key").readBytes()))
            }
            val uris = (1..3).map { number ->
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "itermux-share-$number.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    }))
                val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
                context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                uri
            }
            try {
                withTimeout(10_000) { second.state.first { it.phase == Phase.CONNECTED } }
                // Seed the service-owned picker; ActivityScenario cannot track a changed launch intent.
                withContext(Dispatchers.Main) { service.pendingSharedImages.value = uris }
                compose.waitUntil(8000) { compose.onAllNodesWithTag("share-session-${session.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.activityRule.scenario.recreate()
                compose.onNodeWithTag("share-session-${session.id}").assertIsDisplayed().performClick()
                compose.activityRule.scenario.recreate()
                awaitScreen(session, "IMAGES=3 SUBMITTED=0 NAVKEYS=0")
                assertTrue(service.pendingSharedImages.value.isEmpty())
                val remote = withContext(Dispatchers.Main) { session.transfers.status.value.imagePaths }
                remote.forEach { session.deleteRemoteFile(it) }
            } finally {
                uris.forEach { context.contentResolver.delete(it, null, null) }
                withContext(Dispatchers.Main) {
                    second.write("exit\r")
                    service.close(second.id)
                    service.forget(second.id)
                }
            }
        }
    }

    @Test fun closingTmuxEndsItsRemoteSessionAndCard() = runBlocking {
        withFixture("grok") { session ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val service = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
            // A second session on the same tmux server looks at its sessions.
            val shell = withContext(Dispatchers.Main) {
                service.open(session.host, SessionMode.TMUX, Credentials(privateKey = File(context.filesDir, "tmux_client_key").readBytes()))
            }
            val trust = launch { shell.pending.collect { it?.answer?.complete(listOf("trust")) } }
            suspend fun probe() = withContext(Dispatchers.Main) {
                shell.write("tmux has-session -t =${Tmux.sanitizeSessionName(session.remoteSessionName)} 2>/dev/null && echo LIVE_$((6*7)) || echo GONE_$((6*7))\r")
            }
            try {
                withTimeout(20_000) { shell.state.first { it.phase == Phase.CONNECTED } }
                probe()
                awaitScreen(shell, "LIVE_42")
                withContext(Dispatchers.Main) { service.close(session.id) }
                assertTrue(service.sessions.value.none { it.id == session.id })
                withTimeout(10_000) { session.state.first { it.phase == Phase.DISCONNECTED } }
                probe()
                awaitScreen(shell, "GONE_42")
            } finally { trust.cancel(); withContext(Dispatchers.Main) { service.close(shell.id) } }
        }
    }
}
