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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.ecjkim.wayfarer.client.ToolItemManager;
import com.ecjkim.wayfarer.client.road.RoadMetadataScreen;
import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.CornerType;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Source;
import com.ecjkim.wayfarer.client.road.model.Status;

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

    private static final double NODE_HIT_RADIUS = 2.0;
    private static final int MOUSE_LEFT = 0;
    private static final int MOUSE_RIGHT = 1;

    private State state = State.IDLE;
    private final List<UUID> nodeIds = new ArrayList<>();
    private CornerType currentCornerType = CornerType.SHARP;
    private Vec3 lastNodePos;
    private Segment pendingSegment;
    private int particleTickCounter;

    private final IntSet mouseButtonDownLastTick = new IntOpenHashSet();

    public State getState() {
        return state;
    }

    public CornerType getCurrentCornerType() {
        return currentCornerType;
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

    // ---- Corner type cycling ----

    public void cycleCornerTypeNext() {
        CornerType[] values = CornerType.values();
        int idx = (currentCornerType.ordinal() + 1) % values.length;
        currentCornerType = values[idx];
    }

    public void cycleCornerTypePrev() {
        CornerType[] values = CornerType.values();
        int idx = (currentCornerType.ordinal() - 1 + values.length) % values.length;
        currentCornerType = values[idx];
    }

    /** Cycle corner type and notify the player. */
    public void cycleCornerType(LocalPlayer player) {
        cycleCornerTypeNext();
        if (player != null) {
            player.displayClientMessage(Component.literal("Survey 角落类型: " + currentCornerType.name()), false);
        }
    }

    // ---- Tick ----

    public void tick(Minecraft client, long window) {
        if (client.player == null)
            return;

        boolean hasTool = ToolItemManager.hasToolItem(client.player);

        // ESC key to cancel recording (works in both RECORDING and PAUSED states)
        if (state == State.RECORDING || state == State.PAUSED) {
            if (org.lwjgl.glfw.GLFW.glfwGetKey(window,
                org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                cancelRecording(client.player);
                return;
            }
        }

        // Process mouse clicks in both IDLE and RECORDING states.
        // IDLE: left-click starts recording; right-click is handled but handlers safely no-op.
        // RECORDING: left-click ends recording; right-click adds waypoints.
        if (state == State.IDLE || state == State.RECORDING) {
            processMouseClicks(client, window);
        }

        if (state == State.IDLE)
            return;

        if (state == State.RECORDING) {
            if (!hasTool) {
                // Tool removed: transition to PAUSED (preserve all data)
                state = State.PAUSED;
                client.player.displayClientMessage(Component.literal("工具已离手，Survey 录制暂停（数据保留）。"), false);
                return;
            }
            spawnPathParticles(client);
        } else if (state == State.PAUSED) {
            if (hasTool) {
                // Tool picked up: resume recording
                // Validate that all nodes in nodeIds still exist in the database
                if (validateNodeIntegrity()) {
                    state = State.RECORDING;
                    particleTickCounter = 0;
                    client.player.displayClientMessage(
                        Component.literal("工具已切回，Survey 录制继续 (" + nodeIds.size() + " 个节点)"), false);
                } else {
                    // Data integrity check failed, cancel the recording
                    client.player.displayClientMessage(Component.literal("数据校验失败，Survey 录制已取消。"), false);
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

        if (state == State.IDLE) {
            Node startNode = createNode(blockPos.x, blockPos.y, blockPos.z);
            RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
            synchronized (db) {
                db.addNode(startNode);
                nodeIds.add(startNode.getId());
                lastNodePos = blockPos;
                state = State.RECORDING;
            }
            player.displayClientMessage(Component.literal("Survey 录制已开始（节点 " + formatCoord(blockPos) + "）"), false);
        } else if (state == State.RECORDING) {
            Node endNode = createNode(blockPos.x, blockPos.y, blockPos.z);
            RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
            synchronized (db) {
                db.addNode(endNode);
                nodeIds.add(endNode.getId());
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
                }
                Node hitNode = db.getNode(hitNodeId);
                if (hitNode != null) {
                    lastNodePos = new Vec3(hitNode.getX(), hitNode.getY(), hitNode.getZ());
                }
                state = State.RECORDING;
            }
            player.displayClientMessage(Component.literal("Survey 录制已开始（吸附到现有节点）"), false);
        } else if (state == State.RECORDING) {
            // Ad-snap: connect to existing node, then end
            RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
            synchronized (db) {
                if (!nodeIds.contains(hitNodeId)) {
                    nodeIds.add(hitNodeId);
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

        Node waypoint = createNode(blockPos.x, blockPos.y, blockPos.z);
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            db.addNode(waypoint);
            nodeIds.add(waypoint.getId());
            lastNodePos = blockPos;
        }
        player.displayClientMessage(Component.literal("已放置路径点 #" + nodeIds.size() + " @ " + formatCoord(blockPos)),
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
            }
            Node hitNode = db.getNode(hitNodeId);
            if (hitNode != null) {
                lastNodePos = new Vec3(hitNode.getX(), hitNode.getY(), hitNode.getZ());
            }
        }
        player.displayClientMessage(Component.literal("已吸附到现有节点 #" + nodeIds.size() + " @ " + formatCoord(lastNodePos)),
            false);
    }

    // ---- Recording lifecycle ----

    private void finishRecording(LocalPlayer player) {
        if (nodeIds.size() < 2) {
            cleanupOrphanData();
            player.displayClientMessage(Component.literal("节点太少，已取消录制。"), false);
            state = State.IDLE;
            return;
        }

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            // Store a copy of node IDs for rollback if needed
            List<UUID> nodesToRollback = new ArrayList<>(nodeIds);

            Segment segment =
                new Segment(UUID.randomUUID(), new ArrayList<>(nodeIds), null, Source.USER, Status.DRAFT, 1);
            db.addSegment(segment);

            if (!db.saveToDisk()) {
                // Save failed: roll back BOTH segment and nodes
                db.removeSegment(segment.getId());
                for (UUID nodeId : nodesToRollback) {
                    db.removeNode(nodeId);
                }
                player.displayClientMessage(Component.literal("保存失败，Survey 录制未结束。"), false);
                return;
            }

            pendingSegment = segment;
            // Store node IDs in segment for later cleanup if user cancels metadata screen
            segment.setNodeIds(new ArrayList<>(nodeIds));
            state = State.IDLE;
            nodeIds.clear();
            lastNodePos = null;

            Minecraft client = Minecraft.getInstance();
            client.setScreen(new RoadMetadataScreen(segment, savedRoad -> {
                player.displayClientMessage(Component.literal("道路已保存: " + savedRoad.getName()), false);
                pendingSegment = null;
                // Save was successful, mark segment as committed (no cleanup needed)
                segment.setStatus(Status.CONFIRMED);
            }, () -> {
                // User cancelled metadata screen - clean up ALL data (nodes + segment)
                cleanupPendingSegmentAndNodes(segment);
                pendingSegment = null;
            }));
            player.displayClientMessage(Component.literal("道路记录已停止，选择或创建道路后保存。"), false);
        }
    }

    // ---- Helper methods ----

    private Node createNode(double x, double y, double z) {
        return new Node(UUID.randomUUID(), x, y, z, currentCornerType, Source.USER, 1, System.currentTimeMillis());
    }

    private void cleanupOrphanData() {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            for (UUID nodeId : nodeIds) {
                db.removeNode(nodeId);
            }
            if (pendingSegment != null) {
                db.removeSegment(pendingSegment.getId());
                pendingSegment = null;
            }
            db.saveToDisk();
        }
    }

    /**
     * Clean up a segment and all its nodes when the user cancels metadata editing. This is called when
     * RoadMetadataScreen is dismissed without saving.
     */
    private void cleanupPendingSegmentAndNodes(Segment segment) {
        if (segment == null) {
            return;
        }
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            // Remove all nodes associated with this segment
            List<UUID> nodeIdsToClean = segment.getNodeIds();
            if (nodeIdsToClean != null) {
                for (UUID nodeId : nodeIdsToClean) {
                    db.removeNode(nodeId);
                }
            }
            // Remove the segment itself
            db.removeSegment(segment.getId());
            // Save to disk to persist the cleanup
            db.saveToDisk();
        }
    }

    /** Raycast from player's eyes to find the nearest node within hit radius. */
    private static UUID findNearbyNode(Minecraft client) {
        if (client.player == null)
            return null;
        Player player = client.player;
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 lookVec = player.getViewVector(1.0F);

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        UUID closest = null;
        double closestDistSq = NODE_HIT_RADIUS * NODE_HIT_RADIUS;

        for (Node node : db.getAllNodes()) {
            Vec3 nodePos = new Vec3(node.getX(), node.getY(), node.getZ());
            Vec3 eyeToNode = nodePos.subtract(eyePos);
            double t = eyeToNode.dot(lookVec);
            if (t < 0)
                continue; // behind the player

            Vec3 closestPointOnRay = eyePos.add(lookVec.scale(t));
            double distSq = closestPointOnRay.distanceToSqr(nodePos);

            if (distSq < closestDistSq && t < 50.0) { // max 50 block range
                closestDistSq = distSq;
                closest = node.getId();
            }
        }
        return closest;
    }

    /**
     * Get the block position the player is currently looking at. Uses Minecraft's built-in raycast to find the targeted
     * block.
     *
     * @return the block position as Vec3, or null if not looking at a block
     */
    private Vec3 getLookedAtBlockPos(LocalPlayer player) {
        // Use the player's pick() method which returns the block the player is looking at
        // This is the standard Minecraft API for this purpose
        // pick(double maxDistance, float partialTick, boolean includeFluids)
        HitResult hitResult = player.pick(5.0, 1.0F, false);
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        // Get the block position from the BlockHitResult
        BlockHitResult blockHit = (BlockHitResult)hitResult;
        BlockPos blockPos = blockHit.getBlockPos();
        return new Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
    }

    /** Format a coordinate vector for display. */
    private static String formatCoord(Vec3 pos) {
        return String.format("(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z);
    }

    // ---- Tool detection (called from WayfarerClient tick) ----

    /**
     * Called when the player picks up the tool. Resumes a paused session if applicable.
     */
    public void onToolPickedUp(LocalPlayer player) {
        particleTickCounter = 0;
        if (state == State.IDLE) {
            player.displayClientMessage(Component.literal("Survey 工具就绪，左键点击方块开始录制。"), false);
        }
        // PAUSED → RECORDING transition is handled in tick() with integrity validation
    }

    /** Reset the session completely. */
    public void reset() {
        cleanupOrphanData();
        state = State.IDLE;
        nodeIds.clear();
        lastNodePos = null;
        currentCornerType = CornerType.SHARP;
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
            player.displayClientMessage(Component.literal("Survey 已在录制中"), false);
            return;
        }

        Vec3 pos = player.position();
        Node startNode = createNode(pos.x, pos.y, pos.z);
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            db.addNode(startNode);
            nodeIds.add(startNode.getId());
            lastNodePos = new Vec3(pos.x, pos.y, pos.z);
            state = State.RECORDING;
        }
        player.displayClientMessage(Component.literal("Survey 录制已开始（快捷键）"), false);
    }

    /** Force stop recording at player's current position (RECORDING → IDLE, save flow). */
    public void forceStopRecording(LocalPlayer player) {
        if (player == null) {
            return;
        }
        if (state != State.RECORDING) {
            player.displayClientMessage(Component.literal("Survey 未在录制中"), false);
            return;
        }

        Vec3 pos = player.position();
        Node endNode = createNode(pos.x, pos.y, pos.z);
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        synchronized (db) {
            db.addNode(endNode);
            nodeIds.add(endNode.getId());
            finishRecording(player);
        }
    }

    /** Cancel current recording and discard all data. */
    public void cancelRecording(LocalPlayer player) {
        if (player == null) {
            return;
        }
        if (state == State.IDLE && nodeIds.isEmpty()) {
            player.displayClientMessage(Component.literal("没有可取消的录制"), false);
            return;
        }

        cleanupOrphanData();
        state = State.IDLE;
        nodeIds.clear();
        lastNodePos = null;
        pendingSegment = null;

        player.displayClientMessage(Component.literal("Survey 录制已取消"), false);
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
}
