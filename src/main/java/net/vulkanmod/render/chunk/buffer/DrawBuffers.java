package net.vulkanmod.render.chunk.buffer;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.IndirectBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import static org.lwjgl.vulkan.VK10.*;

public class DrawBuffers {

    private static final int VERTEX_SIZE = PipelineManager.TERRAIN_VERTEX_FORMAT.getVertexSize();
    private static final int INDEX_SIZE = Short.BYTES;
    private final int index;
    private final Vector3i origin;
    private final int minHeight;

    private boolean allocated = false;
    AreaBuffer vertexBuffer, indexBuffer;
    private final EnumMap<TerrainRenderType, AreaBuffer> vertexBuffers = new EnumMap<>(TerrainRenderType.class);

    public DrawBuffers(int index, Vector3i origin, int minHeight) {
        this.index = index;
        this.origin = origin;
        this.minHeight = minHeight;
    }

    public void allocateBuffers() {
        if (!Initializer.CONFIG.perRenderTypeAreaBuffers)
            vertexBuffer = new AreaBuffer(AreaBuffer.Usage.VERTEX, 2097152, VERTEX_SIZE);

        this.allocated = true;
    }

    public void upload(RenderSection section, UploadBuffer buffer, TerrainRenderType renderType) {
        DrawParameters drawParameters = section.getDrawParameters(renderType);
        int vertexOffset = drawParameters.vertexOffset;
        int firstIndex = -1;

        if (!buffer.indexOnly) {
            AreaBuffer.Segment segment = this.getAreaBufferOrAlloc(renderType).upload(buffer.getVertexBuffer(), vertexOffset, drawParameters);
            vertexOffset = segment.offset / VERTEX_SIZE;

            drawParameters.baseInstance = encodeSectionOffset(section.xOffset(), section.yOffset(), section.zOffset());
        }

        if (!buffer.autoIndices) {
            if (this.indexBuffer == null)
                this.indexBuffer = new AreaBuffer(AreaBuffer.Usage.INDEX, 786432, INDEX_SIZE);

            AreaBuffer.Segment segment = this.indexBuffer.upload(buffer.getIndexBuffer(), drawParameters.firstIndex, drawParameters);
            firstIndex = segment.offset / INDEX_SIZE;
        }

        drawParameters.indexCount = buffer.indexCount;
        drawParameters.instanceCount = vertexOffset == -1 ? 0 : 1;
        drawParameters.firstIndex = firstIndex;
        drawParameters.vertexOffset = vertexOffset;

        buffer.release();
    }

    private AreaBuffer getAreaBufferOrAlloc(TerrainRenderType r) {
        return this.vertexBuffers.computeIfAbsent(
            r, t -> Initializer.CONFIG.perRenderTypeAreaBuffers ? new AreaBuffer(AreaBuffer.Usage.VERTEX, r.initialSize, VERTEX_SIZE) : this.vertexBuffer);
    }

    public AreaBuffer getAreaBuffer(TerrainRenderType r) {
        return this.vertexBuffers.get(r);
    }

    private boolean hasRenderType(TerrainRenderType r) {
        return this.vertexBuffers.containsKey(r);
    }

    private int encodeSectionOffset(int xOffset, int yOffset, int zOffset) {
        final int xOffset1 = (xOffset & 127);
        final int zOffset1 = (zOffset & 127);
        final int yOffset1 = (yOffset - this.minHeight & 127);
        return yOffset1 << 16 | zOffset1 << 8 | xOffset1;
    }

