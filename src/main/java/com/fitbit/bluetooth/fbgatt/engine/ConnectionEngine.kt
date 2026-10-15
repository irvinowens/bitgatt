/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * One coroutine scope per connection. Work runs serially on a single thread named the way
 * the old queue thread was named. [clearQueue] drops work that has not started and leaves
 * the thread running. [stop] is the [com.fitbit.bluetooth.fbgatt.GattConnection.finish] path.
 */
class ConnectionEngine(private val threadName: String) {
    private val stopped = AtomicBoolean(true)
    private val threadRef = AtomicReference<Thread?>(null)
    private var executor: ExecutorService? = null
    private var dispatcher: ExecutorCoroutineDispatcher? = null
    private var scope: CoroutineScope? = null
    private var worker: Job? = null
    private var channel = Channel<Runnable>(Channel.UNLIMITED)

    @Synchronized
    fun start() {
        val previousChannel = channel
        val previousWorker = worker
        val previousScope = scope
        val previousDispatcher = dispatcher
        val previousExecutor = executor
        stopped.set(false)
        val fresh = Channel<Runnable>(Channel.UNLIMITED)
        channel = fresh
        val exec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, threadName).also { created -> threadRef.set(created) }
        }
        executor = exec
        val disp = exec.asCoroutineDispatcher()
        dispatcher = disp
        val createdScope = CoroutineScope(SupervisorJob() + disp)
        scope = createdScope
        worker = createdScope.launch {
            for (task in fresh) {
                if (!isActive || stopped.get()) {
                    break
                }
                try {
                    task.run()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (thrown: Throwable) {
                    Timber.w(thrown, "Queued work failed on %s", threadName)
                }
            }
        }
        previousWorker?.cancel(null)
        previousScope?.cancel(null)
        previousChannel.close()
        previousDispatcher?.close()
        previousExecutor?.shutdownNow()
    }

    fun queueTransaction(tx: Runnable) {
        val target = synchronized(this) {
            if (stopped.get()) {
                start()
            }
            channel
        }
        val result = target.trySend(tx)
        if (result.isFailure) {
            synchronized(this) {
                start()
                channel.trySend(tx)
            }
        }
    }

    /**
     * Drops queued work. The running transaction and the dispatcher thread stay.
     */
    fun clearQueue() {
        val target = channel
        while (true) {
            val next = target.tryReceive()
            if (next.isFailure) {
                break
            }
        }
    }

    @Synchronized
    fun stop() {
        stopped.set(true)
        clearQueue()
        worker?.cancel(null)
        scope?.cancel(null)
        channel.close()
        channel = Channel(Channel.UNLIMITED)
        dispatcher?.close()
        executor?.shutdownNow()
        dispatcher = null
        executor = null
        worker = null
        scope = null
        threadRef.set(null)
    }

    fun isQueueThreadStopped(): Boolean {
        val thread = threadRef.get() ?: return true
        return !thread.isAlive || thread.isInterrupted
    }

    fun getTransactionThread(): Thread? = threadRef.get()

    fun scheduleTimeout(handler: Handler, delayMs: Long, block: Runnable) {
        try {
            handler.postDelayed(block, delayMs)
        } catch (ignored: RuntimeException) {
            Timber.v(ignored, "Timeout was not scheduled")
        }
    }

    @Throws(InterruptedException::class)
    fun awaitLatch(latch: CountDownLatch, timeoutMs: Long) {
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        private val mainPosts = java.util.concurrent.ConcurrentHashMap<Thread, LinkedBlockingQueue<Runnable>>()

        /**
         * Runs [action] on the main looper. A live [Handler] posts it. Unit-test stubs return
         * false from [Handler.post], so an alive looper thread drains [pollMain], and a missing
         * looper runs [action] on the caller so existing tests still observe the result.
         */
        @JvmStatic
        fun deliverOnMainLooper(looper: Looper?, handler: Handler?, action: Runnable) {
            var posted = false
            try {
                if (handler != null) {
                    posted = handler.post(action)
                }
            } catch (ignored: RuntimeException) {
                posted = false
            }
            if (posted) {
                return
            }
            val target = try {
                looper?.thread
            } catch (ignored: RuntimeException) {
                null
            }
            if (target != null && target !== Thread.currentThread() && target.isAlive) {
                mainPosts.computeIfAbsent(target) { LinkedBlockingQueue() }.add(action)
                return
            }
            action.run()
        }

        @JvmStatic
        @Throws(InterruptedException::class)
        fun pollMain(thread: Thread, timeoutMs: Long): Runnable? {
            val queue = mainPosts.computeIfAbsent(thread) { LinkedBlockingQueue() }
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }
}
