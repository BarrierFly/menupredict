package dev.zcode.menupredict;

import dev.zcode.menupredict.predict.EnchantPredictor;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.level.block.EnchantingTableBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-side prediction for the "server answers later" actions of functional block menus
 * (category A of the accompanying analysis report): crafting result display, recipe book
 * placement and the enchanting table options/result. Everything the server would compute at
 * menu-open time and on every slot change is recomputed locally here; server replies remain
 * authoritative and reconcile through the vanilla SetSlot/SetContent/DataSlot handling.
 */
public class MenuPredictClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("menupredict");

	/**
	 * Block position captured from the last {@code useItemOn} interaction. The client-side
	 * enchanting menu is created with a NULL level access, so the table position has to be
	 * tracked this way to be able to count bookshelves locally.
	 */
	private static BlockPos lastUsedBlockPos;

	/** Enchanting table position per open menu, resolved when the screen opens. */
	private static final Map<EnchantmentMenu, BlockPos> ENCHANT_TABLE_POS = Collections.synchronizedMap(new WeakHashMap<>());

	@Override
	public void onInitializeClient() {
		LOGGER.info("MenuPredict client-side prediction enabled (crafting result, recipe placement, enchanting)");
	}

	public static void onBlockUse(BlockPos pos) {
		lastUsedBlockPos = pos;
	}

	/**
	 * Called after the client created a menu from {@code ClientboundOpenScreenPacket}: this is
	 * where prediction state is bound, so every later update is predictable without waiting for
	 * server round trips.
	 */
	public static void onMenuOpened(AbstractContainerMenu menu) {
		if (menu instanceof EnchantmentMenu enchantMenu) {
			Minecraft minecraft = Minecraft.getInstance();
			ClientLevel level = minecraft.level;
			BlockPos pos = lastUsedBlockPos;
			if (level != null && pos != null && level.getBlockState(pos).getBlock() instanceof EnchantingTableBlock) {
				ENCHANT_TABLE_POS.put(enchantMenu, pos);
			}
		}
	}

	public static BlockPos getEnchantTablePos(EnchantmentMenu menu) {
		return ENCHANT_TABLE_POS.get(menu);
	}
}
