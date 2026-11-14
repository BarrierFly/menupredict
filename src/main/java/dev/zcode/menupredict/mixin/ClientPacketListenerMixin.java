package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.MenuPredictClient;
import dev.zcode.menupredict.predict.EnchantPredictor;
import dev.zcode.menupredict.predict.PredictedOpen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	/**
	 * Before vanilla creates the screen from the open-screen packet: when a predicted screen of
	 * the same menu type is already open, it is rebound to the server-assigned container id and
	 * kept (no flicker); the SetContent/SetData packets that follow land on it normally.
	 */
	@Inject(
		method = "handleOpenScreen",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/MenuScreens;create(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/Minecraft;ILnet/minecraft/network/chat/Component;)V"
		),
		cancellable = true
	)
	private void menupredict$onOpenScreenBeforeCreate(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		if (PredictedOpen.onServerOpenScreen(Minecraft.getInstance(), packet.getType(), packet.getContainerId())) {
			ci.cancel();
		}
	}

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

	/**
	 * Marks the enchantment seed (DataSlot index 3 in vanilla EnchantmentMenu's fixed slot order)
	 * as synced. A predicted-open menu has no seed until the server's initial data arrives, so
	 * option prediction is gated on this instead of showing wrong values computed from seed 0.
	 */
	@Inject(method = "handleContainerSetData", at = @At("TAIL"))
	private void menupredict$onContainerSetData(ClientboundContainerSetDataPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null
			&& minecraft.player.containerMenu instanceof EnchantmentMenu enchantMenu
			&& minecraft.player.containerMenu.containerId == packet.getContainerId()
			&& packet.getId() == 3) {
			EnchantPredictor.markSeedSynced(enchantMenu);
		}
	}
}
