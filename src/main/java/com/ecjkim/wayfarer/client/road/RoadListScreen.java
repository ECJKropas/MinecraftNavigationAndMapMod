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
package com.ecjkim.wayfarer.client.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.Direction;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;

import org.lwjgl.glfw.GLFW;

/**
 * Three-column layout: Left (Roads) -> Middle (Segments) -> Right (Nodes).
 *
 * <pre>
 * +------------------+----------------------------+---------------------------+
 * |  1. Roads        |  2. Segments               |  3. Nodes                 |
 * +------------------+----------------------------+---------------------------+
 * | [Search]         |  Seg-01 (5 nodes) [USER]   |  N-01 (0,64,0) [SHARP]    |
 * | Unfiled (3)      |  Seg-02 (3 nodes) [AUTO]   |  N-02 (10,64,5) [AUTO]    |
 * | [G01] Highway A  |  Seg-03 (7 nodes) [USER]   |  N-03 (20,64,10) [ROUND]  |
 * +------------------+----------------------------+---------------------------+
 * | Status: Nodes X / Segments Y / Roads Z                                 |
 * +------------------------------------------------------------------------+
 * </pre>
 */
public class RoadListScreen extends Screen {

    public enum Mode {
        LIST, SELECT
    }

    private static final int SEARCH_H = 20;
    private static final int HEADER_H = 16;
    private static final int ITEM_H = 14;
    private static final int BTN_H = 20;
    private static final int STATUS_H = 12;
    private static final int GAP = 4;

    private final Mode mode;
    private final Consumer<Road> onRoadSelected;
    private final Runnable onCancel;
    private final RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();

    // Layout
    private int colLeftW, colMidW, colRightW;
    private int colLeftX, colMidX, colRightX;
    private int panelTop, panelBottom;

    // Scroll
    private int scrollLeft, scrollMid, scrollRight;
    private long scrollTimeMs = 0;

    // Selection
    private Road selectedRoad;
    private Segment selectedSegment;
    private Node selectedNode;
    private final UUID highlightedSegmentId;

    // Drag state
    private boolean dragging = false;
    private Segment draggingSegment = null;
    private Component draggingLabel = null;

    // Search
    private EditBox searchBox;
    private String searchFilter = "";

    // Cache
    private final List<Road> filteredRoads = new ArrayList<>();
    private final List<Segment> allUnfiled = new ArrayList<>();
    private final Map<String, Integer> marqueePhases = new HashMap<>();
    private int marqueePhaseCounter = 0;

    // Buttons (set to null outside LIST mode)
    private Button newRoadBtn;
    private Button delRoadBtn;
    private Button editSegBtn;
    private Button delSegBtn;
    private Button delNodeBtn;

    // ----- Constructors -----

    public RoadListScreen() {
        this(null, null, null);
    }

    public RoadListScreen(Consumer<Road> onRoadSelected, Runnable onCancel) {
        this(onRoadSelected, onCancel, null);
    }

    public RoadListScreen(Consumer<Road> onRoadSelected, Runnable onCancel, UUID highlightedSegmentId) {
        super(Component.literal(I18n.get("wayfarer.road.gui.title")));
        this.mode = (onRoadSelected != null) ? Mode.SELECT : Mode.LIST;
        this.onRoadSelected = onRoadSelected;
        this.onCancel = onCancel;
        this.highlightedSegmentId = highlightedSegmentId;
    }

    // ----- Init -----

