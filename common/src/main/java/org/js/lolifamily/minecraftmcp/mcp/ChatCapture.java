package org.js.lolifamily.minecraftmcp.mcp;

import net.minecraft.network.chat.Component;
import org.js.lolifamily.minecraftmcp.exec.Capture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Open chat-capture windows for {@code run_command target=client}. The ChatComponent mixins append every chat
 * line to all open windows; {@link ClientCommandRunner#run} opens one before sending a command and closes it
 * after the capture window. Public because the mixins live in a different package.
 *
 * <p>References only {@link Capture}, {@link ChatTrust}, {@code network.chat.Component} and JDK types — never a
 * {@code net.minecraft.client.*} class — so it links cleanly on a dedicated server (where it is never used).
 */
public final class ChatCapture {

    private ChatCapture() {}

    /** One per open window, and every window gets EVERY line: a chat line carries nothing tying it back to the
     *  command that caused it, so concurrent run_commands necessarily see each other's output. */
    private static final List<Window> WINDOWS = new CopyOnWriteArrayList<>();

    /** A sink and the policy it was opened under. */
    static final class Window {
        private final Capture sink;
        private final boolean allowUntrusted;
        private final AtomicInteger withheld = new AtomicInteger();

        private Window(Capture sink, boolean allowUntrusted) {
            this.sink = sink;
            this.allowUntrusted = allowUntrusted;
        }

        /** How many lines the policy dropped — reported by the caller, never silently swallowed. */
        int withheld() {
            return withheld.get();
        }
    }

    static Window open(Capture sink, boolean allowUntrusted) {
        Window w = new Window(sink, allowUntrusted);
        WINDOWS.add(w);
        return w;
    }

    static void close(Window w) {
        WINDOWS.remove(w);
    }

    /** Every entry but 26.1's {@code addPlayerMessage}, which is the only one that knows. */
    public static void append(Component c) {
        append(c, false);
    }

    /** {@code knownPlayer} is that entry's structural certainty; everything else asks {@link ChatTrust}, which
     *  is the expensive half — hence the empty check first. */
    public static void append(Component c, boolean knownPlayer) {
        if (WINDOWS.isEmpty()) return;
        boolean untrusted = knownPlayer || ChatTrust.isUntrusted(c);
        String line = c.getString();
        for (Window w : WINDOWS) {
            if (untrusted && !w.allowUntrusted) {
                w.withheld.incrementAndGet();
            } else {
                w.sink.appendLine(line);
            }
        }
    }
}
