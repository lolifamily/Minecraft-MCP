package org.js.lolifamily.minecraftmcp.mixin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks AT MOST ONE of the three {@code ChatComponent} capture mixins per runtime, so their entry points cannot
 * overlap and no line is captured twice. The eras differ by which methods exist, which is a property of the
 * shipped class file — so it is read from there, never guessed: a variant is applied only where its shape was
 * confirmed in those bytes, and none at all where they cannot be read.
 *
 * <p>Runs during config preparation, before any target has loaded: plain Java and slf4j only — no Kotlin, no
 * mod class, and never {@code Class.forName} on a target, which would define it before it is transformed.
 */
public final class McpMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("Minecraft MCP");

    /** {@code (Object, Object, Object)void} — the player-chat entry's shape on every version that has one. */
    private static final Pattern THREE_OBJ = Pattern.compile("^\\(L[^;]+;L[^;]+;L[^;]+;\\)V$");

    private static final int UNKNOWN = 0;
    private static final int LEGACY = 1;
    private static final int FUNNEL = 2;
    private static final int MODERN = 3;

    /** No shape was confirmed, so nothing is applied. Distinct from {@link #UNKNOWN}, which means "not asked yet". */
    private static final int NONE = 4;

    /** Decided once, on one thread: every route here runs inside {@code MixinProcessor.applyMixins}, which is
     *  synchronized, and the three variants are built by one serial loop in {@code MixinConfig.prepareMixins}. */
    private int era = UNKNOWN;

    @Override
    public boolean shouldApplyMixin(String target, String mixin) {
        int want;
        switch (mixin.substring(mixin.lastIndexOf('.') + 1)) {
            case "MixinChatComponentLegacy":
                want = LEGACY;
                break;
            case "MixinChatComponentFunnel":
                want = FUNNEL;
                break;
            case "MixinChatComponentModern":
                want = MODERN;
                break;
            default: return true;   // every other mixin in the config — never gate those
        }
        int e = era;
        if (e == UNKNOWN) {
            era = e = detect(target);
        }
        return e == want;
    }

    /**
     * Which chat-entry shape [target] has, read from the CLASS FILE — not through the bytecode provider. What
     * this MC version DECLARES is a property of the shipped bytes, so nothing here needs the transform
     * pipeline, and staying out of it is the point: this runs during config preparation, nested inside another
     * class's transform with our own {@code prepare()} still on the stack. Asking ModLauncher for transformed
     * bytecode there re-enters the mixin processor at a moment when our own mixins are not yet registered;
     * asking it for UNtransformed bytecode is refused outright.
     */
    private int detect(String target) {
        String path = target.replace('.', '/') + ".class";
        ClassNode cn = new ClassNode();
        try (InputStream in = MixinService.getService().getResourceAsStream(path)) {
            if (in == null) throw new IOException("not on the classpath");
            new ClassReader(in).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            // NONE, not a guess at one of the three: the read that just failed is the only thing that could say
            // which entry this version has, and the config's defaultRequire=1 + required=true turn a selector
            // that matches nothing into a MixinApplyError on ChatComponent — a startup crash, not a lost line.
            LOG.error("[mcp] cannot read {}; chat capture off — run_command target=client reports no feedback", path, t);
            return NONE;
        }
        int found = LEGACY;
        for (MethodNode m : cn.methods) {
            if (!THREE_OBJ.matcher(m.desc).matches()) continue;
            if ("addPlayerMessage".equals(m.name)) return MODERN;   // 26.1+ split the entries by name
            found = FUNNEL;                                          // 1.19-1.21 universal funnel
        }
        return found;
    }

    @Override public void onLoad(String mixinPackage) {}

    @Override public String getRefMapperConfig() {
        return null;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override public List<String> getMixins() {
        return null;
    }

    @Override public void preApply(String target, ClassNode t, String mixin, IMixinInfo info) {}

    @Override public void postApply(String target, ClassNode t, String mixin, IMixinInfo info) {}
}
