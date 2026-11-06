package com.airmesh

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Test dispatcher that records the thread name of every block it executes, so
 * tests can assert which thread a piece of work actually ran on.
 *
 * On Android a socket write on the main thread throws
 * NetworkOnMainThreadException; JVM tests cannot reproduce that exception, so
 * threading bugs are caught by asserting on these recorded names instead.
 */
class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {

    private val threads = CopyOnWriteArrayList<String>()

    /** Thread names in dispatch order; safe to read from any thread. */
    val recordedThreads: List<String>
        get() = threads.toList()

    fun clearRecordedThreads() {
        threads.clear()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(
            context,
            Runnable {
                threads += Thread.currentThread().name
                block.run()
            },
        )
    }
}
