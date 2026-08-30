package org.js.lolifamily.minecraftmcp.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.js.lolifamily.minecraftmcp.exec.Lanes
import org.js.lolifamily.minecraftmcp.platform.Services
import java.util.Base64

/**
 * Everything this endpoint puts on the wire: JSON-RPC envelopes, MCP tool results and the `tools/list`
 * prose. All pure functions of their arguments, which is why they are here rather than in [McpServer] —
 * that file keeps what needs a live server behind it.
 *
 * Every string here is read by a model, not by a maintainer, and is written to lower FRICTION — not to be
 * complete, and not to be exact. A reader's attention is spent on every call and prose earns none of it
 * back unless it changes which call comes next: a caveat for a corner the caller will not reach, or a
 * "do not do X", is billed on every request and collected on almost none. Cover the path taken, then stop.
 *
 * One guarantee stands against that: a SUCCESSFUL result is ground truth — a caller may build on it
 * without checking. Nothing else is promised. A description may be incomplete, and a failure report is
 * written to point at the next correct action rather than to account for what happened. Where that account
 * matters it goes in a KDoc, which costs a model nothing.
 */
internal object McpJson {

    inline fun jsonObject(build: JsonObject.() -> Unit): JsonObject = JsonObject().apply(build)

    // ---- JSON-RPC envelopes --------------------------------------------------------------------

    fun success(id: JsonElement?, result: JsonObject): JsonObject = envelope(id).apply { add("result", result) }

    fun error(id: JsonElement?, code: Int, message: String): JsonObject {
        val err = jsonObject {
            addProperty("code", code)
            addProperty("message", message)
        }
        return envelope(id).apply { add("error", err) }
    }

    /** `add` documents a null value as converted to `JsonNull` — the `"id": null` JSON-RPC owes an id-less request. */
    private fun envelope(id: JsonElement?): JsonObject = jsonObject {
        addProperty("jsonrpc", "2.0")
        add("id", id)
    }

    // ---- tool results --------------------------------------------------------------------------

