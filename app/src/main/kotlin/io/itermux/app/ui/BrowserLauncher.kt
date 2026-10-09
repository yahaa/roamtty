package io.itermux.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.core.net.toUri

internal fun openInBrowser(context: Context, url: String) {
    // A hostless web intent finds browsers without invoking an OEM's APP_BROWSER shortcut or
    // letting a domain-specific app link hide the browser list. No probe is sent to the network.
    val probe = Intent(Intent.ACTION_VIEW, "${url.toUri().normalizeScheme().scheme}://".toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val manager = context.packageManager
    // Without MATCH_ALL Android can return only the default browser, even if it is suspended.
    val packages = usableBrowserPackages(manager.queryIntentActivities(
        probe, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_ALL
    ))
    val defaultPackage = manager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    context.startActivity(browserLaunchIntent(url, packages, defaultPackage))
}

internal fun usableBrowserPackages(activities: List<ResolveInfo>): List<String> =
    activities.mapNotNull { resolved ->
        val activity = resolved.activityInfo ?: return@mapNotNull null
        val app = activity.applicationInfo ?: return@mapNotNull null
        activity.packageName.takeIf {
            activity.enabled && activity.exported && app.enabled && (app.flags and ApplicationInfo.FLAG_SUSPENDED) == 0
        }
    }.distinct()

internal fun browserLaunchIntent(url: String, packages: List<String>, defaultPackage: String?): Intent {
    if (packages.isEmpty()) throw ActivityNotFoundException("No available browser")
    val view = Intent(Intent.ACTION_VIEW, url.toUri().normalizeScheme()).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (defaultPackage in packages) return view.setPackage(defaultPackage)
    if (packages.size == 1) return view.setPackage(packages.single())
    val choices = packages.map { Intent(view).setPackage(it) }
    return Intent.createChooser(choices.first(), null)
        .putExtra(Intent.EXTRA_INITIAL_INTENTS, choices.drop(1).toTypedArray())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
