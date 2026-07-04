package com.trainsystemutilities.client.wiki;

import belugalab.mcss3.debug.GuiScreenCapture;
import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import belugalab.mcss3.screen.JsonLayoutScreen;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.gui.ManagementComputerScreenV2;
import com.trainsystemutilities.client.transit.TransitTerminalScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runtime wiki screenshot pipeline for MCSS/TSU screens.
 *
 * <p>The important rule is that this captures the screen as it is currently
 * displayed. It does not re-init the screen at dialog size, because that changes
 * MCSS auto-scale, overlay placement, canvas coordinates, and SVG rendering.
 */
public final class WikiCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger("TSU-WikiCapture");

    private WikiCapture() {}

    public static void captureCurrentScreen() {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (s == null) {
            sendChat("§eNo screen open to capture.", false);
            return;
        }
        captureScreen(s, deriveId(s));
    }

    public static void captureScreen(Screen screen, String id) {
        captureScreen(screen, id, false);
    }

    public static void captureScreen(Screen screen, String id, boolean silent) {
        captureScreen(screen, id, null, null, silent, !silent);
    }

    public static void captureScreen(Screen screen, String id, String forcedState, boolean silent) {
        captureScreen(screen, id, forcedState, null, silent, !silent);
    }

    public static void captureScreen(Screen screen, String id, String forcedState, boolean silent, boolean savePng) {
        captureScreen(screen, id, forcedState, null, silent, savePng);
    }

    /** silent=true: chat 通知をスキップ。 savePng=true: PNG も保存 (検証/永続化用)。
     *  forcedState != null: deriveState の自動推測を使わずこの state で登録する。
     *  forcedLang != null: currentLang() (= クライアント選択言語、 inject では変わらない) の
     *  代わりにこの言語コードでテクスチャ名を付ける (= 両言語キャプチャで ja_jp/en_us を撮り分ける)。 */
    public static void captureScreen(Screen screen, String id, String forcedState, String forcedLang,
                                     boolean silent, boolean savePng) {
        Minecraft mc = Minecraft.getInstance();
        if (screen == null || id == null || id.isEmpty()) return;

        if (!RenderSystem.isOnRenderThread()) {
            if (!silent) sendChat("§cCapture must run on render thread. Try invoking from a keybind.", false);
            return;
        }

        int scale = (int) Math.round(mc.getWindow().getGuiScale());
        GuiScreenCapture.CaptureResult result = GuiScreenCapture.captureVisibleContent(screen, scale);
        if (result == null || result.image() == null) {
            if (!silent) sendChat("§cCapture failed (see log).", false);
            return;
        }

        String lang = (forcedLang != null && !forcedLang.isBlank()) ? forcedLang : currentLang();
        String state = (forcedState != null && !forcedState.isBlank())
                ? safeState(forcedState, "main") : deriveState(screen, id);
        String primaryName = id + "__" + state + "__" + lang;
        Set<String> names = textureNames(id, state, lang);

        WikiPrebuildCapture.LAYOUT_DIMS.put(id + "__" + state,
                new int[]{result.logicalWidth(), result.logicalHeight()});
        if ("main".equals(state)) {
            WikiPrebuildCapture.LAYOUT_DIMS.put(id,
                    new int[]{result.logicalWidth(), result.logicalHeight()});
        }

        NativeImage img = result.image();
        Path primaryOutput = savePng ? saveImages(mc, img, names, primaryName) : null;
        int registered = registerTextures(mc, img, names);
        img.close();

        if (silent) return;
        if (registered > 0) {
            sendChat("§aCaptured: §f" + primaryOutput
                    + " §7(" + result.logicalWidth() + "x" + result.logicalHeight()
                    + ", id=" + id + ", state=" + state + ")", false);
        } else {
            sendChat("§cTexture register failed (PNG saved if possible).", false);
        }
    }

    public static String deriveId(Screen s) {
        String n = s.getClass().getSimpleName();
        n = n.replace("ScreenV2", "").replace("Screen", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String currentLang() {
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        return (lang == null || lang.isBlank()) ? "en_us" : lang;
    }

    private static String deriveState(Screen screen, String id) {
        String overlay = currentOverlayId(screen);
        if (overlay != null && !overlay.isBlank()) return normalizeState(id, overlay);
        if (screen instanceof ManagementComputerScreenV2 mc) return safeState(mc.wikiCaptureState(), "map");
        if (screen instanceof TransitTerminalScreen tt) return safeState(tt.wikiCaptureState(), "top");
        return "main";
    }

    private static String currentOverlayId(Screen screen) {
        if (screen instanceof JsonLayoutScreen<?> sc) return sc.currentOverlayId();
        if (screen instanceof JsonLayoutPlainScreen ps) return ps.currentOverlayId();
        return null;
    }

    private static String normalizeState(String id, String raw) {
        String state = raw;
        if (state.endsWith(".json")) state = state.substring(0, state.length() - 5);
        if (state.startsWith(id + "-")) state = state.substring(id.length() + 1);
        if (state.startsWith("layouts/")) state = state.substring("layouts/".length());
        state = state.replace('\\', '-').replace('/', '-').trim();
        return safeState(state, "overlay");
    }

    private static String safeState(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        String out = sb.toString().replaceAll("-+", "-");
        return out.isBlank() ? fallback : out;
    }

    private static Set<String> textureNames(String id, String state, String lang) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(id + "__" + state + "__" + lang);
        names.add(id + "__" + state);
        if ("main".equals(state)) {
            names.add(id + "__" + lang);
            names.add(id);
        } else {
            names.add(id + "-" + state + "__" + lang);
            names.add(id + "-" + state);
        }
        return names;
    }

    private static Path saveImages(Minecraft mc, NativeImage img, Set<String> names, String primaryName) {
        Path dir = Paths.get(mc.gameDirectory.getPath(), "screenshots", "wiki");
        Path primary = dir.resolve(primaryName + ".png");
        try {
            Files.createDirectories(dir);
            for (String name : names) {
                img.writeToFile(dir.resolve(name + ".png"));
            }
        } catch (Throwable t) {
            LOGGER.warn("[WikiCapture] PNG save failed: {}", t.getMessage());
        }
        return primary;
    }

    private static int registerTextures(Minecraft mc, NativeImage img, Set<String> names) {
        int count = 0;
        for (String name : names) {
            NativeImage copy = copyImage(img);
            if (copy == null) continue;
            try {
                ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                        TrainSystemUtilities.MOD_ID, "textures/wiki/screens/" + name + ".png");
                DynamicTexture dt = new DynamicTexture(copy);
                mc.getTextureManager().register(loc, dt);
                applyBilinear(dt);
                count++;
                LOGGER.info("[WikiCapture] registered dynamic texture: {}", loc);
            } catch (Throwable t) {
                LOGGER.error("[WikiCapture] dynamic texture register failed for {}: {}", name, t.getMessage());
                copy.close();
            }
        }
        return count;
    }

    private static NativeImage copyImage(NativeImage src) {
        if (src == null) return null;
        NativeImage dst = new NativeImage(src.format(), src.getWidth(), src.getHeight(), false);
        dst.copyFrom(src);
        return dst;
    }

    private static void applyBilinear(DynamicTexture tex) {
        try {
            int id = tex.getId();
            if (id <= 0) return;
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(id);
            com.mojang.blaze3d.platform.GlStateManager._texParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
            com.mojang.blaze3d.platform.GlStateManager._texParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
        } catch (Throwable ignored) {}
    }

    private static void sendChat(String msg, boolean actionBar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg)
                    .withStyle(ChatFormatting.WHITE), actionBar);
        } else {
            LOGGER.info("[WikiCapture] {}", msg);
        }
    }
}
