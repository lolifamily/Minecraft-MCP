package org.js.lolifamily.minecraftmcp.mcp;

import net.minecraft.client.Minecraft;
import org.js.lolifamily.minecraftmcp.compat.ClientCommandCompat;
import org.js.lolifamily.minecraftmcp.exec.Capture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a command AS THE PLAYER, for the {@code run_command} tool's {@code target=client} — i.e. exactly what
 * typing it into the chat box does.
 *
 * <p>It calls the SAME vanilla method the chat screen uses — {@code ClientPacketListener#sendCommand} on MC
 * {@code >=1.19}, else {@code LocalPlayer#chat("/"+cmd)} on {@code <=1.18.2} (the 1.19 secure-chat rework split
 * the send path; see {@link org.js.lolifamily.minecraftmcp.compat.ClientCommandCompat}) — so every loader's
 * client-command interception fires automatically: Fabric mixes into that method, NeoForge/Forge consult
 * {@code ClientCommandHandler} on that path. A client-only mod command (Baritone, clientcommands, ...) is
 * caught and run locally; anything else goes to the connected server, executed with THIS PLAYER's permission.
 *
 * <p>Feedback is captured by the ChatComponent mixins at the public chat entries (best-effort — see those
 * classes). This class opens a {@link ChatCapture} window for {@code CAPTURE_WINDOW_MS} around the send:
 * unrelated chat during the window mixes in, and a reply slower than the window is missed. What lands in the
 * window is filtered by {@link ChatTrust} unless {@code allowUntrusted}.
 *
 * <p>Loads only when the client path is actually taken, so it never touches client classes on a dedicated
 * server.
 */
final class ClientCommandRunner {

    /** Prefix on every line THIS class inserts, so a reader (the LLM) can tell our notes from real captured
     *  game output, which is never prefixed. */
    private static final String NOTE = "[mcp]";

    /** How long the chat-capture window stays open after the send goes out, collecting feedback. */
    private static final long CAPTURE_WINDOW_MS = 500;

    private ClientCommandRunner() {}

    /**
     * Send {@code command} as the player and collect chat-window feedback for {@code CAPTURE_WINDOW_MS}.
     *
     * @param allowUntrusted keep player-influenced lines rather than counting them as withheld.
     * @return the captured game output verbatim; lines we insert ourselves are prefixed {@code [mcp]} so a
     *         reader can tell them from real output. Returns {@code null} when there is no local player
     *         — so the caller can fall through to a precise not-ready error.
     *         {@code Minecraft.getInstance()} is non-null from mod init on, but half-built until its
     *         constructor returns — what makes that safe is that nothing here reads client state off the game
     *         thread, not {@code Lanes.CLIENT.isReady}, which is a physical-side constant.
     */
    static String run(String command, boolean allowUntrusted) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return null;

        List<String> cmds = split(command);
        if (cmds.isEmpty()) return NOTE + " no command given";

        Capture buf = new Capture();
        ChatCapture.Window win = ChatCapture.open(buf, allowUntrusted);   // the mixins fan chat lines into this
        AtomicInteger unsent = new AtomicInteger();                  // commands ClientCommandCompat.send rejected
        String failure = null;                                       // set below; reported WITH the capture, not instead
        try {
            // Wait for the send to actually run before starting the 500ms clock: submit() runs on the next client
            // tick, far later under load, so timing from submit would spend the clock before anything went out.
            // get() also happens-before the unsent reads below, so their visibility needs no guessing. The
            // ChatCapture window is open already — a client-side mod command can answer on the very send tick.
            // Untimed, as ScreenshotRunner: the client loop has no terminal boundary, so a wait that never
            // returns means the game has already stopped — and this thread is a daemon.
            mc.submit(() -> {                                        // chat/command state is client-thread affine
                if (mc.getConnection() == null) {
                    unsent.set(cmds.size());
                    return;
                }
                for (String cmd : cmds) {
                    if (!ClientCommandCompat.send(mc, cmd)) unsent.incrementAndGet();
                }
            }).get();
            Thread.sleep(CAPTURE_WINDOW_MS);
        } catch (Throwable t) {
            // Never throws: the failure becomes an [mcp] note like every other, so the caller hands the return
            // value straight back. Recorded rather than returned because the send already went out — whatever
            // came back before the failure is real feedback, and returning here would drop it.
            // Only ExecutionException is reachable: nothing interrupts an mcp-http thread (McpServer's pool).
            failure = NOTE + " run failed: " + t;
        } finally {
            ChatCapture.close(win);
        }

        String out = buf.take().trim();
        // Built in final order — captured output first, then our notes — so there is no front-insert and
        // emptiness is one check instead of two.
        List<String> lines = new ArrayList<>();
        if (!out.isEmpty()) lines.add(out);
        // First among the notes: it is why the two below may be missing or short.
        if (failure != null) lines.add(failure);
        int dropped = unsent.get();
        if (dropped > 0) {
            // A false from send() is NO send path at all — not "handled locally", which is the ordinary
            // client-mod interception and returns true. Silence here would read as success.
            lines.add(NOTE + " " + dropped + " command(s) dropped (client not ready or unsupported runtime)");
        }
        int withheld = win.withheld();
        if (withheld > 0) {
            lines.add(NOTE + " " + withheld + " player-influenced line(s) withheld — set allow_untrusted_chat=true");
        }
        if (lines.isEmpty()) {
            return NOTE + " sent as your player; no chat feedback within " + CAPTURE_WINDOW_MS + "ms";
        }
        return String.join("\n", lines);
    }

    /** One command per line (like .mcfunction): skip blanks + '#' comments, strip a leading '/' — {@code
     *  sendCommand} wants the bare command, exactly as the chat screen passes it ({@code string.substring(1)}). */
    private static List<String> split(String command) {
        List<String> cmds = new ArrayList<>();
        for (String line : command.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            cmds.add(t.startsWith("/") ? t.substring(1) : t);
        }
        return cmds;
    }
}
