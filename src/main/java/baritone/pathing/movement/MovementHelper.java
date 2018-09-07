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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.behavior.impl.LookBehaviorUtils;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementFall;
import baritone.utils.*;
import baritone.wrapper.IInventoryPlayer;
import net.minecraft.block.*;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.chunk.EmptyChunk;

import java.util.List;
import java.util.Optional;

/**
 * Static helpers for cost calculation
 *
 * @author leijurv
 */
public interface MovementHelper extends ActionCosts, Helper {

    static boolean avoidBreaking(BlockPos pos, IBlockState state) {
        Block b = state.getBlock();
        Block below = BlockStateInterface.get(new BlockPos(pos.getX(), pos.getY() - 1, pos.getZ())).getBlock();
        return Blocks.ICE.equals(b) // ice becomes water, and water can mess up the path
                || b instanceof BlockSilverfish // obvious reasons
                || BlockStateInterface.isLiquid(new BlockPos(pos.getX(), pos.getY() + 1, pos.getZ()))//don't break anything touching liquid on any side
                || BlockStateInterface.isLiquid(new BlockPos(pos.getX() + 1, pos.getY(), pos.getZ()))
                || BlockStateInterface.isLiquid(new BlockPos(pos.getX() - 1, pos.getY(), pos.getZ()))
                || BlockStateInterface.isLiquid(new BlockPos(pos.getX(), pos.getY(), pos.getZ() + 1))
                || BlockStateInterface.isLiquid(new BlockPos(pos.getX(), pos.getY(), pos.getZ() - 1));
    }

    /**
     * Can I walk through this block? e.g. air, saplings, torches, etc
     *
     * @param pos
     * @return
     */
    static boolean canWalkThrough(BlockPos pos) {
        return canWalkThrough(pos, BlockStateInterface.get(pos));
    }

