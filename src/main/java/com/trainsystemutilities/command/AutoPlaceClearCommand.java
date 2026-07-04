package com.trainsystemutilities.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.block.InsulatorBlock;
import com.trainsystemutilities.electrification.block.OverheadPoleBlock;
import com.trainsystemutilities.electrification.block.OverheadTrussBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 架線設備一括削除コマンド (テスト用)。
 *
 * <p>{@code /tsu-electric clear} → 現在 dimension の **全 loaded chunks** をスキャンし、
 * 架線柱 / 架線トラス / 碍子を air に置換。
 *
 * <p>OP-only。 revertible でないので注意。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class AutoPlaceClearCommand {

    private AutoPlaceClearCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(LiteralArgumentBuilder.<CommandSourceStack>literal("tsu-electric")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("clear")
                        .executes(ctx -> run(ctx.getSource()))));
    }

    /** プレイヤー中心 ±32 chunks (= ±512 blocks) をスキャンし、 ロード済 chunks のみ処理。 */
    private static final int CHUNK_RADIUS = 32;

    private static int run(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("tsu.cmd.player_only"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        int removed = 0;
        int chunkCount = 0;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int cx = centerChunkX - CHUNK_RADIUS; cx <= centerChunkX + CHUNK_RADIUS; cx++) {
            for (int cz = centerChunkZ - CHUNK_RADIUS; cz <= centerChunkZ + CHUNK_RADIUS; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                chunkCount++;
                int minBX = cx << 4;
                int minBZ = cz << 4;
                for (int x = minBX; x < minBX + 16; x++) {
                    for (int z = minBZ; z < minBZ + 16; z++) {
                        for (int y = minY; y < maxY; y++) {
                            pos.set(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            Block block = state.getBlock();
                            if (block instanceof OverheadPoleBlock
                                    || block instanceof OverheadTrussBlock
                                    || block instanceof InsulatorBlock) {
                                level.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                                removed++;
                            }
                        }
                    }
                }
            }
        }

        final int finalRemoved = removed;
        final int finalChunks = chunkCount;
        source.sendSuccess(() -> Component.literal(
                "架線設備 " + finalRemoved + " 個を削除 (" + finalChunks + " chunks)")
                .withStyle(ChatFormatting.GREEN), true);
        return Command.SINGLE_SUCCESS;
    }
}