    private void updateChunkAreaOrigin(VkCommandBuffer commandBuffer, Pipeline pipeline, double camX, double camY, double camZ, MemoryStack stack) {
        float xOffset = (float) (camX - this.origin.x);
        float yOffset = (float) (camY - this.origin.y);
        float zOffset = (float) (camZ - this.origin.z);

        ByteBuffer byteBuffer = stack.malloc(12);
        byteBuffer.putFloat(0, -xOffset);
        byteBuffer.putFloat(4, -yOffset);
        byteBuffer.putFloat(8, -zOffset);

        vkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, byteBuffer);
    }

    public void buildDrawBatchesIndirect(IndirectBuffer indirectBuffer, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer byteBuffer = stack.malloc(20 * queue.size());
            long bufferPtr = MemoryUtil.memAddress0(byteBuffer);

            boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
            int drawCount = 0;

            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                RenderSection section = iterator.next();
                DrawParameters drawParameters = section.getDrawParameters(terrainRenderType);

                if (drawParameters.indexCount <= 0) continue;

                long ptr = bufferPtr + (drawCount * 20L);
                MemoryUtil.memPutInt(ptr, drawParameters.indexCount);
                MemoryUtil.memPutInt(ptr + 4, drawParameters.instanceCount);
                MemoryUtil.memPutInt(ptr + 8, drawParameters.firstIndex == -1 ? 0 : drawParameters.firstIndex);
                MemoryUtil.memPutInt(ptr + 12, drawParameters.vertexOffset);
                MemoryUtil.memPutInt(ptr + 16, drawParameters.baseInstance);

                drawCount++;
            }

            if (drawCount == 0) return;

            indirectBuffer.recordCopyCmd(byteBuffer.position(0));
            vkCmdDrawIndexedIndirect(Renderer.getCommandBuffer(), indirectBuffer.getId(), indirectBuffer.getOffset(), drawCount, 20);
        }
    }

    public void buildDrawBatchesDirect(StaticQueue<RenderSection> queue, TerrainRenderType renderType) {
        boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
            RenderSection section = iterator.next();
            DrawParameters drawParameters = section.getDrawParameters(renderType);

            if (drawParameters.indexCount <= 0) continue;

            int firstIndex = drawParameters.firstIndex == -1 ? 0 : drawParameters.firstIndex;
            vkCmdDrawIndexed(commandBuffer, drawParameters.indexCount, drawParameters.instanceCount, firstIndex, drawParameters.vertexOffset, drawParameters.baseInstance);
        }
    }

    public void bindBuffers(VkCommandBuffer commandBuffer, Pipeline pipeline, TerrainRenderType terrainRenderType, double camX, double camY, double camZ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AreaBuffer vertexBuffer = getAreaBuffer(terrainRenderType);
            nvkCmdBindVertexBuffers(commandBuffer, 0, 1, stack.npointer(vertexBuffer.getId()), stack.npointer(0));
            updateChunkAreaOrigin(commandBuffer, pipeline, camX, camY, camZ, stack);
        }

        if (terrainRenderType == TerrainRenderType.TRANSLUCENT) {
            vkCmdBindIndexBuffer(commandBuffer, this.indexBuffer.getId(), 0, VK_INDEX_TYPE_UINT16);
        }
    }

    public void releaseBuffers() {
        if (!this.allocated) return;

        if (this.vertexBuffer == null) {
            this.vertexBuffers.values().forEach(AreaBuffer::freeBuffer);
        } else {
            this.vertexBuffer.freeBuffer();
        }

        this.vertexBuffers.clear();
        if (this.indexBuffer != null) this.indexBuffer.freeBuffer();

        this.vertexBuffer = null;
        this.indexBuffer = null;
        this.allocated = false;
    }

    public boolean isAllocated() {
        return allocated;
    }

    public AreaBuffer getVertexBuffer() {
        return vertexBuffer;
    }

    public EnumMap<TerrainRenderType, AreaBuffer> getVertexBuffers() {
        return vertexBuffers;
    }

    public AreaBuffer getIndexBuffer() {
        return indexBuffer;
    }

    public static class DrawParameters {
        int indexCount = 0, instanceCount = 1, firstIndex = -1, vertexOffset = -1, baseInstance;

        public void reset(ChunkArea chunkArea, TerrainRenderType r) {
            int segmentOffset = vertexOffset * VERTEX_SIZE;
            if (chunkArea != null && chunkArea.getDrawBuffers().hasRenderType(r) && segmentOffset != -1) {
                chunkArea.getDrawBuffers().getAreaBuffer(r).setSegmentFree(segmentOffset);
            }

            this.indexCount = 0;
            this.firstIndex = -1;
            this.vertexOffset = -1;
        }
    }
}
