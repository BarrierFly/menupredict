package dev.zcode.menupredict.predict;

import dev.zcode.menupredict.MenuPredictClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Predicts the menu open itself. Vanilla only shows a functional-block screen once the server's
 * {@code ClientboundOpenScreenPacket} arrives (one round trip); under latency the click seems to
 * do nothing. The menu structure, title, and initial contents (player inventory + empty grid) of
 * a crafting/enchanting table are client-known, so the screen is opened locally right away:
 *
 * <ul>
 * <li>the interaction packet is still sent and the server still decides whether to open;</li>
 * <li>when its OpenScreen packet arrives, the predicted menu is rebound to the server-assigned
 * container id and the following SetContent/SetData packets land on it — no screen flicker;</li>
 * <li>if the server refuses (no reply, e.g. a protection plugin), the screen is rolled back after
 * a timeout and locally moved items return to the inventory;</li>
 * <li>interactions while the real id is unknown are sent with the placeholder id and dropped by
 * the server, so no server-side state is ever created from a prediction.</li>
 * </ul>
 */
public final class PredictedOpen {
	/** Server container ids are {@code counter % 100 + 1}, so -1 never collides with a real id. */
	private static final int PREDICTED_CONTAINER_ID = -1;
	private static final int CONFIRM_TIMEOUT_TICKS = 100;

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
		if (state.getBlock() instanceof CraftingTableBlock) {
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
	}

	/**
	 * Called right before vanilla creates the screen from {@code ClientboundOpenScreenPacket}.
	 * Returns true when a matching predicted screen took over the server menu (vanilla screen
	 * creation is skipped); false lets vanilla proceed.
	 */
	public static boolean onServerOpenScreen(Minecraft minecraft, MenuType<?> menuType, int containerId) {
		PendingOpen current = pending;
		if (current == null) {
			return false;
		}

		pending = null;
		LocalPlayer player = minecraft.player;
		boolean screenStillOurs = player != null && minecraft.screen == current.screen && player.containerMenu == current.menu;
		if (screenStillOurs && current.menu.getType() == menuType) {
			current.menu.containerId = containerId;
			return true;
		}

		if (screenStillOurs) {
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
			return;
		}

		if (++current.ticks >= CONFIRM_TIMEOUT_TICKS) {
			pending = null;
			rollback(minecraft, current);
		}
	}

	private static void rollback(Minecraft minecraft, PendingOpen current) {
		LocalPlayer player = minecraft.player;
		if (player != null) {
			restorePredictedItems(current.menu, player.getInventory());
		}

		if (minecraft.screen == current.screen) {
			current.screen.onClose();
		}
	}

	/**
	 * Items the player moved into the predicted menu while the real container id was unknown
	 * exist only client-side (their clicks were sent with the placeholder id and dropped), so
	 * they are returned to the inventory on rollback.
	 */
	private static void restorePredictedItems(AbstractContainerMenu menu, Inventory inventory) {
		if (menu instanceof CraftingMenu craftingMenu) {
			for (Slot slot : craftingMenu.getInputGridSlots()) {
				restoreSlot(slot, inventory);
			}

			craftingMenu.getResultSlot().set(ItemStack.EMPTY);
		} else if (menu instanceof EnchantmentMenu enchantmentMenu) {
			restoreSlot(enchantmentMenu.getSlot(0), inventory);
			restoreSlot(enchantmentMenu.getSlot(1), inventory);
		}
	}

	private static void restoreSlot(Slot slot, Inventory inventory) {
		ItemStack stack = slot.getItem();
		if (stack.isEmpty()) {
			return;
		}

		while (!stack.isEmpty()) {
			int target = inventory.getSlotWithRemainingSpace(stack);
			if (target == -1) {
				target = inventory.getFreeSlot();
			}

			if (target == -1) {
				inventory.add(stack);
				break;
			}

			int space = stack.getMaxStackSize() - inventory.getItem(target).getCount();
			inventory.add(target, stack.split(space));
		}

		slot.set(ItemStack.EMPTY);
	}

	private static final class PendingOpen {
		final AbstractContainerMenu menu;
		final Screen screen;
		int ticks;

		PendingOpen(AbstractContainerMenu menu, Screen screen) {
			this.menu = menu;
			this.screen = screen;
		}
	}
}
