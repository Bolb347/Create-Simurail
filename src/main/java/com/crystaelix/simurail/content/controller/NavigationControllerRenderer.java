package com.crystaelix.simurail.content.controller;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public class NavigationControllerRenderer extends KineticBlockEntityRenderer<NavigationControllerBlockEntity> {

    private static final boolean DEBUG = true;

    public NavigationControllerRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(NavigationControllerBlockEntity be, float partialTick, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        super.renderSafe(be, partialTick, ms, buffer, light, overlay);

        renderStorageOverlay(be, ms, buffer);
    }

    private void renderStorageOverlay(NavigationControllerBlockEntity be, PoseStack ms, MultiBufferSource buffer) {
        Level level = be.getLevel();
        if (level == null) return;

        List<BlockPos> positions = be.getStorageOverlayPositions();

        if (DEBUG && level.getGameTime() % 40 == 0) {
            System.out.println("[NAV RENDER] pos=" + be.getBlockPos() + " overlayPositions=" + positions.size());
        }

        if (positions.isEmpty()) return;

        BlockPos origin = be.getBlockPos();

        VertexConsumer consumer = buffer.getBuffer(RenderType.lines());

        ms.pushPose();

        for (BlockPos pos : positions) {
            BlockPos local = pos.subtract(origin);

            AABB box = new AABB(
                    local.getX(),
                    local.getY(),
                    local.getZ(),
                    local.getX() + 1,
                    local.getY() + 1,
                    local.getZ() + 1
            ).inflate(0.002);

            LevelRenderer.renderLineBox(
                    ms,
                    consumer,
                    box,
                    0.15f,
                    1.0f,
                    0.15f,
                    1.0f
            );
        }

        ms.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(NavigationControllerBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}