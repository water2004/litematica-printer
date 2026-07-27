package me.aleksilassila.litematica.printer.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import fi.dy.masa.malilib.interfaces.IRenderer;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightStyleType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;

import org.joml.Matrix4f;

// MC >= 1.21.5: MaLiLibPipelines + RenderContext (malilib >= 0.24.3)
// MC >= 1.21.1: MeshData, BufferUploader (new Tesselator/BufferBuilder API)
// MC < 1.21.1: Old Tesselator API (getBuilder, end, vertex)

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

public class BlockHighlightRenderer implements IRenderer {

    // ===== Render Entry Points =====

    @Override
    public void onRenderWorldLast(
            RenderTarget renderTarget,
            Matrix4fc projMatrix,
            CameraRenderState cameraRenderState,
            Frustum frustum,
            RenderBuffers renderBuffers,
            GpuBufferSlice gpuBufferSlice,
            Vector4f vector4f,
            ProfilerFiller profiler
    ) {
        renderInternal(cameraRenderState.pos);
    }

    /** Match malilib's camPos() pattern: rendering camera, not player eye */

    // ===== Shared Render Logic =====

    private void renderInternal(Vec3 cameraPos) {
        if (!Configs.Highlight.HIGHLIGHT_ENABLED.getBooleanValue()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        HighlightStyleType style = (HighlightStyleType) Configs.Highlight.HIGHLIGHT_STYLE.getOptionListValue();
        long fadeDurationMs = Configs.Highlight.HIGHLIGHT_FADE_DURATION.getIntegerValue() * 100L;
        boolean seeThrough = Configs.Highlight.HIGHLIGHT_THROUGH_WALLS.getBooleanValue();
        long now = System.currentTimeMillis();

        int[][] colors = new int[4][4];
        colors[0] = extractArgb(Configs.Highlight.HIGHLIGHT_COLOR_PLACE.getIntegerValue());
        colors[1] = extractArgb(Configs.Highlight.HIGHLIGHT_COLOR_ADJUST.getIntegerValue());
        colors[2] = extractArgb(Configs.Highlight.HIGHLIGHT_COLOR_BREAK.getIntegerValue());
        colors[3] = extractArgb(Configs.Highlight.HIGHLIGHT_COLOR_FAILED.getIntegerValue());

        List<HighlightEntry> entries = new ArrayList<>();

        for (Module module : ModuleManager.VALUES) {
            Queue<Module.PendingHighlight> pending = module.getPendingHighlights();
            if (pending.isEmpty()) continue;
            for (Module.PendingHighlight ph : pending) {
                long elapsed = now - ph.time();
                if (elapsed >= fadeDurationMs) continue;

                int[] c = colors[ph.type().ordinal()];
                int typeA = c[0], typeR = c[1], typeG = c[2], typeB = c[3];
                float baseAlpha = typeA / 255.0f;
                float fadeAlpha = elapsed <= 0 ? baseAlpha : baseAlpha * (1.0f - (float) elapsed / fadeDurationMs);
                if (fadeAlpha <= 0.001f) continue;

                float dx = (float) (ph.pos().getX() + 0.5 - cameraPos.x);
                float dy = (float) (ph.pos().getY() + 0.5 - cameraPos.y);
                float dz = (float) (ph.pos().getZ() + 0.5 - cameraPos.z);
                float distSq = dx * dx + dy * dy + dz * dz;

                entries.add(new HighlightEntry(ph.pos(), fadeAlpha, distSq, typeR, typeG, typeB, style));
            }
        }

        if (entries.isEmpty()) return;

        boolean hasOutline = false;
        boolean hasFilled = false;
        for (HighlightEntry e : entries) {
            if (e.style == HighlightStyleType.OUTLINE || e.style == HighlightStyleType.BOTH) hasOutline = true;
            if (e.style == HighlightStyleType.FILLED || e.style == HighlightStyleType.BOTH) hasFilled = true;
        }

        // Filled translucent rendering needs back-to-front sort for correct alpha blending;
        // outline-only rendering relies on the depth test for order, no sort needed.
        if (hasFilled) {
            entries.sort(Comparator.comparingDouble(e -> -e.distSq));
        }

        drawWithMaLiLib(cameraPos, entries, seeThrough, hasOutline, hasFilled);
    }

    // ===== Modern path: MC >= 1.21.5 (MaLiLibPipelines + RenderContext) =====

    private void drawWithMaLiLib(Vec3 cameraPos, List<HighlightEntry> entries,
                                 boolean seeThrough, boolean hasOutline, boolean hasFilled) {
        RenderPipeline linePipeline = seeThrough
                ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL
                : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH;
        RenderPipeline filledPipeline = seeThrough
                ? MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL
                : MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_LEQUAL_DEPTH_NO_CULL;

        RenderContext ctx = new RenderContext(() -> "litematica_printer:highlight", linePipeline, 0);
        try {
            if (hasOutline) {
                ctx.start(() -> "highlight_outline", linePipeline, 0);
                BufferBuilder lineBuf = ctx.getBuilder();
                for (HighlightEntry e : entries) {
                    if (e.style == HighlightStyleType.FILLED) continue;
                    addOutlineBoxLines(lineBuf, e.pos, e.r, e.g, e.b, (int)(e.alpha * 255), cameraPos);
                }
                MeshData mesh = lineBuf.build();
                if (mesh != null) {
                    ctx.draw(mesh, false, true);
                    mesh.close();
                }
                ctx.reset();
            }

            if (hasFilled) {
                BufferBuilder filledBuf = ctx.start(() -> "highlight_filled", filledPipeline, 0);
                for (HighlightEntry e : entries) {
                    if (e.style == HighlightStyleType.OUTLINE) continue;
                    addFilledBoxModern(filledBuf, e.pos, e.r, e.g, e.b, (int)(e.alpha * 255), cameraPos);
                }
                MeshData mesh = filledBuf.build();
                if (mesh != null) {
                    ctx.draw(mesh, false, false);
                    mesh.close();
                }
                ctx.reset();
            }
        } catch (Exception e) {
            Reference.LOGGER.error("BlockHighlight: drawWithMaLiLib exception: {}", e.getMessage());
        } finally {
            try { ctx.close(); } catch (Exception ignored) {}
        }
    }

    private void addFilledBoxModern(BufferBuilder buf, BlockPos pos,
                                    int r, int g, int b, int a, Vec3 cameraPos) {
        float x1 = (float) (pos.getX() - cameraPos.x - 0.001);
        float y1 = (float) (pos.getY() - cameraPos.y - 0.001 );
        float z1 = (float) (pos.getZ() - cameraPos.z - 0.001 );
        float x2 = (float) (pos.getX() - cameraPos.x + 1 + 0.001);
        float y2 = (float) (pos.getY() - cameraPos.y + 1 + 0.001);
        float z2 = (float) (pos.getZ() - cameraPos.z + 1 + 0.001);

        quadModern(buf, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        quadModern(buf, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a);
        quadModern(buf, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        quadModern(buf, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a);
        quadModern(buf, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, b, a);
        quadModern(buf, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
    }

    private void quadModern(BufferBuilder buf, float x1, float y1, float z1,
                            float x2, float y2, float z2, float x3, float y3, float z3,
                            float x4, float y4, float z4, int r, int g, int b, int a) {
        buf.addVertex(x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(x3, y3, z3).setColor(r, g, b, a);
        buf.addVertex(x4, y4, z4).setColor(r, g, b, a);
    }

    private void addOutlineBoxLines(BufferBuilder buf, BlockPos pos,
                                    int r, int g, int b, int a, Vec3 cameraPos) {
        float x1 = (float) (pos.getX() - cameraPos.x );
        float y1 = (float) (pos.getY() - cameraPos.y );
        float z1 = (float) (pos.getZ() - cameraPos.z );
        float x2 = (float) (pos.getX() - cameraPos.x + 1 );
        float y2 = (float) (pos.getY() - cameraPos.y + 1 );
        float z2 = (float) (pos.getZ() - cameraPos.z + 1 );

        line(buf, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(buf, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(buf, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(buf, x1, y1, z2, x1, y1, z1, r, g, b, a);
        line(buf, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(buf, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(buf, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(buf, x1, y2, z2, x1, y2, z1, r, g, b, a);
        line(buf, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(buf, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(buf, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(buf, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private void line(BufferBuilder buf, float x1, float y1, float z1,
                      float x2, float y2, float z2, int r, int g, int b, int a) {
        buf.addVertex(x1, y1, z1).setColor(r, g, b, a).setLineWidth(1.0f);
        buf.addVertex(x2, y2, z2).setColor(r, g, b, a).setLineWidth(1.0f);
    }

    // ===== Intermediate path: MC >= 1.21.1 && < 1.21.5 (direct BufferUploader) =====


    // ===== Legacy path: MC < 1.21.1 (old Tesselator API) =====


    // ===== Shared Helpers =====

    private static int[] extractArgb(int argb) {
        return new int[]{
                (argb >> 24) & 0xFF,  // alpha
                (argb >> 16) & 0xFF,  // red
                (argb >> 8) & 0xFF,   // green
                argb & 0xFF            // blue
        };
    }

    private record HighlightEntry(BlockPos pos, float alpha, float distSq,
                                   int r, int g, int b, HighlightStyleType style) {}
}
