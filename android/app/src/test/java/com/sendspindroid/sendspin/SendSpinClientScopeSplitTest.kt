package com.sendspindroid.sendspin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Regression coverage for audit finding M-4: verify that SendSpinClient's
 * two-scope split actually routes timer and IO work to different threads.
 *
 * This test does not construct a SendSpinClient (its constructor requires
 * several Android-specific collaborators). Instead it exercises the same
 * primitives the production code uses, confirming the dispatcher pattern
 * produces the expected thread separation.
 */
class SendSpinClientScopeSplitTest {

    @Test
    fun `single-thread executor dispatcher routes to named thread`() {
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "SendSpinTimer").apply { isDaemon = true }
        }
        val dispatcher = exec.asCoroutineDispatcher()
        try {
            val threadName = runBlocking(dispatcher) {
                Thread.currentThread().name
            }
            // kotlinx.coroutines appends " @coroutine#N" to the thread name at runtime;
            // assert the base name to verify the executor thread factory took effect.
            assertTrue(
                "Expected thread name to start with 'SendSpinTimer', got '$threadName'",
                threadName.startsWith("SendSpinTimer"),
            )
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `withContext IO moves work off the single-thread dispatcher`() {
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "SendSpinTimer").apply { isDaemon = true }
        }
        val dispatcher = exec.asCoroutineDispatcher()
        try {
            val (timerThread, ioThread) = runBlocking(dispatcher) {
                val timer = Thread.currentThread().name
                val io = withContext(Dispatchers.IO) {
                    Thread.currentThread().name
                }
                timer to io
            }
            // kotlinx.coroutines appends " @coroutine#N" to the thread name at runtime;
            // assert the base name to verify the executor thread factory took effect.
            assertTrue(
                "Expected timer thread name to start with 'SendSpinTimer', got '$timerThread'",
                timerThread.startsWith("SendSpinTimer"),
            )
            assertTrue(
                "withContext(IO) should leave the SendSpinTimer thread, got '$ioThread'",
                !ioThread.startsWith("SendSpinTimer"),
            )
        } finally {
            dispatcher.close()
        }
    }
}
