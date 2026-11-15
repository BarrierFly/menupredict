package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.MenuPredictClient;
import dev.zcode.menupredict.predict.EnchantPredictor;
import dev.zcode.menupredict.predict.PlaceRecipePredictor;
import dev.zcode.menupredict.predict.PredictedOpen;
import dev.zcode.menupredict.reconcile.PredictionReconciler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.InventoryMenu;
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
	 * as synced and feeds the value to the reconciler. A predicted-open menu has no seed until
	 * the server's initial data arrives, so option prediction is gated on this instead of showing
	 * wrong values computed from seed 0.
	 *
	 * <p>Everything happens in one return hook so the order is fixed: the update window is still
	 * open while {@code markSeedSynced} recomputes the option rows, which therefore knows it only
	 * mirrors the server's own data and must not register a prediction.
	 */
	@Inject(method = "handleContainerSetData", at = @At("HEAD"))
	private void menupredict$beforeContainerSetData(ClientboundContainerSetDataPacket packet, CallbackInfo ci) {
		PredictionReconciler.beginServerUpdate();
	}

	@Inject(method = "handleContainerSetData", at = @At("RETURN"))
	private void menupredict$afterContainerSetData(ClientboundContainerSetDataPacket packet, CallbackInfo ci) {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			AbstractContainerMenu menu = menupredict$resolveMenu(packet.getContainerId());
			if (menu == null) {
				return;
			}

			if (menu instanceof EnchantmentMenu enchantMenu
				&& menu.containerId == packet.getContainerId()
				&& packet.getId() == EnchantPredictor.SEED_DATA_ID) {
				EnchantPredictor.markSeedSynced(enchantMenu);
			}

			PredictionReconciler.onData(menu, packet.getId(), packet.getValue());
		} finally {
			PredictionReconciler.endServerUpdate();
		}
	}

	/**
	 * Slot replies are authoritative values for a prediction. The packet is flagged as a server
	 * update while vanilla applies it, so the predictions it triggers locally are not registered
	 * as new checks — only the reply itself is compared against what was predicted.
	 */
	@Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
	private void menupredict$beforeContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		PredictionReconciler.beginServerUpdate();
	}

	@Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
	private void menupredict$afterContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		try {
			AbstractContainerMenu menu = menupredict$resolveMenu(packet.getContainerId());
			if (menu != null) {
				PredictionReconciler.onSlot(menu, packet.getSlot(), packet.getItem());
			}
		} finally {
			PredictionReconciler.endServerUpdate();
		}
	}

	@Inject(method = "handleContainerContent", at = @At("HEAD"))
	private void menupredict$beforeContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
		PredictionReconciler.beginServerUpdate();
	}

	@Inject(method = "handleContainerContent", at = @At("RETURN"))
	private void menupredict$afterContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
		try {
			AbstractContainerMenu menu = menupredict$resolveMenu(packet.containerId());
			if (menu != null) {
				PredictionReconciler.onContent(menu, packet.items());
			}
		} finally {
			PredictionReconciler.endServerUpdate();
		}
	}

	/**
	 * The server answers a recipe fill it refuses with a ghost recipe packet instead of slot
	 * updates. That is an explicit "your prediction did not happen" verdict, so the pending fill
	 * check is resolved as a mismatch right away instead of silently timing out.
	 */
	@Inject(method = "handlePlaceRecipe", at = @At("HEAD"))
	private void menupredict$onPlaceGhost(ClientboundPlaceGhostRecipePacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && minecraft.player.containerMenu.containerId == packet.containerId()) {
			PredictionReconciler.reportGhostRecipe(minecraft.player.containerMenu, PlaceRecipePredictor.ACTION);
		}
	}

	/**
	 * Maps a container id to the menu the packet actually patches: container 0 is always the
	 * player's own inventory menu (it is synced even while another menu is open), every other id
	 * only applies to the menu currently open.
	 */
	private static AbstractContainerMenu menupredict$resolveMenu(int containerId) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return null;
		}

		if (containerId == InventoryMenu.CONTAINER_ID) {
			return player.inventoryMenu;
		}

		AbstractContainerMenu openMenu = player.containerMenu;
		return openMenu != null && openMenu.containerId == containerId ? openMenu : null;
	}
}
