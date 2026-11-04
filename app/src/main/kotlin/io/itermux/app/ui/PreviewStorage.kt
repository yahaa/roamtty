package io.itermux.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Keeps each host's port previews apart. A tunnel serves the page at `http://localhost:<the server's port>`, so two
 * hosts' dev servers on the same port are one origin to WebView. In one store, cookies, localStorage, IndexedDB,
 * cache and service workers that one host's page left behind would be handed to the other host's page.
 *
 * Where WebView has profiles, each host previews in a profile of its own, kept across previews, so a login to the
 * dev app survives. Older WebViews have one store only: it is emptied when a preview closes, and again before a
 * preview of another host if the last one never closed cleanly.
 */
internal object PreviewStorage {
    private const val PREFS = "port-preview"
    /** The host whose pages may still be in the shared store (only without profiles). */
    private const val KEY_DIRTY_HOST = "dirty-host"
    /** Profiles of deleted hosts still on disk. */
    private const val KEY_PENDING = "pending-delete"

    fun profileName(hostId: String) = "host-$hostId"

    /** Before the first WebView of a preview of [hostId] is made: empties the shared store if another host's pages are in it. */
    suspend fun prepare(context: Context, hostId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return deletePending(prefs)
        val dirty = prefs.getString(KEY_DIRTY_HOST, null)
        if (dirty != null && dirty != hostId) wipe()
        // Written at once: the page may outlive the process that loads it.
        prefs.edit(commit = true) { putString(KEY_DIRTY_HOST, hostId) }
    }

    /** On a WebView just made, before anything else is done with it: points it at [hostId]'s store. */
    fun attach(view: WebView, hostId: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.setProfile(view, profileName(hostId))
        else view.clearCache(true)
    }

    /** As a preview's WebView goes away, before it is destroyed. */
    fun release(context: Context, view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        view.clearCache(true)
        CookieManager.getInstance().removeAllCookies { _ ->
            WebStorage.getInstance().deleteAllData()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_DIRTY_HOST) }
        }
    }

    /** Drops what [hostId]'s pages kept, as the host is deleted. Main thread. */
    fun forget(context: Context, hostId: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        val name = profileName(hostId)
        val store = ProfileStore.getInstance()
        // A profile any WebView used stays loaded until the process ends, and cannot be deleted before: empty it now.
        if (name in store.allProfileNames) runCatching {
            store.getProfile(name)?.let { profile ->
                profile.cookieManager.removeAllCookies(null)
                profile.webStorage.deleteAllData()
            }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit(commit = true) { putStringSet(KEY_PENDING, prefs.getStringSet(KEY_PENDING, null).orEmpty() + name) }
        deletePending(prefs)
    }

    /** Deletes the profiles of deleted hosts that are no longer loaded; the rest wait for a later process. */
    private fun deletePending(prefs: SharedPreferences) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        val pending = prefs.getStringSet(KEY_PENDING, null).orEmpty()
        if (pending.isEmpty()) return
        val store = ProfileStore.getInstance()
        val left = pending.filterTo(mutableSetOf()) { name -> name in store.allProfileNames &&
            runCatching { store.deleteProfile(name) }.getOrDefault(false).not() }
        prefs.edit { if (left.isEmpty()) remove(KEY_PENDING) else putStringSet(KEY_PENDING, left) }
    }

    private suspend fun wipe() {
        WebStorage.getInstance().deleteAllData()
        suspendCancellableCoroutine { done -> CookieManager.getInstance().removeAllCookies { done.resume(Unit) } }
    }
}