    /** [text] goes out whole: a clipped result is no longer ground truth, and only the client knows its own
     *  context budget — a capable one spools a large result to disk and reads it back on demand. */
    @Suppress("ktlint:standard:wrapping")
    fun toolText(id: JsonElement?, text: String, isError: Boolean): JsonObject = success(id, jsonObject {
        add("content", JsonArray().apply {
            add(jsonObject {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
        addProperty("isError", isError)
    })

    @Suppress("ktlint:standard:wrapping")
    fun toolImage(id: JsonElement?, image: ByteArray): JsonObject = success(id, jsonObject {
        add("content", JsonArray().apply {
            add(jsonObject {
                addProperty("type", "image")
                addProperty("data", Base64.getEncoder().encodeToString(image))
                addProperty("mimeType", ScreenshotRunner.MIME)
            })
        })
        addProperty("isError", false)
    })

    // ---- initialize ----------------------------------------------------------------------------

    /** The `initialize` prose: launch constants a script could read for itself, carried here so no eval is
     *  spent asking. The mod's own version is not repeated — `serverInfo.version` has it. */
    fun instructions(): String = "Minecraft ${Services.PLATFORM.minecraftVersion} on ${Services.PLATFORM.platformId}, " +
        if (Services.PLATFORM.isDedicatedServer) "dedicated server." else "physical client."

    // ---- tools/list ----------------------------------------------------------------------------

    /** One tool parameter. [enum] and [default] are structured schema fields, so the prose never has to name
     *  them; [default] is written as a string whatever [type] says, and emitted as that type. */
    private class Param(
        val name: String,
        val description: String,
        val required: Boolean = false,
        val enum: List<String>? = null,
        val default: String? = null,
        val type: String = "string",
    )

    /** The MCP envelope every `tools/list` entry shares. Written once here instead of once per tool: what
     *  differs between two tools is their prose, and this is the part that doesn't. */
    @Suppress("ktlint:standard:wrapping")
    private fun tool(name: String, description: String, vararg params: Param?): JsonObject = jsonObject {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", jsonObject {
            addProperty("type", "object")
            add("properties", jsonObject {
                params.filterNotNull().forEach { p ->
                    add(p.name, jsonObject {
                        addProperty("type", p.type)
                        p.enum?.let { e -> add("enum", JsonArray().apply { e.forEach { add(it) } }) }
                        p.default?.let { if (p.type == "boolean") addProperty("default", it.toBoolean()) else addProperty("default", it) }
                        addProperty("description", p.description)
                    })
                }
            })
            add("required", JsonArray().apply { params.forEach { if (it?.required == true) add(it.name) } })
        })
    }

    /** What each lane IS. Which ones a caller sees, and which is the default, are the schema's `enum` and
     *  `default` instead — so a host that has no client lane never describes one. */
    private val LANE_PROSE = mapOf(
        "client" to "\"client\" (client tick)",
        "render" to "\"render\" (render tick, once per frame)",
        "server" to "\"server\" (integrated or dedicated server tick)",
        "parallel" to "\"parallel\" (off-tick, concurrent, no guard; for computation, blocking I/O, and patch install/removal)",
    )

    /** Same for `run_command`'s two identities. */
    private val COMMAND_PROSE = mapOf(
        "client" to "\"client\" runs it as your player through the vanilla chat-box path — client-only mod " +
            "commands run locally, everything else goes to the connected server with your permission " +
            "(feedback either way is a best-effort capture of the chat window)",
        "server" to "\"server\" runs in the local server at permission level 4 and returns its synchronous " +
            "feedback (only while one is running)",
    )

    /** The `tools/list` payload: the tools' prose. [tool] carries the shape they share. */
    @Suppress("ktlint:standard:wrapping")
    fun toolsList(): JsonArray = JsonArray().apply {
        add(tool(
            "execute_code",
            "Evaluate Kotlin inside the running Minecraft game; returns the last expression's value plus whatever its scoped, " +
                "shadowing println/print writes, from any thread, until it returns. Compiled against the live, deobfuscated game " +
                "classpath: write net.minecraft.* directly using Mojang-mapping names, and read/call private, protected, and Kotlin " +
                "internal members directly — no reflection needed. WARNING: an opened private field outranks a same-named getter/setter.",
            // Patches/Probe are OBJECTS — a bare handle(id) does not compile, and an unqualified far call reads as if it would.
            Param("code", required = true, description = "Kotlin source, run on the target lane's tick thread. To span ticks, " +
                "make the last expression an iterator { ... yield(v) ... }, one step per tick, reporting each v. Only patches and " +
                "probes persist across evals.\n\n" +
                "Patches.onEnter(cls, method, params?, tag?) { key, self, args -> ... } and Patches.onExit(...) { key, self, args, " +
                "returned, thrown -> ... } weave a method, returning a live handle: .id .targets .pending .detached .fires .failures " +
                ".lastError .weaveError. params picks one overload by case-sensitive JVM type name — listOf(\"Level\", \"int\", " +
                "\"*\"); omit for all; a miss or ambiguity throws with the candidates. Re-installing a target replaces it unless " +
                "tag differs.\n\n" +
                "Patches.intercept(cls, method, params?, tag?, onReturn?) { key, self, args -> ... } = @Inject(at=HEAD, " +
                "cancellable=true): write args to rewrite them, return Patches.proceed() or Patches.returns(v) to cancel with v. " +
                "Patches.modify(cls, method, params?, tag?) { key, self, args, returned, thrown -> ... } = @ModifyReturnValue, " +
                "same two answers. onReturn takes modify's lambda and sees the body's result, never a cancelled one. A bad type " +
                "or a throwing callback does nothing and counts in .failures. One writable patch per method, evicting observers " +
                "there unless both are tagged.\n\n" +
                "Also Patches.handle(id), Patches.handles(), Patches.remove(vararg ids), Patches.removeAll(), and " +
                "Patches.removeEnter/removeExit/removeIntercept/removeModify(cls, method, params?) — removal by name matches what " +
                "was woven, so listOf(\"*\") removes what listOf(\"Level\") installed.\n\n" +
                "Probe.emit(channel, value) writes one line from a handler; a handle's .id doubles as a channel, fresh per " +
                "install. Probe.segments(channel) reads without draining, Probe.take(channel) drains — both give a Sequence of " +
                "chunks, not one string, so join or scan it; boundaries fall between lines. Channels are unbounded: " +
                "Probe.mute(channel), Probe.unmute(channel), Probe.clear(channel), Probe.resetAll()."),
            Param("target", enum = Lanes.targets(), default = Lanes.DEFAULT.name,
                description = "Execution lane: " + Lanes.targets().joinToString(", ") { LANE_PROSE.getValue(it) } + ".",),
        ))
        add(tool(
            "run_command",
            "Run a Minecraft command and return its output text. Example: \"time query daytime\".",
            Param("command", required = true, description = "The command(s) to run, without a leading slash. Multiple lines run " +
                "in order like a .mcfunction, dispatched in a single tick; blank lines and lines starting with # are skipped, a " +
                "failing line does not stop the rest."),
            Param("target", enum = Lanes.commandTargets(), default = Lanes.DEFAULT.name,
                description = "Execution identity: " + Lanes.commandTargets().joinToString(". ") { COMMAND_PROSE.getValue(it) } + ".",),
            Param("allow_untrusted_chat", type = "boolean", default = "false",
                description = "Include chat lines a player can influence — other players' messages, whispers, and anything a chat " +
                    "plugin reformats. Withheld and counted otherwise. Only effective with target=\"client\".",
            ).takeIf { Lanes.CLIENT.isReady },
        ))
        // Declared only where one can exist. The probe is a launch constant, so the answer can't go stale and
        // no listChanged notification is owed; a dedicated server just never spends context on it.
        if (Lanes.RENDER.isReady) {
            add(tool(
                "take_screenshot",
                "Capture what the Minecraft client is rendering right now — the world plus any open GUI — and return it as an image.",
            ))
        }
    }
}
