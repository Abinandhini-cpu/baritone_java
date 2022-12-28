/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.cache;

import baritone.api.cache.ICachedWorld;
import baritone.api.cache.IWorldScanner;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.IPlayerContext;
import baritone.utils.accessor.IBitArray;
import baritone.utils.accessor.IBlockStateContainer;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BitArray;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum FasterWorldScanner implements IWorldScanner {
    INSTANCE;
    @Override
    public List<BlockPos> scanChunkRadius(IPlayerContext ctx, BlockOptionalMetaLookup filter, int max, int yLevelThreshold, int maxSearchRadius) {
        assert ctx.world() != null;
        if (maxSearchRadius < 0) {
            throw new IllegalArgumentException("chunkRange must be >= 0");
        }
        return scanChunksInternal(ctx, filter, getChunkRange(ctx.playerFeet().x >> 4, ctx.playerFeet().z >> 4, maxSearchRadius), max);
    }

    @Override
    public List<BlockPos> scanChunk(IPlayerContext ctx, BlockOptionalMetaLookup filter, ChunkPos pos, int max, int yLevelThreshold) {
        return scanChunkInternal(ctx, filter, pos).limit(max).collect(Collectors.toList());
    }

    @Override
    public int repack(IPlayerContext ctx) {
        return this.repack(ctx, 40);
    }

    @Override
    public int repack(IPlayerContext ctx, int range) {
        IChunkProvider chunkProvider = ctx.world().getChunkProvider();
        ICachedWorld cachedWorld = ctx.worldData().getCachedWorld();

        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - range;
        int minZ = playerChunkZ - range;
        int maxX = playerChunkX + range;
        int maxZ = playerChunkZ + range;

        int queued = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Chunk chunk = chunkProvider.getLoadedChunk(x, z);

                if (chunk != null && !chunk.isEmpty()) {
                    queued++;
                    cachedWorld.queueForPacking(chunk);
                }
            }
        }

        return queued;
    }

    // ordered in a way that the closest blocks are generally first
    public static List<ChunkPos> getChunkRange(int centerX, int centerZ, int chunkRadius) {
        List<ChunkPos> chunks = new ArrayList<>();
        // spiral out
        chunks.add(new ChunkPos(centerX, centerZ));
        for (int i = 1; i < chunkRadius; i++) {
            for (int j = 0; j <= i; j++) {
                chunks.add(new ChunkPos(centerX - j, centerZ - i));
                if (j != 0) {
                    chunks.add(new ChunkPos(centerX + j, centerZ - i));
                    chunks.add(new ChunkPos(centerX - j, centerZ + i));
                }
                chunks.add(new ChunkPos(centerX + j, centerZ + i));
                if (j != i) {
                    chunks.add(new ChunkPos(centerX - i, centerZ - j));
                    chunks.add(new ChunkPos(centerX + i, centerZ - j));
                    if (j != 0) {
                        chunks.add(new ChunkPos(centerX - i, centerZ + j));
                        chunks.add(new ChunkPos(centerX + i, centerZ + j));
                    }
                }
            }
        }
        return chunks;
    }

    private List<BlockPos> scanChunksInternal(IPlayerContext ctx, BlockOptionalMetaLookup lookup, List<ChunkPos> chunkPositions, int maxBlocks) {
        assert ctx.world() != null;
        try {
            Stream<BlockPos> posStream = chunkPositions.parallelStream().flatMap(p -> scanChunkInternal(ctx, lookup, p));
            if (maxBlocks >= 0) {
                // WARNING: this can be expensive if maxBlocks is large...
                // see limit's javadoc
                posStream = posStream.limit(maxBlocks);
            }
            return posStream.collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private Stream<BlockPos> scanChunkInternal(IPlayerContext ctx, BlockOptionalMetaLookup lookup, ChunkPos pos) {
        IChunkProvider chunkProvider = ctx.world().getChunkProvider();
        // if chunk is not loaded, return empty stream
        if (!chunkProvider.isChunkGeneratedAt(pos.x, pos.z)) {
            return Stream.empty();
        }

        long chunkX = (long) pos.x << 4;
        long chunkZ = (long) pos.z << 4;

        List<BlockPos> blocks = new ArrayList<>();

        collectChunkSections(lookup, chunkProvider.getLoadedChunk(pos.x, pos.z), (section, isInFilter) -> {
            int yOffset = section.getYLocation();
            BitArray array = (BitArray) ((IBlockStateContainer) section.getData()).getStorage();
            collectBlockLocations(array, isInFilter, place -> blocks.add(new BlockPos(
                    chunkX + ((place & 255) & 15),
                    yOffset + (place >> 8),
                    chunkZ + ((place & 255) >> 4)
            )));
        });
        return blocks.stream();
    }



    private void collectChunkSections(BlockOptionalMetaLookup lookup, Chunk chunk, BiConsumer<ExtendedBlockStorage, boolean[]> consumer) {
        for (ExtendedBlockStorage section : chunk.getBlockStorageArray()) {
            if (section == null || section.isEmpty()) {
                continue;
            }

            BlockStateContainer sectionContainer = section.getData();
            //this won't work if the PaletteStorage is of the type EmptyPaletteStorage
            if (((IBlockStateContainer) sectionContainer).getStorage() == null) {
                continue;
            }

            boolean[] isInFilter = getIncludedFilterIndices(lookup, ((IBlockStateContainer) sectionContainer).getPalette());
            if (isInFilter.length == 0) {
                continue;
            }
            consumer.accept(section, isInFilter);
        }
    }

    private boolean[] getIncludedFilterIndices(BlockOptionalMetaLookup lookup, IBlockStatePalette palette) {
        boolean commonBlockFound = false;
        ObjectIntIdentityMap<IBlockState> paletteMap = getPalette(palette);
        int size = paletteMap.size();

        boolean[] isInFilter = new boolean[size];

        for (int i = 0; i < size; i++) {
            IBlockState state = paletteMap.getByValue(i);
            if (lookup.has(state)) {
                isInFilter[i] = true;
                commonBlockFound = true;
            } else {
                isInFilter[i] = false;
            }
        }

        if (!commonBlockFound) {
            return new boolean[0];
        }
        return isInFilter;
    }

    private static void collectBlockLocations(BitArray array, boolean[] isInFilter, IntConsumer action) {
        long[] longArray = array.getBackingLongArray();
        int arraySize = array.size();
        int bitsPerEntry = ((IBitArray) array).getBitsPerEntry();
        long maxEntryValue = ((IBitArray) array).getMaxEntryValue();

        for (int idx = 0, kl = bitsPerEntry - 1; idx < arraySize; idx++, kl += bitsPerEntry) {
            final int i = idx * bitsPerEntry;
            final int j = i >> 6;
            final int l = i & 63;
            final int k = kl >> 6;
            final long jl = longArray[j] >>> l;

            if (j == k) {
                if (isInFilter[(int) (jl & maxEntryValue)]) {
                    action.accept(idx);
                }
            } else {
                if (isInFilter[(int) ((jl | longArray[k] << (64 - l)) & maxEntryValue)]) {
                    action.accept(idx);
                }
            }
        }
    }

    /**
     * cheats to get the actual map of id -> blockstate from the various palette implementations
     */
    private static ObjectIntIdentityMap<IBlockState> getPalette(IBlockStatePalette palette) {
        if (palette instanceof BlockStatePaletteRegistry) {
            return Block.BLOCK_STATE_IDS;
        } else {
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
            palette.write(buf);
            int size = buf.readVarInt();
            ObjectIntIdentityMap<IBlockState> states = new ObjectIntIdentityMap<>();
            for (int i = 0; i < size; i++) {
                IBlockState state = Block.BLOCK_STATE_IDS.getByValue(buf.readVarInt());
                assert state != null;
                states.put(state, i);
            }
            return states;
        }
    }
}