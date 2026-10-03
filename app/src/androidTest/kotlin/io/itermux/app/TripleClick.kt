package io.itermux.app

import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click

/** Three quick taps at the center, each within the double-tap timeout of the previous one. */
fun TouchInjectionScope.tripleClick() {
    click(); advanceEventTime(80); click(); advanceEventTime(80); click()
}
