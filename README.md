# Minecraft MCP

> [!WARNING]
> **This runs arbitrary code inside your game, and its bytecode injection reads as cheating.** Whoever holds
> the access token gets a shell on this machine; anti-cheat that spots the mod bans you, or bans your
> players — see [Before you install](#before-you-install).

Turn a running **Minecraft** instance into an [MCP](https://modelcontextprotocol.io) server — as a mod on
**Fabric, Forge or NeoForge**, or as a **Paper** plugin; client or dedicated server. An LLM connects over local HTTP and runs
**Kotlin directly inside the live game**, compiled against the game's *deobfuscated* classpath: you write
`net.minecraft.*` in plain Mojang-mapping (mojmap) names and reach `private`, `protected`, package-private
and Kotlin `internal` members with **no reflection** — the mod remaps and access-widens your code on the way in.
It is the last Minecraft MCP server you need to install — early days, but the idea has nowhere further to
go. Once a snippet reaches whatever the process reaches, a successor has nothing left to add.

One core (`common`), one thin entry point per host. The same `execute_code` runs on an integrated server, a
dedicated server, or a client; it is compiled once against the running game and remapped to whatever naming
the runtime actually uses (dev mojmap, Fabric intermediary, Forge SRG, spigot obf).

---

## Before you install

### This is remote code execution by design

The bearer token **is** the root password. Anyone holding it can compile and run arbitrary Kotlin — and
therefore arbitrary JVM — inside the game process: file I/O, spawning processes, JNI, `System.exit`. There
is no sandbox.

- **Never** share, screenshot, or commit the token — or the config file holding it. It is never printed to
  the game log; only the path to the file it lives in is.
- **Never** port-forward the listener or expose it past `localhost`.
- The server binds loopback only, requires the `Host` header to be exactly `localhost:<port>`, **rejects
  any request carrying an `Origin` header** (browser / DNS-rebinding defense), and compares the token in
  constant time.
- The token may be sent as `Authorization: Bearer <token>` **or** as `?token=<token>` in the url — never
  both (that is refused). The url form exists for clients that cannot set a header.
- A second gate (`AuthGate`) governs *above-player* authority on a client: `execute_code` and
  `run_command target=server` only run when you could already cheat — single-player **with cheats on**, or
  a remote server where you are **OP level ≥ 3** — or when **no world is loaded at all**, as at the main
  menu, where there is nothing to govern. A dedicated server's operator owns the box, so it is
  always open. A revoke — cheats off, deopped, a server switch — reaps every running eval and silences
  installed patches, which stay woven. This is a "don't grief a world you don't control" guard, **not** the
  security boundary — the token already is full RCE.
- The per-tick watchdog unwinds a script spinning in its own loop or recursion; a single blocking call
  still stalls the tick. Best-effort, and **not a security boundary**.

### Anti-cheat will get someone banned

The mod self-attaches a JVM agent, retransforms loaded `net.minecraft.*` classes and loads fresh bytecode on
demand — textbook injection signatures — and makes **no attempt to hide**, deliberately. `AuthGate` does not
help: it only refuses *above-player authority* when you could not already cheat. Installing on a client and
installing on a server are two different problems:

- **On a client — get whitelisted, or it bans *you*.** The trap is a client-side anti-cheat bundled in a
  server's modpack: "I'm only testing in single-player" is not safe, because it loads with your game and can
  report home on its own, banning you from a server you never joined. Get whitelisted first, **or pull the
  anti-cheat mod out of your local instance** — removing it for offline debugging is normally fine. (A
  server-side-only anti-cheat never sees your local instance; nothing to do.)
- **On a dedicated server — turn auto-ban off, or it bans *your players*.** Nothing detects the mod here;
  the risk is false positives. A script that moves players, entities or blocks reads as cheating, and the ban
  lands on whoever got moved. For as long as this mod is installed, run the anti-cheat in **warn/log-only
  mode** and review detections by hand.

### Connector, Kilt and hybrid servers — install the host, not the guest

Supported, under one rule: **install the build for the loader the game actually boots** — NeoForge under
Connector, Forge under Kilt, the mod build on a hybrid server (Mohist, Arclight, Banner, CatServer, Silkard,
Youer, …). Reaching what they drag in needs nothing further: it is all in the process, so a script sees
Fabric guests and Bukkit plugins like any other mod.

- **A guest MCP is unsupported.** Guest *mods* are fine; converting this one through Connector or Kilt is not.
- **The plugin build not reaching mods is deliberate.** Paper's API sees what mods *do* — just not from a
  postfix Mixin. The lanes are postfix Mixins at the **highest priority**, last among them, where a plugin
  cannot sit.
- **A plugin loaded on a CLIENT is reachable only reflectively.** The compile classpath is assembled once,
  early, so a plugin some client-side Bukkit layer loads later is not on it — `import` fails where
  `Class.forName` succeeds, runtime lookup being live. The plugin **API** itself is unaffected: it rides the
  game loader like any other mod jar.
- **The two are independent and compose.** A hybrid server running Connector gives one script the host's
  mods, the Fabric guests and the plugins at once.

---

## What it is

```
        MCP client  (Claude, etc.)
              │   HTTP · JSON-RPC · Bearer token     (localhost only)
              ▼
   ┌─────────────────────────────────────────────┐
   │  McpServer            (common)               │  jdk.httpserver · Streamable HTTP · /mcp/
   │  auth · Host/Origin · constant-time token    │  tools: execute_code · run_command · take_screenshot
   │                                              │
   │  ReplHost   (K2 compiler, one script per eval)│  compile off-tick → remap → run on-tick
   │   · masking loader (kotlin off module path)  │
   │   · mojmap ⇄ runtime remap (tiny-remapper)   │
   │   · access-widen private/protected/internal  │
   └─────────────────────────────────────────────┘
              │  steps your Kotlin on a game thread
      ┌───────┼────────────┬───────────────┐
      ▼       ▼            ▼               ▼
   server   client      render         parallel
   tick     tick        frame          off-tick, 1 thread/eval
   └──── Mixin heartbeats, one step/tick ────┘   (no guard)
```

The core (`common`) is host-neutral, compiled against vanilla Minecraft via **Unimined**. Each host adds one
thin entry point — Fabric (`ModInitializer`), Forge and NeoForge (a Kotlin `@Mod` class), Paper (a
`JavaPlugin`, loaded at `STARTUP`) — each calling `CommonClass.init()`, which opens the localhost endpoint
and warms the Kotlin compiler in the background. `bridge` is a pure-`java.*` shim injected into the bootstrap
classloader for the live-patch engine (see [How it works](#how-it-works)).

Your code runs on one of four **lanes**. The ticking three (`server` / `client` / `render`) are driven by
Mixin "heartbeats" injected at a game method's return, stepped once per tick on that side's own thread — so
a step has exclusive access to that side's state. `parallel` runs off-tick, one dedicated thread per eval —
no pool, so nothing queues and nothing bounds how many run at once. Which lanes exist is **observed, not
assumed**: a dedicated server ticks only `server`; a remote-connected client ticks `client` and `render` but
has no local server; single-player ticks all three.

## Connecting

On startup the mod logs the endpoint, and — the first time only — where it put the bearer token:

```
[MCP] listening on http://localhost:25599/mcp/
[MCP] bearer token written to <configdir>/minecraft_mcp.json — point your MCP client at it
```

- **Default port `25599`.** If it is already taken the mod climbs the next few ports (`25599 → 25608`) and logs
  the one it bound. That choice is not saved, so a later launch may land elsewhere — set `mcp.port` to pin it,
  and a port you set is bound exactly or the start fails, never silently moved.
- **Token.** Generated once and kept, so the client you configure today still works after a restart. It is
  **never written to the log**; read it out of the config file.

Configuration is that JSON file plus two out-of-band overrides that beat it — see
[Configuration](#configuration). There is no in-game settings screen.

> Address the server as `localhost`, **not** `127.0.0.1`. The `Host` header must be the exact bytes
> `localhost:<port>`, and the path is `/mcp/` — **with** the trailing slash. Either one wrong and the
> request gets no reply at all: the socket closes having written nothing.

Transport is **MCP Streamable HTTP** (no SSE): POST JSON-RPC to `http://localhost:<port>/mcp/` with an
`Authorization: Bearer <token>` header — or, for a client that cannot set headers, POST to
`http://localhost:<port>/mcp/?token=<token>` instead. Sending both is refused with `-32600`. A wrong or
missing token is refused with `403`, not the usual `401` — a `401` must carry a `WWW-Authenticate`
challenge, which would send a conforming client probing for OAuth metadata this server does not have.
`initialize` returns an `Mcp-Session-Id`; every later request must carry it or is refused with `400`. Issued ids are not
recorded, so one that outlived a game restart still works — where rejecting it would send the model back
through `initialize` and cost it its conversation context and its prompt cache. Point any standard MCP client
at it:

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "streamable-http",
      "url": "http://localhost:25599/mcp/",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Driving the raw protocol by hand:

```bash
# initialize — -i to see the Mcp-Session-Id come back in the response headers
curl -si http://localhost:25599/mcp/ \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# run some Kotlin on the server tick — any non-empty session id is accepted
curl -s http://localhost:25599/mcp/ \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -H 'Mcp-Session-Id: curl' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"execute_code",
                 "arguments":{"code":"net.minecraft.world.level.block.Blocks.STONE.descriptionId","target":"server"}}}'
```

## `execute_code`

Evaluate Kotlin inside the running game. The **last expression is the return value**; what the shadowed
`print` / `println` writes comes back alongside it.

| field    | required | what goes in it                                                                                   |
|----------|----------|---------------------------------------------------------------------------------------------------|
| `code`   | yes      | Kotlin source. The last expression is returned. `println` / `print` are captured (see below).     |
| `target` | no       | Lane: `client`, `render`, `server`, or `parallel`. Omitted picks this host's own side.            |

- **Compiled against the live, deobfuscated game.** Write `net.minecraft.*` with mojmap names. Every loaded
  class — the game, its libraries, other mods, the JDK — is on the compile classpath, and **other mods' APIs
  read in mojmap too**, including the ones that ship compiled against intermediary. On non-mojmap
  runtimes (Fabric intermediary, Forge SRG, spigot) the compiled bytecode is **remapped mojmap → runtime**
  before it runs; on a mojmap runtime it runs as-is.
- **Accessibility is opened for you.** `private`, `protected`, package-private and Kotlin `internal` members
  are callable **directly, no reflection** — across the game's own classes *and* other mods. Only the JVM's hardest
  `private`-across-loaders cases still need reflection. One consequence: an opened field outranks a
  same-named getter/setter, so `obj.foo` reads the *field* — write `obj.getFoo()` to go through the method.
- **Output.** A shadowed `println(...)` / `print(...)` writes to a per-eval sink returned with the result —
  every call that reaches it before the eval returns, from whatever thread, including ones the snippet
  spawned itself. After the return the sink is emptied and later writes are dropped, not buffered.
  Fully-qualified `kotlin.io.println` or `System.out.println` bypass the shadow onto the JVM's real stdout —
  and out of this eval's result. Library code that prints goes the same way.
- **Each eval is isolated.** Every call is its own script, compiled to its own class on its own loader, so
  reusing names like `x` / `result` across calls never collides — and nothing carries over either.
- **Top-level declarations are script declarations.** `class`, `fun`, `val` and local declarations all work
  where you would expect. A named `object` does not: a script's top level cannot hold one, because it would
  capture the script instance. Use a `class`, or an anonymous `object : … { }`.
- **Spanning ticks.** A step must return within the per-step budget (see `mcp.eval.step.budget.ms`), which is
  shared by every eval stepped in that tick. Overrunning it stalls that tick until the watchdog kills the step
  with `ScriptTimeoutError`. To run across ticks, make the last expression an `iterator { … yield(v) … }` — it
  is driven one step per tick, cooperatively, and each `v` is reported the way a returned value is. `yield(Unit)`
  only waits.
- **No timeout, cancellation instead.** A call blocks until the eval finishes; a JSON-RPC
  `notifications/cancelled` ends it immediately with its partial output. (A never-yielding `while (true)` is
  the watchdog's job, not this call's.)

Two things **persist across evals** (pre-imported, no `import` needed):

- **`Patches`** — install a live method hook with ByteBuddy, to watch a method or to change what it does.
  - **Installing.** `Patches.onEnter(cls, method) { key, self, args -> … }` or
    `Patches.onExit(cls, method) { key, self, args, returned, thrown -> … }` (exit fires on a thrown exception
    too). Both take an optional `params` list — `listOf("Level", "int")`, or `"*"` per slot — to pick one
    overload; without it every overload of the name is hooked. Entries are JVM type names, matched
    case-sensitively (`int`, not Kotlin's `Int`). A wrong or ambiguous signature throws, listing the real
    candidates. Re-installing the same target replaces it, minting a new `id`; pass a `tag` to keep two
    patches on one method instead.
  - **Changing what it does.** `Patches.intercept(cls, method) { key, self, args -> … }` runs before the body
    — Mixin's `@Inject(at = HEAD, cancellable = true)`. Write into `args` to rewrite the arguments, then
    return `Patches.proceed()` to run the body or `Patches.returns(v)` to skip it and return `v` instead.
    `Patches.modify(cls, method) { key, self, args, returned, thrown -> … }` runs after and replaces the
    return value the same way — `@ModifyReturnValue`. It cannot stop the body, so side effects have already
    happened. Pass `onReturn = { … }` — the `modify` lambda — to `intercept` to also observe what the body
    produced; it stays silent on a skipped call, its advice being woven *inside* the one that skips.
    A value the method cannot return, or a callback that throws, changes nothing — arguments included, which
    are rolled back — and lands in `failures`. One writable patch per method, and installing it evicts
    observers there too; give both sides a `tag` to keep them, and they run in install order.
    Two limits worth knowing: **constructors cannot be patched at all** (resolution reads
    `getDeclaredMethods`, which does not list them), and types are checked at the *JVM* level — writing
    `null` into a Kotlin non-null parameter or return passes, then trips the callee's own intrinsic.
  - **The handle.** `onEnter` / `onExit` only **observe**: they cannot change the arguments, skip the body, or
    alter the return, and their return value is discarded. The install returns a live handle — an `id`, the methods
    actually woven, and how it has behaved since: `fires`, `failures`, `lastError`. A throwing handler is
    swallowed so the patched method still runs, which leaves `failures` the only sign one is broken. That
    `id` is minted per install and is also the `key` a handler is passed, so a handler can name its own
    patch; an id kept across a re-install reads as gone (`null`) instead of reporting the replacement under
    the old name.
  - **Inspecting and removing.** `Patches.handle(id)` / `Patches.handles()`, `Patches.remove(id)`,
    `Patches.removeAll()` — or undo an install by repeating its own first line: `Patches.removeEnter` /
    `removeExit` / `removeIntercept` / `removeModify`, which take the same optional `params`, matched
    against what the patch actually wove, so `listOf("*", "*")` removes what `listOf("Level", "int")`
    installed.
- **`Probe`** — named channels a patch handler writes and a later eval reads (unbounded). Any name works, and
  the `key` a handler receives is a good one: it changes per install, so a re-run starts a fresh channel
  instead of appending to the previous run's.
  `Probe.emit(channel, value)` → `Probe.segments(channel)`, a `Sequence<String>` of immutable chunks rather
  than one joined string, so the cost of materializing a channel is written where it is paid; a chunk boundary
  always falls between lines. `Probe.take(channel)` reads and empties. Enumerate with `Probe.ids()`, freeze a
  runaway channel with `Probe.mute(channel)` and resume it with `Probe.unmute(channel)`, reclaim with
  `Probe.clear(channel)` / `Probe.resetAll()` — `clear` keeps a mute, `resetAll` drops it.

**Examples**

Read client-side state (`target: "client"`):

```kotlin
val mc = net.minecraft.client.Minecraft.getInstance()
val p = mc.player
"name=${p?.name?.string}  pos=${p?.blockPosition()}  health=${p?.health}"
```

Install a live patch, then read what it observed in a **later** call — the distinctive move here:

```kotlin
// eval 1 — intercept every system message the server broadcasts
Patches.onEnter("net.minecraft.server.players.PlayerList", "broadcastSystemMessage") { _, _, args ->
    Probe.emit("chat", args.firstOrNull())
}
```

```kotlin
// eval 2, any time later — drain the channel
Probe.take("chat").joinToString("")
```

Spread work across ticks:

```kotlin
iterator {
    for (i in 3 downTo 1) {
        println("tick $i")
        yield(Unit)   // resume next tick
    }
    println("done")
}
```

Off the tick, for pure computation or blocking I/O (`target: "parallel"` — no guard; reach game state only
by submitting to a tick thread):

```kotlin
java.net.InetAddress.getByName("example.com").hostAddress
```

## `run_command`

Run a Minecraft command (no leading slash) and get its output text. Multiple lines run in order like a
`.mcfunction`; blank lines and `#` comments are skipped, and a failing line doesn't stop the rest.

| field                  | required | what goes in it                                                          |
|------------------------|----------|--------------------------------------------------------------------------|
| `command`              | yes      | The command(s), one per line, no leading `/`.                            |
| `target`               | no       | `client` or `server`; omitted picks this host's own side.                |
| `allow_untrusted_chat` | no       | Include chat a player could have shaped. `client` only; default `false`. |

- **`server`** — runs in the local server at **permission level 4**, above the world's cheat setting, and
  returns its synchronous feedback. Requires a local server (integrated or dedicated); a pure client on a
  remote server has none. Gated by `AuthGate`, same as `execute_code`.
- **`client`** — runs **as your player** through the vanilla chat-box path: client-only mod commands run
  locally, everything else goes to the connected server (integrated or remote) with *your* permission.
  Feedback is a best-effort capture of the chat window: command output comes back, but a line a player
  could have shaped — other players, whispers, anything a chat plugin reformats — is withheld and counted
  unless you pass `allow_untrusted_chat`. This path grants nothing above the player, so it is **not**
  gated — a non-OP player on a remote server can still use it, exactly as if typing into chat.

```
# target: "server"
time set day
weather clear
```

## `take_screenshot`

What the client is rendering right now — the world plus whatever GUI is open — as a JPEG image. No arguments.

- **Client only.** A dedicated server has no framebuffer and doesn't advertise the tool at all, so those
  sessions never spend context on it.
- Reads the game's own off-screen render target, not the desktop: occlusion, window scaling and DPI don't
  affect it, and you get the real render resolution.
- **Not** gated by `AuthGate` — it returns what the player is already looking at, so it grants nothing above
  them, same as `run_command target=client`.
- Costs the game one texture readback: asynchronous and stall-free from MC 1.21.5, and before that the same
  one-frame hitch as pressing F2.

Size and quality are fixed and deliberately not arguments. What an image costs a vision model is set by its
dimensions, and the only party who could pick a number is a model with no idea what resolution it can see.

## Targets (lanes)

|              | `server`                          | `client`                | `render`                 | `parallel`                     |
|--------------|-----------------------------------|-------------------------|--------------------------|--------------------------------|
| Thread       | server tick                       | client tick (~20/s)     | render frame             | one thread per eval (off-tick) |
| Heartbeat    | `MixinMinecraftServer#tickServer` | `MixinMinecraft#tick`   | `MixinMinecraft#runTick` | — (always ready)               |
| Watchdog     | ✅ own budget                     | ✅ shared with `render` | ✅ shared with `client`  | ❌ (no guard)                  |
| Ready when   | a local server is running         | on a client             | on a client              | always                         |
| Available on | integrated / dedicated server     | client only             | client only              | anywhere                       |

One watchdog budget per pump, shared by every eval stepped in it. `client` and `render` run on the same
thread and share one watchdog.

An eval runs until it finishes, throws, trips the watchdog, or you cancel it: unloading a world, changing
dimension or switching servers does **not** stop it, so a script can watch a teardown happen. Two things do
end one early — an authorization revoke, and (on `server`) the local server stopping, past which nothing
would step it again — both with a "killed" result and its partial output. `parallel` evals stop on
cancellation, an authorization revoke, or JVM exit.

## Configuration

Every `mcp.*` setting resolves from three sources, first hit wins. A value that trims to empty counts as unset
and falls through, so a blank env var cannot shadow the file.

| source                           | spelling              | notes                                                         |
|----------------------------------|-----------------------|---------------------------------------------------------------|
| JVM argument                     | `-Dmcp.port=25599`    | This launch only. Visible in `ps` — don't put the token here. |
| environment variable             | `MCP_PORT=25599`      | The key uppercased, `.` → `_`. Derived, never a lookup table. |
| `<configdir>/minecraft_mcp.json` | `"mcp.port": "25599"` | Persisted. Keys are the property names verbatim.              |

The file is written once, on the launch that had to mint a token — that token, plus a null `mcp.port` for you to
fill in. After that the mod only reads it. `25599` and `"25599"` both parse; a null or blank value is unset. A
value it cannot read is warned about and the default is used, with your value left in place so the typo stays
visible; a file it cannot parse at all stops the endpoint rather than regenerating over it. Edits apply on next
launch.

| setting     | default        | meaning                                                                                                                 |
|-------------|----------------|-------------------------------------------------------------------------------------------------------------------------|
| `mcp.port`  | `25599`        | Listener port. Set = bound exactly or fail. Unset climbs through `25608`; that choice is logged, never saved.           |
| `mcp.token` | generated once | Bearer token. Set it to pin one; otherwise read it from the file. Pin from `A-Za-z0-9._~-` or the `?token=` form drops. |

Those two are the whole of normal configuration. Everything below is an escape hatch: you reach for it because
of a specific symptom, never because of a preference. Every key below resolves from the same three sources as
the two above.

### Escape hatches

| property                       | default | reach for it when                                                                                                                                                                                                                                |
|--------------------------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `mcp.eval.step.budget.ms=<ms>` | `1000`  | Scripts die with `ScriptTimeoutError` doing work that legitimately needs longer. This is the budget for ONE step (one tick's slice), not for the whole eval — raising it raises how long a single tick may stall.                                |
| `mcp.kotlin.regime=share`      | `auto`  | The log shows `SPLIT snippet↔host seam LinkageError` and it recurs. Also accepts `split`.                                                                                                                                                        |
| `mcp.mixin.overlay=false`      | `on`    | The overlay names a member the runtime does not have, and a snippet compiles only to fail on it. Off, mixin-merged members leave the compile classpath: cast where the mixin declares an interface, reflect where it does not.                   |
| `mcp.plugins.gate=false`       | `on`    | A server with plugins has not finished loading worlds and you want the REPL now. Off, the compile classpath is taken as it stands: a plugin's attached urls are on it only if it got there first. `import` fails where `Class.forName` succeeds. |
| `mcp.preload=false`            | `on`    | The classpath changed where the kotlin stamp cannot see it, or you suspect preload of a startup problem. The launch replays nothing and records afresh.                                                                                          |
| `mcp.stack.fold=false`         | `on`    | You are debugging the mod rather than a script and want back the lane pump, the REPL host and the launcher below your own frames — a report folds those to one line. Only those: a repeated cycle and Java's `... N more` still fold.            |

### Non-mojmap runtimes

On a Fabric intermediary, Forge Mixed-SRG or spigot runtime the mod downloads and assembles its own mapping bundle on
first launch, caching it under `<gamedir>/.mcp-cache/<version>/`. Nothing to configure — later launches are a
cache hit.

Only when the log says `auto-cache failed; … supply mcp.remap.mappings + mcp.remap.classpath manually` — the
download or build did not complete on a runtime whose namespace *is* recognized — does the bundle have to be
pointed at by hand:

| property                    | meaning                                                                                         |
|-----------------------------|-------------------------------------------------------------------------------------------------|
| `mcp.remap.mappings=<file>` | named↔runtime mappings: tiny v2 (`.tiny`) or forge TSRG2 (`.tsrg`), picked by extension.        |
| `mcp.remap.classpath=<dir>` | Directory holding the mojmap `mc-symbols.jar` to compile scripts against (`deps.txt` optional). |

The two are **one setting**: set both or neither. Half a pair — or a directory with no `mc-symbols.jar` in it —
is refused with an error and the auto-cache runs instead: mappings alone would leave scripts compiling against
the runtime MC jar, where no mojmap name resolves.

They override a **recognized** namespace's bundle; they cannot teach the mod one it failed to recognize. If the
log says `runtime naming unrecognized`, scripts and patches run unmapped and there is nothing here to point at.

### JVM flags

The mod needs **no JVM flags to run**. Two are **strongly suggested** on a server whose scripts call
`Patches` — the dev run configs set them automatically:

```
-Djdk.attach.allowAttachSelf=true     # Patches only (direct ByteBuddy self-attach)
-XX:+EnableDynamicAgentLoading        # Patches only (silences the JDK 21+ warning; a future JDK needs it)
```

Neither is required today. Without the first, the first patch of the process still installs — ByteBuddy falls
back to spawning a **whole second JVM** to attach on its behalf, which costs seconds rather than milliseconds
and is paid on whichever thread called `Patches` (use `target: "parallel"` to keep that off the tick). Without
the second you get the JDK 21+ warning block on startup, and once a future JDK flips its default
([JEP 451](https://openjdk.org/jeps/451)) `Patches` will throw without it — the flag stays a
supported opt-in, so adding it is the whole fix.

Everything else is agent-free either way: `execute_code`, all lanes, cross-module `internal` access, preload
training, chat capture, server-stop reap and the lane heartbeats.

The dev run configs also set `mcp.kotlin.libs`, `mcp.repl.classes` and `mcp.bridge.jar`, pointing the masking
loader and the patch bootstrap at build outputs. They are build plumbing, not configuration: a production mod
jar carries all three payloads inside itself and needs none of them.

Do **not** add `--add-modules=jdk.httpserver`. MC always launches by main class name, never `-m`, so the
initial module is unnamed and JEP 261 makes `jdk.httpserver` a root automatically — verified on both launch
shapes, and every Mojang runtime (gamma 17 / delta 21 / epsilon 25) ships the module despite being trimmed to
~70. On a hand-trimmed runtime that really lacks it the flag doesn't help either: it turns "no MCP endpoint"
into `FindException: Module jdk.httpserver not found` at boot.

## How it works

For anyone reading or extending the code — the Minecraft-specific machinery is the interesting part:

- **`McpServer`** — a `com.sun.net.httpserver.HttpServer` on *every* loopback address (`127.0.0.1` and `::1`,
  sharing one port — `localhost` is both, and clients disagree on which they dial: glibc picks `::1`, the JDK
  picks `127.0.0.1` and does not fall back), single (non-batched) JSON-RPC at
  `/mcp/`. Auth is loopback bind + exact `Host` match + no-`Origin` + constant-time token compare, of a
  `Bearer` header or a `?token=` parameter. Evals do
  **not** run on the HTTP thread: `execute_code` submits to a lane and the handler blocks on a
  block-until-done future while the eval is stepped on the lane's tick thread.
- **Lanes** — each is a Mixin injected at a game method's *return* that pumps the lane once, stepping every
  active eval one `MoveNext` on that thread. The injection carries the **highest priority**, so it runs last
  among everything else woven at that return. Readiness is asked of the tick source itself, and an eval is cut
  only when its heartbeat has stopped for good. Nothing sniffs "am I integrated / remote / dedicated", and
  nothing times a heartbeat.
- **`ReplHost`** — drives the embeddable Kotlin compiler directly, **warmed once** (the
  `KotlinCoreEnvironment` + FIR library index — the ~6 s classpath scan — is built in the background at
  startup and reused). Each eval compiles as its own *script*: the source is named with a non-`.kt`
  extension, which is the whole of what puts the parser into script mode and lets a snippet mix top-level
  statements with declarations. Only a per-eval source session is built on top of the warm state, so a steady
  eval costs a parse and a resolve, not a classpath scan. Compilation runs off the tick; the compiled snippet
  is stepped on the tick.
- **The masking classloader** — the Kotlin scripting/compiler stack is loaded on a **self-managed loader,
  off the game/module path**. It has to be: `kotlin-compiler-embeddable` ships a package named
  `org.jetbrains.kotlin.native.interop`, and `native` is a Java keyword, so Forge/NeoForge's module system
  rejects the whole jar as an automatic module. `ReplBridge` (game loader) reaches `ReplHost` (masking
  loader) through one reflective hop; after that it's ordinary Kotlin. In production these jars ride inside
  the mod jar (`mcp-kotlin/*.jar`) and are extracted at runtime — the mod is self-contained.
- **Deobfuscation** — you write mojmap; the runtime may not speak it. `NamespaceProbe` checks the runtime
  naming once at startup; if it isn't mojmap (Fabric intermediary, Forge SRG, spigot), compiled snippet bytecode is
  remapped mojmap → runtime with **tiny-remapper** before it loads. On a mojmap runtime nothing is remapped.
  Fabric production needs the reverse as well: intermediary renames *classes*, so a mod jar's signatures say
  `net.minecraft.class_1937` where `mc-symbols.jar` says `Level` — one runtime class under two names, which K2
  reads as two unrelated types. The access-widen overlay renames the mod side back to mojmap, so both agree.
  SRG and spigot rename only members, leaving class names identical already, and nothing is renamed there.
- **Access-widening** — reaching closed members needs two edits, because Kotlin and the JVM disagree on
  where visibility lives: JVM `ACC_*` flags are widened on the referenced classes, **and** the
  `@kotlin.Metadata` visibility proto is flipped (the K2 frontend reads Kotlin visibility from there, not
  from ACC flags). Cross-module `internal` needs neither: every classpath jar is handed to the compiler as a
  *friend module*, which is what `-Xfriend-paths` means once you build the dependency list yourself. At
  runtime an `invokedynamic` `AccessBridge` ignores access entirely. Both edits land in overlay jars
  prepended to the compile classpath — one per installed mod, one for everything else, so updating a mod
  rewrites only its own; on Fabric that same pass carries the intermediary → mojmap class rename above.
- **The patch engine** — `Patches` weaves a method with **ByteBuddy** Advice, self-attaching a JVM agent
  lazily on first use; it is the **only** feature the two [JVM flags](#jvm-flags) affect, because it retransforms
  already-loaded `net.minecraft.*` classes on the game loader — something a classloader cannot do for
  itself. A pure-`java.*` shim (the `bridge` module) is injected into the **bootstrap
  classloader** so a patched `net.minecraft.*` method and the mod's handler resolve the *same* callback
  identity across loaders. Everything else the mod hooks (lane heartbeats, chat capture, server-stop reap)
  is a Mixin, not a patch — so `Patches.removeAll()` only ever touches user patches.

## Building

A multiloader, multi-version Gradle (Kotlin DSL) build on JDK 25. Each MC version is a node under
`versions/<v>/`; one invocation builds one version.

```bash
./gradlew build                              # build the committed default version
echo 1.20.6 > versions/current               # switch the committed default
MCP_MC_VERSION=26.1.2 ./gradlew build        # build a specific version for one run (CI path)
./gradlew build -PmcVersion=26.1.2           # same, as a project property — outranks the env var
./gradlew build -PnoKotlinJij                # don't bundle kotlin; use the platform's provider instead
```

Supported nodes today: **1.18.2** (Forge SRG runtime), **1.20.6** (mojmap runtime), **26.1.2**
(unobfuscated). Hosts: Fabric (Loom), Forge (ForgeGradle 7), NeoForge (ModDevGradle), Paper (run-paper);
`common` is compiled against vanilla via Unimined; `bridge` is a standalone Java 17 jar.

### API docs

Dokka HTML. Each module builds its own site into its `javadocJar` as part of `assemble`, so a plain `build`
already produces them. The root project aggregates all modules into a single browsable site:

```bash
./gradlew :dokkaGenerateHtml                 # aggregated site -> build/dokka/html
```

The leading `:` is required — unqualified, the task name matches every project and writes one site per module
instead of one aggregate.

## Roadmap

### Planned

- [ ] **Lanes for runtimes that split the tick across threads.** The lane model assumes one tick thread per
  side, which is what makes a step's access to that side's state exclusive. Where a runtime isolates world
  state per chunk or region and steps those units concurrently, one `server` lane is no longer that unit —
  an eval has to be bound to the isolation unit it touches. Per-dimension isolation is the same problem at a
  coarser grain. Both land as a lane per unit, chosen by what the script reaches for rather than by a
  `target` string.
- [ ] **An IDE plugin — and GUI more generally — so a human can drive the same REPL.** Debugging a mod is a
  two-party job and only one party has a console today. The half worth building is live completion (IntelliJ
  first) over what the *running* game can reach — the live classpath, the mods actually loaded, the members
  already widened — instead of what the source tree declares.
- [ ] **A companion Skills repository, one skill per mod.** A model that already knows a mod's architecture
  and its few load-bearing invariants starts debugging on the first eval; one that doesn't spends several
  rediscovering them. That knowledge is per-mod, stable, and worth writing down once.
- [ ] **A backport to 1.7 – 1.14 — a branch, not another node.** No mojmap exists below 1.14.4, so `named` becomes
  MCP across the whole band, on every loader including Fabric: which names the *runtime* wears — Forge's srg,
  Legacy Fabric's intermediary — is what the remapper already handles, and holding `named` to one namespace keeps
  it that way. What forces the branch is `common` itself, which writes `net.minecraft.*` directly and names mixin
  targets as class literals, so no single source tree spans two sets of class names. Java 8 is the second half:
  every dependency the REPL stack needs is Java 8 compatible, so the cost is all in our own code — and not as a
  list of API substitutions, because parts of it are *designed on* the module system, classpath discovery and the
  masking loader among them. `Patches`' agent self-attach (`tools.jar`, which a JRE 8 does not ship) and the
  pre-Brigadier command path each need an answer of their own.

### Never

**A CLI.** "Just make it a command" does not pay for itself here:

- **Escaping gets worse, not better.** A Kotlin snippet through a shell picks up a quoting layer on top of
  JSON's: nested quotes and backslashes that are their own puzzle to solve, longer to write once solved, and
  a distraction from the problem either way. MCP hands the source over as one string field and the client
  owns the encoding.
- **A screenshot would cost two round trips.** Issue the command, then read back the file it wrote — two
  serial RTTs and a cache read, where the tool returns the image inline in one.
- **The schema is the cost you would pay anyway.** Tool-definition tokens are the standard charge against
  MCP, and "only three tools, all-string arguments" is half the answer. The other half is what those
  descriptions actually spend it on: this mod's own API — `Patches`, `Probe`, the lanes — which no model
  arrives knowing. A CLI moves that same text to `--help` and charges the same for it.
- **It adds nothing for composition.** This is arbitrary JVM execution: anything you would pipe it into, you
  can write in the snippet instead. There is no capability a shell puts within reach that RCE does not
  already have.

**Anything the agent does better.** Where the client is the right place for a control, this server leaves it
there rather than keeping a second, worse copy:

- **A server-side execution timeout.** The model already chooses how long to wait, and a client that gives up
  sends `notifications/cancelled`, which ends the eval and returns its partial output. That path is handled
  here; a timer on this side could only disagree with it. `execute_code` only — the other two block on the
  game thread and end when it answers.
- **A `cancel` tool.** Not the model's to reach for: `execute_code` has not returned, so it handed back no id
  to name, and the model is blocked on the very result it would need to decide the call is stuck. Parallel
  calls do not help — a turn emits them together, before any of them answers. Giving it something to name
  means returning a handle up front and making every eval a poll, which is a different and worse tool. What
  can act mid-call is the harness — a user interrupt, a client-side deadline — and it already has
  `notifications/cancelled`; one too dead to send that is too dead to call a tool.
- **Truncating long output.** Real problem, wrong layer. Cutting here either drops the result or spills it to
  a file outside the agent's managed scope — never auto-cleaned, and reading it back can raise a permission
  prompt. Only the client knows its own context budget. A model can also keep the output small at the source,
  counting and summarizing inside the snippet instead of returning the raw collection — which is the fix a cap
  is standing in for.
- **Throttling concurrent tool calls.** Calls may overlap — a client can issue a turn's worth at once —
  but the count is whatever one model turn emits, and that ceiling holds without anything here enforcing it. A
  limiter would be sized against a fan-out that does not arrive; where one does, the client issued it on
  purpose and knows its own outstanding count, which this side would be guessing at. It caps nothing either —
  the token is arbitrary JVM execution, so a single eval can take a hundred screenshots or spawn a hundred
  threads without passing a limiter on the tool call. The lanes serialize on thread affinity, not as a limit.

The watchdog is that same reasoning stopped one step short: it unwinds a runaway loop because the slip is
cheap to make and expensive to leave running, not because it stands in for a script that terminates. Simple
cases only, by construction — everything past that is the script's job.

**A unit-test suite.** Logic here does not regress, it gets surprised — the code is small and typed, and what
breaks it is the environment turning out to be something other than what the code assumed. A test over a pure
function is written from that same assumption: it fails on a typo and passes on the day the assumption is
wrong, which is the only day that matters here:

- **If MC cannot produce the input, it is not a bug.** A branch only a hand-built fixture can reach is
  defending against a caller that does not exist, and asserting on it teaches the next reader that it does;
  the honest fix is deleting the branch.
- **The bar for adding a test is integration.** Remapping, for one, would have to run against a real MC jar on
  the runtime naming it targets, not a synthetic descriptor exercising a case MC never emits. That is expensive
  enough that the tree has none today — and a cheap unit test is not a down payment on one.

## Credit

The idea is not mine — it is **Space Engineers**'. That game runs Roslyn inside itself, so scripts, mods and
plugins are all one language and differ only in what they are allowed to reach. What it has no answer for is
*testing* one. The closest thing is a third-party plugin,
[ScriptDev](https://github.com/viktor-ferenczi/se-script-dev), which watches a `Script.cs` and pushes it into
the programmable block whenever it changes — one-way, and silent about what happened next. Sitting with that,
the wish wrote itself: an MCP that compiles and runs whatever you hand it in the live game, and hands back
what it printed. That became [se-mcp](https://github.com/lolifamily/se-mcp) — three thin plugins over one
shared core, covering the SE1 client, the SE1 dedicated server and the SE2 client. Experimental next to this
one, but the shape is the shape you are reading about here: the core does the work, per-frame lanes step a
coroutine one `MoveNext` at a time, a watchdog unwinds the runaways, and accessibility is forced open at
compile time so `internal` needs no reflection. It argued its way onto
[StarCpt/PluginHub](https://github.com/StarCpt/PluginHub) — an RCE plugin is not an easy listing to win —
and went on to collect a whole handful of stars.

Minecraft is that same experiment aimed at the hardest case I could find, and picked for exactly that: near
the top of the complexity range, with a userbase to match. If the trick survives obfuscation, four loaders, a
module system and a decade of version drift, then anywhere you can get symbols is reachable, and this is a
brick thrown to draw out jade. Inspecting a live JVM is not new — there are plenty of ways to do it without
stopping the world, and every one of them is still less direct than typing a line of Kotlin. That part only
became true recently. Hand-writing a snippet was never going to beat a breakpoint; a model's round trip
through a debugger loses to a REPL every time. The tooling did not change. What is holding the keyboard did,
and the habits have to catch up.

There is a surface argument too. A command exposes what someone once thought to expose; a snippet reaches
whatever the process can reach, so a model stops asking whether the game has a command for the thing and goes
and looks instead. Code is also the modality the model actually has: simulated clicks and screenshot-driven
UI work are a thin slice of any training set, where source is most of it — and for a popular mod, the API a
snippet reaches for is very likely one the model has already read. That is not inference from a screenshot;
it is recall. Letting one poke around in the real code, live, does things no instruction set was ever going
to — and it is more fun to watch than it has any right to be.

It is also my first Java project, written by someone who knew neither Java nor Kotlin, end to end with
Claude — several of the Opus models. What it would have cost before is not a number I want to estimate, and I
came out of it with my sanity, which is more than
[`screaming.txt`](https://github.com/KiltMC/Kilt/blob/version/1.20.1/screaming.txt) in Kilt's tree says for
its author — "remapper screaming" included, and I know exactly which lines those were. Call it the AI
dividend, collected.

The honest bill, though: this was not a weekend. The spike alone — establishing that the idea worked at all —
took more than a week, and the whole thing ran about two months of full days, waking to sleeping, not
evenings after work. A fair share of that went on doubting whether I can drive an agent at all: abandoning a
line of attack one step too early, or grinding for hours on a premise that had already been wrong when I set
it. Some of that is the problem's shape. You cannot write a complete PRD for a question whose answer is *find
out whether this is possible*, and no Skill helps you build one either — Skills are not a resource you want
to accumulate, least of all on a one-person project. So this ran with no standalone design documents at all,
deliberately. The load-bearing decisions live in comments beside the code they constrain. Most of the wrong
turns were deleted rather than archived; the ones still there are the ones worth a warning — a road you would
otherwise go down again, marked closed and why. Attention should land near what it explains: whoever reads
this next, person or model, should find the reasoning where it applies rather than in a directory that has
drifted away from it.

Credit of a different kind goes to a livestream. [**Glavo**](https://space.bilibili.com/20314891) is a core
developer of HMCL — one of the launchers this ecosystem actually starts the game with — and, by
self-description, [a cat](https://www.bilibili.com/video/BV1TXJE6EEVK). Not a catgirl. A cat. That is what
got me to click, and without it none of the rest of this happens. An entity from an aviation mod clipped
through the floor mid-stream. Locating it came down to eyeballing candidates by hand; moving it, once
located, was retry after retry, with creative-mode tools barely able to budge the thing. Past that there was
nothing to do but play it cute for the chat. None of it for lack of skill — Glavo writes the launcher, the
JVM is home turf, and nobody in that stream was better placed to build the missing tool. That is what
surprised me. The ceiling is not expertise; it is that a shipped game leaves no seam an expert can talk to
while it runs, and everyone, expert included, has quietly priced that in and stopped noticing it was
missing.

Thanks as well to the several **Unity MCP** implementations, which settled the question of whether anyone
wants this — they do; there is just nothing pointed at games that have already shipped. And to
[Linux DO](https://linux.do), for the experience and the guidance while I was learning to work this way.

## License

MIT — see [LICENSE](LICENSE).
