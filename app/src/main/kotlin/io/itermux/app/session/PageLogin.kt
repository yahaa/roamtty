package io.itermux.app.session

import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.ssh.Credentials
import io.itermux.ssh.JschSession
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** A login of a page's own (no PTY); its prompts wait in [pending] for the page to answer them. */
internal fun ItermuxApp.pageLogin(host: Host, credentials: Credentials, pending: MutableStateFlow<PendingPrompt?>) =
    JschSession(host.target(SessionMode.SHELL), credentials, pins, interaction = { prompt ->
        val request = PendingPrompt(prompt)
        withContext(Dispatchers.Main.immediate) { pending.value = request }
        try { request.answer.await() }
        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { pending.compareAndSet(request, null) } }
    }, connectTimeoutMs = 15_000)