    static boolean canWalkThrough(BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block instanceof BlockFire
                || block instanceof BlockTripWire
                || block instanceof BlockWeb
                || block instanceof BlockEndPortal) {//you can't actually walk through a lilypad from the side, and you shouldn't walk through fire
            return false;
        }
        if (block instanceof BlockDoor || block instanceof BlockFenceGate) {
            if (block == Blocks.IRON_DOOR) {
                return false;
            }
            return true; // we can just open the door
        }
        if (block instanceof BlockSnow || block instanceof BlockTrapDoor) {
            // we've already checked doors
            // so the only remaining dynamic isPassables are snow, fence gate, and trapdoor
            // if they're cached as a top block, we don't know their metadata
            // default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (mc.world.getChunk(pos) instanceof EmptyChunk) {
                return true;
            }
        }
        if (BlockStateInterface.isFlowing(state)) {
            return false; // Don't walk through flowing liquids
        }
        if (block instanceof BlockLiquid) {
            if (Baritone.settings().assumeWalkOnWater.get()) {
                return false;
            }
            IBlockState up = BlockStateInterface.get(pos.up());
            if (up.getBlock() instanceof BlockLiquid || up.getBlock() instanceof BlockLilyPad) {
                return false;
            }
        }
        return block.isPassable(mc.world, pos);
    }

    static boolean isReplacable(BlockPos pos, IBlockState state) {
        // for MovementTraverse and MovementAscend
        // block double plant defaults to true when the block doesn't match, so don't need to check that case
        // all other overrides just return true or false
        // the only case to deal with is snow
        /*
         *  public boolean isReplaceable(IBlockAccess worldIn, BlockPos pos)
         *     {
         *         return ((Integer)worldIn.getBlockState(pos).getValue(LAYERS)).intValue() == 1;
         *     }
         */
        if (state.getBlock() instanceof BlockSnow) {
            // as before, default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (mc.world.getChunk(pos) instanceof EmptyChunk) {
                return true;
            }
        }
        return state.getBlock().isReplaceable(mc.world, pos);
    }

    static boolean isDoorPassable(BlockPos doorPos, BlockPos playerPos) {
        if (playerPos.equals(doorPos))
            return false;

        IBlockState state = BlockStateInterface.get(doorPos);
        if (!(state.getBlock() instanceof BlockDoor))
            return true;

        return isHorizontalBlockPassable(doorPos, state, playerPos, BlockDoor.OPEN);
    }

    static boolean isGatePassable(BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos))
            return false;

        IBlockState state = BlockStateInterface.get(gatePos);
        if (!(state.getBlock() instanceof BlockFenceGate))
            return true;

        return isHorizontalBlockPassable(gatePos, state, playerPos, BlockFenceGate.OPEN);
    }

    static boolean isHorizontalBlockPassable(BlockPos blockPos, IBlockState blockState, BlockPos playerPos, PropertyBool propertyOpen) {
        if (playerPos.equals(blockPos))
            return false;

        EnumFacing.Axis facing = blockState.getValue(BlockHorizontal.FACING).getAxis();
        boolean open = blockState.getValue(propertyOpen);

        EnumFacing.Axis playerFacing;
        if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos)) {
            playerFacing = EnumFacing.Axis.Z;
        } else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos)) {
            playerFacing = EnumFacing.Axis.X;
        } else {
            return true;
        }

        return facing == playerFacing == open;
    }

    static boolean avoidWalkingInto(Block block) {
        return BlockStateInterface.isLava(block)
                || block instanceof BlockCactus
                || block instanceof BlockFire
                || block instanceof BlockEndPortal
                || block instanceof BlockWeb;
    }

    /**
     * Can I walk on this block without anything weird happening like me falling
     * through? Includes water because we know that we automatically jump on
     * water
     *
     * @return
     */
    static boolean canWalkOn(BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.AIR) {
            return false;
        }
        if (block instanceof BlockLadder || (Baritone.settings().allowVines.get() && block instanceof BlockVine)) { // TODO reconsider this
            return true;
        }
        if (block instanceof BlockGlass || block instanceof BlockStainedGlass) {
            return true;
        }
        if (Blocks.FARMLAND.equals(block) || Blocks.GRASS_PATH.equals(block)) {
            return true;
        }
        if (Blocks.ENDER_CHEST.equals(block) || Blocks.CHEST.equals(block)) {
            return true;
        }
        if (block instanceof BlockSlab) {
            if (!Baritone.settings().allowWalkOnBottomSlab.get()) {
                if (((BlockSlab) block).isDouble()) {
                    return true;
                }
                return state.getValue(BlockSlab.HALF) != BlockSlab.EnumBlockHalf.BOTTOM;
            }
            return true;
        }
        if (block instanceof BlockStairs) {
            return true;
        }
        if (BlockStateInterface.isWater(block)) {
            Block up = BlockStateInterface.get(pos.up()).getBlock();
            if (up instanceof BlockLilyPad) {
                return true;
            }
            if (BlockStateInterface.isFlowing(state)) {
                // the only scenario in which we can walk on flowing water is if it's under still water with jesus off
                return BlockStateInterface.isWater(up) && !Baritone.settings().assumeWalkOnWater.get();
            }
            // if assumeWalkOnWater is on, we can only walk on water if there isn't water above it
            // if assumeWalkOnWater is off, we can only walk on water if there is water above it
            return BlockStateInterface.isWater(up) ^ Baritone.settings().assumeWalkOnWater.get();
        }
        if (BlockStateInterface.isMagma(block)) {
            return false;
        }
        return state.isBlockNormalCube() && !BlockStateInterface.isLava(block);
    }

    static boolean canWalkOn(BlockPos pos) {
        return canWalkOn(pos, BlockStateInterface.get(pos));
    }

    static boolean canFall(BlockPos pos) {
        return BlockStateInterface.get(pos).getBlock() instanceof BlockFalling;
    }

    static boolean canPlaceAgainst(BlockPos pos) {
        IBlockState state = BlockStateInterface.get(pos);
        // TODO isBlockNormalCube isn't the best check for whether or not we can place a block against it. e.g. glass isn't normalCube but we can place against it
        return state.isBlockNormalCube();
    }

    static double getMiningDurationTicks(CalculationContext context, BlockPos position, boolean includeFalling) {
        IBlockState state = BlockStateInterface.get(position);
        return getMiningDurationTicks(context, position, state, includeFalling);
    }

    static double getMiningDurationTicks(CalculationContext context, BlockPos position, IBlockState state, boolean includeFalling) {
        Block block = state.getBlock();
        if (!block.equals(Blocks.AIR) && !canWalkThrough(position, state)) { // TODO is the air check really necessary? Isn't air canWalkThrough?
            if (!context.allowBreak()) {
                return COST_INF;
            }
            if (avoidBreaking(position, state)) {
                return COST_INF;
            }
            double m = Blocks.CRAFTING_TABLE.equals(block) ? 10 : 1; // TODO see if this is still necessary. it's from MineBot when we wanted to penalize breaking its crafting table
            double result = m / context.getToolSet().getStrVsBlock(state);
            if (includeFalling) {
                BlockPos up = position.up();
                IBlockState above = BlockStateInterface.get(up);
                if (above.getBlock() instanceof BlockFalling) {
                    result += getMiningDurationTicks(context, up, above, true);
                }
            }
            return result;
        }
        return 0; // we won't actually mine it, so don't check fallings above
    }

    static boolean isBottomSlab(IBlockState state) {
        return state.getBlock() instanceof BlockSlab
                && !((BlockSlab) state.getBlock()).isDouble()
                && state.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.BOTTOM;
    }

    static boolean isBottomSlab(BlockPos pos) {
        return isBottomSlab(BlockStateInterface.get(pos));
    }

    /**
     * The entity the player is currently looking at
     *
     * @return the entity object
     */
    static Optional<Entity> whatEntityAmILookingAt() {
        if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == RayTraceResult.Type.ENTITY) {
            return Optional.of(mc.objectMouseOver.entityHit);
        }
        return Optional.empty();
    }

    /**
     * AutoTool
     */
    static void switchToBestTool() {
        LookBehaviorUtils.getSelectedBlock().ifPresent(pos -> {
            IBlockState state = BlockStateInterface.get(pos);
            if (state.getBlock().equals(Blocks.AIR)) {
                return;
            }
            switchToBestToolFor(state);
        });
    }

    /**
     * AutoTool for a specific block
     *
     * @param b the blockstate to mine
     */
    static void switchToBestToolFor(IBlockState b) {
        switchToBestToolFor(b, new ToolSet());
    }

    /**
     * AutoTool for a specific block with precomputed ToolSet data
     *
     * @param b  the blockstate to mine
     * @param ts previously calculated ToolSet
     */
    static void switchToBestToolFor(IBlockState b, ToolSet ts) {
        mc.player.inventory.currentItem = ts.getBestSlot(b);
    }

    static boolean throwaway(boolean select) {
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        List<ItemStack> inv = ((IInventoryPlayer) p.inventory).getMainInventory();
        for (byte i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            if (item == null)
                continue;

            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (Baritone.settings().acceptableThrowawayItems.get().contains(item.getItem())) {
                if (select) {
                    p.inventory.currentItem = i;
                }
                return true;
            }
        }
        return false;
    }

    static void moveTowards(MovementState state, BlockPos pos) {
        state.setTarget(new MovementTarget(
                new Rotation(Utils.calcRotationFromVec3d(mc.player.getPositionEyes(1.0F),
                        Utils.getBlockPosCenter(pos),
                        new Rotation(mc.player.rotationYaw, mc.player.rotationPitch)).getFirst(), mc.player.rotationPitch),
                false
        )).setInput(InputOverrideHandler.Input.MOVE_FORWARD, true);
    }

    static Movement generateMovementFallOrDescend(BlockPos pos, BlockPos dest, CalculationContext calcContext) {
        // A
        //SA
        // A
        // B
        // C
        // D
        //if S is where you start, B needs to be air for a movementfall
        //A is plausibly breakable by either descend or fall
        //C, D, etc determine the length of the fall

        if (!canWalkThrough(dest.down(2))) {
            //if B in the diagram aren't air
            //have to do a descend, because fall is impossible

            //this doesn't guarantee descend is possible, it just guarantees fall is impossible
            return new MovementDescend(pos, dest.down()); // standard move out by 1 and descend by 1
        }

        // we're clear for a fall 2
        // let's see how far we can fall
        for (int fallHeight = 3; true; fallHeight++) {
            BlockPos onto = dest.down(fallHeight);
            if (onto.getY() < 0) {
                // when pathing in the end, where you could plausibly fall into the void
                // this check prevents it from getting the block at y=-1 and crashing
                break;
            }
            IBlockState ontoBlock = BlockStateInterface.get(onto);
            if (BlockStateInterface.isWater(ontoBlock.getBlock())) {
                return new MovementFall(pos, onto);
            }
            if (canWalkThrough(onto, ontoBlock)) {
                continue;
            }
            if (canWalkOn(onto, ontoBlock)) {
                if ((calcContext.hasWaterBucket() && fallHeight <= calcContext.maxFallHeightBucket() + 1) || fallHeight <= calcContext.maxFallHeightNoWater() + 1) {
                    // fallHeight = 4 means onto.up() is 3 blocks down, which is the max
                    return new MovementFall(pos, onto.up());
                } else {
                    return null;
                }
            }
            break;
        }
        return null;
    }
}
