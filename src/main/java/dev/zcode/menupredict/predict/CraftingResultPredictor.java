package dev.zcode.menupredict.predict;

import dev.zcode.menupredict.mixin.AbstractCraftingMenuAccessor;
import dev.zcode.menupredict.reconcile.PredictionReconciler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;

import java.util.Map;
import java.util.Optional;

/**
 * Predicts the crafting result slot locally. The server computes it exclusively
 * ({@code CraftingMenu.slotChangedCraftingGrid}) and pushes it via an explicit SetSlot packet;
 * every input of that computation — the synced recipe book entries plus the grid contents — is
 * available on the client, so the result is shown immediately and the server packet reconciles.
 */
public final class CraftingResultPredictor {
	public static final String ACTION = "menupredict.action.crafting_result";
	/** Result slot index is 0 in both {@code CraftingMenu} and {@code InventoryMenu}. */
	public static final int RESULT_SLOT = 0;

	private CraftingResultPredictor() {
	}

	public static void onCraftingMenuSlotsChanged(CraftingMenu menu) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && menu == minecraft.player.containerMenu) {
			predictAndRecord(menu, minecraft);
		}
	}

	public static void onInventoryMenuSlotsChanged(InventoryMenu menu) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && menu == minecraft.player.inventoryMenu) {
			predictAndRecord(menu, minecraft);
		}
	}

	/**
	 * Predicts the result and registers it for verification when the visible value changed. An
	 * unchanged result (an incomplete grid that keeps showing nothing) still gets a server reply,
	 * but flashing a confirmation for something the player did not see appear would be noise.
	 */
	private static void predictAndRecord(AbstractCraftingMenu menu, Minecraft minecraft) {
		AbstractCraftingMenuAccessor accessor = (AbstractCraftingMenuAccessor) menu;
		ItemStack before = accessor.menupredict$resultSlots().getItem(RESULT_SLOT).copy();

		Optional<ItemStack> predicted = predict(menu, minecraft);
		if (predicted.isEmpty()) {
			return;
		}

		ItemStack after = predicted.get();
		if (!ItemStack.matches(before, after)) {
			PredictionReconciler.expectSlots(ACTION, menu, Map.of(RESULT_SLOT, after));
		}
	}

	/**
	 * Computes and writes the result slot for the current grid state, without registering anything
	 * for verification. Returns the stack that was committed when the prediction is definitive,
	 * or empty when the client cannot know the answer (a non-empty grid whose recipe is not in the
	 * synced recipe book — a documented limitation of this mod). Unpredictable grids still leave
	 * the slot empty here, exactly like before.
	 */
	public static Optional<ItemStack> predict(AbstractCraftingMenu menu, Minecraft minecraft) {
		if (PlaceRecipePredictor.isPlacing()) {
			return Optional.empty();
		}

		AbstractCraftingMenuAccessor accessor = (AbstractCraftingMenuAccessor) menu;
		CraftingInput input = accessor.menupredict$craftSlots().asCraftInput();
		Optional<ItemStack> resolved = Optional.empty();
		if (!input.isEmpty()) {
			resolved = RecipeMatcher.match(input, RecipeMatcher.knownEntries())
				.flatMap(match -> RecipeMatcher.resolveSingleResult(match.entry(), minecraft.level));
		}

		ItemStack result = resolved.orElse(ItemStack.EMPTY);
		accessor.menupredict$resultSlots().setItem(RESULT_SLOT, result);

		return input.isEmpty() ? Optional.of(ItemStack.EMPTY) : resolved;
	}
}
