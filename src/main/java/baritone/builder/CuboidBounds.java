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

package baritone.builder;

import baritone.api.utils.BetterBlockPos;

/**
 * Bounding box of a cuboid
 * <p>
 * Basically just a lot of helper util methods lol
 */
public class CuboidBounds implements Bounds {

    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    private final int sizeXMinusOne;
    private final int sizeYMinusOne;
    private final int sizeZMinusOne;
    public final int size;
    private final int sizeMinusOne;

    public CuboidBounds(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.sizeXMinusOne = sizeX - 1;
        this.sizeYMinusOne = sizeY - 1;
        this.sizeZMinusOne = sizeZ - 1;
        this.size = sizeX * sizeY * sizeZ;
        this.sizeMinusOne = size - 1;
        if (Main.DEBUG) {
            sanityCheck();
        }
    }

    @Override
    public int toIndex(int x, int y, int z) {
        if (Main.DEBUG && !inRange(x, y, z)) {
            throw new IllegalStateException();
        }
        return (x * sizeY + y) * sizeZ + z;
    }

    @Override
    public boolean inRange(int x, int y, int z) {
        return inRangeBranchless(x, y, z);
    }

    @Override
    public int volume() {
        return size;
    }

    @Deprecated
    public boolean inRangeBranchy(int x, int y, int z) { // benchmarked: approx 4x slower than branchless
        return (x >= 0) && (x < sizeX) && (y >= 0) && (y < sizeY) && (z >= 0) && (z < sizeZ);
    }

    public boolean inRangeBranchless(int x, int y, int z) {
        return (x | y | z | (sizeXMinusOne - x) | (sizeYMinusOne - y) | (sizeZMinusOne - z)) >= 0;
    }

    public boolean inRangeBranchless2(int x, int y, int z) {
        return (x | y | z | ((sizeX - 1) - x) | ((sizeY - 1) - y) | ((sizeZ - 1) - z)) >= 0;
    }

    public boolean inRangeBranchless3(int x, int y, int z) {
        return (x | y | z | (sizeX - (x + 1)) | (sizeY - (y + 1)) | (sizeZ - (z + 1))) >= 0;
    }

    public boolean inRangeBranchless4(int x, int y, int z) {
        return (x | y | z | ((sizeX - x) - 1) | ((sizeY - y) - 1) | ((sizeZ - z) - 1)) >= 0;
    }

    public boolean inRangeIndex(int index) {
        return (index | (sizeMinusOne - index)) >= 0;
    }


    @Override
    public void forEach(BoundsIntsConsumer consumer) {
        int sizeX = this.sizeX, sizeY = this.sizeY, sizeZ = this.sizeZ;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    consumer.consume(x, y, z);
                }
            }
        }
    }

    @Override
    public void forEach(BoundsLongConsumer consumer) {
        int sizeX = this.sizeX, sizeY = this.sizeY, sizeZ = this.sizeZ;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    consumer.consume(BetterBlockPos.toLong(x, y, z));
                }
            }
        }
    }

    @Override
    public void forEach(BoundsIntAndLongConsumer consumer) {
        int sizeX = this.sizeX, sizeY = this.sizeY, sizeZ = this.sizeZ;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    consumer.consume(x, y, z, BetterBlockPos.toLong(x, y, z));
                }
            }
        }
    }

    private void sanityCheck() {
        if (sizeY > 256) {
            throw new IllegalStateException();
        }
        long chk = ((long) sizeX) * ((long) sizeY) * ((long) sizeZ);
        if (chk != (long) size) {
            throw new IllegalStateException();
        }
        int index = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (!inRange(x, y, z)) {
                        throw new IllegalStateException();
                    }
                    if (toIndex(x, y, z) != index) {
                        throw new IllegalStateException();
                    }
                    index++;
                }
            }
        }
        if (index != size) {
            throw new IllegalStateException();
        }
        if (inRange(-1, 0, 0) || inRange(0, -1, 0) || inRange(0, 0, -1)) {
            throw new IllegalStateException();
        }
        if (inRange(sizeX, 0, 0) || inRange(0, sizeY, 0) || inRange(0, 0, sizeZ)) {
            throw new IllegalStateException();
        }
        Bounds.sanityCheckConnectedness(this);
    }
}
