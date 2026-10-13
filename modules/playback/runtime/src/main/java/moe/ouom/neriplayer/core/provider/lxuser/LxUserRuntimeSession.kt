package moe.ouom.neriplayer.core.provider.lxuser

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps one LX runtime alive across songs.
 *
 * Building a runtime means compiling the user's script - measured at ~200 ms for a
 * 145 KB obfuscated source - and discarding whatever cache the script had built up.
 * Keeping it alive skips that work and lets the source reuse its own cached
 * lookups, which most public sources keep for around twenty minutes.
 *
 * A QuickJS context may only be touched from the thread that created it, so every
 * call is funnelled through one dedicated thread. Resolves are serialised by the
 * caller anyway, so this costs no parallelism; it just pins the context to a
 * stable thread. The caller's own wall-clock budget still bounds each song, and a
 * failed action discards the context so the next song starts clean.
 *
 * Work is queued by priority. The prefetcher resolves several upcoming tracks at
 * once, and each of those takes seconds; without priority the track the user just
 * tapped would sit behind them.
 */
internal object LxUserRuntimeSession {
    private class Task(
        val urgent: Boolean,
        val sequence: Long,
        val body: () -> Unit,
    ) : Runnable, Comparable<Task> {
        override fun run() = body()

        override fun compareTo(other: Task): Int = when {
            urgent != other.urgent -> if (urgent) -1 else 1
            else -> sequence.compareTo(other.sequence)
        }
    }

    private val sequence = AtomicLong()

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
    ) { runnable -> Thread(runnable, "lx-user-runtime").apply { isDaemon = true } }

    // Only ever touched on the executor's thread, so no extra locking is needed.
    private var runtime: LxUserRuntime? = null
    private var identity: String? = null

    private val pacingLock = Any()
    private var nextRequestAt = 0L

    /**
     * Blocks until this caller is allowed to issue the next request to the source.
     *
     * The quota is shared by every concurrent resolve. Pacing each resolve on its own
     * multiplied the intended gap by the prefetcher's four parallel resolves, and the
     * public mirrors answered `429 请求过于频繁` - which made songs fall back to the
     * official 30 s trial clip.
     */
    fun awaitRequestSlot(gapMs: Long) {
        val waitMs = synchronized(pacingLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val slot = if (nextRequestAt > now) nextRequestAt else now
            nextRequestAt = slot + gapMs
            slot - now
        }
        if (waitMs > 0L) Thread.sleep(waitMs)
    }

    /**
     * Runs [block] against the shared runtime. Pass `urgent = false` for background
     * work such as prefetching so a user-initiated resolve is served first.
     */
    fun <T> withRuntime(
        recordId: String,
        scriptSource: String,
        urgent: Boolean = true,
        block: (LxUserRuntime) -> T,
    ): T {
        val result = CompletableFuture<T>()
        executor.execute(
            Task(urgent, sequence.incrementAndGet()) {
                try {
                    result.complete(block(runtimeFor(recordId, scriptSource)))
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            },
        )
        return try {
            result.get()
        } catch (error: ExecutionException) {
            // An action that threw may have left the context mid-flight. Drop it on
            // the owning thread so the next song rebuilds from a known-good state.
            executor.execute(
                Task(true, sequence.incrementAndGet()) {
                    runCatching { runtime?.close() }
                    runtime = null
                    identity = null
                },
            )
            throw error.cause ?: error
        }
    }

    private fun runtimeFor(recordId: String, scriptSource: String): LxUserRuntime {
        runtime?.let { existing ->
            if (identity == recordId) return existing
        }
        runCatching { runtime?.close() }
        runtime = null
        identity = null
        val candidate = LxUserRuntime()
        try {
            candidate.load(LxUserScript(scriptSource))
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
        runtime = candidate
        identity = recordId
        return candidate
    }
}
