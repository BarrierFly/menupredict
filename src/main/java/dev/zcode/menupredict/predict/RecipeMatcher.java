package dev.zcode.menupredict.predict;

import dev.zcode.menupredict.mixin.ClientRecipeBookAccessor;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replicates the parts of server-side crafting matching that can be derived from the data the
 * client has: {@code RecipeDisplayEntry} (display + synced {@link Ingredient} list) instead of a
 * full server {@code RecipeManager}.
 *
 * <p>Semantics follow 1.21.11 {@code ShapedRecipePattern.matches} / {@code ShapelessRecipe.matches}
 * operating on the bounding-box-trimmed {@link CraftingInput}:
 * <ul>
 * <li>shaped: trimmed input dimensions must equal the display dimensions and every cell must
 * test against the synced ingredient (blank cells must stay empty); both orientations are
 * accepted, which equals vanilla's symmetrical check;</li>
 * <li>shapeless: ingredient count must equal the input count and the multiset must satisfy
 * {@code StackedItemContents.canCraft} on the input's own stacked contents.</li>
 * </ul>
 */
final class RecipeMatcher {
	private RecipeMatcher() {
	}

	record GridMatch(IntList slotsToIngredientIndex, int recipeWidth, int recipeHeight, RecipeDisplayEntry entry) {
	}

	static Map<RecipeDisplayId, RecipeDisplayEntry> knownEntries() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return Map.of();
		}

		ClientRecipeBook recipeBook = minecraft.player.getRecipeBook();
		return ((ClientRecipeBookAccessor) recipeBook).menupredict$known();
	}

	/**
	 * Finds the known recipe entry matching the given trimmed crafting input. When more than one
	 * known entry matches (possible with datapack recipes that overlap on one grid), the server
	 * picks the first one in its own registry order — an order the client cannot reproduce — so
	 * the match is only returned when every candidate resolves to the same result; otherwise the
	 * grid is left to the server, exactly like the multi-result ambiguity of {@code resolveForStacks}.
	 */
	static Optional<GridMatch> match(CraftingInput input, Map<RecipeDisplayId, RecipeDisplayEntry> entries, ClientLevel level) {
		GridMatch first = null;
		for (RecipeDisplayEntry entry : entries.values()) {
			GridMatch match = matchEntry(entry, input);
			if (match == null) {
				continue;
			}

			if (first != null && !resultsAgree(first, match, level)) {
				return Optional.empty();
			}

			if (first == null) {
				first = match;
			}
		}

		return Optional.ofNullable(first);
	}

	/** True when both matching entries resolve to the same single result stack. */
	private static boolean resultsAgree(GridMatch left, GridMatch right, ClientLevel level) {
		Optional<ItemStack> leftResult = resolveSingleResult(left.entry(), level);
		Optional<ItemStack> rightResult = resolveSingleResult(right.entry(), level);
		return leftResult.isPresent()
			&& rightResult.isPresent()
			&& ItemStack.matches(leftResult.get(), rightResult.get());
	}

	/** Matches one entry against the input; returns null when it does not match. */
	static GridMatch matchEntry(RecipeDisplayEntry entry, CraftingInput input) {
		Optional<List<Ingredient>> requirements = entry.craftingRequirements();
		if (requirements.isEmpty() || requirements.get().isEmpty() || input.isEmpty()) {
			return null;
		}

		List<Ingredient> ingredients = requirements.get();
		RecipeDisplay display = entry.display();
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			if (input.width() != shaped.width() || input.height() != shaped.height()) {
				return null;
			}

			if (input.ingredientCount() != ingredients.size()) {
				return null;
			}

			IntList mapping = reconstructShapedMapping(shaped, ingredients);
			if (mapping == null) {
				return null;
			}

			boolean matches = matchesShaped(input, ingredients, mapping, shaped.width(), false)
				|| matchesShaped(input, ingredients, mapping, shaped.width(), true);
			return matches ? new GridMatch(mapping, shaped.width(), shaped.height(), entry) : null;
		} else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			// ShapelessRecipe.matches requires the trimmed input's item count to equal the
			// ingredient count exactly: extra items in the grid mean no match, even though the
			// multiset of any subset would satisfy canCraft.
			if (input.ingredientCount() != ingredients.size() || ingredients.size() != shapeless.ingredients().size()) {
				return null;
			}

			return input.stackedContents().canCraft(ingredients, null)
				? new GridMatch(identityList(ingredients.size()), input.width(), input.height(), entry)
				: null;
		}

		return null;
	}

	/**
	 * Reproduces {@code PlacementInfo.createFromOptionals} in display terms: blank display cells
	 * map to -1, non-blank cells map to sequential indices of the synced ingredient list.
	 */
	static IntList reconstructShapedMapping(ShapedCraftingRecipeDisplay display, List<Ingredient> ingredients) {
		IntList mapping = new IntArrayList(display.width() * display.height());
		int index = 0;

		for (SlotDisplay cell : display.ingredients()) {
			if (cell instanceof SlotDisplay.Empty) {
				mapping.add(-1);
			} else {
				if (index >= ingredients.size()) {
					return null;
				}

				mapping.add(index++);
			}
		}

		return index == ingredients.size() ? mapping : null;
	}

	private static boolean matchesShaped(CraftingInput input, List<Ingredient> ingredients, IntList mapping, int width, boolean mirrored) {
		int height = mapping.size() / width;

		for (int row = 0; row < height; row++) {
			for (int col = 0; col < width; col++) {
				int cell = mirrored ? width - col - 1 + row * width : col + row * width;
				int ingredientIndex = mapping.getInt(cell);
				ItemStack stack = input.getItem(col, row);
				if (ingredientIndex == -1 ? !stack.isEmpty() : !ingredients.get(ingredientIndex).test(stack)) {
					return false;
				}
			}
		}

		return true;
	}

	private static IntList identityList(int size) {
		IntList list = new IntArrayList(size);

		for (int i = 0; i < size; i++) {
			list.add(i);
		}

		return list;
	}

	/**
	 * Resolves the display result to a single concrete stack, mirroring the server's
	 * {@code recipe.assemble} + enabled-features check. Ambiguous (multi-stack) or unresolvable
	 * results are not predicted so vanilla keeps handling them.
	 */
	static Optional<ItemStack> resolveSingleResult(RecipeDisplayEntry entry, ClientLevel level) {
		List<ItemStack> results = entry.resultItems(SlotDisplayContext.fromLevel(level));
		if (results.size() != 1) {
			return Optional.empty();
		}

		ItemStack result = results.get(0).copy();
		return result.isItemEnabled(level.enabledFeatures()) ? Optional.of(result) : Optional.empty();
	}
}
