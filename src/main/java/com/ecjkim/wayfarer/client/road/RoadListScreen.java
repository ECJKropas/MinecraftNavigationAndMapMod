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
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import com.ecjkim.wayfarer.client.road.data.RoadNetworkDatabase;
import com.ecjkim.wayfarer.client.road.model.CornerType;
import com.ecjkim.wayfarer.client.road.model.Node;
import com.ecjkim.wayfarer.client.road.model.Road;
import com.ecjkim.wayfarer.client.road.model.Segment;
import com.ecjkim.wayfarer.client.road.model.Status;

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

    // Selection
    private Road selectedRoad;
    private Segment selectedSegment;
    private Node selectedNode;

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

    // Buttons (set to null outside LIST mode)
    private Button newRoadBtn;
    private Button delRoadBtn;
    private Button editSegBtn;
    private Button delSegBtn;
    private Button delNodeBtn;

    // ----- Constructors -----

    public RoadListScreen() {
        this(null, null);
    }

    public RoadListScreen(Consumer<Road> onRoadSelected, Runnable onCancel) {
        super(Component.literal(I18n.get("wayfarer.road.gui.title")));
        this.mode = (onRoadSelected != null) ? Mode.SELECT : Mode.LIST;
        this.onRoadSelected = onRoadSelected;
        this.onCancel = onCancel;
    }

    // ----- Init -----

    @Override
    protected void init() {
        computeLayout();

        searchBox = new EditBox(this.font, colLeftX + 2, 10, colLeftW - 4, SEARCH_H, Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setResponder(t -> {
            searchFilter = t.toLowerCase().trim();
            scrollLeft = 0;
            selectedRoad = null;
            selectedSegment = null;
            selectedNode = null;
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        panelTop = 10 + SEARCH_H + GAP;
        panelBottom = this.height - STATUS_H - (mode == Mode.LIST ? BTN_H + 4 : 2);

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

        int total = allUnfiled.isEmpty() ? filteredRoads.size() : 1 + filteredRoads.size();
        int maxVis = (panelBottom - y) / ITEM_H;
        clampScrollLeft(total, maxVis);
        int idx = -scrollLeft, py = y;

        if (!allUnfiled.isEmpty()) {
            idx++;
            if (idx >= 0 && py + ITEM_H <= panelBottom) {
                boolean sel = selectedRoad == null && selectedSegment != null && allUnfiled.contains(selectedSegment);
                boolean hov = hit(mx, my, x, py, colLeftW, ITEM_H);
                drawItem(g, x, py, colLeftW, ITEM_H, I18n.get("wayfarer.road.gui.unfiled_segments"),
                    String.valueOf(allUnfiled.size()), 0xFFAA88FF, sel, hov);
            }
            py += ITEM_H;
        }

        for (Road road : filteredRoads) {
            idx++;
            if (idx < 0)
                continue;
            if (py + ITEM_H > panelBottom)
                break;
            boolean sel = selectedRoad != null && selectedRoad.getId().equals(road.getId());
            boolean hov = hit(mx, my, x, py, colLeftW, ITEM_H);
            int n = road.getSegmentIds() != null ? road.getSegmentIds().size() : 0;
            drawItem(g, x, py, colLeftW, ITEM_H, roadLabel(road), String.valueOf(n),
                classificationColor(road.getClassification()), sel, hov);
            py += ITEM_H;
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
        } else if (!allUnfiled.isEmpty() && selectedRoad == null && selectedSegment != null) {
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
            boolean hov = hit(mx, my, x, py, colMidW, ITEM_H);
            int n = seg.getNodeIds() != null ? seg.getNodeIds().size() : 0;
            String src = seg.getSource() != null ? seg.getSource().name() : "";
            String st = seg.getStatus() != null ? " " + seg.getStatus().name() : "";
            String label = "Seg-" + (segs.indexOf(seg) + 1) + " (" + n + "n)" + st + " [" + src + "]";
            drawItem(g, x, py, colMidW, ITEM_H, label, null, sel ? 0xFF88FF88 : (hov ? 0xFFCCCCCC : 0xFFAAAAAA), sel,
                hov);
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
            g.drawString(this.font, "Seg-" + (currentSegments().indexOf(selectedSegment) + 1) + " (" + n + " nodes)",
                x + 4, y, 0xFFCCCCFF, false);
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
            String ct = node.getCornerType() != null ? node.getCornerType().name() : "?";
            String label = "N-" + (i + 1) + " (" + fmt(node.getX()) + "," + fmt(node.getY()) + "," + fmt(node.getZ())
                + ") [" + ct + "]";
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
                if (dragging) {
                    dropSegment((int)mx, (int)my);
                } else {
                    clickLeft(ix, iy);
                }
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
        if (button == 1 && ix >= colMidX && ix <= colMidX + colMidW) {
            rightClickMid(ix, iy);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        int ix = (int)mx, da = (int)amount * 3;
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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            dragging = false;
            if (mode == Mode.SELECT && onCancel != null)
                onCancel.run();
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- Click handlers ----

    private void dropSegment(int mx, int my) {
        dragging = false;
        draggingSegment = null;
        draggingLabel = null;
        int off = allUnfiled.isEmpty() ? 0 : 1;
        int relY = my - panelTop + scrollLeft * ITEM_H;
        int idx = relY / ITEM_H;
        int ri = idx - off;
        if (ri >= 0 && ri < filteredRoads.size() && selectedSegment != null) {
            Road targetRoad = filteredRoads.get(ri);
            moveSegmentToRoad(selectedSegment, targetRoad);
            selectedRoad = targetRoad;
            selectedSegment = null;
        }
    }

    private void clickLeft(int mx, int my) {
        int off = allUnfiled.isEmpty() ? 0 : 1;
        int relY = my - panelTop + scrollLeft * ITEM_H;
        int idx = relY / ITEM_H;

        if (!allUnfiled.isEmpty() && idx == 0) {
            selectedRoad = null;
            selectedSegment = allUnfiled.get(0);
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
        int headerH =
            (selectedRoad != null || (!allUnfiled.isEmpty() && selectedRoad == null && selectedSegment != null))
                ? HEADER_H : 0;
        int relY = my - panelTop - headerH + scrollMid * ITEM_H;
        int idx = relY / ITEM_H;
        if (idx >= 0 && idx < segs.size()) {
            selectedSegment = segs.get(idx);
            selectedNode = null;
            scrollRight = 0;
            dragging = true;
            draggingSegment = selectedSegment;
            String name = I18n.get("wayfarer.road.gui.segment") + " #" + idx;
            draggingLabel = Component.literal(name);
        }
    }

    private void clickRight(int mx, int my) {
        List<Node> nodes = currentNodes();
        int headerH = selectedSegment != null ? HEADER_H : 0;
        int relY = my - panelTop - headerH + scrollRight * ITEM_H;
        int idx = relY / ITEM_H;
        if (idx >= 0 && idx < nodes.size()) {
            selectedNode = nodes.get(idx);
            CornerType[] vals = CornerType.values();
            CornerType next = vals[(selectedNode.getCornerType().ordinal() + 1) % vals.length];
            selectedNode.setCornerType(next);
            db.updateNode(selectedNode.getId(), selectedNode);
            db.asyncSave();
        }
    }

    private void rightClickMid(int mx, int my) {
        List<Segment> segs = currentSegments();
        int headerH =
            (selectedRoad != null || (!allUnfiled.isEmpty() && selectedRoad == null && selectedSegment != null))
                ? HEADER_H : 0;
        int relY = my - panelTop - headerH + scrollMid * ITEM_H;
        int idx = relY / ITEM_H;
        if (idx >= 0 && idx < segs.size()) {
            Segment seg = segs.get(idx);
            Status next = seg.getStatus() == Status.CONFIRMED ? Status.DRAFT : Status.CONFIRMED;
            seg.setStatus(next);
            db.updateSegment(seg.getId(), seg);
            db.asyncSave();
        }
    }

    // ---- Button actions ----

    private void createNewRoad() {
        Road r = new Road(UUID.randomUUID(), "New Road", "#FFFFFF", new ArrayList<>(), 1);
        db.addRoad(r);
        db.asyncSave();
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
        db.asyncSave();
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
        db.asyncSave();
    }

    private void deleteSelectedNode() {
        if (selectedNode == null || selectedSegment == null)
            return;
        selectedSegment.getNodeIds().remove(selectedNode.getId());
        db.updateSegment(selectedSegment.getId(), selectedSegment);
        db.removeNode(selectedNode.getId());
        selectedNode = null;
        db.asyncSave();
    }

    // ---- Drag ----

    private void moveSegmentToRoad(Segment seg, Road target) {
        if (seg.getRoadId() != null) {
            Road old = db.getRoad(seg.getRoadId());
            if (old != null && old.getSegmentIds() != null) {
                old.getSegmentIds().remove(seg.getId());
                db.updateRoad(old.getId(), old);
            }
        }
        seg.setRoadId(target.getId());
        db.updateSegment(seg.getId(), seg);
        if (target.getSegmentIds() == null) {
            target.setSegmentIds(new ArrayList<>());
        }
        if (!target.getSegmentIds().contains(seg.getId())) {
            target.getSegmentIds().add(seg.getId());
        }
        db.updateRoad(target.getId(), target);
        db.asyncSave();
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
    }

    private List<Segment> currentSegments() {
        if (selectedRoad != null)
            return db.getSegmentsForRoad(selectedRoad.getId());
        if (!allUnfiled.isEmpty() && selectedRoad == null
            && (selectedSegment == null || selectedSegment.getRoadId() == null)) {
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
        if (r.getNumber() != null && !r.getNumber().isEmpty())
            sb.append("[").append(r.getNumber()).append("] ");
        if (r.getClassification() != null && !r.getClassification().isEmpty())
            sb.append(r.getClassification()).append(" ");
        sb.append(r.getName());
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

    // ---- Utility ----

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
