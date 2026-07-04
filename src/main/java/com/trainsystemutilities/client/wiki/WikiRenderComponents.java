package com.trainsystemutilities.client.wiki;

import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.mcss3.draw.VectorRenderer;
import com.trainsystemutilities.blockentity.LineSymbol;
import com.trainsystemutilities.network.TrackNetworkScanner;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared wiki rendering helpers for TSU embeds.
 */
public final class WikiRenderComponents {

    private static final LineSymbol RAILWAY_SYMBOL = symbol("JA", 1, "#4fc3f7", "Loop");
    private static final List<LineSymbol> STATION_SYMBOLS = List.of(
            symbol("JA", 1, "#4fc3f7", "Track"),
            symbol("JF", 6, "#ff7043", "Harbor"),
            symbol("OA", 1, "#66bb6a", "Junction"),
            symbol("JA", 2, "#5c6bc0", "Depot")
    );

    private static final List<TrackNetworkScanner.NodeInfo> ROUTE_NODES = List.of(
            new TrackNetworkScanner.NodeInfo(0, 64, 108),
            new TrackNetworkScanner.NodeInfo(1, 104, 84),
            new TrackNetworkScanner.NodeInfo(2, 158, 86),
            new TrackNetworkScanner.NodeInfo(3, 208, 122),
            new TrackNetworkScanner.NodeInfo(4, 188, 170),
            new TrackNetworkScanner.NodeInfo(5, 118, 176),
            new TrackNetworkScanner.NodeInfo(6, 72, 148)
    );

    // 環状線の各エッジに中間制御点を多めに置くことで catmull-rom 補間を滑らかに
    private static final TrackNetworkScanner.EdgeInfo EDGE_01 = edge(0, 1,
            p(64, 108), p(70, 100), p(78, 94), p(86, 90), p(95, 87), p(104, 84));
    private static final TrackNetworkScanner.EdgeInfo EDGE_12 = edge(1, 2,
            p(104, 84), p(116, 82), p(130, 80), p(144, 82), p(158, 86));
    private static final TrackNetworkScanner.EdgeInfo EDGE_23 = edge(2, 3,
            p(158, 86), p(170, 90), p(184, 98), p(198, 110), p(208, 122));
    private static final TrackNetworkScanner.EdgeInfo EDGE_34 = edge(3, 4,
            p(208, 122), p(210, 134), p(206, 150), p(198, 162), p(188, 170));
    private static final TrackNetworkScanner.EdgeInfo EDGE_45 = edge(4, 5,
            p(188, 170), p(170, 178), p(152, 182), p(134, 180), p(118, 176));
    private static final TrackNetworkScanner.EdgeInfo EDGE_56 = edge(5, 6,
            p(118, 176), p(102, 174), p(88, 170), p(78, 160), p(72, 148));
    private static final TrackNetworkScanner.EdgeInfo EDGE_60 = edge(6, 0,
            p(72, 148), p(66, 138), p(62, 132), p(60, 122), p(64, 108));
    private static final TrackNetworkScanner.EdgeInfo EDGE_15 = edge(1, 5,
            p(104, 84), p(106, 110), p(106, 126), p(110, 150), p(118, 176));

    private static final List<TrackNetworkScanner.EdgeInfo> ROUTE_EDGES = List.of(
            EDGE_01, EDGE_12, EDGE_23, EDGE_34, EDGE_45, EDGE_56, EDGE_60, EDGE_15
    );

    private static final List<TrackNetworkScanner.StationInfo> ROUTE_STATIONS = List.of(
            new TrackNetworkScanner.StationInfo("Track Station", new BlockPos(66, 64, 109)),
            new TrackNetworkScanner.StationInfo("Harbor", new BlockPos(120, 64, 142)),
            new TrackNetworkScanner.StationInfo("Junction", new BlockPos(162, 64, 85)),
            new TrackNetworkScanner.StationInfo("Depot", new BlockPos(205, 64, 124))
    );

    private static final List<TrackNetworkScanner.SignalInfo> ROUTE_SIGNALS = List.of(
            new TrackNetworkScanner.SignalInfo(new BlockPos(92, 64, 92), TrackNetworkScanner.SignalState.GREEN),
            new TrackNetworkScanner.SignalInfo(new BlockPos(146, 64, 86), TrackNetworkScanner.SignalState.YELLOW),
            new TrackNetworkScanner.SignalInfo(new BlockPos(190, 64, 160), TrackNetworkScanner.SignalState.RED)
    );

