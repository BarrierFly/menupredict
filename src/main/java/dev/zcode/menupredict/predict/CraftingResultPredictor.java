package dev.zcode.menupredict.predict;

import dev.zcode.menupredict.mixin.AbstractCraftingMenuAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;

/**
 * Predicts the crafting result slot locally. The server computes it exclusively
 * ({@code CraftingMenu.slotChangedCraftingGrid}) and pushes it via an explicit SetSlot packet;
 * every input of that computation — the synced recipe book entries plus the grid contents — is
 * available on the client, so the result is shown immediately and the server packet reconciles.
 */
public final class CraftingResultPredictor {
	private CraftingResultPredictor() {
	}

	public static void onCraftingMenuSlotsChanged(CraftingMenu menu) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && menu == minecraft.player.containerMenu) {
			predictResult(menu, minecraft);
		}
	}

	public static void onInventoryMenuSlotsChanged(InventoryMenu menu) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && menu == minecraft.player.inventoryMenu) {
			predictResult(menu, minecraft);
		}
	}

	/**
	 * Runs the result computation for the current grid state. Called on every grid slot change
	 * and once at the end of a predicted recipe-book fill.
	 */
	public static void predictResult(AbstractCraftingMenu menu, Minecraft minecraft) {
		if (PlaceRecipePredictor.isPlacing()) {
			return;
		}

		AbstractCraftingMenuAccessor accessor = (AbstractCraftingMenuAccessor) menu;
		CraftingInput input = accessor.menupredict$craftSlots().asCraftInput();
		ItemStack result = ItemStack.EMPTY;
		if (!input.isEmpty()) {
			result = RecipeMatcher.match(input, RecipeMatcher.knownEntries())
				.flatMap(match -> RecipeMatcher.resolveSingleResult(match.entry(), minecraft.level))
				.orElse(ItemStack.EMPTY);
		}

		accessor.menupredict$resultSlots().setItem(0, result);
	}
}