    @Override
    protected void init() {
        computeLayout();

        // Reset marquee state so that scrolling starts from a clean state each time the screen opens
        scrollTimeMs = 0;
        marqueePhases.clear();
        marqueePhaseCounter = 0;

        searchBox = new EditBox(this.font, colLeftX + 2, 10, colLeftW - 4, SEARCH_H, Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setResponder(t -> {
            searchFilter = t.toLowerCase().trim();
            scrollLeft = 0;
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        panelTop = 10 + SEARCH_H + GAP;
        panelBottom = this.height - STATUS_H - (mode == Mode.LIST ? BTN_H + 4 : 2);

        // Auto-select and scroll to highlighted segment if provided
        if (highlightedSegmentId != null && mode == Mode.SELECT) {
            refreshCache();
            List<Segment> segs = currentSegments();
            for (int i = 0; i < segs.size(); i++) {
                if (segs.get(i).getId().equals(highlightedSegmentId)) {
                    selectedSegment = segs.get(i);
                    // Calculate scroll so highlighted segment is visible
                    int maxVis = (panelBottom - panelTop) / ITEM_H;
                    if (i >= maxVis) {
                        scrollMid = i - maxVis + 1;
                    } else {
                        scrollMid = 0;
                    }
                    break;
                }
            }
            // If not found in unfiled, check all segments
            if (selectedSegment == null) {
                for (Segment s : db.getAllSegments()) {
                    if (s.getId().equals(highlightedSegmentId)) {
                        if (s.getRoadId() != null) {
                            selectedRoad = db.getRoad(s.getRoadId());
                            selectedSegment = s;
                        }
                        break;
                    }
                }
            }
        }

        if (mode == Mode.LIST) {
            int halfW = (colLeftW - 8) / 2;
            newRoadBtn = Button.builder(Component.literal(I18n.get("wayfarer.road.gui.new_road")), b -> createNewRoad())
                .bounds(colLeftX + 4, this.height - STATUS_H - BTN_H - 2, halfW, BTN_H).build();
            addRenderableWidget(newRoadBtn);
            delRoadBtn =
                Button.builder(Component.literal(I18n.get("wayfarer.road.gui.delete_road")), b -> deleteSelectedRoad())
                    .bounds(colLeftX + 8 + halfW, this.height - STATUS_H - BTN_H - 2, halfW, BTN_H).build();
            addRenderableWidget(delRoadBtn);

            editSegBtn = Button.builder(Component.literal(I18n.get("wayfarer.road.gui.edit")), b -> {
            }).bounds(colMidX + 4, this.height - STATUS_H - BTN_H - 2, 45, BTN_H).build();
            addRenderableWidget(editSegBtn);
            delSegBtn =
                Button.builder(Component.literal(I18n.get("wayfarer.road.gui.delete")), b -> deleteSelectedSegment())
                    .bounds(colMidX + 53, this.height - STATUS_H - BTN_H - 2, 45, BTN_H).build();
            addRenderableWidget(delSegBtn);

            delNodeBtn =
                Button.builder(Component.literal(I18n.get("wayfarer.road.gui.delete")), b -> deleteSelectedNode())
                    .bounds(colRightX + 4, this.height - STATUS_H - BTN_H - 2, 45, BTN_H).build();
            addRenderableWidget(delNodeBtn);
        }

        if (mode == Mode.SELECT) {
            addRenderableWidget(
                Button.builder(Component.literal(I18n.get("wayfarer.road.gui.metadata.button_cancel")), b -> {
                    if (onCancel != null)
                        onCancel.run();
                    this.minecraft.setScreen(null);
                }).bounds(colRightX + colRightW - 60, 8, 60, BTN_H).build());
        }
    }

    private void computeLayout() {
        colLeftW = Math.max(130, (int)(this.width * 0.28));
        colMidW = Math.max(150, (int)(this.width * 0.33));
        colRightW = this.width - GAP * 3 - colLeftW - colMidW;
        if (colRightW < 120) {
            colRightW = 120;
            colMidW = Math.max(120, this.width - GAP * 3 - colLeftW - colRightW);
        }
        colLeftX = GAP;
        colMidX = colLeftX + colLeftW + GAP;
        colRightX = colMidX + colMidW + GAP;
    }

    // ----- Render -----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        refreshCache();
        updateButtonStates();
        scrollTimeMs += (long)(partialTick * 1000);

        // Drag polling: hold left mouse to pick up, release to drop
        long window = this.minecraft.getWindow().getWindow();
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (!dragging && leftDown && mouseY >= panelTop && mouseY <= panelBottom && mouseX >= colMidX
            && mouseX <= colMidX + colMidW) {
            List<Segment> segs = currentSegments();
            int headerH = (selectedRoad != null || (selectedRoad == null && selectedSegment != null)) ? HEADER_H : 0;
            int relY = mouseY - panelTop - headerH + scrollMid * ITEM_H;
            int idx = relY / ITEM_H;
            if (idx >= 0 && idx < segs.size()) {
                Segment seg = segs.get(idx);
                if (selectedSegment != null && selectedSegment.getId().equals(seg.getId())) {
                    dragging = true;
                    draggingSegment = selectedSegment;
                    draggingLabel = Component.literal(I18n.get("wayfarer.road.gui.segment") + " #" + idx);
                }
            }
        } else if (dragging && !leftDown) {
            if (mouseY >= panelTop && mouseY <= panelBottom && mouseX >= colLeftX && mouseX <= colLeftX + colLeftW) {
                dropSegment(mouseX, mouseY);
            } else {
                dragging = false;
                draggingSegment = null;
                draggingLabel = null;
            }
        }

        renderLeftColumn(g, mouseX, mouseY);
        renderMidColumn(g, mouseX, mouseY);
        renderRightColumn(g, mouseX, mouseY);
        renderStatusBar(g);

        if (mode == Mode.SELECT) {
            g.drawCenteredString(this.font, Component.literal(I18n.get("wayfarer.road.gui.select_mode_title")),
                (colMidX + colRightX + colRightW) / 2, 10, 0xFFFFCC00);
        }

        if (dragging && draggingLabel != null) {
            int textWidth = this.font.width(draggingLabel);
            g.drawString(this.font, draggingLabel, mouseX - textWidth / 2, mouseY - this.font.lineHeight / 2,
                0xFFFFFFFF, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    // ---- Left column ----

    private void renderLeftColumn(GuiGraphics g, int mx, int my) {
        int x = colLeftX, xr = x + colLeftW, y = panelTop;
        g.fill(x, y - 1, xr, y, 0xFF4E5768);

        int total = 1 + filteredRoads.size();
        int maxVis = (panelBottom - y) / ITEM_H;
        clampScrollLeft(total, maxVis);

        int py = y;
        int rendered = 0;

        // Unfiled at virtual index 0
        if (scrollLeft == 0 && rendered < maxVis) {
            boolean sel = selectedRoad == null && selectedSegment != null && allUnfiled.contains(selectedSegment);
            boolean hov = hit(mx, my, x, py, colLeftW, ITEM_H);
            drawItem(g, x, py, colLeftW, ITEM_H, I18n.get("wayfarer.road.gui.unfiled_segments"),
                String.valueOf(allUnfiled.size()), 0xFFAA88FF, sel, hov);
            py += ITEM_H;
            rendered++;
        }

        // Roads at virtual index 1..n
        int firstRoad = Math.max(0, scrollLeft - 1);
        for (int i = firstRoad; i < filteredRoads.size() && rendered < maxVis; i++) {
            Road road = filteredRoads.get(i);
            boolean sel = selectedRoad != null && selectedRoad.getId().equals(road.getId());
            boolean hov = hit(mx, my, x, py, colLeftW, ITEM_H);
            int n = road.getSegmentIds() != null ? road.getSegmentIds().size() : 0;
            String badge = String.valueOf(n);
            String label = roadLabel(road);
            int color = classificationColor(road.getClassification());

            // Draw background
            if (sel)
                g.fill(x, py, x + colLeftW, py + ITEM_H, 0x663366AA);
            else if (hov)
                g.fill(x, py, x + colLeftW, py + ITEM_H, 0x33333333);

            // Calculate available text width (reserve space for badge)
            int bw = this.font.width(badge) + 6;
            int badgeRightPad = 4;
            int textX = x + 5;
            int textW = colLeftW - 5 - bw - badgeRightPad;
            int labelW = this.font.width(label);

            if (labelW > textW + 4) {
                // Marquee scroll for overflowing names
                String phaseKey = road.getId().toString();
                int phaseOffset = marqueePhases.computeIfAbsent(phaseKey, k -> {
                    int p = marqueePhaseCounter * 1500;
                    marqueePhaseCounter++;
                    return p;
                });
                drawMarqueeText(g, label, textX, py + 1, color, textW, labelW, phaseOffset);
            } else {
                g.drawString(this.font, label, textX, py + 1, color, false);
            }

            // Badge
            g.fill(x + colLeftW - bw - badgeRightPad, py + 1, x + colLeftW - badgeRightPad, py + ITEM_H - 2,
                0x66444444);
            g.drawString(this.font, badge, x + colLeftW - bw + 1, py + 2, 0xFFAAAAAA, false);

            py += ITEM_H;
            rendered++;
        }

        if (total > maxVis) {
            String si = (scrollLeft + 1) + "-" + Math.min(scrollLeft + maxVis, total) + "/" + total;
            g.drawString(this.font, si, x + 4, panelBottom - 10, 0xFF666666, false);
        }
    }

    // ---- Middle column ----

    private void renderMidColumn(GuiGraphics g, int mx, int my) {
        int x = colMidX, xr = x + colMidW, y = panelTop;
        g.fill(x, y - 1, xr, y, 0xFF4E5768);

        List<Segment> segs = currentSegments();
        int headerH = 0, py = y;
        if (selectedRoad != null) {
            g.drawString(this.font, "> " + selectedRoad.getName(), x + 4, y, 0xFFCCCCFF, false);
            headerH = HEADER_H;
            py += headerH;
        } else if (selectedRoad == null && selectedSegment != null) {
            g.drawString(this.font, "> " + I18n.get("wayfarer.road.gui.unfiled_segments"), x + 4, y, 0xFFCCCCFF, false);
            headerH = HEADER_H;
            py += headerH;
        }

        int maxVis = (panelBottom - py) / ITEM_H;
        int total = segs.size();
        if (scrollMid > total - maxVis)
            scrollMid = Math.max(0, total - maxVis);
        if (scrollMid < 0)
            scrollMid = 0;

        int idx = -scrollMid;
        for (Segment seg : segs) {
            idx++;
            if (idx < 0)
                continue;
            if (py + ITEM_H > panelBottom)
                break;
            boolean sel = selectedSegment != null && selectedSegment.getId().equals(seg.getId());
            boolean highlighted = highlightedSegmentId != null && highlightedSegmentId.equals(seg.getId());
            boolean hov = hit(mx, my, x, py, colMidW, ITEM_H);
            int n = seg.getNodeIds() != null ? seg.getNodeIds().size() : 0;
            String src = seg.getSource() != null ? seg.getSource().name() : "";
            String dir = seg.getDirection() != null ? " " + shortDirection(seg.getDirection()) : "";
            String sid = seg.getId().toString();
            String label = "Seg-" + sid.substring(sid.length() - 4) + " (" + n + "n)" + dir + " [" + src + "]";
            int color;
            if (highlighted) {
                color = 0xFFFFFF88;
            } else if (sel) {
                color = 0xFF88FF88;
            } else if (hov) {
                color = 0xFFCCCCCC;
            } else {
                color = 0xFFAAAAAA;
            }
            drawItem(g, x, py, colMidW, ITEM_H, label, null, color, sel, hov, highlighted);
            py += ITEM_H;
        }

        if (segs.isEmpty() && selectedRoad != null) {
            g.drawString(this.font, "(empty)", x + 8, py, 0xFF666666, false);
        }
    }

    // ---- Right column ----

    private void renderRightColumn(GuiGraphics g, int mx, int my) {
        int x = colRightX, xr = x + colRightW, y = panelTop;
        g.fill(x, y - 1, xr, y, 0xFF4E5768);

        List<Node> nodes = currentNodes();
        int headerH = 0, py = y;
        if (selectedSegment != null) {
            int n = nodes.size();
            String sid = selectedSegment.getId().toString();
            g.drawString(this.font, "Seg-" + sid.substring(sid.length() - 4) + " (" + n + " nodes)", x + 4, y,
                0xFFCCCCFF, false);
            headerH = HEADER_H;
            py += headerH;
        }

        int maxVis = (panelBottom - py) / ITEM_H;
        int total = nodes.size();
        if (scrollRight > total - maxVis)
            scrollRight = Math.max(0, total - maxVis);
        if (scrollRight < 0)
            scrollRight = 0;

        for (int i = 0; i < nodes.size(); i++) {
            int di = i - scrollRight;
            if (di < 0)
                continue;
            if (py + ITEM_H > panelBottom)
                break;
            Node node = nodes.get(i);
            boolean sel = selectedNode != null && selectedNode.getId().equals(node.getId());
            boolean hov = hit(mx, my, x, py, colRightW, ITEM_H);
            String nid = node.getId().toString();
            String label = "N-" + nid.substring(nid.length() - 4) + " (" + fmt(node.getX()) + "," + fmt(node.getY())
                + "," + fmt(node.getZ()) + ")";
            drawItem(g, x, py, colRightW, ITEM_H, label, null, sel ? 0xFF88FFFF : (hov ? 0xFFDDDDDD : 0xFFFFFFFF), sel,
                hov);
            py += ITEM_H;
        }
    }

    // ---- Status bar ----

    private void renderStatusBar(GuiGraphics g) {
        int sy = this.height - STATUS_H;
        g.fill(0, sy - 1, this.width, sy, 0xFF4E5768);
        int tn = 0, ts = 0, tr = 0;
        for (Road r : db.getRoads()) {
            tr++;
            List<Segment> segs = db.getSegmentsForRoad(r.getId());
            ts += segs.size();
            for (Segment s : segs) {
                if (s.getNodeIds() != null)
                    tn += s.getNodeIds().size();
            }
        }
        String text = I18n.get("wayfarer.road.gui.status_bar", tn, ts, tr);
        g.drawCenteredString(this.font, text, this.width / 2, sy + 1, 0xFF888888);
    }

    // ----- Mouse -----

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int ix = (int)mx, iy = (int)my;
        if (iy < panelTop || iy > panelBottom)
            return super.mouseClicked(mx, my, button);

        if (button == 0) {
            if (ix >= colLeftX && ix <= colLeftX + colLeftW) {
                clickLeft(ix, iy);
                return true;
            }
            if (ix >= colMidX && ix <= colMidX + colMidW) {
                clickMid(ix, iy);
                return true;
            }
            if (ix >= colRightX && ix <= colRightX + colRightW) {
                clickRight(ix, iy);
                return true;
            }
        }
        if (button == 1 && ix >= colLeftX && ix <= colLeftX + colLeftW) {
            rightClickLeft(ix, iy);
            return true;
        }
        if (button == 1 && ix >= colMidX && ix <= colMidX + colMidW) {
            rightClickMid(ix, iy);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        int ix = (int)mx, da = (int)amount;
        if (ix >= colLeftX && ix <= colLeftX + colLeftW) {
            scrollLeft = clamp0(scrollLeft - da);
            return true;
        }
        if (ix >= colMidX && ix <= colMidX + colMidW) {
            scrollMid = clamp0(scrollMid - da);
            return true;
        }
        if (ix >= colRightX && ix <= colRightX + colRightW) {
            scrollRight = clamp0(scrollRight - da);
            return true;
        }
        return super.mouseScrolled(mx, my, amount);
    }

    // ---- Click handlers ----

    private void dropSegment(int mx, int my) {
        dragging = false;
        draggingSegment = null;
        draggingLabel = null;
        int off = 1;
        int relY = my - panelTop + scrollLeft * ITEM_H;
        int idx = relY / ITEM_H;

        if (selectedSegment == null)
            return;

        if (idx == 0) {
            if (selectedSegment.getRoadId() != null) {
                Road old = db.getRoad(selectedSegment.getRoadId());
                if (old != null && old.getSegmentIds() != null) {
                    old.getSegmentIds().remove(selectedSegment.getId());
                    db.updateRoad(old.getId(), old);
                }
            }
            selectedSegment.setRoadId(null);
            db.updateSegment(selectedSegment.getId(), selectedSegment);
            db.saveToDisk();
            selectedRoad = null;
            selectedSegment = null;
            return;
        }

        int ri = idx - off;
        if (ri >= 0 && ri < filteredRoads.size()) {
            Road targetRoad = filteredRoads.get(ri);
            moveSegmentToRoad(selectedSegment, targetRoad);
            selectedRoad = targetRoad;
            selectedSegment = null;
        }
    }

    private void clickLeft(int mx, int my) {
        int off = 1;
        int relY = my - panelTop + scrollLeft * ITEM_H;
        int idx = relY / ITEM_H;

        if (idx == 0) {
            selectedRoad = null;
            selectedSegment = allUnfiled.isEmpty() ? null : allUnfiled.get(0);
            selectedNode = null;
            scrollMid = 0;
            scrollRight = 0;
            return;
        }
        int ri = idx - off;
        if (ri >= 0 && ri < filteredRoads.size()) {
            Road road = filteredRoads.get(ri);
            if (mode == Mode.SELECT) {
                if (onRoadSelected != null)
                    onRoadSelected.accept(road);
                this.minecraft.setScreen(null);
                return;
            }
            selectedRoad = road;
            selectedSegment = null;
            selectedNode = null;
            scrollMid = 0;
            scrollRight = 0;
        }
    }

    private void clickMid(int mx, int my) {
        List<Segment> segs = currentSegments();
        int headerH = (selectedRoad != null || (selectedRoad == null && selectedSegment != null)) ? HEADER_H : 0;
        int relY = my - panelTop - headerH + scrollMid * ITEM_H;
        int idx = relY / ITEM_H;
        if (idx >= 0 && idx < segs.size()) {
            selectedSegment = segs.get(idx);
            selectedNode = null;
            scrollRight = 0;
        }
    }

    private void clickRight(int mx, int my) {
        List<Node> nodes = currentNodes();
        int headerH = selectedSegment != null ? HEADER_H : 0;
        int relY = my - panelTop - headerH + scrollRight * ITEM_H;
        int idx = relY / ITEM_H;
        if (idx >= 0 && idx < nodes.size()) {
            selectedNode = nodes.get(idx);
        }
    }

    private void rightClickMid(int mx, int my) {
        List<Segment> segs = currentSegments();
        int headerH = (selectedRoad != null || (selectedRoad == null && selectedSegment != null)) ? HEADER_H : 0;
        int relY = my - panelTop - headerH + scrollMid * ITEM_H;
        int idx = relY / ITEM_H;
        if (idx >= 0 && idx < segs.size()) {
            Segment seg = segs.get(idx);
            Direction[] vals = Direction.values();
            int curIdx = seg.getDirection() != null ? seg.getDirection().ordinal() : 0;
            Direction next = vals[(curIdx + 1) % vals.length];
            seg.setDirection(next);
            db.updateSegment(seg.getId(), seg);
            db.saveToDisk();
        }
    }

    private void rightClickLeft(int mx, int my) {
        if (mode != Mode.LIST)
            return;

        int off = 1;
        int relY = my - panelTop + scrollLeft * ITEM_H;
        int idx = relY / ITEM_H;

        if (idx == 0)
            return; // Unfiled is not editable

        int ri = idx - off;
        if (ri >= 0 && ri < filteredRoads.size()) {
            Road road = filteredRoads.get(ri);
            this.minecraft.setScreen(new RoadMetadataScreen(road));
        }
    }

    // ---- Button actions ----

    private void createNewRoad() {
        Road r = new Road(UUID.randomUUID(), "New Road", "#FFFFFF", new ArrayList<>(), 1);
        db.addRoad(r);
        db.saveToDisk();
        scrollLeft = 0;
        selectedRoad = r;
        selectedSegment = null;
        selectedNode = null;
    }

    private void deleteSelectedRoad() {
        if (selectedRoad == null)
            return;
        for (Segment s : db.getSegmentsForRoad(selectedRoad.getId())) {
            s.setRoadId(null);
            db.updateSegment(s.getId(), s);
        }
        db.removeRoad(selectedRoad.getId());
        selectedRoad = null;
        selectedSegment = null;
        selectedNode = null;
        db.saveToDisk();
    }

    private void deleteSelectedSegment() {
        if (selectedSegment == null)
            return;
        if (selectedSegment.getRoadId() != null) {
            Road r = db.getRoad(selectedSegment.getRoadId());
            if (r != null && r.getSegmentIds() != null) {
                r.getSegmentIds().remove(selectedSegment.getId());
                db.updateRoad(r.getId(), r);
            }
        }
        db.removeSegment(selectedSegment.getId());
        selectedSegment = null;
        selectedNode = null;
        db.saveToDisk();
    }

    private void deleteSelectedNode() {
        if (selectedNode == null || selectedSegment == null)
            return;
        selectedSegment.getNodeIds().remove(selectedNode.getId());
        db.updateSegment(selectedSegment.getId(), selectedSegment);
        db.removeNode(selectedNode.getId());
        selectedNode = null;
        db.saveToDisk();
    }

    // ---- Drag ----

    private void moveSegmentToRoad(Segment seg, Road target) {
        if (seg.getRoadId() != null) {
            Road old = db.getRoad(seg.getRoadId());
            if (old != null && old.getSegmentIds() != null) {
                List<UUID> oldSegIds = new ArrayList<>(old.getSegmentIds());
                oldSegIds.remove(seg.getId());
                old.setSegmentIds(oldSegIds);
                db.updateRoad(old.getId(), old);
            }
        }
        seg.setRoadId(target.getId());
        db.updateSegment(seg.getId(), seg);
        List<UUID> targetSegIds =
            target.getSegmentIds() != null ? new ArrayList<>(target.getSegmentIds()) : new ArrayList<>();
        if (!targetSegIds.contains(seg.getId())) {
            targetSegIds.add(seg.getId());
        }
        target.setSegmentIds(targetSegIds);
        db.updateRoad(target.getId(), target);
        db.saveToDisk();
    }

    // ---- Cache ----

    private void refreshCache() {
        filteredRoads.clear();
        allUnfiled.clear();
        String f = searchFilter;
        for (Road r : db.getRoads()) {
            if (f.isEmpty() || (r.getName() != null && r.getName().toLowerCase().contains(f))) {
                filteredRoads.add(r);
            }
        }
        for (Segment s : db.getAllSegments()) {
            if (s.getRoadId() == null)
                allUnfiled.add(s);
        }
        // Sort unfiled segments by modifiedAt descending (newest first)
        allUnfiled.sort((a, b) -> Long.compare(b.getModifiedAt(), a.getModifiedAt()));
    }

    private List<Segment> currentSegments() {
        if (selectedRoad != null)
            return db.getSegmentsForRoad(selectedRoad.getId());
        if (selectedRoad == null && (selectedSegment == null || selectedSegment.getRoadId() == null)) {
            return allUnfiled;
        }
        return new ArrayList<>();
    }

    private List<Node> currentNodes() {
        if (selectedSegment != null)
            return db.getNodesForSegment(selectedSegment.getId());
        return new ArrayList<>();
    }

    private void updateButtonStates() {
        if (mode != Mode.LIST)
            return;
        if (delRoadBtn != null)
            delRoadBtn.active = selectedRoad != null;
        if (editSegBtn != null)
            editSegBtn.active = selectedSegment != null;
        if (delSegBtn != null)
            delSegBtn.active = selectedSegment != null;
        if (delNodeBtn != null)
            delNodeBtn.active = selectedNode != null;
    }

    // ---- Drawing helpers ----

    private String roadLabel(Road r) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        if (r.getClassification() != null && !r.getClassification().isEmpty())
            sb.append(r.getClassification(), 0, 1);
        if (r.getNumber() != null && !r.getNumber().isEmpty())
            sb.append(r.getNumber());
        sb.append("] ").append(r.getName());
        return sb.toString();
    }

    private String segShortLabel(Segment seg) {
        int n = seg.getNodeIds() != null ? seg.getNodeIds().size() : 0;
        return "Seg (" + n + " nodes)";
    }

    private void drawItem(GuiGraphics g, int x, int y, int w, int h, String label, String badge, int color, boolean sel,
        boolean hov) {
        if (sel)
            g.fill(x, y, x + w, y + h, 0x663366AA);
        else if (hov)
            g.fill(x, y, x + w, y + h, 0x33333333);
        g.drawString(this.font, label, x + 5, y + 1, color, false);
        if (badge != null) {
            int bw = this.font.width(badge) + 6;
            g.fill(x + w - bw - 4, y + 1, x + w - 4, y + h - 2, 0x66444444);
            g.drawString(this.font, badge, x + w - bw + 1, y + 2, 0xFFAAAAAA, false);
        }
    }

    private void drawItem(GuiGraphics g, int x, int y, int w, int h, String label, String badge, int color, boolean sel,
        boolean hov, boolean highlighted) {
        if (highlighted)
            g.fill(x, y, x + w, y + h, 0x44FFD700);
        else if (sel)
            g.fill(x, y, x + w, y + h, 0x663366AA);
        else if (hov)
            g.fill(x, y, x + w, y + h, 0x33333333);
        g.drawString(this.font, label, x + 5, y + 1, color, false);
        if (badge != null) {
            int bw = this.font.width(badge) + 6;
            g.fill(x + w - bw - 4, y + 1, x + w - 4, y + h - 2, 0x66444444);
            g.drawString(this.font, badge, x + w - bw + 1, y + 2, 0xFFAAAAAA, false);
        }
    }

    // ---- Marquee text (leftward, with gap between copies, pauses between cycles) ----

    private static final int MARQUEE_GAP_W = 20;
    private static final long MARQUEE_WAIT_MS = 2000;

    private void drawMarqueeText(GuiGraphics g, String text, int x, int y, int color, int visibleW, int fullW,
        int phaseOffsetMs) {
        int overflow = fullW - visibleW;
        if (overflow <= 0) {
            g.drawString(this.font, text, x, y, color, false);
            return;
        }

        long elapsedMs = scrollTimeMs + phaseOffsetMs;

        int segmentW = fullW + MARQUEE_GAP_W;
        long scrollMs = (long)segmentW * 1000L;
        long totalPeriodMs = scrollMs + MARQUEE_WAIT_MS;

        long phase = elapsedMs % totalPeriodMs;

        int offset;
        if (phase < MARQUEE_WAIT_MS) {
            offset = 0;
        } else {
            offset = (int)((phase - MARQUEE_WAIT_MS) * segmentW / (double)scrollMs);
        }

        int baseX = x - offset;

        drawMarqueeCopy(g, text, baseX, x, x + visibleW, y, color);
        drawMarqueeCopy(g, text, baseX + segmentW, x, x + visibleW, y, color);
    }

    private void drawMarqueeCopy(GuiGraphics g, String text, int textStartX, int visStart, int visEnd, int y,
        int color) {
        int textEndX = textStartX + this.font.width(text);

        int overlapStart = Math.max(visStart, textStartX);
        int overlapEnd = Math.min(visEnd, textEndX);

        if (overlapStart >= overlapEnd) {
            return;
        }

        int textPxStart = overlapStart - textStartX;
        int textPxEnd = overlapEnd - textStartX;

        int charStart = findCharAtPixel(text, textPxStart);
        int charEnd = findCharAtPixel(text, textPxEnd);

        if (charStart >= charEnd) {
            return;
        }

        int drawX = textStartX + getCharPixel(text, charStart);
        g.drawString(this.font, text.substring(charStart, charEnd), drawX, y, color, false);
    }

    private int getCharPixel(String text, int charIdx) {
        int px = 0;
        for (int i = 0; i < charIdx && i < text.length(); i++) {
            px += this.font.width(String.valueOf(text.charAt(i)));
        }
        return px;
    }

    private int findCharAtPixel(String text, int targetPx) {
        int px = 0;
        int i = 0;
        while (i < text.length()) {
            int cw = this.font.width(String.valueOf(text.charAt(i)));
            if (px + cw > targetPx)
                break;
            px += cw;
            i++;
        }
        return i;
    }

    // ---- Utility ----

    private static String shortDirection(Direction dir) {
        switch (dir) {
            case BIDIRECTIONAL:
                return "Bi-Dir";
            case FORWARD:
                return "Fwd";
            case BACKWARD:
                return "Bwd";
            default:
                return dir.name();
        }
    }

    private int classificationColor(String cls) {
        if (cls == null || cls.isEmpty())
            return 0xFFFFFFFF;
        switch (cls.charAt(0)) {
            case 'G':
                return 0xFFFF8800;
            case 'S':
                return 0xFFFFFF00;
            case 'X':
                return 0xFF00FF00;
            case 'Y':
                return 0xFF4488FF;
            case 'C':
                return 0xFF888888;
            default:
                return 0xFFFFFFFF;
        }
    }

    private void clampScrollLeft(int total, int maxVis) {
        if (scrollLeft > total - maxVis)
            scrollLeft = Math.max(0, total - maxVis);
        if (scrollLeft < 0)
            scrollLeft = 0;
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static String fmt(double v) {
        return String.format("%.0f", v);
    }

    private static int clamp0(int v) {
        return Math.max(0, v);
    }
}
