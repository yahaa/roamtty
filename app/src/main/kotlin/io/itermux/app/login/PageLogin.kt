package io.itermux.app.login

import io.itermux.app.data.Host
import io.itermux.ssh.Credentials
import io.itermux.ssh.JschSession
import io.itermux.ssh.PinStore
import io.itermux.ssh.Prompt
import io.itermux.ssh.SessionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** A question a login asks the user (trust this host key, a keyboard-interactive answer) and where its answer goes. */
data class PendingPrompt(val prompt: Prompt, val answer: CompletableDeferred<List<String>?> = CompletableDeferred())

/** A login of a page's own (no PTY); its prompts wait in [pending] for the page to answer them. */
internal fun pageLogin(host: Host, credentials: Credentials, pins: PinStore, pending: MutableStateFlow<PendingPrompt?>) =
    JschSession(host.target(SessionMode.SHELL), credentials, pins, interaction = { prompt ->
        val request = PendingPrompt(prompt)
        withContext(Dispatchers.Main.immediate) { pending.value = request }
        try { request.answer.await() }
        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { pending.compareAndSet(request, null) } }
    }, connectTimeoutMs = 15_000)
