package net.vulkanmod.mixin.screen;

import com.mojang.blaze3d.platform.GlStateManager; // Import for managing OpenGL state
import com.mojang.blaze3d.systems.RenderSystem; // Import for rendering
import com.mojang.realms.client.RealmsMainScreen; // Import for Realms main screen (optional)
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.AbstractOptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.text.StringTextComponent; // Use StringTextComponent for text
import net.vulkanmod.config.gui.VOptionScreen; // Assuming VOptionScreen is your custom video options screen

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractOptionsScreen.class) // Use AbstractOptionsScreen for 1.21
public class OptionsScreenM extends Screen {

    @Shadow @Final private Screen lastScreen; // Shadow the lastScreen field
    @Shadow @Final private Options options; // Shadow the options field

    protected OptionsScreenM(Component title) {
        super(title);
    }

    @Inject(method = "method_25162", at = @At("HEAD"), cancellable = true) // Use method_25162 for 1.21
    private void injectVideoOptionScreen(CallbackInfoReturnable<Screen> cir) {
        if (this.minecraft != null && this.minecraft.screen != null && !(this.minecraft.screen instanceof RealmsMainScreen)) { // Check for Realms main screen (optional)
            cir.setReturnValue(new VOptionScreen(new StringTextComponent("Video Settings"), this)); // Use StringTextComponent for text
        }
    }

    @Override // Override the render method to potentially add custom rendering for your mixin (optional)
    public void render(int mouseX, int mouseY, float partialTicks) {
        super.render(mouseX, mouseY, partialTicks);
        // Add your custom rendering code here (optiona
    }
}
