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
import net.minecraft.world.entity.player.Player;
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

public class SurveySession {
    public enum State {
        IDLE, RECORDING
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

    public void cycleCornerTypeNext() {
        CornerType[] values = CornerType.values();
        currentCornerType = values[(currentCornerType.ordinal() + 1) % values.length];
    }

    public void cycleCornerTypePrev() {
        CornerType[] values = CornerType.values();
        currentCornerType = values[(currentCornerType.ordinal() - 1 + values.length) % values.length];
    }

    public void tick(Minecraft client, long window) {
        if (client.player == null)
            return;

        boolean hasTool = ToolItemManager.hasToolItem(client.player);
        if (state == State.IDLE)
            return;

        if (state == State.RECORDING) {
            if (!hasTool) {
                state = State.IDLE;
                client.player.sendSystemMessage(Component.literal("工具已离手，Survey 录制暂停。"));
                return;
            }
            processMouseClicks(client, window);
            spawnPathParticles(client);
        }
    }

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

    private void handleLeftClickOnAir(LocalPlayer player) {
        if (state == State.IDLE) {
            Vec3 pos = player.position();
            Node startNode = createNode(pos.x, pos.y, pos.z);
            RoadNetworkDatabase.getInstance().addNode(startNode);
            nodeIds.add(startNode.getId());
            lastNodePos = new Vec3(pos.x, pos.y, pos.z);
            state = State.RECORDING;
            player.sendSystemMessage(Component.literal("Survey 录制已开始，右键放置路径点，左键结束录制。"));
        } else if (state == State.RECORDING) {
            Vec3 pos = player.position();
            Node endNode = createNode(pos.x, pos.y, pos.z);
            RoadNetworkDatabase.getInstance().addNode(endNode);
            nodeIds.add(endNode.getId());
            finishRecording(player);
        }
    }

    private void handleLeftClickOnNode(LocalPlayer player, UUID hitNodeId) {
        if (state == State.IDLE) {
            player.sendSystemMessage(Component.literal("点击空地开始录制"));
        } else if (state == State.RECORDING) {
            if (!nodeIds.contains(hitNodeId)) {
                nodeIds.add(hitNodeId);
            }
            RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
            Node hitNode = db.getNode(hitNodeId);
            if (hitNode != null) {
                lastNodePos = new Vec3(hitNode.getX(), hitNode.getY(), hitNode.getZ());
            }
            finishRecording(player);
        }
    }

    private void handleRightClickOnAir(LocalPlayer player) {
        if (state != State.RECORDING)
            return;
        Vec3 pos = player.position();
        Node waypoint = createNode(pos.x, pos.y, pos.z);
        RoadNetworkDatabase.getInstance().addNode(waypoint);
        nodeIds.add(waypoint.getId());
        lastNodePos = new Vec3(pos.x, pos.y, pos.z);
        player.sendSystemMessage(Component.literal("已放置路径点 (" + nodeIds.size() + ")"));
    }

    private void handleRightClickOnNode(LocalPlayer player, UUID hitNodeId) {
        if (state != State.RECORDING)
            return;
        if (!nodeIds.contains(hitNodeId)) {
            nodeIds.add(hitNodeId);
        }
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        Node hitNode = db.getNode(hitNodeId);
        if (hitNode != null) {
            lastNodePos = new Vec3(hitNode.getX(), hitNode.getY(), hitNode.getZ());
        }
        player.sendSystemMessage(Component.literal("已吸附到现有节点 (" + nodeIds.size() + ")"));
    }

    private void finishRecording(LocalPlayer player) {
        if (nodeIds.size() < 2) {
            cleanupOrphanData();
            player.sendSystemMessage(Component.literal("节点太少，已取消录制。"));
            state = State.IDLE;
            return;
        }

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        Segment segment = new Segment(UUID.randomUUID(), new ArrayList<>(nodeIds), null, Source.USER, Status.DRAFT, 1);
        db.addSegment(segment);
        db.saveToDisk();

        pendingSegment = segment;
        state = State.IDLE;
        nodeIds.clear();
        lastNodePos = null;

        Minecraft client = Minecraft.getInstance();
        client.setScreenAndShow(new RoadMetadataScreen(segment, savedRoad -> {
            player.sendSystemMessage(Component.literal("道路已保存: " + savedRoad.getName()));
            pendingSegment = null;
        }, () -> {
            cleanupOrphanData();
            pendingSegment = null;
        }));
        player.sendSystemMessage(Component.literal("道路记录已停止，选择或创建道路后保存。"));
    }

    private Node createNode(double x, double y, double z) {
        return new Node(UUID.randomUUID(), x, y, z, currentCornerType, Source.USER, 1, System.currentTimeMillis());
    }

    private void cleanupOrphanData() {
        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        for (UUID nodeId : nodeIds) {
            db.removeNode(nodeId);
        }
        if (pendingSegment != null) {
            db.removeSegment(pendingSegment.getId());
            pendingSegment = null;
        }
        db.saveToDisk();
    }

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
                continue;

            Vec3 closestPointOnRay = eyePos.add(lookVec.scale(t));
            double distSq = closestPointOnRay.distanceToSqr(nodePos);

            if (distSq < closestDistSq && t < 50.0) {
                closestDistSq = distSq;
                closest = node.getId();
            }
        }
        return closest;
    }

    public void onToolPickedUp(LocalPlayer player) {
        if (state == State.IDLE) {
            player.sendSystemMessage(Component.literal("Survey 工具就绪，左键点击空地开始录制。"));
        } else {
            state = State.RECORDING;
            player.sendSystemMessage(Component.literal("工具已切回，Survey 录制继续。"));
        }
        particleTickCounter = 0;
    }

    private void spawnPathParticles(Minecraft client) {
        if (nodeIds.size() < 2)
            return;
        particleTickCounter++;
        if (particleTickCounter % 5 != 0)
            return;

        RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
        for (int i = 0; i < nodeIds.size() - 1; i++) {
            Node a = db.getNode(nodeIds.get(i));
            Node b = db.getNode(nodeIds.get(i + 1));
            if (a == null || b == null)
                continue;

            double ax = a.getX(), ay = a.getY() + 1.0, az = a.getZ();
            double bx = b.getX(), by = b.getY() + 1.0, bz = b.getZ();

            double t = client.player.getRandom().nextDouble();
            double px = ax + (bx - ax) * t;
            double py = ay + (by - ay) * t;
            double pz = az + (bz - az) * t;

            client.particleEngine.createParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, px, py, pz, 0,
                0.01, 0);
        }
    }

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
}
