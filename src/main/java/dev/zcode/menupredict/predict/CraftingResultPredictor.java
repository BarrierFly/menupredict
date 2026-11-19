package dev.zcode.menupredict.predict;

import dev.zcode.menupredict.mixin.AbstractCraftingMenuAccessor;
import dev.zcode.menupredict.reconcile.PredictionReconciler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
 *
 * <p>Two reply-side rules keep that reconciliation honest:
 * <ul>
 * <li>one local click registers one check. A click moves the grid through intermediate states
 * (a take empties it cell by cell), and the server computes an answer for every one of them, but
 * it answers the finished click, not its steps — so only the click's final answer is registered
 * ({@link #endLocalClick});</li>
 * <li>a result-slot reply that is stale (computed for a grid state that no longer exists, because
 * the client already acted on) must not regress the display — {@link #reassertAfterResultReply}
 * writes the current grid's fresh answer back over it. Grid slots self-correct through
 * {@code slotsChanged} on their own; the result container fires no callback, so the result slot
 * is the one slot that needs this explicit re-assert.</li>
 * </ul>
 */
public final class CraftingResultPredictor {
	public static final String ACTION = "menupredict.action.crafting_result";
	/** Result slot index is 0 in both {@code CraftingMenu} and {@code InventoryMenu}. */
	public static final int RESULT_SLOT = 0;

	/** True between the head and the return of one local mouse click; see {@link #endLocalClick}. */
	private static boolean insideLocalClick;

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
	 * Opens the per-click window: predictions caused by the grid writes inside one click are
	 * display-only and are not registered, so the click's intermediate answers (empty grid, slab,
	 * pressure plate, button ...) never become checks the server would "contradict".
	 */
	public static void beginLocalClick() {
		insideLocalClick = true;
	}

	/**
	 * Closes the per-click window and registers the one prediction the finished click produced:
	 * the current grid's definitive answer, when it differs from the pre-click value. An
	 * unresolvable final grid claims nothing, exactly like the standalone prediction path.
	 */
	public static void endLocalClick(AbstractContainerMenu menu, Map<Integer, ItemStack> before) {
		insideLocalClick = false;
		if (!(menu instanceof AbstractCraftingMenu craftingMenu)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}

		Optional<ItemStack> predicted = compute(craftingMenu, minecraft);
		if (predicted.isEmpty()) {
			return;
		}

		ItemStack previous = before.get(RESULT_SLOT);
		ItemStack asserted = predicted.get();
		if (previous == null || !ItemStack.matches(previous, asserted)) {
			PredictionReconciler.expectSlots(ACTION, craftingMenu, Map.of(RESULT_SLOT, asserted.copy()));
		}
	}

	/**
	 * Re-asserts the current grid's answer after a server reply for the result slot was applied.
	 *
	 * <p>The server pushes a result for every intermediate grid state — every grid write, and
	 * every single ingredient removal while it processes a take. Once the client runs ahead of
	 * those replies (which is this mod's whole purpose), they arrive for grid states that no
	 * longer exist and vanilla's verbatim application regresses the display to a past answer:
	 * the bamboo button a 1-plank grid yields, landing after the grid was filled or taken. When
	 * the current grid still has a definitive local answer, that answer — not the stale one — is
	 * what the player is looking at. A grid the client cannot resolve (unknown or ambiguous
	 * recipe) keeps the authoritative packet value, like the no-prediction fallback.
	 *
	 * <p>The packet is fed to the reconciler before this runs, so a genuine same-state divergence
	 * between the client's replica and the server is still reported; the re-assert only decides
	 * what the display shows while the divergence stands.
	 */
	public static void reassertAfterResultReply(AbstractCraftingMenu menu, Minecraft minecraft) {
		if (PlaceRecipePredictor.isPlacing() || minecraft.level == null) {
			return;
		}

		Optional<ItemStack> predicted = compute(menu, minecraft);
		if (predicted.isEmpty()) {
			return;
		}

		AbstractCraftingMenuAccessor accessor = (AbstractCraftingMenuAccessor) menu;
		ItemStack current = accessor.menupredict$resultSlots().getItem(RESULT_SLOT);
		ItemStack asserted = predicted.get();
		if (!ItemStack.matches(current, asserted)) {
			accessor.menupredict$resultSlots().setItem(RESULT_SLOT, asserted.copy());
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
		if (insideLocalClick || predicted.isEmpty()) {
			// Inside one click the grid passes through intermediate answers; the click as a whole
			// is registered once by endLocalClick, not step by step.
			return;
		}

		ItemStack after = predicted.get();
		if (!ItemStack.matches(before, after)) {
			PredictionReconciler.expectSlots(ACTION, menu, Map.of(RESULT_SLOT, after));
		}
	}

	/** Computes and writes the result slot for the current grid state, without registering anything. */
	public static Optional<ItemStack> predict(AbstractCraftingMenu menu, Minecraft minecraft) {
		if (PlaceRecipePredictor.isPlacing()) {
			return Optional.empty();
		}

		Optional<ItemStack> resolved = compute(menu, minecraft);
		ItemStack result = resolved.orElse(ItemStack.EMPTY);
		((AbstractCraftingMenuAccessor) menu).menupredict$resultSlots().setItem(RESULT_SLOT, result);
		return resolved;
	}

	/**
	 * Computes the answer for the current grid without writing anything: empty when the client
	 * cannot know it (a non-empty grid whose recipe is not in the synced recipe book — a
	 * documented limitation of this mod), present (possibly empty) when the answer is definitive.
	 */
	private static Optional<ItemStack> compute(AbstractCraftingMenu menu, Minecraft minecraft) {
		AbstractCraftingMenuAccessor accessor = (AbstractCraftingMenuAccessor) menu;
		CraftingInput input = accessor.menupredict$craftSlots().asCraftInput();
		if (input.isEmpty()) {
			return Optional.of(ItemStack.EMPTY);
		}

		return RecipeMatcher.match(input, RecipeMatcher.knownEntries(), minecraft.level)
			.flatMap(match -> RecipeMatcher.resolveSingleResult(match.entry(), minecraft.level));
	}
}
