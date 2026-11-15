package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.predict.PredictedOpen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {
	/** Drives the confirm/rollback timer of a predicted (not yet server-confirmed) open menu. */
	@Inject(method = "containerTick", at = @At("TAIL"))
	private void menupredict$onContainerTick(CallbackInfo ci) {
		PredictedOpen.tickPending(Minecraft.getInstance());
	}

	/**
	 * A predicted screen the player closes before the server answers: the server may still send
	 * its open packet later, and a stale pending entry would then hijack (or roll back) whatever
	 * screen the player has open by that time. Dropping it here keeps the state in sync.
	 */
	@Inject(method = "onClose", at = @At("HEAD"))
	private void menupredict$onClose(CallbackInfo ci) {
		PredictedOpen.onScreenClosed((AbstractContainerScreen<?>) (Object) this);
	}
}
