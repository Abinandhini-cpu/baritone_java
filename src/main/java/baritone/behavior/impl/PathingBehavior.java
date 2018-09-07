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

package baritone.behavior.impl;

import baritone.Baritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.behavior.Behavior;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.calc.IPathFinder;
import baritone.pathing.goals.*;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.IPath;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BlockStateInterface;
import baritone.utils.CompatibilityHelper;
import baritone.utils.PathRenderer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.EmptyChunk;

import java.awt.*;
import java.util.Collections;
import java.util.Optional;

public class PathingBehavior extends Behavior {

    public static final PathingBehavior INSTANCE = new PathingBehavior();

    private PathingBehavior() {
    }

    private PathExecutor current;
    private PathExecutor next;

    private Goal goal;

    private volatile boolean isPathCalcInProgress;
    private final Object pathCalcLock = new Object();

    private final Object pathPlanLock = new Object();

    private boolean lastAutoJump;

    private void dispatchPathEvent(PathEvent event) {
        new Thread(() -> Baritone.INSTANCE.getGameEventHandler().onPathEvent(event)).start();
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            this.cancel();
            return;
        }
        if (current == null) {
            return;
        }
        boolean safe = current.onTick(event);
        synchronized (pathPlanLock) {
            if (current.failed() || current.finished()) {
                current = null;
                if (goal == null || goal.isInGoal(playerFeet())) {
                    displayChatMessageRaw("All done. At " + goal);
                    dispatchPathEvent(PathEvent.AT_GOAL);
                    next = null;
                    return;
                }
                if (next != null && !next.getPath().positions().contains(playerFeet())) {
                    // if the current path failed, we may not actually be on the next one, so make sure
                    displayChatMessageRaw("Discarding next path as it does not contain current position");
                    // for example if we had a nicely planned ahead path that starts where current ends
                    // that's all fine and good
                    // but if we fail in the middle of current
                    // we're nowhere close to our planned ahead path
                    // so need to discard it sadly.
                    dispatchPathEvent(PathEvent.DISCARD_NEXT);
                    next = null;
                }
                if (next != null) {
                    displayChatMessageRaw("Continuing on to planned next path");
                    dispatchPathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
                    current = next;
                    next = null;
                    return;
                }
                // at this point, current just ended, but we aren't in the goal and have no plan for the future
                synchronized (pathCalcLock) {
                    if (isPathCalcInProgress) {
                        dispatchPathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
                        // if we aren't calculating right now
                        return;
                    }
                    dispatchPathEvent(PathEvent.CALC_STARTED);
                    findPathInNewThread(pathStart(), true, Optional.empty());
                }
                return;
            }
            // at this point, we know current is in progress
            if (safe) {
                // a movement just ended
                if (next != null) {
                    if (next.getPath().positions().contains(playerFeet())) {
                        // jump directly onto the next path
                        displayChatMessageRaw("Splicing into planned next path early...");
                        dispatchPathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
                        current = next;
                        next = null;
                        return;
                    }
                }
            }
            synchronized (pathCalcLock) {
                if (isPathCalcInProgress) {
                    // if we aren't calculating right now
                    return;
                }
                if (next != null) {
                    // and we have no plan for what to do next
                    return;
                }
                if (goal == null || goal.isInGoal(current.getPath().getDest())) {
                    // and this path dosen't get us all the way there
                    return;
                }
                if (ticksRemainingInSegment().get() < Baritone.settings().planningTickLookAhead.get()) {
                    // and this path has 5 seconds or less left
                    displayChatMessageRaw("Path almost over. Planning ahead...");
                    dispatchPathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
                    findPathInNewThread(current.getPath().getDest(), false, Optional.of(current.getPath()));
                }
            }
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (current != null && CompatibilityHelper.isAutoJumpSupported()) {
            switch (event.getState()) {
                case PRE:
                    lastAutoJump = CompatibilityHelper.isAutoJump();
                    CompatibilityHelper.setAutoJump(false);
                    break;
                case POST:
                    CompatibilityHelper.setAutoJump(lastAutoJump);
                    break;
            }
        }
    }

