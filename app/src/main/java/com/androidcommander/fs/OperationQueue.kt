package com.androidcommander.fs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Single-worker mutating-operation queue (instruction section 2). Exactly one mutating
 * filesystem operation runs at a time; commands issued while one runs are enqueued, never
 * dropped and never greyed out in the UI -- navigation/selection commands bypass this queue
 * entirely so they're never blocked behind a running copy/move.
 */
class OperationQueue(scope: CoroutineScope) {
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (op in channel) op()
        }
    }

    fun enqueue(op: suspend () -> Unit) {
        channel.trySend(op)
    }
}
