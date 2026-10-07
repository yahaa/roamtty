package io.itermux.app

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.itermux.app.ui.TerminalToolbox

/** Keeps the collapsed terminal toolbox and the keyboard on screen; tests that cover auto-hide opt back in. */
class ItermuxTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        TerminalToolbox.autoHideMillis = 0
        TerminalToolbox.keyboardIdleHideMillis = 0
        super.onCreate(arguments)
    }
}