    public Optional<Double> ticksRemainingInSegment() {
        if (current == null) {
            return Optional.empty();
        }
        return Optional.of(current.getPath().ticksRemainingFrom(current.getPosition()));
    }

    public void setGoal(Goal goal) {
        this.goal = goal;
    }

    public Goal getGoal() {
        return goal;
    }

    public PathExecutor getCurrent() {
        return current;
    }

    public PathExecutor getNext() {
        return next;
    }

    public Optional<IPath> getPath() {
        return Optional.ofNullable(current).map(PathExecutor::getPath);
    }

    public void cancel() {
        current = null;
        next = null;
        Baritone.INSTANCE.getInputOverrideHandler().clearAllKeys();
        AbstractNodeCostSearch.getCurrentlyRunning().ifPresent(AbstractNodeCostSearch::cancel);
    }

    /**
     * Start calculating a path if we aren't already
     *
     * @return true if this call started path calculation, false if it was already calculating or executing a path
     */
    public boolean path() {
        if (goal == null) {
            return false;
        }
        if (goal.isInGoal(playerFeet())) {
            return false;
        }
        synchronized (pathPlanLock) {
            if (current != null) {
                return false;
            }
            synchronized (pathCalcLock) {
                if (isPathCalcInProgress) {
                    return false;
                }
                dispatchPathEvent(PathEvent.CALC_STARTED);
                findPathInNewThread(pathStart(), true, Optional.empty());
                return true;
            }
        }
    }

    public BlockPos pathStart() {
        BlockPos feet = playerFeet();
        if (BlockStateInterface.get(feet.down()).getBlock().equals(Blocks.AIR) && MovementHelper.canWalkOn(feet.down().down())) {
            return feet.down();
        }
        return feet;
    }

