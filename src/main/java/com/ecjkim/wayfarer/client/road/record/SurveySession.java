/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod

 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.

 * MinecraftNavigationAndMapMod is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with MinecraftNavigationAndMapMod.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.ecjkim.wayfarer.client.road.record;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.ecjkim.wayfarer.client.ToolItemManager;
import com.ecjkim.wayfarer.client.WayfarerConfig;
import com.ecjkim.wayfarer.client.road.RoadMetadataScreen;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Manages the survey (manual) road recording state machine.
 *
 * <p>
 * States: {@code IDLE} → click air with tool → {@code RECORDING} → click to end → back to {@code IDLE}. Right-click
 * adds waypoint nodes; Ctrl+scroll changes the corner type preset.
 * </p>
 */
public class SurveySession {
    public enum State {
        IDLE, RECORDING, PAUSED
    }

    private static final int MOUSE_LEFT = 0;
    private static final int MOUSE_RIGHT = 1;

    private State state = State.IDLE;
    private final List<UUID> nodeIds = new ArrayList<>();
    private final List<NodeOrigin> nodeOrigins = new ArrayList<>();
    private Direction currentDirection = Direction.BIDIRECTIONAL;
    private Vec3 lastNodePos;
    private Segment pendingSegment;
    private int particleTickCounter;

    private final IntSet mouseButtonDownLastTick = new IntOpenHashSet();
    private boolean wasInGuiLastTick = false;

    public State getState() {
        return state;
    }

    public Direction getCurrentDirection() {
        return currentDirection;
    }

    public Vec3 getLastNodePos() {
        return lastNodePos;
    }

    public int getNodeCount() {
        return nodeIds.size();
    }

    public List<UUID> getNodeIds() {
        return nodeIds;
    }

    // ---- Direction cycling ----

    public void cycleDirectionNext() {
        Direction[] values = Direction.values();
        int idx = (currentDirection.ordinal() + 1) % values.length;
        currentDirection = values[idx];
    }

    public void cycleDirectionPrev() {
        Direction[] values = Direction.values();
        int idx = (currentDirection.ordinal() - 1 + values.length) % values.length;
        currentDirection = values[idx];
    }

    /** Cycle direction type and notify the player. */
    public void cycleDirection(LocalPlayer player) {
        cycleDirectionNext();
        if (player != null) {
            player.displayClientMessage(
                Component.translatable("wayfarer.road.survey.direction", currentDirection.name()), false);
        }
    }

    // ---- Tick ----

