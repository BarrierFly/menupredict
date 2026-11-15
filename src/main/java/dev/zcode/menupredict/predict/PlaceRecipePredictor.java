package dev.zcode.menupredict.predict;

import dev.zcode.menupredict.mixin.AbstractCraftingMenuAccessor;
import dev.zcode.menupredict.reconcile.PredictionReconciler;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.recipebook.PlaceRecipeHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replicates {@code ServerPlaceRecipe} (recipe book click → fill grid) on the client before the
 * placement packet is even sent. All inputs — the recipe entry (display + synced ingredients),
 * the player inventory, stack limits and the grid state — are client-side data, so the grid is
 * filled, leftovers returned and the next crafting step executable one round trip earlier. The
 * server reruns the same deterministic algorithm and reconciles through SetSlot/SetContent.
 */
public final class PlaceRecipePredictor {
	public static final String ACTION = "menupredict.action.place_recipe";

	private static boolean placing;

	private PlaceRecipePredictor() {
	}

	static boolean isPlacing() {
		return placing;
	}

	public static void beforeSend(int containerId, RecipeDisplayId recipeDisplayId, boolean useMaxItems) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || player.isSpectator()) {
			return;
		}

		AbstractContainerMenu containerMenu = player.containerMenu;
		if (containerMenu == null || containerMenu.containerId != containerId || !(containerMenu instanceof AbstractCraftingMenu menu)) {
			return;
		}

		RecipeDisplayEntry entry = RecipeMatcher.knownEntries().get(recipeDisplayId);
		if (entry == null) {
			return;
		}

		RecipeDisplay display = entry.display();
		if (!(display instanceof ShapedCraftingRecipeDisplay) && !(display instanceof ShapelessCraftingRecipeDisplay)) {
			return;
		}

		Optional<List<Ingredient>> requirements = entry.craftingRequirements();
		if (requirements.isEmpty() || requirements.get().isEmpty()) {
			return;
		}

		List<Ingredient> ingredients = requirements.get();
		Inventory inventory = player.getInventory();
		if (!player.isCreative() && !testClearGrid(menu, inventory)) {
			return;
		}

		StackedContents<Holder<Item>> stackedContents = new StackedContents<>();
		fillStackedContents(stackedContents, menu, inventory);

		IntList slotsToIngredientIndex;
		int recipeWidth;
		int recipeHeight;
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			recipeWidth = shaped.width();
			recipeHeight = shaped.height();
			slotsToIngredientIndex = RecipeMatcher.reconstructShapedMapping(shaped, ingredients);
			if (slotsToIngredientIndex == null) {
				return;
			}
		} else {
			ShapelessCraftingRecipeDisplay shapeless = (ShapelessCraftingRecipeDisplay) display;
			if (ingredients.size() != shapeless.ingredients().size()) {
				return;
			}

			recipeWidth = menu.getGridWidth();
			recipeHeight = menu.getGridHeight();
			slotsToIngredientIndex = new IntArrayList(ingredients.size());
			for (int i = 0; i < ingredients.size(); i++) {
				slotsToIngredientIndex.add(i);
			}
		}

		if (!stackedContents.tryPick(ingredients, 1, null)) {
			if (clearGrid(menu, inventory)) {
				showGhostRecipe(minecraft, display);
			}

			return;
		}

		int biggestCraftableStack = stackedContents.tryPickAll(ingredients, Integer.MAX_VALUE, null);
		boolean recipeMatches = RecipeMatcher.matchEntry(entry, currentGridInput(menu)) != null;
		if (recipeMatches) {
			for (Slot slot : menu.getInputGridSlots()) {
				ItemStack stack = slot.getItem();
				if (!stack.isEmpty() && Math.min(biggestCraftableStack, stack.getMaxStackSize()) < stack.getCount() + 1) {
					return;
				}
			}
		}

		int amount = calculateAmountToCraft(biggestCraftableStack, useMaxItems, recipeMatches, menu);
		List<Holder<Item>> pickedItems = new ArrayList<>();
		if (!stackedContents.tryPick(ingredients, amount, pickedItems::add)) {
			return;
		}

		int clamped = clampToMaxStackSize(amount, pickedItems);
		if (clamped != amount) {
			pickedItems.clear();
			if (!stackedContents.tryPick(ingredients, clamped, pickedItems::add)) {
				return;
			}

			amount = clamped;
		}

		final int fillAmount = amount;
		Map<Integer, ItemStack> before = gridSnapshot(menu);
		// This new action supersedes any prediction still waiting for a reply on this menu.
		PredictionReconciler.discard(menu);
		placing = true;
		try {
			if (!clearGrid(menu, inventory)) {
				return;
			}

			PlaceRecipeHelper.placeRecipe(
				menu.getGridWidth(),
				menu.getGridHeight(),
				recipeWidth,
				recipeHeight,
				slotsToIngredientIndex,
				(ingredientIndex, gridSlotIndex, col, row) -> {
					if (ingredientIndex != -1) {
						Slot slot = menu.getInputGridSlots().get(gridSlotIndex);
						Holder<Item> holder = pickedItems.get(ingredientIndex);
						int remaining = fillAmount;

						while (remaining > 0) {
							remaining = moveItemToGrid(slot, holder, remaining, inventory);
							if (remaining == -1) {
								return;
							}
						}
					}
				}
			);
		} finally {
			placing = false;
		}

		Optional<ItemStack> result = CraftingResultPredictor.predict(menu, minecraft);
		recordFillPrediction(menu, before, result);
	}

	/** Grid + result slot contents before the fill, used to find the slots the fill rewrote. */
	private static Map<Integer, ItemStack> gridSnapshot(AbstractCraftingMenu menu) {
		Map<Integer, ItemStack> snapshot = new HashMap<>();

		for (Slot slot : menu.getInputGridSlots()) {
			snapshot.put(slot.index, slot.getItem().copy());
		}

		snapshot.put(menu.getResultSlot().index, menu.getResultSlot().getItem().copy());
		return snapshot;
	}

	/**
	 * Registers the grid slots and result the fill rewrote for verification. The server re-runs
	 * {@code ServerPlaceRecipe} for the same packet and reports its grid/result through slot
	 * changes, so every stack written here has an authoritative counterpart to compare against.
	 * An unresolvable result is left out: the server decides it, nothing was claimed locally.
	 */
	private static void recordFillPrediction(AbstractCraftingMenu menu, Map<Integer, ItemStack> before, Optional<ItemStack> result) {
		Map<Integer, ItemStack> expected = new HashMap<>();

		for (Slot slot : menu.getInputGridSlots()) {
			expected.put(slot.index, slot.getItem().copy());
		}

		result.ifPresent(stack -> expected.put(menu.getResultSlot().index, stack.copy()));
		PredictionReconciler.expectChangedSlots(ACTION, menu, before, expected);
	}

	private static CraftingInput currentGridInput(AbstractCraftingMenu menu) {
		return ((AbstractCraftingMenuAccessor) menu).menupredict$craftSlots().asCraftInput();
	}

	private static void fillStackedContents(StackedContents<Holder<Item>> stackedContents, AbstractCraftingMenu menu, Inventory inventory) {
		for (ItemStack stack : inventory.getNonEquipmentItems()) {
			accountSimpleStack(stackedContents, stack);
		}

		for (Slot slot : menu.getInputGridSlots()) {
			accountSimpleStack(stackedContents, slot.getItem());
		}
	}

	private static void accountSimpleStack(StackedContents<Holder<Item>> stackedContents, ItemStack stack) {
		if (Inventory.isUsableForCrafting(stack)) {
			accountStack(stackedContents, stack);
		}
	}

	private static void accountStack(StackedContents<Holder<Item>> stackedContents, ItemStack stack) {
		if (!stack.isEmpty()) {
			stackedContents.account(stack.getItemHolder(), Math.min(stack.getMaxStackSize(), stack.getCount()));
		}
	}

	private static int calculateAmountToCraft(int biggestCraftableStack, boolean useMaxItems, boolean recipeMatches, AbstractCraftingMenu menu) {
		if (useMaxItems) {
			return biggestCraftableStack;
		}

		if (recipeMatches) {
			int minCount = Integer.MAX_VALUE;

			for (Slot slot : menu.getInputGridSlots()) {
				ItemStack stack = slot.getItem();
				if (!stack.isEmpty() && minCount > stack.getCount()) {
					minCount = stack.getCount();
				}
			}

			if (minCount != Integer.MAX_VALUE) {
				minCount++;
			}

			return minCount;
		}

		return 1;
	}

	private static int clampToMaxStackSize(int amount, List<Holder<Item>> pickedItems) {
		for (Holder<Item> holder : pickedItems) {
			amount = Math.min(amount, holder.value().getDefaultMaxStackSize());
		}

		return amount;
	}

	private static int moveItemToGrid(Slot slot, Holder<Item> holder, int remaining, Inventory inventory) {
		ItemStack slotStack = slot.getItem();
		int inventorySlot = inventory.findSlotMatchingCraftingIngredient(holder, slotStack);
		if (inventorySlot == -1) {
			return -1;
		}

		ItemStack inventoryStack = inventory.getItem(inventorySlot);
		ItemStack removed = remaining < inventoryStack.getCount()
			? inventory.removeItem(inventorySlot, remaining)
			: inventory.removeItemNoUpdate(inventorySlot);
		int moved = removed.getCount();
		if (slotStack.isEmpty()) {
			slot.set(removed);
		} else {
			slotStack.grow(moved);
		}

		return remaining - moved;
	}

	private static boolean testClearGrid(AbstractCraftingMenu menu, Inventory inventory) {
		List<ItemStack> list = new ArrayList<>();
		int freeSlots = 0;

		for (ItemStack stack : inventory.getNonEquipmentItems()) {
			if (stack.isEmpty()) {
				freeSlots++;
			}
		}

		for (Slot slot : menu.getInputGridSlots()) {
			ItemStack stack = slot.getItem().copy();
			if (!stack.isEmpty()) {
				int remainingSpaceSlot = inventory.getSlotWithRemainingSpace(stack);
				if (remainingSpaceSlot == -1 && list.size() <= freeSlots) {
					for (ItemStack accumulated : list) {
						if (ItemStack.isSameItem(accumulated, stack)
							&& accumulated.getCount() != accumulated.getMaxStackSize()
							&& accumulated.getCount() + stack.getCount() <= accumulated.getMaxStackSize()) {
							accumulated.grow(stack.getCount());
							stack.setCount(0);
							break;
						}
					}

					if (!stack.isEmpty()) {
						if (list.size() >= freeSlots) {
							return false;
						}

						list.add(stack);
					}
				} else if (remainingSpaceSlot == -1) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Server {@code ServerPlaceRecipe.clearGrid} — grid contents go back to the inventory before
	 * the fill. On the client the vanilla drop-on-overflow is not replicated: when the inventory
	 * cannot hold everything the prediction aborts and the server reply (broadcast diffs) takes
	 * over, so no phantom item entities are spawned locally.
	 */
	private static boolean clearGrid(AbstractCraftingMenu menu, Inventory inventory) {
		AbstractCraftingMenuAccessor accessor = (AbstractCraftingMenuAccessor) menu;

		for (Slot slot : menu.getInputGridSlots()) {
			ItemStack stack = slot.getItem().copy();
			if (!stack.isEmpty() && !placeItemBackInInventory(inventory, stack)) {
				return false;
			}

			slot.set(stack);
		}

		accessor.menupredict$resultSlots().clearContent();
		accessor.menupredict$craftSlots().clearContent();
		return true;
	}

	private static boolean placeItemBackInInventory(Inventory inventory, ItemStack stack) {
		while (!stack.isEmpty()) {
			int slot = inventory.getSlotWithRemainingSpace(stack);
			if (slot == -1) {
				slot = inventory.getFreeSlot();
			}

			if (slot == -1) {
				return false;
			}

			int space = stack.getMaxStackSize() - inventory.getItem(slot).getCount();
			inventory.add(slot, stack.split(space));
		}

		return true;
	}

	private static void showGhostRecipe(Minecraft minecraft, RecipeDisplay display) {
		if (minecraft.screen instanceof RecipeUpdateListener recipeUpdateListener) {
			recipeUpdateListener.fillGhostRecipe(display);
		}
	}
}
