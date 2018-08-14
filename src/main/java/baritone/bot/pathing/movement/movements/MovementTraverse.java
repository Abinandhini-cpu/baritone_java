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

package baritone.bot.pathing.movement.movements;

import baritone.bot.behavior.impl.LookBehaviorUtils;
import baritone.bot.pathing.movement.CalculationContext;
import baritone.bot.pathing.movement.Movement;
import baritone.bot.pathing.movement.MovementHelper;
import baritone.bot.pathing.movement.MovementState;
import baritone.bot.utils.BlockStateInterface;
import baritone.bot.utils.InputOverrideHandler;
import baritone.bot.utils.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockVine;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

public class MovementTraverse extends Movement {

    private BlockPos[] against = new BlockPos[3];

    /**
     * Did we have to place a bridge block or was it always there
     */
    private boolean wasTheBridgeBlockAlwaysThere = true;

    public MovementTraverse(BlockPos from, BlockPos to) {
        super(from, to, new BlockPos[]{to.up(), to}, new BlockPos[]{to.down()});
        int i = 0;
        if (!to.north().equals(from))
            against[i++] = to.north().down();

        if (!to.south().equals(from))
            against[i++] = to.south().down();

        if (!to.east().equals(from))
            against[i++] = to.east().down();

        if (!to.west().equals(from))
            against[i] = to.west().down();

        //note: do NOT add ability to place against .down().down()
    }

    @Override
    protected double calculateCost(CalculationContext context) {
        IBlockState pb0 = BlockStateInterface.get(positionsToBreak[0]);
        IBlockState pb1 = BlockStateInterface.get(positionsToBreak[1]);
        IBlockState destOn = BlockStateInterface.get(positionsToPlace[0]);
        if (MovementHelper.canWalkOn(positionsToPlace[0], destOn)) {//this is a walk, not a bridge
            double WC = BlockStateInterface.isWater(pb0.getBlock()) || BlockStateInterface.isWater(pb1.getBlock()) ? WALK_ONE_IN_WATER_COST : WALK_ONE_BLOCK_COST;
            if (destOn.getBlock().equals(Blocks.SOUL_SAND)) {
                WC *= WALK_ONE_IN_WATER_COST / WALK_ONE_BLOCK_COST;
            }
            if (MovementHelper.canWalkThrough(positionsToBreak[0]) && MovementHelper.canWalkThrough(positionsToBreak[1])) {
                if (WC == WALK_ONE_BLOCK_COST) {
                    // if there's nothing in the way, and this isn't water or soul sand, and we aren't sneak placing
                    // we can sprint =D
                    WC = SPRINT_ONE_BLOCK_COST;
                }
                return WC;
            }
            //double hardness1 = blocksToBreak[0].getBlockHardness(Minecraft.getMinecraft().world, positionsToBreak[0]);
            //double hardness2 = blocksToBreak[1].getBlockHardness(Minecraft.getMinecraft().world, positionsToBreak[1]);
            //Out.log("Can't walk through " + blocksToBreak[0] + " (hardness" + hardness1 + ") or " + blocksToBreak[1] + " (hardness " + hardness2 + ")");
            return WC + getTotalHardnessOfBlocksToBreak(context.getToolSet());
        } else {//this is a bridge, so we need to place a block
            //return 1000000;
            Block f = BlockStateInterface.get(src.down()).getBlock();
            if (f instanceof BlockLadder || f instanceof BlockVine) {
                return COST_INF;
            }
            IBlockState pp0 = BlockStateInterface.get(positionsToPlace[0]);
            if (pp0.getBlock().equals(Blocks.AIR) || (!BlockStateInterface.isWater(pp0.getBlock()) && pp0.getBlock().isReplaceable(Minecraft.getMinecraft().world, positionsToPlace[0]))) {
                if (!context.hasThrowaway()) {
                    return COST_INF;
                }
                double WC = BlockStateInterface.isWater(pb0.getBlock()) || BlockStateInterface.isWater(pb1.getBlock()) ? WALK_ONE_IN_WATER_COST : WALK_ONE_BLOCK_COST;
                for (BlockPos against1 : against) {
                    if (BlockStateInterface.get(against1).isBlockNormalCube()) {
                        return WC + PLACE_ONE_BLOCK_COST + getTotalHardnessOfBlocksToBreak(context.getToolSet());
                    }
                }
                if (BlockStateInterface.get(src).getBlock().equals(Blocks.SOUL_SAND)) {
                    return COST_INF; // can't sneak and backplace against soul sand =/
                }
                WC = WC * SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST;//since we are placing, we are sneaking
                return WC + PLACE_ONE_BLOCK_COST + getTotalHardnessOfBlocksToBreak(context.getToolSet());
            }
            return COST_INF;
            //Out.log("Can't walk on " + Baritone.get(positionsToPlace[0]).getBlock());
        }
    }