    // 環状線の列車は 64 サンプル/スパン + 滑らかな速度で動かす
    private static final List<AnimatedTrain> ROUTE_TRAINS = List.of(
            train("Loop Rapid",
                    sampledPath(concatRoutePoints(
                            EDGE_01.points(), EDGE_12.points(), EDGE_23.points(), EDGE_34.points(),
                            EDGE_45.points(), EDGE_56.points(), EDGE_60.points()), true, 64),
                    20f, 0.02f, 8),
            train("Branch Local",
                    sampledPath(concatRoutePoints(EDGE_15.points(), reversePoints(EDGE_15.points())), false, 48),
                    16f, 0.47f, 4)
    );

    private WikiRenderComponents() {}

    public static void renderRouteMap(GuiGraphics graphics, Font font, int mapX, int mapY, int mapW, int mapH,
                                      float elapsedSeconds) {
        if (mapW <= 0 || mapH <= 0) return;

        graphics.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        graphics.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF0d0d1a);

        Bounds bounds = computeBounds(ROUTE_EDGES);
        double rangeX = Math.max(40d, bounds.maxX - bounds.minX + 24d);
        double rangeZ = Math.max(40d, bounds.maxZ - bounds.minZ + 24d);
        double zoom = Math.max(0.1d, Math.min(mapW / rangeX, mapH / rangeZ));
        double panX = -((bounds.minX + bounds.maxX) / 2d);
        double panZ = -((bounds.minZ + bounds.maxZ) / 2d);
        double centerX = mapX + mapW / 2d;
        double centerY = mapY + mapH / 2d;

        for (TrackNetworkScanner.EdgeInfo edge : ROUTE_EDGES) {
            List<double[]> points = sampleCurve(edge.points(), false, 10);
            for (int i = 0; i < points.size() - 1; i++) {
                float x1 = (float) (centerX + (points.get(i)[0] + panX) * zoom);
                float y1 = (float) (centerY + (points.get(i)[1] + panZ) * zoom);
                float x2 = (float) (centerX + (points.get(i + 1)[0] + panX) * zoom);
                float y2 = (float) (centerY + (points.get(i + 1)[1] + panZ) * zoom);
                drawVectorLine(graphics, x1, y1, x2, y2, 0xFF6688AA, 2.0f);
            }
        }
        graphics.bufferSource().endBatch();

