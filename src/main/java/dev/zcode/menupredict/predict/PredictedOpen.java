package dev.zcode.menupredict.predict;

import dev.zcode.menupredict.MenuPredictClient;
import dev.zcode.menupredict.reconcile.PredictionReconciler;
import dev.zcode.menupredict.reconcile.ReconcileToast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.CartographyTableScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Predicts the menu open itself. Vanilla only shows a functional-block screen once the server's
 * {@code ClientboundOpenScreenPacket} arrives (one round trip); under latency the click seems to
 * do nothing. For every functional block whose menu structure and initial contents (player
 * inventory + empty block slots) are client-known — crafting table, enchanting table, smithing
 * table, anvil, stonecutter, grindstone, loom and cartography table — the screen is opened
 * locally right away, using the same two-arg menu constructor vanilla's own
 * {@code MenuScreens.create} uses on the client:
 *
 * <ul>
 * <li>the interaction packet is still sent and the server still decides whether to open;</li>
 * <li>when its OpenScreen packet arrives, the predicted menu is rebound to the server-assigned
 * container id and the authoritative title, and the following SetContent/SetData packets land on
 * it — no screen flicker;</li>
 * <li>if the server refuses (no reply, e.g. a protection plugin), the screen is rolled back after
 * a timeout: every slot the player changed locally during the window is restored to its original
 * value, so moved items return to their original inventory slots and items taken from a predicted
 * crafting result are removed again; closing the unconfirmed screen undoes the same way;</li>
 * <li>interactions while the real id is unknown are sent with the placeholder id and dropped by
 * the server, so no server-side state is ever created from a prediction.</li>
 * </ul>
 */
public final class PredictedOpen {
	/** Server container ids are {@code counter % 100 + 1}, so -1 never collides with a real id. */
	private static final int PREDICTED_CONTAINER_ID = -1;
	private static final int CONFIRM_TIMEOUT_TICKS = 100;
	public static final String ACTION = "menupredict.action.open_screen";

	private static PendingOpen pending;

	private PredictedOpen() {
	}

	public static void onBlockUseResult(Minecraft minecraft, Level level, BlockState state, BlockPos pos, InteractionResult result) {
		LocalPlayer player = minecraft.player;
		if (player == null || level == null || minecraft.screen != null || pending != null) {
			return;
		}

		// Sneak-use skips the block interaction server-side; only a consumed block use opens a menu.
		if (result == null || !result.consumesAction() || player.isSecondaryUseActive()) {
			return;
		}

		AbstractContainerMenu menu;
		AbstractContainerScreen<?> screen;
		// SmithingTableBlock extends CraftingTableBlock, so it must be tested first or a smithing
		// table would open a predicted crafting screen that the server's SMITHING menu then
		// contradicts.
		if (state.getBlock() instanceof SmithingTableBlock) {
			SmithingMenu smithingMenu = new SmithingMenu(PREDICTED_CONTAINER_ID, player.getInventory());
			menu = smithingMenu;
			screen = new SmithingScreen(smithingMenu, player.getInventory(), Component.translatable("container.upgrade"));
		} else if (state.getBlock() instanceof AnvilBlock) {
			AnvilMenu anvilMenu = new AnvilMenu(PREDICTED_CONTAINER_ID, player.getInventory());
			menu = anvilMenu;
			screen = new AnvilScreen(anvilMenu, player.getInventory(), Component.translatable("container.repair"));
		} else if (state.getBlock() instanceof StonecutterBlock) {
			StonecutterMenu stonecutterMenu = new StonecutterMenu(PREDICTED_CONTAINER_ID, player.getInventory());
			menu = stonecutterMenu;
			screen = new StonecutterScreen(stonecutterMenu, player.getInventory(), Component.translatable("container.stonecutter"));
		} else if (state.getBlock() instanceof GrindstoneBlock) {
			GrindstoneMenu grindstoneMenu = new GrindstoneMenu(PREDICTED_CONTAINER_ID, player.getInventory());
			menu = grindstoneMenu;
			screen = new GrindstoneScreen(grindstoneMenu, player.getInventory(), Component.translatable("container.grindstone_title"));
		} else if (state.getBlock() instanceof LoomBlock) {
			LoomMenu loomMenu = new LoomMenu(PREDICTED_CONTAINER_ID, player.getInventory());
			menu = loomMenu;
			screen = new LoomScreen(loomMenu, player.getInventory(), Component.translatable("container.loom"));
		} else if (state.getBlock() instanceof CartographyTableBlock) {
			CartographyTableMenu cartographyMenu = new CartographyTableMenu(PREDICTED_CONTAINER_ID, player.getInventory());
			menu = cartographyMenu;
			screen = new CartographyTableScreen(cartographyMenu, player.getInventory(), Component.translatable("container.cartography_table"));
		} else if (state.getBlock() instanceof CraftingTableBlock) {
			CraftingMenu craftingMenu = new CraftingMenu(PREDICTED_CONTAINER_ID, player.getInventory());
			menu = craftingMenu;
			screen = new CraftingScreen(craftingMenu, player.getInventory(), Component.translatable("container.crafting"));
		} else if (state.getBlock() instanceof EnchantingTableBlock) {
			EnchantmentMenu enchantmentMenu = new EnchantmentMenu(PREDICTED_CONTAINER_ID, player.getInventory());
			menu = enchantmentMenu;
			screen = new EnchantmentScreen(enchantmentMenu, player.getInventory(), Component.translatable("container.enchant"));
			MenuPredictClient.bindEnchantTablePos(enchantmentMenu, pos);
		} else {
			return;
		}

		player.containerMenu = menu;
		minecraft.setScreen(screen);
		pending = new PendingOpen(menu, screen);
		// Until the server confirms, this menu's clicks carry the placeholder id and are dropped.
		PredictionReconciler.suppress(menu);
	}

