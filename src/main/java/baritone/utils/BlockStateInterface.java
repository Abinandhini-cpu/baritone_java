/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.Baritone;
import baritone.chunk.WorldData;
import baritone.chunk.WorldProvider;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

public class BlockStateInterface implements Helper {

    private static Chunk prev = null;

    public static IBlockState get(BlockPos pos) { // wrappers for chunk caching capability

        // Invalid vertical position
        if (pos.getY() < 0 || pos.getY() >= 256)
            return Blocks.AIR.getDefaultState();

        if (!Baritone.settings().pathThroughCachedOnly.get()) {
            Chunk cached = prev;
            // there's great cache locality in block state lookups
            // generally it's within each movement
            // if it's the same chunk as last time
            // we can just skip the mc.world.getChunk lookup
            // which is a Long2ObjectOpenHashMap.get
            if (cached != null && cached.x == pos.getX() >> 4 && cached.z == pos.getZ() >> 4) {
                return cached.getBlockState(pos);
            }
            Chunk chunk = mc.world.getChunk(pos);
            if (chunk.isLoaded()) {
                prev = chunk;
                return chunk.getBlockState(pos);
            }
        }
        WorldData world = WorldProvider.INSTANCE.getCurrentWorld();
        if (world != null) {
            IBlockState type = world.cache.getBlock(pos);
            if (type != null) {
                return type;
            }
        }


        return Blocks.AIR.getDefaultState();
    }

    public static void clearCachedChunk() {
        prev = null;
    }

    public static Block getBlock(BlockPos pos) {
        return get(pos).getBlock();
    }

    public static final Block waterFlowing = Blocks.FLOWING_WATER;
    public static final Block waterStill = Blocks.WATER;
    public static final Block lavaFlowing = Blocks.FLOWING_LAVA;
    public static final Block lavaStill = Blocks.LAVA;

    /**
     * Returns whether or not the specified block is
     * water, regardless of whether or not it is flowing.
     *
     * @param b The block
     * @return Whether or not the block is water
     */
    public static boolean isWater(Block b) {
        return waterFlowing.equals(b) || waterStill.equals(b);
    }

    /**
     * Returns whether or not the block at the specified pos is
     * water, regardless of whether or not it is flowing.
     *
     * @param bp The block pos
     * @return Whether or not the block is water
     */
    public static boolean isWater(BlockPos bp) {
        return isWater(BlockStateInterface.getBlock(bp));
    }

    public static boolean isLava(Block b) {
        return lavaFlowing.equals(b) || lavaStill.equals(b);
    }

    public static boolean isMagma(Block b) {
        return b.getTranslationKey().equals("tile.magma");
    }

    /**
     * Returns whether or not the specified pos has a liquid
     *
     * @param p The pos
     * @return Whether or not the block is a liquid
     */
    public static boolean isLiquid(BlockPos p) {
        return BlockStateInterface.getBlock(p) instanceof BlockLiquid;
    }

    public static boolean isFlowing(IBlockState state) {
        // Will be IFluidState in 1.13
        return state.getBlock() instanceof BlockLiquid
                && state.getPropertyKeys().contains(BlockLiquid.LEVEL)
                && state.getValue(BlockLiquid.LEVEL) != 0;
    }

    public static boolean isAir(BlockPos pos) {
        return BlockStateInterface.getBlock(pos).equals(Blocks.AIR);
    }

    public static boolean isAir(IBlockState state) {
        return state.getBlock().equals(Blocks.AIR);
    }

    static boolean canFall(BlockPos pos) {
        return BlockStateInterface.get(pos).getBlock() instanceof BlockFalling;
    }


}
