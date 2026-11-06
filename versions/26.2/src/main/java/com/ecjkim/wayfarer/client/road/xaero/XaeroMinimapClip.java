package com.ecjkim.wayfarer.client.road.xaero;

import java.lang.reflect.Field;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;

import xaero.hud.minimap.element.render.MinimapElementRenderInfo;

final class XaeroMinimapClip {
    private static Field ps;
    private static Field pc;
    private static Field zoom;
    private static Field halfViewW;
    private static Field halfViewH;

    private XaeroMinimapClip() {}

    static double zoom() {
        try {
            Object handler = xaero.minimap.XaeroMinimap.instance.getMinimap().getOverMapRendererHandler();
            return number(handler, zoom, "zoom");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 1.0;
        }
    }

    static double[] rotation() {
        try {
            Object handler = xaero.minimap.XaeroMinimap.instance.getMinimap().getOverMapRendererHandler();
            return new double[] {number(handler, ps, "ps"), number(handler, pc, "pc")};
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return new double[] {0.0, 1.0};
        }
    }

    static boolean begin(PoseStack pose, XaeroRouteElement element, MinimapElementRenderInfo info) {
        try {
            Object handler = xaero.minimap.XaeroMinimap.instance.getMinimap().getOverMapRendererHandler();
            double sin = number(handler, ps, "ps");
            double cos = number(handler, pc, "pc");
            double mapZoom = number(handler, zoom, "zoom");
            double halfW = number(handler, halfViewW, "halfViewW");
            double halfH = number(handler, halfViewH, "halfViewH");
            double dx = element.anchorX() - info.renderPos.x;
            double dz = element.anchorZ() - info.renderPos.z;
            double anchorScreenX = cos * dx + sin * dz;
            double anchorScreenY = sin * dx - cos * dz;
            var matrix = pose.last().pose();
            double centerX = matrix.m30() - anchorScreenX * mapZoom;
            double centerY = matrix.m31() - anchorScreenY * mapZoom;
            Minecraft client = Minecraft.getInstance();
            double scale = client.getWindow().getGuiScale();
            int left = (int)Math.floor((centerX - halfW) * scale);
            int right = (int)Math.ceil((centerX + halfW) * scale);
            int top = (int)Math.floor((centerY - halfH) * scale);
            int bottom = (int)Math.ceil((centerY + halfH) * scale);
            int physicalHeight = client.getWindow().getHeight();
            RenderSystem.enableScissorForRenderTypeDraws(left, physicalHeight - bottom,
                Math.max(1, right - left), Math.max(1, bottom - top));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static void end(boolean clipped) {
        if (clipped) RenderSystem.disableScissorForRenderTypeDraws();
    }

    private static double number(Object target, Field cached, String name) throws ReflectiveOperationException {
        Field field = cached;
        if (field == null) {
            field = target.getClass().getSuperclass().getDeclaredField(name);
            field.setAccessible(true);
            switch (name) {
                case "ps" -> ps = field;
                case "pc" -> pc = field;
                case "zoom" -> zoom = field;
                case "halfViewW" -> halfViewW = field;
                case "halfViewH" -> halfViewH = field;
                default -> {}
            }
        }
        return ((Number)field.get(target)).doubleValue();
    }
}
