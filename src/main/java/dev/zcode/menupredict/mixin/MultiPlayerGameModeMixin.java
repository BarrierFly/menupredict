package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.MenuPredictClient;
import dev.zcode.menupredict.predict.CraftingResultPredictor;
import dev.zcode.menupredict.predict.PlaceRecipePredictor;
import dev.zcode.menupredict.predict.PredictedOpen;
import dev.zcode.menupredict.reconcile.PredictionReconciler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	/** Menu contents and pending predictions captured before a local click is applied. */
	private static Map<Integer, ItemStack> menupredict$clickSnapshot;
	private static List<Integer> menupredict$olderCheckIds;
	private static AbstractContainerMenu menupredict$clickMenu;

	/**
	 * Client-side prediction of the recipe book fill before the placement packet leaves the
	 * client — the packet itself is sent unchanged so the server keeps validating.
	 */
	@Inject(method = "handlePlaceRecipe", at = @At("HEAD"))
	private void menupredict$onHandlePlaceRecipe(int containerId, RecipeDisplayId recipeDisplayId, boolean useMaxItems, CallbackInfo ci) {
		PlaceRecipePredictor.beforeSend(containerId, recipeDisplayId, useMaxItems);
	}

	/**
	 * After the interaction resolves: records the last right-clicked block position (an
	 * enchanting menu is created client-side with a NULL level access, so the table position is
	 * otherwise unknown), and — when the used block is one of the supported functional blocks —
	 * opens the menu locally instead of waiting for the server's ClientboundOpenScreenPacket.
	 */
	@Inject(method = "useItemOn", at = @At("TAIL"))
	private void menupredict$onUseItemOn(
		LocalPlayer player, InteractionHand hand, BlockHitResult blockHitResult, CallbackInfoReturnable<InteractionResult> cir
	) {
		BlockPos pos = blockHitResult.getBlockPos();
		if (pos == null) {
			return;
		}

		MenuPredictClient.onBlockUse(pos);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null) {
			PredictedOpen.onBlockUseResult(minecraft, minecraft.level, minecraft.level.getBlockState(pos), pos, cir.getReturnValue());
		}
	}

	/**
	 * A click is about to change the menu locally. Predictions still waiting for the server are
	 * then superseded by this newer local state; they are dropped before the click creates its
	 * own prediction, so a slow reply cannot be judged against the wrong action.
	 */
	@Inject(method = "handleInventoryMouseClick", at = @At("HEAD"))
	private void menupredict$beforeMouseClick(int containerId, int slotId, int buttonNum, ClickType clickType, Player player, CallbackInfo ci) {
		menupredict$clickSnapshot = null;
		menupredict$olderCheckIds = null;
		menupredict$clickMenu = null;

		LocalPlayer localPlayer = Minecraft.getInstance().player;
		if (localPlayer == null || localPlayer.containerMenu == null || localPlayer.containerMenu.containerId != containerId) {
			return;
		}

		menupredict$clickMenu = localPlayer.containerMenu;
		menupredict$clickSnapshot = PredictionReconciler.snapshot(menupredict$clickMenu);
		menupredict$olderCheckIds = PredictionReconciler.currentCheckIds();
		CraftingResultPredictor.beginLocalClick();
	}

	@Inject(method = "handleInventoryMouseClick", at = @At("RETURN"))
	private void menupredict$afterMouseClick(int containerId, int slotId, int buttonNum, ClickType clickType, Player player, CallbackInfo ci) {
		Map<Integer, ItemStack> snapshot = menupredict$clickSnapshot;
		List<Integer> olderCheckIds = menupredict$olderCheckIds;
		AbstractContainerMenu menu = menupredict$clickMenu;
		menupredict$clickSnapshot = null;
		menupredict$olderCheckIds = null;
		menupredict$clickMenu = null;

		if (snapshot == null || olderCheckIds == null || menu == null) {
			return;
		}

		// While a predicted open is unconfirmed, every local click happens only client-side; keep
		// the original values so a rollback (or an early close) can undo exactly what was touched.
		PredictedOpen.recordClickUndo(menu, snapshot);
		PredictionReconciler.onLocalClick(olderCheckIds, snapshot, menu);
		CraftingResultPredictor.endLocalClick(menu, snapshot);
	}
}
