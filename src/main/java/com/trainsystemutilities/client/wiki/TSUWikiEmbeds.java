package com.trainsystemutilities.client.wiki;
import belugalab.mcss3.ir.compiler.JsonToIrCompiler;
import belugalab.mcss3.ir.IrNode;
import belugalab.mcss3.screen.JsonLayoutHandler;
import belugalab.mcss3.wiki.WikiEmbedRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trainsystemutilities.client.gui.TsuLayouts;

import java.util.HashMap;
import java.util.Map;

/**
 * Wiki embed registrations for TSU。
 *
 * <p>柱 3 (Schema-driven JSON) 移行後: 各 demo の layout は
 * {@code assets/trainsystemutilities/layouts/wiki/*.json} に格納され、 ここでは
 * JSON ロード + JsonToIrCompiler 経由で IR を取得して embed registry に渡す。
 * canvas 描画 (路線記号アイコン / route map) は {@link WikiDemoHandlers} が担当。
 */
public final class TSUWikiEmbeds {

    /** 各 GUI のネイティブ寸法 (px)。 */
    private static final int MANAGEMENT_COMPUTER_NATIVE_H = 332;
    private static final int RAILWAY_MANAGEMENT_NATIVE_H = 316;
    private static final int POSTER_MANAGEMENT_NATIVE_H = 332;

    private static final Map<String, IrNode> IR_CACHE = new HashMap<>();

    private TSUWikiEmbeds() {}

    /** P0-12: idempotency guard — 二度目以降の registerAll() は no-op。
     *  client lifecycle で /reload や mod soft-reset 時に重複呼出されても
     *  WikiEmbedRegistry に冗長 put が走らないようにする。 */
    private static volatile boolean registered = false;

    public static void registerAll() {
        // P0-12: 二度目以降は早期 return (= /reload や hot-reload 時の重複登録を抑止)
        if (registered) return;
        registered = true;
        // Generic embeds: item icon / 3D model / screen capture (PNG)
        TSUWikiCommonEmbeds.register();

        // === live texture 方式 (実 Screen キャプチャを blit。 canvas/SVG/データが本物) ===
        // WikiLiveCapture が management-computer / railway-management の各 state を
        // login 時に実 render → DynamicTexture 登録する。 ここではそれを表示するだけ。
        WikiEmbedRegistry.register("management-computer",
                ctx -> liveTexture(ctx, "management-computer", normalizeTab(ctx.option("tab", "map"))));
        WikiEmbedRegistry.register("management-computer-map",
                ctx -> liveTexture(ctx, "management-computer", "map"));
        WikiEmbedRegistry.register("management-computer-trains",
                ctx -> liveTexture(ctx, "management-computer", "trains"));
        WikiEmbedRegistry.register("management-computer-schedule",
                ctx -> liveTexture(ctx, "management-computer", "schedule"));
        WikiEmbedRegistry.register("management-computer-stations",
                ctx -> liveTexture(ctx, "management-computer", "stations"));
        WikiEmbedRegistry.register("management-computer-symbol",
                ctx -> liveTexture(ctx, "management-computer", "symbol"));

        WikiEmbedRegistry.register("railway-management",
                ctx -> liveTexture(ctx, "railway-management", "main"));

        WikiEmbedRegistry.register("symbol-editor",
                ctx -> liveTexture(ctx, "management-computer", "symbol-editor"));
        WikiEmbedRegistry.register("color-picker",
                ctx -> liveTexture(ctx, "management-computer", "color-picker"));

        WikiEmbedRegistry.register("railway-settings",
                ctx -> liveTexture(ctx, "railway-management", "settings"));
        WikiEmbedRegistry.register("railway-color",
                ctx -> liveTexture(ctx, "railway-management", "color"));

        WikiEmbedRegistry.register("poster-management",
                ctx -> liveTexture(ctx, "poster-management", "main"));
        WikiEmbedRegistry.register("poster-animation",
                ctx -> liveTexture(ctx, "poster-management", "anim"));
        WikiEmbedRegistry.register("layout-editor",
                ctx -> liveTexture(ctx, "management-computer", "layout-edit"));
        WikiEmbedRegistry.register("color-settings",
                ctx -> liveTexture(ctx, "management-computer", "monitor-color"));
    }