    @Override
    public void run(MovementState state) {
        Block fd = BlockStateInterface.get(src.down()).getBlock();
        boolean ladder = fd instanceof BlockLadder || fd instanceof BlockVine;
        boolean isTheBridgeBlockThere = MovementHelper.canWalkOn(positionsToPlace[0]) || ladder;
        BlockPos whereAmI = playerFeet();
        if (whereAmI.getY() != dest.getY() && !ladder) {
            System.out.println("Wrong Y coordinate");
            if (whereAmI.getY() < dest.getY()) {
                state.setInput(InputOverrideHandler.Input.JUMP, true);
            }
            return;
        }
        if (isTheBridgeBlockThere) {
            if (playerFeet().equals(dest)) {
                state.setStatus(MovementState.MovementStatus.SUCCESS);
                return;
            }
            if (wasTheBridgeBlockAlwaysThere && !BlockStateInterface.isLiquid(playerFeet())) {
                player().setSprinting(true);
            }
            MovementHelper.moveTowards(state, positionsToBreak[0]);
        } else {
            wasTheBridgeBlockAlwaysThere = false;
            for (BlockPos against1 : against) {
                if (BlockStateInterface.get(against1).isBlockNormalCube()) {
                    if (!MovementHelper.throwaway(true)) { // get ready to place a throwaway block
                        displayChatMessageRaw("bb pls get me some blocks. dirt or cobble");
                        state.setStatus(MovementState.MovementStatus.UNREACHABLE);
                        return;
                    }
                    state.setInput(InputOverrideHandler.Input.SNEAK, true);
                    double faceX = (dest.getX() + against1.getX() + 1.0D) * 0.5D;
                    double faceY = (dest.getY() + against1.getY()) * 0.5D;
                    double faceZ = (dest.getZ() + against1.getZ() + 1.0D) * 0.5D;
                    state.setTarget(new MovementState.MovementTarget(Utils.calcRotationFromVec3d(playerHead(), new Vec3d(faceX, faceY, faceZ), playerRotations())));

                    EnumFacing side = Minecraft.getMinecraft().objectMouseOver.sideHit;
                    if (Objects.equals(LookBehaviorUtils.getSelectedBlock().orElse(null), against1) && Minecraft.getMinecraft().player.isSneaking()) {
                        if (LookBehaviorUtils.getSelectedBlock().get().offset(side).equals(positionsToPlace[0])) {
                            state.setInput(InputOverrideHandler.Input.CLICK_RIGHT, true);
                        } else {
                            // Out.gui("Wrong. " + side + " " + LookBehaviorUtils.getSelectedBlock().get().offset(side) + " " + positionsToPlace[0], Out.Mode.Debug);
                        }
                    }
                    System.out.println("Trying to look at " + against1 + ", actually looking at" + LookBehaviorUtils.getSelectedBlock());
                    return;
                }
            }
            state.setInput(InputOverrideHandler.Input.SNEAK, true);
            if (whereAmI.equals(dest)) {
                // if we are in the block that we are trying to get to, we are sneaking over air and we need to place a block beneath us against the one we just walked off of
                // Out.log(from + " " + to + " " + faceX + "," + faceY + "," + faceZ + " " + whereAmI);
                if (!MovementHelper.throwaway(true)) {// get ready to place a throwaway block
                    displayChatMessageRaw("bb pls get me some blocks. dirt or cobble");
                    state.setStatus(MovementState.MovementStatus.UNREACHABLE);
                    return;
                }
                double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
                double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
                double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
                // faceX, faceY, faceZ is the middle of the face between from and to
                BlockPos goalLook = src.down(); // this is the block we were just standing on, and the one we want to place against
                state.setTarget(new MovementState.MovementTarget(Utils.calcRotationFromVec3d(playerHead(), new Vec3d(faceX, faceY, faceZ), playerRotations())));

                state.setInput(InputOverrideHandler.Input.MOVE_BACK, true);
                state.setInput(InputOverrideHandler.Input.SNEAK, true);
                if (Objects.equals(LookBehaviorUtils.getSelectedBlock().orElse(null), goalLook)) {
                    state.setInput(InputOverrideHandler.Input.CLICK_RIGHT, true); // wait to right click until we are able to place
                }
                // Out.log("Trying to look at " + goalLook + ", actually looking at" + Baritone.whatAreYouLookingAt());
            } else {
                MovementHelper.moveTowards(state, positionsToBreak[0]);
                // TODO MovementManager.moveTowardsBlock(to); // move towards not look at because if we are bridging for a couple blocks in a row, it is faster if we dont spin around and walk forwards then spin around and place backwards for every block
            }
        }
    }
}
