package io.itermux.app

import android.content.*
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.session.*
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the isolated key-echo fixture. The test really stops the Activity and unbinds the service. */
@RunWith(AndroidJUnit4::class)
class SessionLifetimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as ItermuxApp
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }

    private fun holdsCpuLock() = shell("dumpsys power").lineSequence().any {
        it.contains("PARTIAL_WAKE_LOCK") && it.contains("Roamtty:SSH")
    }

    private class Binding(val context: Context) : ServiceConnection {
        val result = CompletableDeferred<SessionService>()
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            result.complete((binder as SessionService.LocalBinder).service)
        }
        override fun onServiceDisconnected(name: ComponentName?) = Unit
        suspend fun open(): SessionService {
            assertTrue(context.bindService(Intent(context, SessionService::class.java), this, Context.BIND_AUTO_CREATE))
            return withTimeout(10_000) { result.await() }.also { it.awaitRestoration() }
        }
        fun close() = context.unbindService(this)
    }

    @Test fun screenOffConnectionAndDisconnectedEntrySurviveServiceRecreation() = runBlocking {
        assumeTrue(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        val host = Host(name = "Lifetime fixture", address = "127.0.0.1", port = 22228,
            username = "itermux_test", mode = "SHELL")
        app.database.hosts().save(host)
        app.database.hosts().forgetPin(host.target().endpoint)
        val forceDoze = InstrumentationRegistry.getArguments().getString("forceDoze") == "true"
        val previouslyExempt = context.getSystemService(android.os.PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        var binding: Binding? = Binding(context)
        var prompts: Job? = null
        try {
            val service = binding!!.open()
            val session = withContext(Dispatchers.Main) { service.open(host, SessionMode.SHELL, Credentials("test-only".toByteArray())) }
            prompts = launch { session.pending.collect { it?.answer?.complete(listOf("trust")) } }
            assertEquals(Phase.CONNECTED, withTimeout(20_000) { session.state.first { it.phase == Phase.CONNECTED || !it.active } }.phase)
            withContext(Dispatchers.Main) { session.rename("Preserved background task"); session.draft = "DO_NOT_REPLAY" }
            activity.moveToState(Lifecycle.State.CREATED)
            binding!!.close(); binding = null
            if (forceDoze) {
                shell("dumpsys deviceidle whitelist +${context.packageName}")
                shell("dumpsys battery unplug")
            }
            shell("input keyevent 223")
            if (forceDoze) {
                val response = shell("dumpsys deviceidle force-idle")
                println(response.trim())
                assertTrue(response, response.contains("Now forced in to deep idle mode"))
            }
            // Longer than three 15-second SSH keepalive intervals, without a bound Activity.
            delay(75_000)
            if (forceDoze) {
                val state = shell("dumpsys deviceidle").lineSequence().first { it.trim().startsWith("mState=") }
                println(state.trim())
                assertTrue(state, state.contains("mState=IDLE"))
            }
            assertEquals("Screen-off SSH must remain connected", Phase.CONNECTED, session.state.value.phase)
            assertTrue("The service must hold its CPU lock", holdsCpuLock())
            withContext(Dispatchers.Main) { session.write("Q") }
            withTimeout(8000) {
                while (!withContext(Dispatchers.Main) { session.getEmulator().screen.transcriptText }.contains("KEY_51")) delay(50)
            }
            // End the last transport while no UI is bound: this used to erase the whole list.
            withContext(Dispatchers.Main) { session.disconnect() }
            withTimeout(8000) {
                while (shell("dumpsys activity services ${BuildConfig.APPLICATION_ID}").contains("ServiceRecord{")) delay(100)
            }
            assertFalse("No CPU lock after disconnect", holdsCpuLock())
            if (forceDoze) shell("dumpsys deviceidle unforce")
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            activity.moveToState(Lifecycle.State.RESUMED)
            binding = Binding(context)
            val restoredService = binding!!.open()
            assertNotSame(service, restoredService)
            val restored = restoredService.sessions.value.single { it.id == session.id }
            assertEquals(Phase.DISCONNECTED, restored.state.value.phase)
            assertEquals("Preserved background task", restored.title.value)
            assertEquals("", restored.draft)
            assertFalse(restored.getEmulator().screen.transcriptText.contains("KEY_51"))
            withContext(Dispatchers.Main) { restoredService.close(restored.id) }
            activity.moveToState(Lifecycle.State.CREATED)
            binding!!.close(); binding = null
            delay(300)
            activity.moveToState(Lifecycle.State.RESUMED)
            binding = Binding(context)
            assertTrue("Explicit close must remove the saved row", binding!!.open().sessions.value.none { it.id == session.id })
        } finally {
            prompts?.cancel()
            if (forceDoze) {
                shell("dumpsys deviceidle unforce")
                shell("dumpsys battery reset")
                if (!previouslyExempt) shell("dumpsys deviceidle whitelist -${context.packageName}")
            }
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            binding?.close()
            activity.close()
            app.database.hosts().delete(host.id)
        }
    }
}
