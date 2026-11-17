package com.nettarion.hyperborea.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [blocks] concurrently on [scope] and blocks the calling thread until they have all
 * finished **or** [budgetMs] has elapsed, whichever comes first. Returns true when everything
 * finished inside the budget.
 *
 * The blocks are deliberately *not* children of the waiting coroutine and are *not* cancelled
 * when the budget runs out: the wait is what is bounded, not the work. That is the contract a
 * lifecycle callback on the main thread needs when the work is a teardown that must not be
 * interrupted (halting a treadmill belt, releasing USB) — the caller gets its thread back on
 * time, and the teardown keeps running on [scope] for as long as the process lives.
 *
 * (Waiting with `withTimeoutOrNull { launch { work() } }` does not bound anything once the work
 * is `NonCancellable`: a timed-out scope still joins its children.)
 */
internal fun runTeardownWithin(
    scope: CoroutineScope,
    budgetMs: Long,
    vararg blocks: suspend () -> Unit,
): Boolean {
    val jobs = blocks.map { block -> scope.launch { block() } }
    return runBlocking { withTimeoutOrNull(budgetMs) { jobs.joinAll() } != null }
}
