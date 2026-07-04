package com.trainsystemutilities.client.wiki;

import belugalab.mcss3.screen.JsonLayoutHandler;
import com.trainsystemutilities.blockentity.LineSymbol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Wiki demo の JSON layout から呼ばれる {@link JsonLayoutHandler} 群。
 *
 * <p>柱 3 (Schema-driven JSON) migration で {@code RailwayManagementDemoIr} /
 * {@code ManagementComputerDemoIr} から抽出した canvas 描画ロジック (= 路線記号アイコン /
 * route map preview) のみを保持する。 layout 構造は JSON 側 (= {@code layouts/wiki/*.json})。
 */
public final class WikiDemoHandlers {

    private static final LineSymbol RAILWAY_SYMBOL = new LineSymbol("JA", 1, "#4fc3f7", "Loop", 12);

    private static final List<LineSymbol> STATION_SYMBOLS = List.of(
            new LineSymbol("JA", 1, "#4fc3f7", "Track", 12),
            new LineSymbol("JF", 6, "#ff7043", "Harbor", 12),
            new LineSymbol("OA", 1, "#66bb6a", "Junction", 12),
            new LineSymbol("JA", 2, "#5c6bc0", "Depot", 12)
    );

    private static final JsonLayoutHandler RAILWAY_HANDLER = new JsonLayoutHandler() {
        @Override
        public void drawCanvas(GuiGraphics g, String[] classes, String key,
                               int x, int y, int w, int h, int mouseX, int mouseY) {
            if ("railwaySymbol".equals(key)) {
                int size = Math.min(w, h);
                WikiRenderComponents.drawSymbolIcon(g, Minecraft.getInstance().font, x, y, size, RAILWAY_SYMBOL);
            }
        }
    };

    private WikiDemoHandlers() {}

    public static JsonLayoutHandler railway() { return RAILWAY_HANDLER; }

    /** Management Computer は route map の elapsed time を保持するため per-call で生成。 */
    public static JsonLayoutHandler managementComputer() {
        return new JsonLayoutHandler() {
            private final long startNano = System.nanoTime();

            @Override
            public void drawCanvas(GuiGraphics g, String[] classes, String key,
                                   int x, int y, int w, int h, int mouseX, int mouseY) {
                if ("routeMap".equals(key)) {
                    float elapsed = (System.nanoTime() - startNano) / 1_000_000_000f;
                    WikiRenderComponents.renderRouteMap(g, Minecraft.getInstance().font, x, y, w, h, elapsed);
                    return;
                }
                if (key != null && key.startsWith("station-sym-")) {
                    try {
                        int idx = Integer.parseInt(key.substring("station-sym-".length()));
                        if (idx >= 0 && idx < STATION_SYMBOLS.size()) {
                            WikiRenderComponents.drawSymbolIcon(g, Minecraft.getInstance().font,
                                    x, y, Math.min(w, h), STATION_SYMBOLS.get(idx));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        };
    }
}
