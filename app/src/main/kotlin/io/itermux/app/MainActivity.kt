package io.itermux.app

import android.content.*
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import io.itermux.app.session.SessionService
import io.itermux.app.ui.ItermuxScreen

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<SessionService?>(null)
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = (binder as SessionService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Prevent system screenshots/recent-task snapshots from persisting credentials or terminal output.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { ItermuxScreen(application as ItermuxApp, service) }
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