    public void tick(Minecraft client, long window) {
        if (client.player == null)
            return;

        boolean hasTool = ToolItemManager.hasToolItem(client.player);
        boolean inGui = client.screen != null;

        // Detect GUI → game transition: sync mouse state so the first frame back
        // doesn't generate spurious click edges from a button still physically held.
        if (!inGui && wasInGuiLastTick) {
            mouseButtonDownLastTick.clear();
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, MOUSE_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS)
                mouseButtonDownLastTick.add(MOUSE_LEFT);
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, MOUSE_RIGHT) == org.lwjgl.glfw.GLFW.GLFW_PRESS)
                mouseButtonDownLastTick.add(MOUSE_RIGHT);
        }
        wasInGuiLastTick = inGui;

        // Process mouse clicks only when holding the Survey tool item and NOT in a GUI screen.
        if (hasTool && !inGui && (state == State.IDLE || state == State.RECORDING)) {
            processMouseClicks(client, window);
        }

        // If tool is not held, clear the tracked mouse button state
        // so we don't miss a subsequent press when the player picks the tool up again.
        if (!hasTool) {
            mouseButtonDownLastTick.clear();
        }

        if (state == State.IDLE)
            return;

        if (state == State.RECORDING) {
            if (!hasTool) {
                // Tool removed: transition to PAUSED (preserve all data)
                state = State.PAUSED;
                client.player.displayClientMessage(Component.translatable("wayfarer.road.survey.tool_removed_paused"),
                    false);
                return;
            }
            // Don't spawn particles when in a GUI screen
            if (!inGui) {
                spawnPathParticles(client);
            }
        } else if (state == State.PAUSED) {
            if (hasTool) {
                // Tool picked up: resume recording
                // Validate that all nodes in nodeIds still exist in the database
                if (validateNodeIntegrity()) {
                    state = State.RECORDING;
                    particleTickCounter = 0;
                    client.player.displayClientMessage(
                        Component.translatable("wayfarer.road.survey.tool_resumed", nodeIds.size()), false);
                } else {
                    // Data integrity check failed, cancel the recording
                    client.player.displayClientMessage(Component.translatable("wayfarer.road.survey.integrity_failed"),
                        false);
                    cancelRecording(client.player);
                }
            }
            // In PAUSED state: do NOT process mouse clicks or spawn particles
        }
    }

    /**
     * Validate that all nodes in nodeIds still exist in the database. Returns false if any node is missing (data
     * corruption detected).
     */
    private boolean validateNodeIntegrity() {
        if (nodeIds.isEmpty()) {
            return false;
        }
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        for (UUID nodeId : nodeIds) {
            if (db.getNode(nodeId) == null) {
                return false;
            }
        }
        return true;
    }

    // ---- Mouse handling ----

    private void processMouseClicks(Minecraft client, long window) {
        boolean leftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, MOUSE_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean rightDown =
            org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, MOUSE_RIGHT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

        boolean leftEdge = leftDown && !mouseButtonDownLastTick.contains(MOUSE_LEFT);
        boolean rightEdge = rightDown && !mouseButtonDownLastTick.contains(MOUSE_RIGHT);

        if (leftDown)
            mouseButtonDownLastTick.add(MOUSE_LEFT);
        else
            mouseButtonDownLastTick.remove(MOUSE_LEFT);
        if (rightDown)
            mouseButtonDownLastTick.add(MOUSE_RIGHT);
        else
            mouseButtonDownLastTick.remove(MOUSE_RIGHT);

        if (leftEdge) {
            onLeftClick(client);
        } else if (rightEdge) {
            onRightClick(client);
        }
    }

    private void onLeftClick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null)
            return;

        UUID hitNodeId = findNearbyNode(client);
        if (hitNodeId != null) {
            handleLeftClickOnNode(player, hitNodeId);
        } else {
            handleLeftClickOnAir(player);
        }
    }

    private void onRightClick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null)
            return;

        UUID hitNodeId = findNearbyNode(client);
        if (hitNodeId != null) {
            handleRightClickOnNode(player, hitNodeId);
        } else {
            handleRightClickOnAir(player);
        }
    }

    // ---- Left-click handlers ----

    /**
     * Handle left-click: create a node at the block the player is looking at.
     *
     * <p>
     * IDLE + click air = start recording with first node at block position RECORDING + click air = end recording with
     * final node at block position
     * </p>
     */
    private void handleLeftClickOnAir(LocalPlayer player) {
        Vec3 blockPos = getLookedAtBlockPos(player);
        if (blockPos == null) {
            return;
        }
        blockPos = snapCoordIfNeeded(blockPos);

        if (state == State.IDLE) {
            // Try auto-snap to a segment if enabled
            WayfarerConfig config = WayfarerConfig.getInstance();
            if (config.isAutoSnapEndpoints()) {
                UUID[] snapResult = snapToSegment(blockPos.x, blockPos.y, blockPos.z, config.getRdpEpsilon());
                if (snapResult != null) {
                    // Successfully snapped to a segment
                    Node snappedNode = RoadNetworkDatabase.getInstance().getNode(snapResult[0]);
                    if (snappedNode != null) {
                        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
                        synchronized (db) {
                            nodeIds.add(snappedNode.getId());
                            nodeOrigins.add(NodeOrigin.LEFT_CLICK);
                            lastNodePos = new Vec3(snappedNode.getX(), snappedNode.getY(), snappedNode.getZ());
                            state = State.RECORDING;
                        }
                        String segmentIdSuffix =
                            snapResult[1].toString().substring(snapResult[1].toString().length() - 4);
                        player.displayClientMessage(
                            Component.translatable("wayfarer.road.survey.node_inserted_to_segment", segmentIdSuffix),
                            false);
                        return;
                    }
                }
            }

            // No snap target found, create a new node
            Node startNode = createNode(blockPos.x, blockPos.y, blockPos.z);
            RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
            synchronized (db) {
                db.addNode(startNode);
                nodeIds.add(startNode.getId());
                nodeOrigins.add(NodeOrigin.LEFT_CLICK);
                lastNodePos = blockPos;
                state = State.RECORDING;
            }
            player.displayClientMessage(
                Component.translatable("wayfarer.road.survey.recording_started_node", formatCoord(blockPos)), false);
        } else if (state == State.RECORDING) {
            // Try auto-snap to a segment if enabled
            WayfarerConfig config = WayfarerConfig.getInstance();
            if (config.isAutoSnapEndpoints()) {
                UUID[] snapResult = snapToSegment(blockPos.x, blockPos.y, blockPos.z, config.getRdpEpsilon());
                if (snapResult != null) {
                    // Successfully snapped to a segment
                    Node snappedNode = RoadNetworkDatabase.getInstance().getNode(snapResult[0]);
                    if (snappedNode != null) {
                        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
                        synchronized (db) {
                            nodeIds.add(snappedNode.getId());
                            nodeOrigins.add(NodeOrigin.LEFT_CLICK);
                            lastNodePos = new Vec3(snappedNode.getX(), snappedNode.getY(), snappedNode.getZ());
                            finishRecording(player);
                        }
                        String segmentIdSuffix =
                            snapResult[1].toString().substring(snapResult[1].toString().length() - 4);
                        player.displayClientMessage(
                            Component.translatable("wayfarer.road.survey.node_inserted_to_segment", segmentIdSuffix),
                            false);
                        return;
                    }
                }
            }

            Node endNode = createNode(blockPos.x, blockPos.y, blockPos.z);
            RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
            synchronized (db) {
                db.addNode(endNode);
                nodeIds.add(endNode.getId());
                nodeOrigins.add(NodeOrigin.LEFT_CLICK);
                finishRecording(player);
            }
        }
    }

    private void handleLeftClickOnNode(LocalPlayer player, UUID hitNodeId) {
        if (state == State.IDLE) {
            // Start recording by clicking on an existing node
            RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
            synchronized (db) {
                if (!nodeIds.contains(hitNodeId)) {
                    nodeIds.add(hitNodeId);
                    nodeOrigins.add(NodeOrigin.LEFT_CLICK);
                }
                Node hitNode = db.getNode(hitNodeId);
                if (hitNode != null) {
                    lastNodePos = new Vec3(hitNode.getX(), hitNode.getY(), hitNode.getZ());
                }
                state = State.RECORDING;
            }
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.recording_started_snapped"),
                false);
        } else if (state == State.RECORDING) {
            // Ad-snap: connect to existing node, then end
            RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
            synchronized (db) {
                if (!nodeIds.contains(hitNodeId)) {
                    nodeIds.add(hitNodeId);
                    nodeOrigins.add(NodeOrigin.LEFT_CLICK);
                }
                Node hitNode = db.getNode(hitNodeId);
                if (hitNode != null) {
                    lastNodePos = new Vec3(hitNode.getX(), hitNode.getY(), hitNode.getZ());
                }
                finishRecording(player);
            }
        }
    }

    // ---- Right-click handlers ----

    /**
     * Handle right-click: create a waypoint at the block the player is looking at.
     */
    private void handleRightClickOnAir(LocalPlayer player) {
        if (state != State.RECORDING) {
            return;
        }

        Vec3 blockPos = getLookedAtBlockPos(player);
        if (blockPos == null) {
            return;
        }
        blockPos = snapCoordIfNeeded(blockPos);

        Node waypoint = createNode(blockPos.x, blockPos.y, blockPos.z);
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            db.addNode(waypoint);
            nodeIds.add(waypoint.getId());
            nodeOrigins.add(NodeOrigin.RIGHT_CLICK);
            lastNodePos = blockPos;
        }
        player.displayClientMessage(
            Component.translatable("wayfarer.road.survey.waypoint_placed", nodeIds.size(), formatCoord(blockPos)),
            false);
    }

    private void handleRightClickOnNode(LocalPlayer player, UUID hitNodeId) {
        if (state != State.RECORDING) {
            return;
        }
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            if (!nodeIds.contains(hitNodeId)) {
                nodeIds.add(hitNodeId);
                nodeOrigins.add(NodeOrigin.RIGHT_CLICK);
            }
            Node hitNode = db.getNode(hitNodeId);
            if (hitNode != null) {
                lastNodePos = new Vec3(hitNode.getX(), hitNode.getY(), hitNode.getZ());
            }
        }
        player.displayClientMessage(
            Component.translatable("wayfarer.road.survey.waypoint_snapped", nodeIds.size(), formatCoord(lastNodePos)),
            false);
    }

    // ---- Recording lifecycle ----

    private void finishRecording(LocalPlayer player) {
        if (nodeIds.size() < 2) {
            cleanupOrphanData();
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.too_few_nodes"), false);
            state = State.IDLE;
            return;
        }

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            // Store a copy of node IDs for rollback if needed
            List<UUID> nodesToRollback = new ArrayList<>(nodeIds);

            Segment segment =
                new Segment(UUID.randomUUID(), new ArrayList<>(nodeIds), null, Source.USER, currentDirection, 1);
            db.addSegment(segment);

            if (!db.saveToDisk()) {
                // Save failed: roll back BOTH segment and nodes
                db.removeSegment(segment.getId());
                for (UUID nodeId : nodesToRollback) {
                    db.removeNode(nodeId);
                }
                player.displayClientMessage(Component.translatable("wayfarer.road.survey.save_failed_not_ended"),
                    false);
                return;
            }

            pendingSegment = segment;
            // Store node IDs in segment for later cleanup if user cancels metadata screen
            segment.setNodeIds(new ArrayList<>(nodeIds));
            state = State.IDLE;
            nodeIds.clear();
            nodeOrigins.clear();
            lastNodePos = null;

            Minecraft client = Minecraft.getInstance();
            client.setScreen(new RoadMetadataScreen(segment, savedRoad -> {
                player.displayClientMessage(
                    Component.translatable("wayfarer.road.survey.road_saved", savedRoad.getName()), false);
                pendingSegment = null;
            }, () -> {
                // User closed metadata screen without saving — segment stays as unfiled
                segment.setModifiedAt(System.currentTimeMillis());
                RoadNetworkDatabase.getInstance().updateSegment(segment.getId(), segment);
                RoadNetworkDatabase.getInstance().saveToDisk();
                pendingSegment = null;
                player.displayClientMessage(Component.translatable("wayfarer.road.gui.metadata.segment_left_unfiled"),
                    false);
            }, () -> {
                // User discarded the segment — clean up was already done by RoadMetadataScreen.discardSegment()
                pendingSegment = null;
                player.displayClientMessage(Component.translatable("wayfarer.road.survey.segment_discarded"), false);
            }));
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.recording_ended"), false);
        }
    }

    // ---- Helper methods ----

    private Node createNode(double x, double y, double z) {
        return new Node(UUID.randomUUID(), x, y, z, Source.USER, 1, System.currentTimeMillis());
    }

    private void cleanupOrphanData() {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            for (UUID nodeId : nodeIds) {
                // Only remove nodes that are exclusive to this recording (not shared with other segments)
                int segmentCount = db.getSegmentCountForNode(nodeId);
                if (segmentCount <= 1) {
                    db.removeNode(nodeId);
                }
                // If segmentCount > 1, the node is shared - don't remove it
            }
            if (pendingSegment != null) {
                db.removeSegment(pendingSegment.getId());
                pendingSegment = null;
            }
            db.saveToDisk();
        }
    }

    /**
     * Clean up a segment and its exclusive nodes when the user cancels metadata editing. Shared nodes are preserved.
     */
    private void cleanupPendingSegmentAndNodes(Segment segment) {
        if (segment == null) {
            return;
        }
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            // Remove exclusive nodes (not shared with other segments)
            List<UUID> nodeIdsToClean = segment.getNodeIds();
            if (nodeIdsToClean != null) {
                for (UUID nodeId : nodeIdsToClean) {
                    // Only remove nodes that are exclusive to this segment
                    int segmentCount = db.getSegmentCountForNode(nodeId);
                    if (segmentCount <= 1) {
                        db.removeNode(nodeId);
                    }
                }
            }
            // Remove the segment itself
            db.removeSegment(segment.getId());
            // Save to disk to persist the cleanup
            db.saveToDisk();
        }
    }

    /**
     * Find a nearby node that the player is looking at, within a distance based on rdpEpsilon. Excludes nodes that are
     * already in the current recording list.
     */
    private UUID findNearbyNode(Minecraft client) {
        if (client.player == null)
            return null;
        LocalPlayer player = client.player;

        // Get the looked-at block position
        Vec3 blockPos = getLookedAtBlockPos(player);
        if (blockPos == null) {
            return null;
        }
        blockPos = snapCoordIfNeeded(blockPos);

        // Use rdpEpsilon as the snap distance threshold
        WayfarerConfig config = WayfarerConfig.getInstance();
        double epsilon = config.getRdpEpsilon();
        double maxDistSq = epsilon * epsilon;

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        UUID closest = null;
        double closestDistSq = maxDistSq;

        // Find the last LEFT_CLICK node for near-node exception logic
        UUID lastLeftClickNodeId = null;
        double lastLeftClickX = Double.NaN, lastLeftClickZ = Double.NaN;
        for (int i = nodeIds.size() - 1; i >= 0; i--) {
            if (nodeOrigins.get(i) == NodeOrigin.LEFT_CLICK) {
                lastLeftClickNodeId = nodeIds.get(i);
                Node n = db.getNode(lastLeftClickNodeId);
                if (n != null) {
                    lastLeftClickX = n.getX();
                    lastLeftClickZ = n.getZ();
                }
                break;
            }
        }
        double nearExceptionDistSq = maxDistSq * 4.0;

        for (Node node : db.getAllNodes()) {
            UUID nodeId = node.getId();
            int nodeIdx = nodeIds.indexOf(nodeId);
            boolean isInRecording = nodeIdx >= 0;

            if (isInRecording) {
                // Exception 1: always allow snapping to start node
                if (nodeIdx == 0) {
                    // allowed
                } else {
                    // Exception 2: allow snapping to a RIGHT_CLICK waypoint node
                    // if the last LEFT_CLICK node is very close to it
                    if (nodeOrigins.get(nodeIdx) == NodeOrigin.RIGHT_CLICK && lastLeftClickNodeId != null
                        && !Double.isNaN(lastLeftClickX)) {
                        double nDx = node.getX() - lastLeftClickX;
                        double nDz = node.getZ() - lastLeftClickZ;
                        double nDistSq = nDx * nDx + nDz * nDz;
                        if (nDistSq < nearExceptionDistSq) {
                            // allowed
                        } else {
                            continue;
                        }
                    } else {
                        continue;
                    }
                }
            }

            double dx = blockPos.x - node.getX();
            double dz = blockPos.z - node.getZ();
            double distSq = dx * dx + dz * dz;

            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = nodeId;
            }
        }
        return closest;
    }

    /**
     * Try to snap a point to the nearest road segment edge. If the point is within {@code epsilon} distance of a
     * segment edge, insert a new node at the perpendicular foot and split the segment.
     *
     * <p>
     * Segments that contain nodes from the current recording list are excluded to prevent snapping to nodes that were
     * just created in this recording session.
     * </p>
     *
     * @param x X coordinate of the point
     * @param y Y coordinate of the point
     * @param z Z coordinate of the point
     * @param epsilon maximum snap distance
     * @return a UUID array [nodeId, segmentId] if snapped, or null if no snap target found
     */
    private UUID[] snapToSegment(double x, double y, double z, double epsilon) {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();

        double bestDist = epsilon;
        Segment bestSeg = null;
        int bestInsertAfter = -1;
        double bestFootX = 0;
        double bestFootY = 0;
        double bestFootZ = 0;

        for (Segment seg : db.getAllSegments()) {
            // Skip segments that contain nodes from the current recording
            List<UUID> segNodeIds = seg.getNodeIds();
            if (segNodeIds != null && segNodeIds.stream().anyMatch(nodeIds::contains)) {
                continue;
            }

            List<Node> segNodes = db.getNodesForSegment(seg.getId());
            for (int i = 0; i < segNodes.size() - 1; i++) {
                Node a = segNodes.get(i);
                Node b = segNodes.get(i + 1);
                double dx2 = b.getX() - a.getX();
                double dz2 = b.getZ() - a.getZ();
                double lenSq = dx2 * dx2 + dz2 * dz2;
                if (lenSq == 0)
                    continue;
                double t = ((x - a.getX()) * dx2 + (z - a.getZ()) * dz2) / lenSq;
                if (t < 0 || t > 1)
                    continue;
                double projX = a.getX() + t * dx2;
                double projZ = a.getZ() + t * dz2;
                double pdx = x - projX;
                double pdz = z - projZ;
                double d = Math.sqrt(pdx * pdx + pdz * pdz);
                if (d < bestDist) {
                    bestDist = d;
                    bestSeg = seg;
                    bestInsertAfter = i;
                    bestFootX = projX;
                    bestFootY = a.getY() + t * (b.getY() - a.getY());
                    bestFootZ = projZ;
                }
            }
        }

        if (bestSeg != null) {
            // Insert node into segment (this will split the segment into two)
            Node newNode = db.insertNodeIntoSegment(bestSeg.getId(), bestInsertAfter + 1, bestFootX, bestFootZ);
            if (newNode != null) {
                return new UUID[] {newNode.getId(), bestSeg.getId()};
            }
        }

        return null;
    }

    /**
     * Get the position of the block the player is looking at. Returns the block's grid coordinates, so that nodes are
     * placed exactly on the targeted block's footprint regardless of which face the player clicked on.
     *
     * @return the block's grid position as Vec3, or null if not looking at a block
     */
    private Vec3 getLookedAtBlockPos(LocalPlayer player) {
        HitResult hitResult = player.pick(5.0, 1.0F, false);
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockHitResult blockHit = (BlockHitResult)hitResult;
        net.minecraft.core.BlockPos blockPos = blockHit.getBlockPos();
        return new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    }

    /** Format a coordinate vector for display. */
    private static String formatCoord(Vec3 pos) {
        return String.format("(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z);
    }

    private static Vec3 snapCoordIfNeeded(Vec3 pos) {
        if (WayfarerConfig.getInstance().isAutoIntegral()) {
            return new Vec3(Math.round(pos.x), Math.round(pos.y), Math.round(pos.z));
        }
        return pos;
    }

    // ---- Tool detection (called from WayfarerClient tick) ----

    /**
     * Called when the player picks up the tool. Resumes a paused session if applicable.
     */
    public void onToolPickedUp(LocalPlayer player) {
        particleTickCounter = 0;
        WayfarerConfig config = WayfarerConfig.getInstance();
        if (config.isShowKeyHints()) {
            if (state == State.IDLE) {
                player.displayClientMessage(Component.translatable("wayfarer.road.survey.hint_idle"), false);
            } else if (state == State.RECORDING) {
                player.displayClientMessage(Component.translatable("wayfarer.road.survey.hint_recording"), false);
            } else if (state == State.PAUSED) {
                player.displayClientMessage(Component.translatable("wayfarer.road.survey.hint_paused"), false);
            }
        } else {
            if (state == State.IDLE) {
                player.displayClientMessage(Component.translatable("wayfarer.road.survey.tool_ready"), false);
            }
        }
    }

    /** Reset the session completely. */
    public void reset() {
        cleanupOrphanData();
        state = State.IDLE;
        nodeIds.clear();
        nodeOrigins.clear();
        lastNodePos = null;
        currentDirection = Direction.BIDIRECTIONAL;
        pendingSegment = null;
        mouseButtonDownLastTick.clear();
        particleTickCounter = 0;
    }

    // ---- Programmatic control (for hotkey bindings) ----

    /** Force start recording at player's current position (IDLE → RECORDING). */
    public void forceStartRecording(LocalPlayer player) {
        if (player == null) {
            return;
        }
        if (state != State.IDLE) {
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.already_recording"), false);
            return;
        }

        Vec3 pos = player.position();
        Node startNode = createNode(pos.x, pos.y, pos.z);
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            db.addNode(startNode);
            nodeIds.add(startNode.getId());
            nodeOrigins.add(NodeOrigin.LEFT_CLICK);
            lastNodePos = new Vec3(pos.x, pos.y, pos.z);
            state = State.RECORDING;
        }
        player.displayClientMessage(Component.translatable("wayfarer.road.survey.recording_started_hotkey"), false);
    }

    /** Force stop recording at player's current position (RECORDING → IDLE, save flow). */
    public void forceStopRecording(LocalPlayer player) {
        if (player == null) {
            return;
        }
        if (state != State.RECORDING) {
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.not_recording"), false);
            return;
        }

        Vec3 pos = player.position();
        Node endNode = createNode(pos.x, pos.y, pos.z);
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            db.addNode(endNode);
            nodeIds.add(endNode.getId());
            nodeOrigins.add(NodeOrigin.LEFT_CLICK);
            finishRecording(player);
        }
    }

    /** Cancel current recording and discard all data. */
    public void cancelRecording(LocalPlayer player) {
        if (player == null) {
            return;
        }
        if (state == State.IDLE && nodeIds.isEmpty()) {
            player.displayClientMessage(Component.translatable("wayfarer.road.survey.nothing_to_cancel"), false);
            return;
        }

        cleanupOrphanData();
        state = State.IDLE;
        nodeIds.clear();
        nodeOrigins.clear();
        lastNodePos = null;
        pendingSegment = null;

        player.displayClientMessage(Component.translatable("wayfarer.road.survey.recording_cancelled"), false);
    }

    // ---- Particle path ----

    private void spawnPathParticles(Minecraft client) {
        if (nodeIds.size() < 2)
            return;
        particleTickCounter++;
        if (particleTickCounter % 5 != 0)
            return;

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        // Pick a random adjacent pair from nodeIds and spawn particles along the line
        int segIndex = (particleTickCounter / 5) % (nodeIds.size() - 1);
        Node a = db.getNode(nodeIds.get(segIndex));
        Node b = db.getNode(nodeIds.get(segIndex + 1));
        if (a == null || b == null)
            return;

        double ax = a.getX(), ay = a.getY(), az = a.getZ();
        double bx = b.getX(), by = b.getY(), bz = b.getZ();

        // Spawn 2-3 particles per pulse, at random positions along the line
        int count = 2 + client.level.random.nextInt(2);
        for (int i = 0; i < count; i++) {
            double t = client.level.random.nextDouble();
            double px = ax + (bx - ax) * t;
            double py = ay + (by - ay) * t + 1.2;
            double pz = az + (bz - az) * t;
            client.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, px, py, pz, 0,
                0.01, 0);
        }
    }

    private enum NodeOrigin {
        LEFT_CLICK, RIGHT_CLICK
    }
}
