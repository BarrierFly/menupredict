package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.MenuPredictClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	/**
	 * Binds prediction state right after the client created its menu from the open-screen packet:
	 * from this point on every subsequent update is predicted locally instead of waiting for
	 * server round trips.
	 */
	@Inject(method = "handleOpenScreen", at = @At("TAIL"))
	private void menupredict$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			MenuPredictClient.onMenuOpened(minecraft.player.containerMenu);
		}
	}
}
