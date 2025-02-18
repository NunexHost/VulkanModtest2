package net.vulkanmod.vulkan.framebuffer;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.queue.QueueFamilyIndices;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.*;
import static net.vulkanmod.vulkan.device.DeviceManager.vkDevice;
import static net.vulkanmod.vulkan.util.VUtil.UINT32_MAX;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class SwapChain extends Framebuffer {
    // Necessary until tearing-control-unstable-v1 is fully implemented on all GPU Drivers for Wayland
    // (As Immediate Mode (and by extension Screen tearing) doesn't exist on some Wayland installations currently)
    private static final int defUncappedMode = checkPresentMode(VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR);

    private final Long2ReferenceOpenHashMap<long[]> FBO_map = new Long2ReferenceOpenHashMap<>();
    private long swapChainId = VK_NULL_HANDLE;
    private List<VulkanImage> swapChainImages;
    private VkExtent2D extent2D;
    // A matrix that describes the transformations that should be applied
    // to the output of the game.
    private Matrix4f pretransformMatrix = new Matrix4f();
    // The pretransform flags that were given to the swapchain,
    // masked (see "setupPreRotation(VkExtent2D, VkSurfaceCapabilitiesKHR)")
    private int pretransformFlags;
    public boolean isBGRAformat;
    private boolean vsync = false;
    private int[] glIds;

    public SwapChain() {
        this.attachmentCount = 2;
        this.depthFormat = Vulkan.getDefaultDepthFormat();

        this.hasColorAttachment = true;
        this.hasDepthAttachment = true;

        recreate();
    }

    public void recreate() {
        if (this.depthAttachment != null) {
            this.depthAttachment.free();
            this.depthAttachment = null;
        }

        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }

        createSwapChain();
    }

    private void createSwapChain() {
        try (MemoryStack stack = stackPush()) {
            VkDevice device = Vulkan.getVkDevice();
            DeviceManager.SurfaceProperties surfaceProperties = DeviceManager.querySurfaceProperties(device.getPhysicalDevice(), stack);

            VkSurfaceFormatKHR surfaceFormat = getFormat(surfaceProperties.formats);
            int presentMode = getPresentMode(surfaceProperties.presentModes);
            VkExtent2D extent = getExtent(surfaceProperties.capabilities);
            setupPreRotation(extent, surfaceProperties.capabilities);

            if (extent.width() == 0 && extent.height() == 0) {
                if (this.swapChainId != VK_NULL_HANDLE) {
                    this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                    vkDestroySwapchainKHR(device, this.swapChainId, null);
                    this.swapChainId = VK_NULL_HANDLE;
                }

                this.width = 0;
                this.height = 0;
                return;
            }

            // minImageCount depends on driver: Mesa/RADV needs a min of 4, but most other drivers are at least 2 or 3
            // TODO using FIFO present mode with image num > 2 introduces (unnecessary) input lag
            int requestedImages = Initializer.CONFIG.presentMode == 1 ? surfaceProperties.capabilities.minImageCount() : Math.max(Initializer.CONFIG.imageCount, surfaceProperties.capabilities.minImageCount());
            //int requestedImages = Math.max(Initializer.CONFIG.imageCount, surfaceProperties.capabilities.minImageCount());

            IntBuffer imageCount = stack.ints(requestedImages);

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(Vulkan.getSurface());

            // Image settings
            this.format = surfaceFormat.format();
            this.extent2D = VkExtent2D.create().set(extent);

            createInfo.minImageCount(requestedImages);
            createInfo.imageFormat(this.format);
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            createInfo.imageUsage(Initializer.CONFIG.dontUseImageSampled ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);

            if(QueueFamilyIndices.graphicsFamily != (QueueFamilyIndices.presentFamily)) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(QueueFamilyIndices.graphicsFamily, QueueFamilyIndices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(surfaceProperties.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);

            createInfo.oldSwapchain(this.swapChainId);

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);

            if (vkCreateSwapchainKHR(device, createInfo, null, pSwapChain) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create swap chain");
            }

            if (this.swapChainId != VK_NULL_HANDLE) {
                this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                vkDestroySwapchainKHR(device, this.swapChainId, null);
            }

            this.swapChainId = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));

            Initializer.LOGGER.info("Requested Image Count -> " + requestedImages + " Actual Images -> " + imageCount.get(0));
            
            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, pSwapchainImages);

            this.swapChainImages = new ArrayList<>(imageCount.get(0));

            this.width = extent2D.width();
            this.height = extent2D.height();

            for (int i = 0; i < pSwapchainImages.capacity(); i++) {
                long imageId = pSwapchainImages.get(i);
                long imageView = VulkanImage.createImageView(imageId, this.format, VK_IMAGE_ASPECT_COLOR_BIT, 1);

                VulkanImage image = new VulkanImage(imageId, this.format, 1, this.width, this.height, 4, 0, imageView);
                image.updateTextureSampler(true, true, false);
                this.swapChainImages.add(image);
            }

        }

        createGlIds();
        createDepthResources();
    }

    private void createGlIds() {
        this.glIds = new int[this.swapChainImages.size()];

        for (int i = 0; i < this.swapChainImages.size(); i++) {
            int id = GlTexture.genTextureId();
            this.glIds[i] = id;
            GlTexture.bindIdToImage(id, this.swapChainImages.get(i));
        }
    }

    public int getColorAttachmentGlId() {
        return this.glIds[Renderer.getCurrentImage()];
    }

    private long[] createFramebuffers(RenderPass renderPass) {

        try (MemoryStack stack = MemoryStack.stackPush()) {

            long[] framebuffers = new long[this.swapChainImages.size()];

            for (int i = 0; i < this.swapChainImages.size(); ++i) {
                LongBuffer attachments = stack.longs(this.swapChainImages.get(i).getImageView(), this.depthAttachment.getImageView());

                LongBuffer pFramebuffer = stack.mallocLong(1);

                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
                framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                framebufferInfo.renderPass(renderPass.getId());
                framebufferInfo.width(this.width);
                framebufferInfo.height(this.height);
                framebufferInfo.layers(1);
                framebufferInfo.pAttachments(attachments);

                if (vkCreateFramebuffer(Vulkan.getVkDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create framebuffer");
                }

                framebuffers[i] = pFramebuffer.get(0);
            }

            return framebuffers;
        }
    }

    private void createDepthResources() {
        this.depthAttachment = VulkanImage.createDepthImage(depthFormat, this.width, this.height,
                !Initializer.CONFIG.dontUseImageSampled ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                false, false);
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, RenderPass renderPass, MemoryStack stack) {
        if (!DYNAMIC_RENDERING) {
            long[] framebufferId = this.FBO_map.computeIfAbsent(renderPass.id, renderPass1 -> createFramebuffers(renderPass));
            renderPass.beginRenderPass(commandBuffer, framebufferId[Renderer.getCurrentImage()], stack);
        } else
            renderPass.beginDynamicRendering(commandBuffer, stack);

        Renderer.getInstance().setBoundRenderPass(renderPass);
        Renderer.getInstance().setBoundFramebuffer(this);
    }

    public void cleanUp() {
        VkDevice device = Vulkan.getVkDevice();

        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }

        vkDestroySwapchainKHR(device, this.swapChainId, null);
        this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));

        this.depthAttachment.free();
    }

    public long getId() {
        return this.swapChainId;
    }

    public List<VulkanImage> getImages() {
        return this.swapChainImages;
    }

    public long getImageId(int i) {
        return this.swapChainImages.get(i).getId();
    }

    public VkExtent2D getExtent() {
        return this.extent2D;
    }

    public Matrix4f getPretransformMatrix(){
        return this.pretransformMatrix;
    }

    public int getPretransformFlags() {
        return this.pretransformFlags;
    }

    public VulkanImage getColorAttachment() {
        return this.swapChainImages.get(Renderer.getCurrentImage());
    }

    public long getImageView(int i) {
        return this.swapChainImages.get(i).getImageView();
    }

    private VkSurfaceFormatKHR getFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        List<VkSurfaceFormatKHR> list = availableFormats.stream().toList();

        VkSurfaceFormatKHR format = list.get(0);

        for (VkSurfaceFormatKHR availableFormat : list) {
            if (availableFormat.format() == VK_FORMAT_R8G8B8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                return availableFormat;

            if (availableFormat.format() == VK_FORMAT_B8G8R8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = availableFormat;
            }
        }

        if (format.format() == VK_FORMAT_B8G8R8A8_UNORM)
            isBGRAformat = true;
        return format;
    }

    private int getPresentMode(IntBuffer availablePresentModes) {
        int requestedMode;

        // Check the configuration option
        switch (Initializer.CONFIG.presentMode) {
            case 1 -> requestedMode = VK_PRESENT_MODE_FIFO_KHR;
            case 2 -> requestedMode = VK_PRESENT_MODE_MAILBOX_KHR;
            default -> requestedMode = defUncappedMode;
        }

        // If the requested mode is supported, use it
        for (int i = 0; i < availablePresentModes.capacity(); i++) {
            if (availablePresentModes.get(i) == requestedMode) {
                Initializer.LOGGER.info("Using present mode: " + getDisplayModeString(requestedMode));
                return requestedMode;
            }
        }

        // Log a warning if the requested mode is not supported and fall back to FIFO mode
        Initializer.LOGGER.warn("Requested mode not supported: " + getDisplayModeString(requestedMode) + ": using FIFO (VSync)");
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private String getDisplayModeString(int requestedMode) {
        return switch (requestedMode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "Immediate";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "Mailbox (FastSync)";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO Relaxed (Adaptive VSync)";
            default -> "FIFO (VSync)";
        };
    }

    private static VkExtent2D getExtent(VkSurfaceCapabilitiesKHR capabilities) {

        if (capabilities.currentExtent().width() != UINT32_MAX) {
            return capabilities.currentExtent();
        }

        //Fallback
        IntBuffer width = stackGet().ints(0);
        IntBuffer height = stackGet().ints(0);

        glfwGetFramebufferSize(window, width, height);

        VkExtent2D actualExtent = VkExtent2D.mallocStack().set(width.get(0), height.get(0));

        VkExtent2D minExtent = capabilities.minImageExtent();
        VkExtent2D maxExtent = capabilities.maxImageExtent();

        actualExtent.width(MathUtil.clamp(minExtent.width(), maxExtent.width(), actualExtent.width()));
        actualExtent.height(MathUtil.clamp(minExtent.height(), maxExtent.height(), actualExtent.height()));

        return actualExtent;
    }

    private static int checkPresentMode(int... requestedModes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var a = DeviceManager.querySurfaceProperties(vkDevice.getPhysicalDevice(), stack).presentModes;
            for (int dMode : requestedModes) {
                for (int i = 0; i < a.capacity(); i++) {
                    if (a.get(i) == dMode) {
                        return dMode;
                    }
                }
            }
            // Log a warning if none of the requested modes are supported and fall back to FIFO mode
            Initializer.LOGGER.warn("None of the requested modes are supported by the driver: using FIFO");
            return VK_PRESENT_MODE_FIFO_KHR; // If None of the request modes exist/are supported by Driver
        }
    }

    private void setupPreRotation(VkExtent2D extent, VkSurfaceCapabilitiesKHR surfaceCapabilities) {
        // Mask off anything else that does not interest us in the transform
        pretransformFlags = surfaceCapabilities.currentTransform() &
                (VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR |
                VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR |
                VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR);
        int rotateDegrees = 0;
        boolean swapXY = false;
        switch (pretransformFlags) {
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR -> {
                rotateDegrees = 90;
                swapXY = true;
            }
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR -> {
                rotateDegrees = 270;
                swapXY = true;
            }
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR -> rotateDegrees = 180;
        }
        pretransformMatrix = pretransformMatrix.identity();
        if(rotateDegrees != 0) {
            pretransformMatrix.rotate((float) Math.toRadians(rotateDegrees), 0, 0, 1);
            pretransformMatrix.invert();
        }
        if(swapXY) {
            int originalWidth = extent.width();
            int originalHeight = extent.height();
            extent.width(originalHeight);
            extent.height(originalWidth);
        }
    }

    public boolean isVsync() {
        return this.vsync;
    }

    public void setVsync(boolean vsync) {
        this.vsync = vsync;
    }

    public int getImagesNum() {
        return this.swapChainImages.size();
    }
}
