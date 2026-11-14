package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.MenuPredictClient;
import dev.zcode.menupredict.predict.PlaceRecipePredictor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	/**
	 * Client-side prediction of the recipe book fill before the placement packet leaves the
	 * client — the packet itself is sent unchanged so the server keeps validating.
	 */
	@Inject(method = "handlePlaceRecipe", at = @At("HEAD"))
	private void menupredict$onHandlePlaceRecipe(int containerId, RecipeDisplayId recipeDisplayId, boolean useMaxItems, CallbackInfo ci) {
		PlaceRecipePredictor.beforeSend(containerId, recipeDisplayId, useMaxItems);
	}

	/**
	 * Tracks the last right-clicked block so an enchanting menu (which is created client-side
	 * with a NULL level access) can still resolve its table position for local bookshelf counting.
	 */
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void menupredict$onUseItemOn(
		LocalPlayer player, InteractionHand hand, BlockHitResult blockHitResult, CallbackInfoReturnable<InteractionResult> cir
	) {
		BlockPos pos = blockHitResult.getBlockPos();
		if (pos != null) {
			MenuPredictClient.onBlockUse(pos);
		}
	}
}