    /** live キャプチャ texture (= 実 Screen render) を blit する embed。
     *  texture loc = {@code <baseId>__<state>__<lang>}。 未生成なら en_us → "..." の順で fallback。
     *  寸法は WikiCapture が登録した crop 後 dims (= 実 aspect) を使う。 */
    private static WikiEmbedRegistry.EmbedBuilder.Result liveTexture(
            WikiEmbedRegistry.EmbedContext ctx, String baseId, String state) {
        int[] dims = WikiPrebuildCapture.LAYOUT_DIMS.get(baseId + "__" + state);
        int nativeW = (dims != null && dims[0] > 0) ? dims[0] : 300;
        int nativeH = (dims != null && dims[1] > 0) ? dims[1] : 200;
        int dispW = Math.min(ctx.renderWidth(), nativeW * 2);
        int dispH = Math.max(1, Math.round(dispW * (float) nativeH / nativeW));
        return WikiEmbedRegistry.EmbedBuilder.Result.custom(
                (g, font, x, y, ww, hh, rctx, dt) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    String lang = mc.getLanguageManager().getSelected();
                    if (lang == null || lang.isBlank()) lang = "en_us";
                    net.minecraft.resources.ResourceLocation loc = liveLoc(baseId, state, lang);
                    if (mc.getTextureManager().getTexture(loc, null) == null) {
                        loc = liveLoc(baseId, state, "en_us"); // lang fallback
                        if (mc.getTextureManager().getTexture(loc, null) == null) {
                            g.drawString(font, "(generating…)", x, y, 0xFF888888, false);
                            return;
                        }
                    }
                    int dx = x + Math.max(0, (ww - dispW) / 2);
                    g.blit(loc, dx, y, 0, 0, dispW, dispH, dispW, dispH);
                }, dispH);
    }

    private static net.minecraft.resources.ResourceLocation liveLoc(String baseId, String state, String lang) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                com.trainsystemutilities.TrainSystemUtilities.MOD_ID,
                "textures/wiki/screens/" + baseId + "__" + state + "__" + lang + ".png");
    }

    private static WikiEmbedRegistry.EmbedBuilder.Result managementComputerResult(
            WikiEmbedRegistry.EmbedContext context, String fallbackTab) {
        String popup = normalizePopup(context.option("popup", ""));
        if (!popup.isEmpty()) {
            return popupResult(popup);
        }
        String tab = normalizeTab(context.option("tab", fallbackTab));
        return irResult("layouts/wiki/mgmt-computer-" + tab + ".json",
                WikiDemoHandlers.managementComputer(), MANAGEMENT_COMPUTER_NATIVE_H);
    }

    private static WikiEmbedRegistry.EmbedBuilder.Result popupResult(String popupId) {
        return switch (normalizePopup(popupId)) {
            case "layout-editor" -> irResult("layouts/wiki/popup-layout-editor.json", null, 188);
            case "color-settings" -> irResult("layouts/wiki/popup-color-settings.json", null, 252);
            case "symbol-editor" -> irResult("layouts/wiki/popup-symbol-editor.json", null, 286);
            case "color-picker" -> irResult("layouts/wiki/popup-color-picker.json", null, 296);
            default -> irResult("layouts/wiki/mgmt-computer-map.json",
                    WikiDemoHandlers.managementComputer(), MANAGEMENT_COMPUTER_NATIVE_H);
        };
    }

    private static WikiEmbedRegistry.EmbedBuilder.Result irResult(
            String resourcePath, JsonLayoutHandler handler, int height) {
        IrNode ir = loadIr(resourcePath);
        return WikiEmbedRegistry.EmbedBuilder.Result.ir(ir, handler, height);
    }

    /** resourcePath を 1 度だけロード + compile して cache (= 各 embed render で IR 再構築を避ける)。 */
    private static IrNode loadIr(String resourcePath) {
        IrNode cached = IR_CACHE.get(resourcePath);
        if (cached != null) return cached;
        String json = TsuLayouts.load(resourcePath);
        if (json == null || json.isEmpty()) {
            throw new IllegalStateException("Wiki layout resource not found: " + resourcePath);
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        IrNode ir = JsonToIrCompiler.compile(root).root();
        IR_CACHE.put(resourcePath, ir);
        return ir;
    }

    private static String normalizeTab(String tab) {
        return switch (tab) {
            case "trains", "schedule", "stations", "symbol" -> tab;
            default -> "map";
        };
    }

    private static String normalizePopup(String popup) {
        return switch (popup) {
            case "layout", "layout-editor" -> "layout-editor";
            case "color", "color-settings" -> "color-settings";
            case "symbol", "symbol-editor" -> "symbol-editor";
            case "picker", "color-picker" -> "color-picker";
            default -> "";
        };
    }
}
