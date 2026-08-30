package org.js.lolifamily.minecraftmcp.exec

/**
 * Thrown by the timeout guard `ScriptWeave.instrument` inlines into a script's own bytecode when a tick's shared
 * script-time budget is exhausted. An [Error] rather than an [Exception] so a script's `catch (e: Exception)`
 * cannot swallow it.
 *
 * ONE instance serves the whole process ([TimeoutGuard.timeout]): the throw is a bare `GETSTATIC` + `ATHROW`,
 * because a `NEW` needs an `INVOKESPECIAL <init>` — the stack frame the stack-full edge doesn't have. A shared
 * throwable has to be immutable, so the flags below null out all three of [Throwable]'s writable fields: one
 * eval can't poison every later one through `addSuppressed` (a list with no clearing API, re-rendered by every
 * later report, pinning the snippet loader that threw) or through `initCause` (one-shot while `cause == this`),
 * and skipping `fillInStackTrace` keeps a throw O(1) — otherwise unwinding deep recursion walks the stack on
 * every re-throw, making the whole unwind O(depth²).
 *
 * It therefore carries no stack and cannot be given one: `setStackTrace` is a no-op here too. Where the lane was
 * when the budget ran out belongs to the lane thread — several evals share one budget — so it has to reach the
 * report by a side channel rather than be written onto this value.
 */
class ScriptTimeoutError(budgetMs: Long) :
    Error(
        "shared ${budgetMs}ms tick budget ran out mid-step — spread work across ticks with iterator { ... yield(v) ... }",
        null, // cause
        false, // enableSuppression
        false, // writableStackTrace
    )