	/**
	 * Called when the player closes a screen. If it is the still-unconfirmed predicted one, the
	 * pending state is dropped quietly: the player cancelled the prediction, so there is no
	 * outcome to report, a later open packet must not be matched against it, and the locally
	 * applied changes are undone so the client already shows the server state again.
	 */
	public static void onScreenClosed(AbstractContainerScreen<?> screen) {
		PendingOpen current = pending;
		if (current == null || current.screen != screen) {
			return;
		}

		pending = null;
		undoPredictedChanges(current);
		PredictionReconciler.suppress(null);
		PredictionReconciler.discard(current.menu);
	}

	/**
	 * Called right before vanilla creates the screen from {@code ClientboundOpenScreenPacket}.
	 * Returns true when a matching predicted screen took over the server menu (vanilla screen
	 * creation is skipped); false lets vanilla proceed.
	 */
	public static boolean onServerOpenScreen(Minecraft minecraft, MenuType<?> menuType, int containerId, Component title) {
		PendingOpen current = pending;
		if (current == null) {
			return false;
		}

		pending = null;
		LocalPlayer player = minecraft.player;
		boolean screenStillOurs = player != null && minecraft.screen == current.screen && player.containerMenu == current.menu;
		if (screenStillOurs && current.menu.getType() == menuType) {
			current.menu.containerId = containerId;
			// The server's title is authoritative (a custom-named enchanting table sends a literal
			// one); the predicted screen was opened with the vanilla default translation key.
			current.screen.title = title;
			// Everything written on the placeholder-id menu was never executed by the server; the
			// SetContent that follows the open packet is authoritative for it, so nothing local is
			// left to compare (the open itself is verified here).
			PredictionReconciler.suppress(null);
			PredictionReconciler.discard(current.menu);
			PredictionReconciler.report(ACTION, ReconcileToast.Outcome.MATCH);
			return true;
		}

		if (screenStillOurs) {
			PredictionReconciler.report(ACTION, ReconcileToast.Outcome.MISMATCH);
			rollback(minecraft, current);
		}

		return false;
	}

	/** Confirms, drops, or rolls back the pending predicted screen; runs every container screen tick. */
	public static void tickPending(Minecraft minecraft) {
		PendingOpen current = pending;
		if (current == null) {
			return;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.screen != current.screen || player.containerMenu != current.menu) {
			pending = null;
			PredictionReconciler.suppress(null);
			PredictionReconciler.discard(current.menu);
			return;
		}

		if (++current.ticks >= CONFIRM_TIMEOUT_TICKS) {
			// The server never answered: it refused the interaction (e.g. a protection plugin). The
			// predicted menu never existed server-side, which is a real divergence from what was
			// shown locally, so it is reported before rolling back.
			pending = null;
			PredictionReconciler.report(ACTION, ReconcileToast.Outcome.MISMATCH);
			rollback(minecraft, current);
		}
	}

	private static void rollback(Minecraft minecraft, PendingOpen current) {
		// The undo runs while the menu is still suppressed, so the slot writes it triggers do not
		// register any new reconciliation checks for a menu that is about to be discarded.
		undoPredictedChanges(current);
		PredictionReconciler.suppress(null);
		PredictionReconciler.discard(current.menu);
		if (minecraft.screen == current.screen) {
			current.screen.onClose();
		}
	}

	/**
	 * Records the first local click that changed each slot of the unconfirmed predicted menu, so
	 * a rollback can restore exactly the slots the player touched during the window. This covers
	 * not only items moved into the grid but also crafting takes: taking a predicted result
	 * consumes the grid ingredients and adds the crafted item to the inventory, and none of that
	 * ever happened server-side.
	 */
	public static void recordClickUndo(AbstractContainerMenu menu, Map<Integer, ItemStack> before) {
		PendingOpen current = pending;
		if (current == null || current.menu != menu) {
			return;
		}

		for (int i = 0; i < menu.slots.size(); i++) {
			ItemStack original = before.get(i);
			if (original != null && !ItemStack.matches(original, menu.slots.get(i).getItem())) {
				current.undoSlots.putIfAbsent(i, original.copy());
			}
		}
	}

	/**
	 * Restores every slot the player changed locally during the unconfirmed window to its value
	 * before the first click that changed it, and clears the carried stack. The client view then
	 * converges with the server state, which never executed any of these clicks: moved items
	 * return to their original inventory slots, and consumed grid ingredients and taken results
	 * are undone with them. Server-driven inventory updates that arrived during the window are
	 * kept, because only slots a click actually changed are restored.
	 */
	private static void undoPredictedChanges(PendingOpen current) {
		for (Map.Entry<Integer, ItemStack> entry : current.undoSlots.entrySet()) {
			Slot slot = current.menu.slots.get(entry.getKey());
			if (slot != null) {
				slot.set(entry.getValue().copy());
			}
		}

		current.menu.setCarried(ItemStack.EMPTY);
	}

	private static final class PendingOpen {
		final AbstractContainerMenu menu;
		final Screen screen;
		/** Slot index → the value the slot had before the player's first click changed it while unconfirmed. */
		final Map<Integer, ItemStack> undoSlots = new HashMap<>();
		int ticks;

		PendingOpen(AbstractContainerMenu menu, Screen screen) {
			this.menu = menu;
			this.screen = screen;
		}
	}
}
