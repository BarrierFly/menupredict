package dev.zcode.menupredict.predict;

import dev.zcode.menupredict.MenuPredictClient;
import dev.zcode.menupredict.mixin.EnchantmentMenuAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.block.EnchantingTableBlock;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Predicts the enchanting table options and result. The enchantment seed is a synced DataSlot
 * and {@code getEnchantmentCost}/{@code selectEnchantment} are deterministic, so everything the
 * server derives in {@code EnchantmentMenu.slotsChanged} — costs, clue enchantment and clue
 * level — can be recomputed locally as soon as the item slot changes. Clicking a button applies
 * the enchantment (item, lapis, experience) locally the same way the server's
 * {@code access.execute} block will; the server's SetSlot/DataSlot/experience replies reconcile.
 */
public final class EnchantPredictor {
	/**
	 * Menus whose enchantment seed has been received via a SetData packet. The seed only exists
	 * server-side, so a predicted-open menu must not compute options from the placeholder seed 0;
	 * normal opens always receive the seed before any user interaction can change a slot.
	 */
	private static final Set<EnchantmentMenu> SEED_SYNCED = Collections.newSetFromMap(new WeakHashMap<>());

	private EnchantPredictor() {
	}

	public static void markSeedSynced(EnchantmentMenu menu) {
		SEED_SYNCED.add(menu);
		// The item may already sit in the slot (predicted open under latency): predict now.
		onSlotsChanged(menu);
	}

	public static void onSlotsChanged(EnchantmentMenu menu) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || menu != player.containerMenu) {
			return;
		}

		if (!SEED_SYNCED.contains(menu)) {
			return;
		}

		BlockPos tablePos = MenuPredictClient.getEnchantTablePos(menu);
		if (tablePos == null || !(level.getBlockState(tablePos).getBlock() instanceof EnchantingTableBlock)) {
			return;
		}

		Container enchantSlots = ((EnchantmentMenuAccessor) menu).menupredict$enchantSlots();
		ItemStack item = enchantSlots.getItem(0);
		if (item.isEmpty() || !item.isEnchantable()) {
			return;
		}

		IdMap<Holder<Enchantment>> idMap = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
		Optional<HolderSet.Named<Enchantment>> candidates = level.registryAccess()
			.lookupOrThrow(Registries.ENCHANTMENT)
			.get(EnchantmentTags.IN_ENCHANTING_TABLE);
		int bookshelfCount = countBookshelves(level, tablePos);
		int seed = menu.getEnchantmentSeed();
		RandomSource random = RandomSource.create();

		random.setSeed(seed);

		for (int i = 0; i < 3; i++) {
			menu.costs[i] = EnchantmentHelper.getEnchantmentCost(random, i, bookshelfCount, item);
			menu.enchantClue[i] = -1;
			menu.levelClue[i] = -1;
			if (menu.costs[i] < i + 1) {
				menu.costs[i] = 0;
			}
		}

		for (int i = 0; i < 3; i++) {
			if (menu.costs[i] > 0) {
				List<EnchantmentInstance> list = enchantList(random, seed, i, item, menu.costs[i], candidates);
				if (!list.isEmpty()) {
					EnchantmentInstance instance = list.get(random.nextInt(list.size()));
					menu.enchantClue[i] = idMap.getId(instance.enchantment());
					menu.levelClue[i] = instance.level();
				}
			}
		}
	}

	/**
	 * Called after the vanilla local button validation succeeded ({@code clickMenuButton} returned
	 * true). Replicates the server-side enchantment application so the enchanted item is usable
	 * immediately; server replies stay authoritative.
	 */
	public static void onEnchantClicked(EnchantmentMenu menu, Player player, int button) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || menu != minecraft.player.containerMenu || player != minecraft.player) {
			return;
		}

		Container enchantSlots = ((EnchantmentMenuAccessor) menu).menupredict$enchantSlots();
		ItemStack item = enchantSlots.getItem(0);
		ItemStack lapis = enchantSlots.getItem(1);
		int lapisAmount = button + 1;
		Optional<HolderSet.Named<Enchantment>> candidates = minecraft.level.registryAccess()
			.lookupOrThrow(Registries.ENCHANTMENT)
			.get(EnchantmentTags.IN_ENCHANTING_TABLE);
		List<EnchantmentInstance> list = enchantList(
			RandomSource.create(), menu.getEnchantmentSeed(), button, item, menu.costs[button], candidates
		);
		if (list.isEmpty()) {
			return;
		}

		player.onEnchantmentPerformed(item, lapisAmount);
		if (item.is(Items.BOOK)) {
			item = item.transmuteCopy(Items.ENCHANTED_BOOK);
			enchantSlots.setItem(0, item);
		}

		for (EnchantmentInstance instance : list) {
			item.enchant(instance.enchantment(), instance.level());
		}

		lapis.consume(lapisAmount, player);
		if (lapis.isEmpty()) {
			enchantSlots.setItem(1, ItemStack.EMPTY);
		}

		enchantSlots.setChanged();
	}

	private static List<EnchantmentInstance> enchantList(
		RandomSource random,
		int seed,
		int button,
		ItemStack item,
		int cost,
		Optional<HolderSet.Named<Enchantment>> candidates
	) {
		random.setSeed(seed + button);
		if (candidates.isEmpty()) {
			return List.of();
		}

		List<EnchantmentInstance> list = EnchantmentHelper.selectEnchantment(random, item, cost, candidates.get().stream());
		if (item.is(Items.BOOK) && list.size() > 1) {
			list.remove(random.nextInt(list.size()));
		}

		return list;
	}

	private static int countBookshelves(ClientLevel level, BlockPos tablePos) {
		int count = 0;

		for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
			if (EnchantingTableBlock.isValidBookShelf(level, tablePos, offset)) {
				count++;
			}
		}

		return count;
	}
}
