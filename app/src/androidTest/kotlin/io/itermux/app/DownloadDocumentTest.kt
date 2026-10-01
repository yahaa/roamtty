package io.itermux.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.ui.CreateDownloadDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DownloadDocumentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemSavePickerPreservesRemoteNamesAndExtensions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val resolver = instrumentation.targetContext.contentResolver
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val name = mutableStateOf("")
        val result = AtomicReference<Uri?>()
        val created = mutableListOf<Uri>()
        compose.setContent {
            val picker = rememberLauncherForActivityResult(CreateDownloadDocument()) { result.set(it) }
            Button(onClick = { picker.launch(name.value) }, modifier = Modifier.testTag("save-download")) {
                Text("Save download")
            }
        }
        try {
            val prefix = "itermux-${UUID.randomUUID()}-"
            for (suffix in listOf("report.pdf", "image.PNG", "archive.tar.gz", "README", ".env", "中文 文件.txt")) {
                val expected = prefix + suffix
                result.set(null)
                compose.runOnIdle { name.value = expected }
                compose.onNodeWithTag("save-download").performClick()
                val deadline = SystemClock.uptimeMillis() + 10_000
                var clicked = false
                while (!clicked && SystemClock.uptimeMillis() < deadline) {
                    val button = automation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId("android:id/button1")
                        ?.firstOrNull { it.isEnabled && it.isClickable }
                    clicked = button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (!clicked) SystemClock.sleep(50)
                }
                assertTrue("System save button is available", clicked)
                while (result.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                val uri = requireNotNull(result.get()) { "System save picker did not return a document" }
                created += uri
                val actual = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    it.getString(0)
                }
                assertEquals("The saved document must keep the remote filename", expected, actual)
            }
        } finally {
            created.forEach { DocumentsContract.deleteDocument(resolver, it) }
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }
}
