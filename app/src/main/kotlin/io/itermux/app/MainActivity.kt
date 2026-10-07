package io.itermux.app

import android.content.*
import android.content.res.Configuration
import android.net.Uri
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.itermux.app.session.SessionService
import io.itermux.app.ui.DarkColors
import io.itermux.app.ui.ItermuxScreen
import io.itermux.app.ui.LightColors
import androidx.compose.ui.graphics.toArgb
import java.time.Duration
import java.time.Instant

private const val SPLASH_MIN_MS = 1600L

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<SessionService?>(null)
    private var bound = false
    private var terminalChrome = false
    private var lightTheme = false
    private var pendingShareImages by mutableStateOf<List<Uri>>(emptyList())
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SessionService.LocalBinder).service.also {
                // Returning to the app (often after a night of sleep) must not show a frozen terminal.
                it.verifyConnections()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) {
            // Let the ghost bob, blink and wave once before fading out, even when the first frame is ready sooner.
            splashScreen.setOnExitAnimationListener { splash ->
                val shown = splash.iconAnimationStart?.let { Duration.between(it, Instant.now()).toMillis() } ?: SPLASH_MIN_MS
                splash.animate().alpha(0f).setStartDelay((SPLASH_MIN_MS - shown).coerceAtLeast(0L)).setDuration(200L)
                    .withEndAction { splash.remove() }.start()
            }
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        pendingShareImages = savedInstanceState?.getStringArrayList("pendingShareImages")?.map(Uri::parse)
            ?: ShareImageIntents.takeImageUris(intent)
        setContent {
            ItermuxScreen(
                application as ItermuxApp,
                service,
                pendingShareImages = pendingShareImages,
                onShareImagesConsumed = { pendingShareImages = emptyList() },
            ) { visible, light ->
                terminalChrome = visible
                lightTheme = light
                updateSystemBars()
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingShareImages = pendingShareImages + ShareImageIntents.takeImageUris(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("pendingShareImages", ArrayList(pendingShareImages.map(Uri::toString)))
        super.onSaveInstanceState(outState)
    }
    private fun updateSystemBars() {
        val background = (if (lightTheme) LightColors else DarkColors).background.toArgb()
        // The terminal and the file browser draw their own background under a transparent status bar.
        @Suppress("DEPRECATION")
        window.statusBarColor = if (terminalChrome) android.graphics.Color.TRANSPARENT else background
        @Suppress("DEPRECATION")
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
            // Landscape gives every row to content on all pages; the portrait terminal
            // and file browser keep the status bar (clock, battery, network); other portrait pages keep both bars.
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            when {
                landscape -> hide(WindowInsetsCompat.Type.systemBars())
                !terminalChrome -> show(WindowInsetsCompat.Type.systemBars())
                else -> {
                    show(WindowInsetsCompat.Type.statusBars())
                    hide(WindowInsetsCompat.Type.navigationBars())
                }
            }
        }
        window.decorView.setBackgroundColor(background)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }
    override fun onStart() {
        super.onStart()
        android.util.Log.i("RoamttyConn", "activity start")
        bound = bindService(Intent(this, SessionService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun onStop() {
        android.util.Log.i("RoamttyConn", "activity stop")
        if (bound) { unbindService(connection); bound = false }
        // Keep the terminal UI and editor state while the activity is in the background.
        // The foreground service still owns the connection; onStart rebinds to the current service.
        super.onStop()
    }
}