    /**
     * In a new thread, pathfind to target blockpos
     *
     * @param start
     * @param talkAboutIt
     */
    private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt, final Optional<IPath> previous) {
        synchronized (pathCalcLock) {
            if (isPathCalcInProgress) {
                throw new IllegalStateException("Already doing it");
            }
            isPathCalcInProgress = true;
        }
        new Thread(() -> {
            if (talkAboutIt) {
                displayChatMessageRaw("Starting to search for path from " + start + " to " + goal);
            }

            Optional<IPath> path = findPath(start, previous);
            if (Baritone.settings().cutoffAtLoadBoundary.get()) {
                path = path.map(IPath::cutoffAtLoadedChunks);
            }
            Optional<PathExecutor> executor = path.map(p -> p.staticCutoff(goal)).map(PathExecutor::new);
            synchronized (pathPlanLock) {
                if (current == null) {
                    if (executor.isPresent()) {
                        dispatchPathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                        current = executor.get();
                    } else {
                        dispatchPathEvent(PathEvent.CALC_FAILED);
                    }
                } else {
                    if (next == null) {
                        if (executor.isPresent()) {
                            dispatchPathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                            next = executor.get();
                        } else {
                            dispatchPathEvent(PathEvent.NEXT_CALC_FAILED);
                        }
                    } else {
                        throw new IllegalStateException("I have no idea what to do with this path");
                    }
                }
            }

            if (talkAboutIt && current != null && current.getPath() != null) {
                if (goal == null || goal.isInGoal(current.getPath().getDest())) {
                    displayChatMessageRaw("Finished finding a path from " + start + " to " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                } else {
                    displayChatMessageRaw("Found path segment from " + start + " towards " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");

                }
            }
            synchronized (pathCalcLock) {
                isPathCalcInProgress = false;
            }
        }).start();
    }

    /**
     * Actually do the pathing
     *
     * @param start
     * @return
     */
    private Optional<IPath> findPath(BlockPos start, Optional<IPath> previous) {
        Goal goal = this.goal;
        if (goal == null) {
            displayChatMessageRaw("no goal");
            return Optional.empty();
        }
        if (Baritone.settings().simplifyUnloadedYCoord.get()) {
            BlockPos pos = null;
            if (goal instanceof GoalBlock) {
                pos = ((GoalBlock) goal).getGoalPos();
            }
            if (goal instanceof GoalTwoBlocks) {
                pos = ((GoalTwoBlocks) goal).getGoalPos();
            }
            if (goal instanceof GoalNear) {
                pos = ((GoalNear) goal).getGoalPos();
            }
            if (goal instanceof GoalGetToBlock) {
                pos = ((GoalGetToBlock) goal).getGoalPos();
            }
            // TODO simplify each individual goal in a GoalComposite
            if (pos != null && world().getChunk(pos) instanceof EmptyChunk) {
                displayChatMessageRaw("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
                goal = new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        long timeout;
        if (current == null) {
            timeout = Baritone.settings().pathTimeoutMS.<Long>get();
        } else {
            timeout = Baritone.settings().planAheadTimeoutMS.<Long>get();
        }
        try {
            IPathFinder pf = new AStarPathFinder(start, goal, previous.map(IPath::positions));
            return pf.calculate(timeout);
        } catch (Exception e) {
            displayChatMessageRaw("Pathing exception: " + e);
            e.printStackTrace();
            return Optional.empty();
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        // System.out.println("Render passing");
        // System.out.println(event.getPartialTicks());
        float partialTicks = event.getPartialTicks();
        if (goal != null && Baritone.settings().renderGoal.value) {
            PathRenderer.drawLitDankGoalBox(player(), goal, partialTicks, Color.GREEN);
        }
        if (!Baritone.settings().renderPath.get()) {
            return;
        }

        long start = System.nanoTime();


        PathExecutor current = this.current; // this should prevent most race conditions?
        PathExecutor next = this.next; // like, now it's not possible for current!=null to be true, then suddenly false because of another thread
        // TODO is this enough, or do we need to acquire a lock here?
        // TODO benchmark synchronized in render loop

        // Render the current path, if there is one
        if (current != null && current.getPath() != null) {
            int renderBegin = Math.max(current.getPosition() - 3, 0);
            PathRenderer.drawPath(current.getPath(), renderBegin, player(), partialTicks, Color.RED, Baritone.settings().fadePath.get(), 10, 20);
        }
        if (next != null && next.getPath() != null) {
            PathRenderer.drawPath(next.getPath(), 0, player(), partialTicks, Color.MAGENTA, Baritone.settings().fadePath.get(), 10, 20);
        }

        long split = System.nanoTime();
        if (current != null) {
            PathRenderer.drawManySelectionBoxes(player(), current.toBreak(), partialTicks, Color.RED);
            PathRenderer.drawManySelectionBoxes(player(), current.toPlace(), partialTicks, Color.GREEN);
            PathRenderer.drawManySelectionBoxes(player(), current.toWalkInto(), partialTicks, Color.MAGENTA);
        }

        // If there is a path calculation currently running, render the path calculation process
        AbstractNodeCostSearch.getCurrentlyRunning().ifPresent(currentlyRunning -> {
            currentlyRunning.bestPathSoFar().ifPresent(p -> {
                PathRenderer.drawPath(p, 0, player(), partialTicks, Color.BLUE, Baritone.settings().fadePath.get(), 10, 20);
                currentlyRunning.pathToMostRecentNodeConsidered().ifPresent(mr -> {

                    PathRenderer.drawPath(mr, 0, player(), partialTicks, Color.CYAN, Baritone.settings().fadePath.get(), 10, 20);
                    PathRenderer.drawManySelectionBoxes(player(), Collections.singletonList(mr.getDest()), partialTicks, Color.CYAN);
                });
            });
        });
        long end = System.nanoTime();
        //System.out.println((end - split) + " " + (split - start));
        // if (end - start > 0)
        //   System.out.println("Frame took " + (split - start) + " " + (end - split));

    }
}
