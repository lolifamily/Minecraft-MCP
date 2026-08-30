package org.js.lolifamily.minecraftmcp.exec;

import org.js.lolifamily.minecraftmcp.platform.Services;

import java.util.Arrays;
import java.util.List;

/**
 * The execution lanes and how they observe the running game: three heartbeat-pumped tick lanes
 * (server / client / render) plus the off-tick {@link #PARALLEL} lane.
 *
 * <p>Each tick lane is a passive pump target: its heartbeat is a Mixin ({@code MixinMinecraftServer} for the
 * server tick, {@code MixinMinecraft} for the client tick + render frame) that calls {@link Lane#pump} at the
 * tick's RETURN, on that side's own thread.
 *
 * <p>The mixin topology is what lines the lanes up with the session: the client/render heartbeats are client
 * mixins (never applied on a dedicated server), and the server heartbeat is a common mixin whose
 * {@code tickServer} simply never runs on a remote-connected client.
 */
public final class Lanes {

    private Lanes() {}

    /** Physical side, a launch constant. Declared FIRST: static initializers run in textual order, so the lane
     *  probes below would capture {@code false} if this came after them. */
    private static final boolean CLIENT_SIDE = !Services.INSTANCE.getPLATFORM().isDedicatedServer();

    /** Server tick — integrated server (single-player) or dedicated. self = the platform's server handle, which
     *  answers its own liveness: one that has halted reports false, so a handle left over from a stopped server
     *  reads not-ready with nobody having to clear it. Pumped by {@code MixinMinecraftServer} at
     *  {@code tickServer}'s RETURN. */
    public static final Lane SERVER = new Lane(
            "server",
            ctx -> Services.INSTANCE.getPLATFORM().isServerRunning(ctx));

    /** Client logical tick (~20/s). self = Minecraft. Live for the whole process on a client — the game loop
     *  outlives every world — so the side alone answers it, with no window before the first tick where the lane
     *  would claim to be dead. Pumped by {@code MixinMinecraft} at {@code tick}'s RETURN. */
    public static final Lane CLIENT = new Lane("client", ctx -> CLIENT_SIDE);

    /** Render frame (framerate). self = Minecraft, same as the client lane. Pumped by {@code MixinMinecraft} at
     *  {@code runTick}'s RETURN. */
    public static final Lane RENDER = new Lane("render", ctx -> CLIENT_SIDE);

    /** Off-tick lane: each eval runs concurrently on its own worker thread — no tick affinity, no
     *  cross-tick yield, no scriptguard. Not heartbeat-driven, so it is always ready. See {@link ParallelLane}. */
    public static final ParallelLane PARALLEL = new ParallelLane("parallel");

    /** The lane an unspecified {@code target} means. Physical side, not world topology — a launch constant, so
     *  the default the schema advertises cannot go stale, and a script keeps its meaning when that client joins
     *  a remote server. */
    public static final Lane DEFAULT = CLIENT_SIDE ? CLIENT : SERVER;

    /** The lanes this host has, {@link #DEFAULT} first — the schema's {@code enum}, the order they are described
     *  in, and what {@link #byTarget} resolves against, so the three cannot drift apart.
     *
     *  <p>Absent here means UNKNOWN, not "not ready": a dedicated server's client/render heartbeats are client
     *  mixins that never apply, and reporting a permanent absence as a transient state invites a retry that can
     *  only fail again. Not-ready stays for a lane this host HAS whose source is stopped right now. */
    private static final ExecLane[] TARGETS = CLIENT_SIDE
            ? new ExecLane[] { CLIENT, RENDER, SERVER, PARALLEL }
            : new ExecLane[] { SERVER, PARALLEL };

    /** Same for {@code run_command}, which has no render or parallel identity. */
    private static final Lane[] COMMAND_TARGETS = CLIENT_SIDE
            ? new Lane[] { CLIENT, SERVER }
            : new Lane[] { SERVER };

    /**
     * Resolve a lane by the tool's {@code target} argument.
     *
     * @param target the lane name; {@code null}/empty resolves to {@link #DEFAULT}
     * @return the matching lane, or {@code null} when this host has no lane by that name
     */
    public static ExecLane byTarget(String target) {
        if (target == null || target.isEmpty()) return DEFAULT;
        for (ExecLane l : TARGETS) {
            if (l.getName().equals(target)) return l;
        }
        return null;
    }

    /** The {@code target} enum for {@code execute_code}, {@link #DEFAULT} first. */
    public static List<String> targets() {
        return Arrays.stream(TARGETS).map(ExecLane::getName).toList();
    }

    /** The {@code target} enum for {@code run_command}, {@link #DEFAULT} first. */
    public static List<String> commandTargets() {
        return Arrays.stream(COMMAND_TARGETS).map(Lane::getName).toList();
    }

    /**
     * Where work can go right now, {@link #DEFAULT} first — the ONE list every "not ready" message shows,
     * whichever tool it came from. Over {@link #TARGETS}, never a heartbeat-only subset: how a lane is driven
     * is ours to know, so a caller told that its lane is down must still hear {@link #PARALLEL} is up.
     *
     * @return a comma-separated list of ready target names; never {@code "(none)"}, PARALLEL being always ready
     */
    public static String readyTargets() {
        StringBuilder sb = new StringBuilder();
        for (ExecLane l : TARGETS) {
            if (l.isReady()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(l.getName());
            }
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }
}
