package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.reconcile.ReconcileToast;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenMixin {
	/**
	 * Draws the reconciliation toast above an open screen. This is the single entry point the game
	 * renderer uses for every screen (container screens override {@code render}, not this method),
	 * so the toast is not covered by the menu background.
	 */
	@Inject(method = "renderWithTooltipAndSubtitles", at = @At("TAIL"))
	private void menupredict$onRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ReconcileToast.render(guiGraphics, true);
	}
}
