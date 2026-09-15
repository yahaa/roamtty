package io.itermux.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import io.itermux.app.data.Host
import io.itermux.app.session.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Two-process test driven ONLY by scripts/process-death-test.ps1 on disposable emulators. */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ItermuxApp
    private fun requireStage(stage: String) {
        Assume.assumeTrue(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("lifecycleStage") == stage)
    }
    private fun service(): SessionService = (serviceRule.bindService(Intent(context, SessionService::class.java)) as SessionService.LocalBinder).service
    private suspend fun connected(session: TerminalConnection) {
        val state = withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }
        assertEquals("${session.mode}: ${state.message}", Phase.CONNECTED, state.phase)
    }
    private fun CoroutineScope.answer(session: TerminalConnection, password: String) = launch {
        session.pending.collect { it?.answer?.complete(if (it.prompt is Prompt.VerifyHost) listOf("trust") else listOf(password)) }
    }
    private suspend fun awaitText(session: TerminalConnection, marker: String) = withTimeout(8000) {
        while (!withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains(marker)) delay(50)
    }
    @Test fun prepare(): Unit = runBlocking {
        requireStage("prepare")
        ActivityScenario.launch(MainActivity::class.java).use {
            val service = service()
            val password = instrumentation.context.assets.open("test_password").bufferedReader().use { it.readText().trim() }
            val host = Host(id = "itermux-lifecycle-test-host", name = "Lifecycle fixture", address = "127.0.0.1", port = 22228, username = "itermux_test", mode = "TMUX")
            app.database.hosts().save(host.copy(encryptedPassword = app.vault.encrypt(password.toByteArray(), "${host.id}:password")))
            val shell = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials(password.toByteArray())) }
            val tmux = withContext(Dispatchers.Main) { service.open(host, SessionMode.TMUX, Credentials(password.toByteArray())) }
            val prompts = listOf(answer(shell, password), answer(tmux, password))
            try {
                connected(shell); connected(tmux)
                withContext(Dispatchers.Main) { tmux.write("export ITERMUX_PROCESS_RESTORE=restored_567; printf '\\nPROCESS_%s\\n' 'READY'; sleep 60\r") }
                awaitText(tmux, "PROCESS_READY")
                java.io.File(context.getExternalFilesDir(null), "lifecycle-ready.txt").writeText(android.os.Process.myPid().toString())
                // The external driver now kills this process, including ordinary SSH and the runner.
                withTimeout(90_000) { awaitCancellation() }
            } finally { prompts.forEach { it.cancel() }; withContext(Dispatchers.Main) { service.disconnectAll() } }
        }
    }
    @Test fun recover() = runBlocking {
        requireStage("recover")
        ActivityScenario.launch(MainActivity::class.java).use {
            val service = service()
            assertTrue("A fresh process must not present stale SSH sessions", service.sessions.value.isEmpty())
            val host = requireNotNull(app.database.hosts().find("itermux-lifecycle-test-host"))
            val password = app.vault.decrypt(requireNotNull(host.encryptedPassword), "${host.id}:password").toString(Charsets.UTF_8)
            val tmux = withContext(Dispatchers.Main) { service.open(host, SessionMode.TMUX, Credentials(password.toByteArray())) }
            val prompts = answer(tmux, password)
            try {
                connected(tmux)
                withContext(Dispatchers.Main) { tmux.write("\u0003") }
                delay(250)
                withContext(Dispatchers.Main) { tmux.write("printf '\\n%s\\n' \"\$ITERMUX_PROCESS_RESTORE\"\r") }
                awaitText(tmux, "restored_567")
            } finally {
                prompts.cancel()
                withContext(Dispatchers.Main) { service.close(tmux.id) }
                app.database.hosts().delete(host.id)
            }
        }
    }
}
