package net.vulkanmod.mixin.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.text2speech.NarratorComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Narrator;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.VideoSettingsScreen; // Updated class name
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen; // Assuming your custom screen class
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VideoSettingsScreen.class) // Updated mixin target
@Environment(EnvType.CLIENT)
public class OptionsScreenM extends Screen {

    @Shadow @Final private Screen parent; // Updated field name
    @Shadow @Final private Options options;

    protected OptionsScreenM(Component title) {
        super(title);
    }

    @Inject(method = "method_29701", at = @At("HEAD"), cancellable = true) // Updated method name and injection point
    private void injectVideoOptionScreen(CallbackInfoReturnable<Screen> cir) {
        cir.setReturnValue(new VOptionScreen(Component.literal("Video Settings"), this));
    }

    @Override // Override necessary methods for proper screen functionality (might be needed)
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE || keyCode == InputConstants.KEY_BACK) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        Narrator.INSTANCE.narrate(NarratorComponent.buildScreenChangeNarration(this.getTitle())); // Accessibility narration
    }
}
