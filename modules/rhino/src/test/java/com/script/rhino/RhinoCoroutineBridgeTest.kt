package com.script.rhino

import com.script.ScriptBindings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.coroutines.EmptyCoroutineContext

class RhinoCoroutineBridgeTest {
    private fun scope(operation: () -> Any?): Scriptable {
        RhinoScriptEngine
        val bindings = ScriptBindings().apply {
            put("pause", object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? = operation()
            })
        }
        return RhinoScriptEngine.getRuntimeScope(bindings)
    }

    @Test fun `synchronous fallback runs exactly once and preserves business exceptions`() {
        var count = 0
        val error = assertThrows(IllegalStateException::class.java) {
            runScriptWithContext(EmptyCoroutineContext) {
                suspendContinuation { count++; throw IllegalStateException("business") }
            }
        }
        assertEquals("business", error.message)
        assertEquals(1, count)
        assertNull(Context.getCurrentContext())
    }

    @Test fun `fallback inherits cancellation without blocking its own dispatcher`() = runBlocking {
        val parent = Job()
        val started = CountDownLatch(1)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val pending = async(worker) {
                runScriptWithContext(parent + worker) {
                    suspendContinuation { started.countDown(); awaitCancellation() }
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            parent.cancel()
            withTimeout(2000) { pending.join() }
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun `interpreted and compiled continuations resume repeated suspensions`() = runBlocking {
        for (compiled in listOf(false, true)) {
            var calls = 0
            val scope = scope {
                assertNotNull(rhinoContext.coroutineContext?.get(Job))
                suspendContinuation { yield(); calls++; "ok" }
            }
            val result = if (compiled) RhinoScriptEngine.compile("pause() + pause()").evalSuspend(scope)
                else RhinoScriptEngine.evalSuspend("pause() + pause()", scope)
            assertEquals("okok", result)
            assertEquals(2, calls)
            assertNull(Context.getCurrentContext())
        }
    }

    @Test fun `suspended scripts do not leak contexts and cancellation releases them`() = runBlocking {
        supervisorScope {
            for (compiled in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val scope = scope {
                    suspendContinuation { started.complete(Unit); awaitCancellation() }
                }
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    if (compiled) RhinoScriptEngine.compile("pause()").evalSuspend(scope)
                    else RhinoScriptEngine.evalSuspend("pause()", scope)
                }
                started.await()
                assertNull("suspended script must detach from caller thread", Context.getCurrentContext())
                pending.cancelAndJoin()
                assertNull(Context.getCurrentContext())
            }
        }
    }

    @Test fun `nested sync execution restores script permission recursion and parent context`() {
        val outer = Job()
        val inner = Job()
        runScriptWithContext(outer) {
            val context = rhinoContext
            context.allowScriptRun = true
            val depth = context.recursiveCount
            val scope = scope { "ok" }
            RhinoScriptEngine.eval("1", scope, inner)
            assertTrue(context.allowScriptRun)
            assertEquals(depth, context.recursiveCount)
            assertSame(outer, context.coroutineContext?.get(Job))
            RhinoScriptEngine.compile("1").eval(scope, inner)
            assertTrue(context.allowScriptRun)
            assertSame(outer, context.coroutineContext?.get(Job))
            context.allowScriptRun = false
        }
        assertNull(Context.getCurrentContext())
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test fun `cancelled caller never enters a script context`() = runBlocking {
        val cancelled = Job().apply { cancel() }
        val pending = async(cancelled, start = CoroutineStart.ATOMIC) {
            RhinoScriptEngine.evalSuspend("1", scope { "unused" })
        }
        pending.join()
        assertTrue(pending.isCancelled)
        assertNull(Context.getCurrentContext())
    }
}
