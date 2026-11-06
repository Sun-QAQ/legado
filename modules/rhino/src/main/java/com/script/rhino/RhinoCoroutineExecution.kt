package com.script.rhino

import com.script.ScriptException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContinuationPending
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

internal suspend fun executeRhinoContinuations(scope: Scriptable, script: (RhinoContext) -> Script): Any? {
    coroutineContext.ensureActive()
    // Rhino's VM bridge stores its Context in a one-slot Object array. A private holder
    // keeps suspended evaluations out of unrelated coroutines on the same thread.
    val state = (VMBridgeReflect.contextLocal.get() as? Array<*>)?.clone() ?: arrayOfNulls<Any>(1)
    return withContext(VMBridgeReflect.contextLocal.asContextElement(state)) {
        val cx = Context.enter() as RhinoContext
        val previousContext = cx.coroutineContext
        val previousAllowScriptRun = cx.allowScriptRun
        cx.coroutineContext = coroutineContext.minusKey(ContinuationInterceptor)
        cx.allowScriptRun = true
        cx.recursiveCount++
        try {
            cx.checkRecursive()
            try {
                cx.executeScriptWithContinuations(script(cx), scope)
            } catch (captured: ContinuationPending) {
                var pending = captured
                while (true) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val operation = pending.applicationState as suspend () -> Any?
                        val result = operation()
                        return@withContext cx.resumeContinuation(pending.continuation, scope, result)
                    } catch (next: ContinuationPending) {
                        pending = next
                    }
                }
            }
        } catch (error: RhinoException) {
            val line = if (error.lineNumber() == 0) -1 else error.lineNumber()
            val message = if (error is JavaScriptException) error.value.toString() else error.toString()
            throw ScriptException(message, error.sourceName(), line).apply { initCause(error) }
        } catch (error: IOException) {
            throw ScriptException(error)
        } finally {
            cx.coroutineContext = previousContext
            cx.allowScriptRun = previousAllowScriptRun
            cx.recursiveCount--
            Context.exit()
        }
    }
}
