package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.reconcile.PredictionReconciler;
import dev.zcode.menupredict.reconcile.ReconcileToast;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {
	/** Drives the timeouts of pending prediction checks and the toast lifetime. */
	@Inject(method = "tick(Z)V", at = @At("HEAD"))
	private void menupredict$onTick(boolean pause, CallbackInfo ci) {
		PredictionReconciler.tick();
	}

	/**
	 * Draws the reconciliation toast on the HUD. Only used while no screen is open: the screen
	 * pass draws later and would cover it, see {@code ScreenMixin}.
	 */
	@Inject(method = "render", at = @At("TAIL"))
	private void menupredict$onRender(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		ReconcileToast.render(guiGraphics, false);
	}
}
