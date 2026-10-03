package io.itermux.app

import android.content.*
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

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<SessionService?>(null)
    private var bound = false
    private var terminalVisible = false
    private var lightTheme = false
    private var pendingShareImages by mutableStateOf<List<Uri>>(emptyList())
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = (binder as SessionService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) {
            // Dismiss as soon as the first frame is ready; never wait for the icon loop.
            splashScreen.setOnExitAnimationListener { splash ->
                splash.animate().alpha(0f).setDuration(140L)
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
                terminalVisible = visible
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
        @Suppress("DEPRECATION")
        window.statusBarColor = background
        @Suppress("DEPRECATION")
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
            if (terminalVisible) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        window.decorView.setBackgroundColor(background)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, SessionService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun onStop() {
        if (bound) { unbindService(connection); bound = false }
        // Keep the terminal UI and editor state while the activity is in the background.
        // The foreground service still owns the connection; onStart rebinds to the current service.
        super.onStop()
    }
}