        for (TrackNetworkScanner.StationInfo station : ROUTE_STATIONS) {
            int sx = (int) Math.round(centerX + (station.position().getX() + panX) * zoom);
            int sy = (int) Math.round(centerY + (station.position().getZ() + panZ) * zoom);
            graphics.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFF4fc3f7);
            graphics.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF1a1a2e);
            if (zoom > 0.55d) {
                graphics.drawString(font, station.name(), sx + 5, sy - 4, 0xFF4fc3f7, true);
            }
        }

        for (TrackNetworkScanner.SignalInfo signal : ROUTE_SIGNALS) {
            int sx = (int) Math.round(centerX + (signal.position().getX() + panX) * zoom);
            int sy = (int) Math.round(centerY + (signal.position().getZ() + panZ) * zoom);
            int color = switch (signal.state()) {
                case GREEN -> 0xFF4caf50;
                case RED -> 0xFFf44336;
                case YELLOW -> 0xFFffc107;
                case ORANGE -> 0xFFff9800;
            };
            graphics.fill(sx - 2, sy - 2, sx + 2, sy + 2, color);
        }

        for (AnimatedTrain train : ROUTE_TRAINS) {
            PositionAndDirection state = train.path().sample((elapsedSeconds / train.loopSeconds() + train.phase()) % 1f);
            int sx = (int) Math.round(centerX + (state.x() + panX) * zoom);
            int sy = (int) Math.round(centerY + (state.z() + panZ) * zoom);

            for (int carriage = 0; carriage < train.carriageCount(); carriage++) {
                int cx = sx - (int) Math.round(state.dx() * 5f * carriage);
                int cy = sy - (int) Math.round(state.dz() * 5f * carriage);
                graphics.fill(cx - 3, cy - 2, cx + 3, cy + 2, 0xFFff9800);
                graphics.fill(cx - 2, cy - 1, cx + 2, cy + 1, 0xFFffcc02);
            }

            if (zoom > 0.35d) {
                graphics.drawString(font, train.name(), sx + 5, sy - 4, 0xFFffcc02, true);
            }
        }

        drawSymbolIcon(graphics, font, mapX + 8, mapY + 8, 24, RAILWAY_SYMBOL);
        graphics.disableScissor();
    }

    public static void drawSymbolIcon(GuiGraphics graphics, Font font, int x, int y, int size, LineSymbol symbol) {
        int borderColor = parseColor(symbol.getBorderColor(), 0xFF4fc3f7);
        float radius = Math.min(symbol.getBorderRadius() * size / 40f, size / 2f);

        SmoothRenderer.fillRoundedRect(graphics, x, y, size, size, radius, 0xFFFFFFFF);
        SmoothRenderer.strokeRoundedRect(graphics, x, y, size, size, radius, 2f, borderColor);

        int borderWidth = 2;
        int innerWidth = size - borderWidth * 2 - 2;
        int midY = y + size / 2;
        String letters = symbol.getLetters();
        String number = symbol.getNumberStr();
        int lettersWidth = font.width(letters);
        int numberWidth = font.width(number);
        float maxTextWidth = Math.max(lettersWidth, numberWidth);
        float scale = Math.min(1f, innerWidth / Math.max(1f, maxTextWidth));
        int letterY = midY - 9;
        int numberY = midY + 2;

        graphics.pose().pushPose();
        if (scale < 1f) {
            graphics.pose().scale(scale, scale, 1f);
            int scaledCenterX = (int) ((x + size / 2f) / scale);
            graphics.drawString(font, letters, scaledCenterX - lettersWidth / 2, (int) (letterY / scale), 0xFF000000, false);
            graphics.drawString(font, number, scaledCenterX - numberWidth / 2, (int) (numberY / scale), 0xFF000000, false);
        } else {
            graphics.drawString(font, letters, x + size / 2 - lettersWidth / 2, letterY, 0xFF000000, false);
            graphics.drawString(font, number, x + size / 2 - numberWidth / 2, numberY, 0xFF000000, false);
        }
        graphics.pose().popPose();

        graphics.fill(x + borderWidth + 2, midY, x + size - borderWidth - 2, midY + 1, borderColor);
    }

    private static int parseColor(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            String normalized = value.startsWith("#") ? value.substring(1) : value;
            return Integer.parseInt(normalized, 16) | 0xFF000000;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void drawVectorLine(GuiGraphics graphics, float x1, float y1, float x2, float y2,
                                       int color, float width) {
        var buffer = VectorRenderer.getGuiBuffer(graphics.bufferSource());
        VectorRenderer.drawLine(buffer, graphics.pose().last().pose(), x1, y1, x2, y2, color, width);
    }

    private static Bounds computeBounds(List<TrackNetworkScanner.EdgeInfo> edges) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (TrackNetworkScanner.EdgeInfo edge : edges) {
            for (double[] point : edge.points()) {
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minZ = Math.min(minZ, point[1]);
                maxZ = Math.max(maxZ, point[1]);
            }
        }
        return new Bounds(minX, maxX, minZ, maxZ);
    }

    private static AnimatedTrain train(String name, SampledPath path, float loopSeconds, float phase, int carriageCount) {
        return new AnimatedTrain(name, path, loopSeconds, phase, carriageCount);
    }

    private static SampledPath sampledPath(List<double[]> controlPoints, boolean closed, int samplesPerSpan) {
        return SampledPath.of(sampleCurve(controlPoints, closed, samplesPerSpan));
    }

    private static List<double[]> concatRoutePoints(List<double[]>... segments) {
        List<double[]> merged = new ArrayList<>();
        boolean firstSegment = true;
        for (List<double[]> segment : segments) {
            for (int i = 0; i < segment.size(); i++) {
                if (!firstSegment && i == 0) continue;
                double[] point = segment.get(i);
                merged.add(p(point[0], point[1]));
            }
            firstSegment = false;
        }
        return merged;
    }

    private static List<double[]> reversePoints(List<double[]> points) {
        List<double[]> reversed = new ArrayList<>();
        for (int i = points.size() - 1; i >= 0; i--) {
            double[] point = points.get(i);
            reversed.add(p(point[0], point[1]));
        }
        return reversed;
    }

    private static List<double[]> sampleCurve(List<double[]> points, boolean closed, int samplesPerSpan) {
        if (points.size() < 2) return points;

        List<double[]> sampled = new ArrayList<>();
        int spanCount = closed ? points.size() : points.size() - 1;
        int subdivisions = Math.max(2, samplesPerSpan);
        for (int span = 0; span < spanCount; span++) {
            double[] p0 = controlPoint(points, span - 1, closed);
            double[] p1 = controlPoint(points, span, closed);
            double[] p2 = controlPoint(points, span + 1, closed);
            double[] p3 = controlPoint(points, span + 2, closed);

            for (int step = 0; step < subdivisions; step++) {
                if (span > 0 && step == 0) continue;
                double t = step / (double) subdivisions;
                sampled.add(catmullRom(p0, p1, p2, p3, t));
            }
        }

        if (closed) {
            sampled.add(catmullRom(
                    controlPoint(points, spanCount - 1, true),
                    controlPoint(points, spanCount, true),
                    controlPoint(points, spanCount + 1, true),
                    controlPoint(points, spanCount + 2, true),
                    0d));
        } else {
            double[] last = points.get(points.size() - 1);
            sampled.add(p(last[0], last[1]));
        }
        return sampled;
    }

    private static double[] controlPoint(List<double[]> points, int index, boolean closed) {
        int size = points.size();
        if (closed) {
            int wrapped = ((index % size) + size) % size;
            double[] point = points.get(wrapped);
            return p(point[0], point[1]);
        }
        int clamped = Math.max(0, Math.min(size - 1, index));
        double[] point = points.get(clamped);
        return p(point[0], point[1]);
    }

    private static double[] catmullRom(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double x = 0.5d * ((2d * p1[0])
                + (-p0[0] + p2[0]) * t
                + (2d * p0[0] - 5d * p1[0] + 4d * p2[0] - p3[0]) * t2
                + (-p0[0] + 3d * p1[0] - 3d * p2[0] + p3[0]) * t3);
        double z = 0.5d * ((2d * p1[1])
                + (-p0[1] + p2[1]) * t
                + (2d * p0[1] - 5d * p1[1] + 4d * p2[1] - p3[1]) * t2
                + (-p0[1] + 3d * p1[1] - 3d * p2[1] + p3[1]) * t3);
        return p(x, z);
    }

    private static TrackNetworkScanner.EdgeInfo edge(int fromId, int toId, double[]... points) {
        return new TrackNetworkScanner.EdgeInfo(fromId, toId, List.of(points));
    }

    private static double[] p(double x, double z) {
        return new double[]{x, z};
    }

    private static LineSymbol symbol(String letters, int number, String color, String name) {
        return new LineSymbol(letters, number, color, name, 12);
    }

    private record AnimatedTrain(String name, SampledPath path, float loopSeconds, float phase, int carriageCount) {
    }

    private record Bounds(double minX, double maxX, double minZ, double maxZ) {
    }

    private record PositionAndDirection(double x, double z, float dx, float dz) {
    }

    private static final class SampledPath {
        private final List<double[]> points;
        private final double[] cumulativeLengths;
        private final double totalLength;

        private SampledPath(List<double[]> points, double[] cumulativeLengths, double totalLength) {
            this.points = points;
            this.cumulativeLengths = cumulativeLengths;
            this.totalLength = totalLength;
        }

        static SampledPath of(List<double[]> sampledPoints) {
            List<double[]> safePoints = sampledPoints.size() >= 2 ? sampledPoints : List.of(p(0, 0), p(0, 1));
            double[] lengths = new double[safePoints.size()];
            double total = 0d;
            lengths[0] = 0d;
            for (int i = 1; i < safePoints.size(); i++) {
                double dx = safePoints.get(i)[0] - safePoints.get(i - 1)[0];
                double dz = safePoints.get(i)[1] - safePoints.get(i - 1)[1];
                total += Math.sqrt(dx * dx + dz * dz);
                lengths[i] = total;
            }
            return new SampledPath(safePoints, lengths, total);
        }

        PositionAndDirection sample(float progress) {
            if (points.size() < 2 || totalLength <= 0d) {
                double[] point = points.isEmpty() ? p(0, 0) : points.get(0);
                return new PositionAndDirection(point[0], point[1], 0f, 1f);
            }

            // Wrap progress to [0, 1) - assumes path is naturally closed (last point == first point)
            float wrapped = progress - (float) Math.floor(progress);
            double targetDistance = totalLength * wrapped;
            // Binary-style search for the segment containing targetDistance
            int segmentIndex = 0;
            int lastIdx = cumulativeLengths.length - 1;
            for (int i = 1; i <= lastIdx; i++) {
                if (cumulativeLengths[i] >= targetDistance) {
                    segmentIndex = i - 1;
                    break;
                }
                if (i == lastIdx) segmentIndex = lastIdx - 1;
            }

            double startDistance = cumulativeLengths[segmentIndex];
            double endDistance = cumulativeLengths[Math.min(points.size() - 1, segmentIndex + 1)];
            double span = Math.max(0.0001d, endDistance - startDistance);
            double localT = (targetDistance - startDistance) / span;
            localT = Math.max(0d, Math.min(1d, localT));
            double[] a = points.get(segmentIndex);
            double[] b = points.get(Math.min(points.size() - 1, segmentIndex + 1));
            double x = a[0] + (b[0] - a[0]) * localT;
            double z = a[1] + (b[1] - a[1]) * localT;

            // Tangent: use the immediate segment direction for smoothness on dense sampling.
            float dx = (float) (b[0] - a[0]);
            float dz = (float) (b[1] - a[1]);
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if (length > 0.0001f) {
                dx /= length;
                dz /= length;
            } else {
                dx = 0f;
                dz = 1f;
            }
            return new PositionAndDirection(x, z, dx, dz);
        }
    }
}
