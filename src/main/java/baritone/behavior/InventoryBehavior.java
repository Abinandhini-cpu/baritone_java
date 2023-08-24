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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
import baritone.api.utils.InventorySlot;
import baritone.api.utils.Pair;
import baritone.utils.ItemInteractionHelper;
import baritone.utils.ToolSet;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.*;
import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class InventoryBehavior extends Behavior implements Helper {

    private int ticksSinceLastInventoryMove;
    private int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        ticksSinceLastInventoryMove++;

        // TODO: Move these checks into "requestSwapWithHotBar" or whatever supersedes it
        if (!this.canAccessInventory()) {
            return;
        }
        if (ctx.player().openContainer != ctx.player().inventoryContainer) {
            // we have a crafting table or a chest or something open
            return;
        }

        if (Baritone.settings().allowHotbarManagement.value && baritone.getPathingBehavior().isPathing()) {
            this.setupHotbar();
        }

        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1]);
        }
    }

    private void setupHotbar() {
        // TODO: Some way of indicating which slots are currently reserved by this setting

        final InventorySlot throwaway = this.findSlotMatching(this::isThrowawayItem);
        if (throwaway != null && throwaway.getType() == InventorySlot.Type.INVENTORY) {
            // aka there are none on the hotbar, but there are some in main inventory
            this.requestSwapWithHotBar(throwaway.getInventoryIndex(), 8);
            return;
        }

        final InventorySlot pick = this.bestToolAgainst(Blocks.STONE, ItemPickaxe.class);
        if (pick != null && pick.getType() == InventorySlot.Type.INVENTORY) {
            this.requestSwapWithHotBar(pick.getInventoryIndex(), 0);
        }
    }

    private InventorySlot bestToolAgainst(final Block against, final Class<? extends ItemTool> cla$$) {
        return new ToolSet(ctx).getBestSlot(against.getDefaultState(), Baritone.settings().preferSilkTouch.value, stack -> cla$$.isInstance(stack.getItem()));
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, IntPredicate disallowedHotbar) {
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt())) {
                return false;
            }
        }
        return true;
    }

    public OptionalInt getTempHotbarSlot(IntPredicate disallowedHotbar) {
        // we're using 0 and 8 for pickaxe and throwaway
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (ctx.player().inventory.mainInventory.get(i).isEmpty() && !disallowedHotbar.test(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 1; i < 8; i++) {
                if (!disallowedHotbar.test(i)) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(candidates.get(new Random().nextInt(candidates.size())));
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar) {
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Baritone.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        ctx.playerController().windowClick(ctx.player().inventoryContainer.windowId, inInventory < 9 ? inInventory + 36 : inInventory, inHotbar, ClickType.SWAP, ctx.player());
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        return true;
    }

    public boolean hasGenericThrowaway() {
        return this.canSelectItem(this::isThrowawayItem);
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        final Predicate<Predicate<? super ItemStack>> op = select ? this::trySelectItem : this::canSelectItem;

        final IBlockState maybe = baritone.getBuilderProcess().placeAt(x, y, z, baritone.bsi.get0(x, y, z));
        if (maybe != null) {
            return op.test(stack -> {
                if (!(stack.getItem() instanceof ItemBlock)) {
                    return false;
                }
                Block block = ((ItemBlock) stack.getItem()).getBlock();
                return maybe.equals(block.getStateForPlacement(
                        ctx.world(),
                        ctx.playerFeet(),
                        EnumFacing.UP,
                        0.5f, 1.0f, 0.5f,
                        stack.getItem().getMetadata(stack.getMetadata()),
                        ctx.player()
                ));
            }) || op.test(stack -> {
                // Since a stack didn't match the desired block state, accept a match of just the block
                return stack.getItem() instanceof ItemBlock
                        && ((ItemBlock) stack.getItem()).getBlock().equals(maybe.getBlock());
            });
        }
        return op.test(this::isThrowawayItem);
    }

    public boolean canSelectItem(Predicate<? super ItemStack> desired) {
        return this.resolveSelectionStrategy(this.findSlotMatching(desired)) != null;
    }

    public boolean trySelectItem(Predicate<? super ItemStack> desired) {
        final SelectionStrategy strategy = this.resolveSelectionStrategy(this.findSlotMatching(desired));
        if (strategy != null) {
            strategy.run();
            // TODO: Consider cases where returning the SelectionType is needed/useful to the caller
            return true;
        }
        return false;
    }

    public SelectionStrategy resolveSelectionStrategy(final InventorySlot slot) {
        if (slot != null) {
            switch (slot.getType()) {
                case HOTBAR:
                    return SelectionStrategy.of(SelectionType.IMMEDIATE, () ->
                            ctx.player().inventory.currentItem = slot.getInventoryIndex());
                case OFFHAND:
                    // TODO: It's probably worth adding a parameter to this method so that the caller can indicate what
                    //       the purpose of the item is. For example, attacks are always done using the main hand, so
                    //       just switching to a non-interacting hotbar item isn't sufficient.
                    final ItemStack heldItem = ctx.player().inventory.getCurrentItem();

                    if (!ItemInteractionHelper.couldInteract(heldItem)) {
                        // Don't need to do anything, the item held in the main hand doesn't have any interaction.
                        return SelectionStrategy.of(SelectionType.IMMEDIATE, () -> {});
                    }

                    final InventorySlot hotbar = this.findHotbarMatching(item -> !ItemInteractionHelper.couldInteract(item));
                    if (hotbar != null) {
                        return SelectionStrategy.of(SelectionType.IMMEDIATE, () ->
                                ctx.player().inventory.currentItem = hotbar.getInventoryIndex());
                    }

                    // TODO: Swap offhand with an unimportant item
                    break;
                case INVENTORY:
                    if (this.canAccessInventory()) {
                        return SelectionStrategy.of(SelectionType.ENQUEUED, () -> {
                            // TODO: Determine if hotbar swap can be immediate, and return type accordingly
                            //       Also don't only swap into slot 7 that's silly
                            requestSwapWithHotBar(slot.getInventoryIndex(), 7);
                            ctx.player().inventory.currentItem = 7;
                        });
                    }
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    public InventorySlot findBestAccessibleMatching(final Comparator<? super ItemStack> comparator,
                                                    final Predicate<? super ItemStack> filter) {
        final Stream<Pair<InventorySlot, ItemStack>> accessible = this.canAccessInventory()
                ? ctx.inventory().allSlots()
                : Stream.concat(ctx.inventory().hotbarSlots(), Stream.of(ctx.inventory().offhand()));
        return this.findBestMatching0(accessible, comparator, filter);
    }

    public InventorySlot findSlotMatching(final Predicate<? super ItemStack> filter) {
        return this.findBestSlotMatching(null, filter);
    }

    /**
     * Returns an {@link InventorySlot} that contains a stack matching the given predicate. A comparator may be
     * specified to prioritize slot selection. The comparator may be {@code null}, in which case, the first slot
     * matching the predicate is returned. The considered slots are in the order of hotbar, offhand, and finally the
     * main inventory (if {@link #canAccessInventory()} is {@code true}).
     *
     * @param comparator A comparator to find the best element, may be {@code null}
     * @param filter The predicate to match
     * @return A matching slot, or {@code null} if none.
     */
    public InventorySlot findBestSlotMatching(final Comparator<? super ItemStack> comparator, final Predicate<? super ItemStack> filter) {
        return this.findBestMatching0(ctx.inventory().allSlots(), comparator, filter);
    }

    public InventorySlot findHotbarMatching(final Predicate<? super ItemStack> filter) {
        return this.findBestHotbarMatching(null, filter);
    }

    public InventorySlot findBestHotbarMatching(final Comparator<? super ItemStack> comparator, final Predicate<? super ItemStack> filter) {
        return this.findBestMatching0(ctx.inventory().hotbarSlots(), comparator, filter);
    }

    private InventorySlot findBestMatching0(final Stream<Pair<InventorySlot, ItemStack>>    slots,
                                            final Comparator<? super ItemStack>             comparator,
                                            final Predicate<? super ItemStack>              filter) {
        final Stream<Pair<InventorySlot, ItemStack>> filtered = slots.filter(slot -> filter.test(slot.second()));
        return (comparator != null
                ? filtered.max((a, b) -> comparator.compare(a.second(), b.second()))
                : filtered.findFirst()
        ).map(Pair::first).orElse(null);
    }

    public boolean canAccessInventory() {
        return Baritone.settings().allowInventory.value;
    }

    public boolean isThrowawayItem(ItemStack stack) {
        return this.isThrowawayItem(stack.getItem());
    }

    public boolean isThrowawayItem(Item item) {
        return Baritone.settings().acceptableThrowawayItems.value.contains(item);
    }

    public interface SelectionStrategy extends Runnable {

        @Override
        void run();

        SelectionType getType();

        static SelectionStrategy of(final SelectionType type, final Runnable runnable) {
            return new SelectionStrategy() {
                @Override
                public void run() {
                    runnable.run();
                }

                @Override
                public SelectionType getType() {
                    return type;
                }
            };
        }
    }

    public enum SelectionType {
        IMMEDIATE, ENQUEUED
    }
}
