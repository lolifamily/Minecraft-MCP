package org.js.lolifamily.minecraftmcp.compat

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import org.js.lolifamily.minecraftmcp.repl.Mappings
import java.lang.reflect.InvocationTargetException

/**
 * Client-side command send, spanning the MC 1.19 "secure chat" rework by resolving the moved method
 * reflectively at runtime. The client's command-send path was split at 1.19:
 *
 *  - `>=1.19`  : [net.minecraft.client.multiplayer.ClientPacketListener].sendCommand(String) — command WITHOUT
 *                a leading `/` (a dedicated, signable command packet).
 *  - `<=1.18.2`: [net.minecraft.client.player.LocalPlayer].chat(String) — command sent as a chat line WITH a
 *                leading `/` (the server parses it).
 *
 * Method names are reflective strings, so each is mapped to the runtime namespace via [Mappings].
 */
object ClientCommandCompat {

    // The mojmap FQNs that key the mapping table — distinct from the ::class.java references below, which the
    // build already reobf'd to the runtime namespace. The table is written in mojmap; the classpath is not.
    private const val LISTENER_MOJMAP = "net.minecraft.client.multiplayer.ClientPacketListener"
    private const val PLAYER_MOJMAP = "net.minecraft.client.player.LocalPlayer"

    /** Sends [command] (bare, no leading '/') to the server. @return false if neither send path was usable:
     *  no connection AND no player (menu / not connected), or an unrecognized runtime where neither method
     *  name resolved. */
    @JvmStatic
    fun send(mc: Minecraft, command: String): Boolean {
        val connection = mc.connection
        if (connection != null &&
            invoke(connection, ClientPacketListener::class.java, LISTENER_MOJMAP, "sendCommand", command)
        ) {
            return true
        }
        val player = mc.player
        return player != null &&
            invoke(player, LocalPlayer::class.java, PLAYER_MOJMAP, "chat", "/$command")
    }

    /** Resolved on [owner], not `target.javaClass`: getMethod on a non-public runtime class hands back a
     *  Method whose invoke() throws IllegalAccessException even for a method declared public on a public
     *  supertype. Invocation still targets [target], so an override dispatches normally. */
    private fun invoke(target: Any, owner: Class<*>, ownerMojmap: String, methodMojmap: String, arg: String): Boolean {
        val name = Mappings.current()?.mapMethod(ownerMojmap, methodMojmap) ?: methodMojmap
        val m = try { owner.getMethod(name, String::class.java) } catch (_: NoSuchMethodException) { return false }
        try {
            m.invoke(target, arg)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e // surface the send path's real throwable, as CommandsCompat does
        }
        return true
    }
}
