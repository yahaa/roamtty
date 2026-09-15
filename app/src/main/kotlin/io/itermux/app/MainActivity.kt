package io.itermux.app

import android.content.*
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
import io.itermux.app.ui.ItermuxScreen

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<SessionService?>(null)
    private var bound = false
    private var terminalVisible = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = (binder as SessionService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Prevent system screenshots/recent-task snapshots from persisting credentials or terminal output.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            ItermuxScreen(application as ItermuxApp, service) { visible ->
                terminalVisible = visible
                updateSystemBars()
            }
        }
    }
    private fun updateSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (terminalVisible) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
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
