package dev.zcode.menupredict.mixin;

import dev.zcode.menupredict.MenuPredictClient;
import dev.zcode.menupredict.predict.PlaceRecipePredictor;
import dev.zcode.menupredict.predict.PredictedOpen;
import net.minecraft.client.Minecraft;
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
	 * After the interaction resolves: records the last right-clicked block position (an
	 * enchanting menu is created client-side with a NULL level access, so the table position is
	 * otherwise unknown), and — when the used block is a crafting/enchanting table — opens the
	 * menu locally instead of waiting for the server's ClientboundOpenScreenPacket.
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
}
