package io.itermux.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.core.net.toUri

internal fun openTerminalUrl(context: Context, url: String) {
    val view = webViewIntent(url)
    val manager = context.packageManager
    val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_ALL
    if (view.data?.host?.lowercase() in listOf("github.com", "www.github.com")) {
        val github = Intent(view).setPackage("com.github.android")
        if (github.`package` in usableWebPackages(manager.queryIntentActivities(github, flags))) {
            try {
                context.startActivity(github)
                return
            } catch (_: ActivityNotFoundException) {
                // The app may have been removed after resolving the link; use the browser.
            } catch (_: SecurityException) {
                // A policy change may prevent launching the app; use the browser.
            }
        }
    }
    // A hostless web intent finds browsers without invoking an OEM's APP_BROWSER shortcut or
    // letting a domain-specific app link hide the browser list. No probe is sent to the network.
    val probe = Intent(Intent.ACTION_VIEW, "${view.data?.scheme}://".toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    // Without MATCH_ALL Android can return only the default browser, even if it is suspended.
    val packages = usableWebPackages(manager.queryIntentActivities(probe, flags))
    val defaultPackage = manager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    context.startActivity(browserLaunchIntent(url, packages, defaultPackage))
}

internal fun usableWebPackages(activities: List<ResolveInfo>): List<String> =
    activities.mapNotNull { resolved ->
        val activity = resolved.activityInfo ?: return@mapNotNull null
        val app = activity.applicationInfo ?: return@mapNotNull null
        activity.packageName.takeIf {
            activity.enabled && activity.exported && app.enabled && (app.flags and ApplicationInfo.FLAG_SUSPENDED) == 0
        }
    }.distinct()

internal fun browserLaunchIntent(url: String, packages: List<String>, defaultPackage: String?): Intent {
    if (packages.isEmpty()) throw ActivityNotFoundException("No available browser")
    val view = webViewIntent(url)
    if (defaultPackage in packages) return view.setPackage(defaultPackage)
    if (packages.size == 1) return view.setPackage(packages.single())
    val choices = packages.map { Intent(view).setPackage(it) }
    return Intent.createChooser(choices.first(), null)
        .putExtra(Intent.EXTRA_INITIAL_INTENTS, choices.drop(1).toTypedArray())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun webViewIntent(url: String) = Intent(Intent.ACTION_VIEW, url.toUri().normalizeScheme())
    .addCategory(Intent.CATEGORY_BROWSABLE)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
