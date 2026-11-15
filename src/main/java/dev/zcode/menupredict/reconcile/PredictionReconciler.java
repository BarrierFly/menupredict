package dev.zcode.menupredict.reconcile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Compares what a local prediction wrote with the authoritative value the server sends back.
 *
 * <p>Every prediction registers the values it wrote (slot stacks and/or menu data slots) together
 * with the action it belongs to. The {@code ClientPacketListener} hooks feed the server's replies
 * in; instead of judging each packet at once, the latest reply per slot is kept and the verdict is
 * taken once the replies have settled, because one action's outcome can span several packets.
 *
 * <p>A prediction is dropped quietly (never reported) when it can no longer be verified:
 * <ul>
 * <li>the player clicks again and changes one of the slots it was waiting for (the local state has
 * moved on, so a pending reply belongs to the older state);</li>
 * <li>the server never answers — it ignored the action, or had nothing to send.</li>
 * </ul>
 *
 * <p>Predictions that run while a server packet is being applied are not registered: those are
 * recomputations of authoritative data, not user actions that ask for a reply.
 */
public final class PredictionReconciler {
	/** Replies of one action can span a few packets; wait this long for them to settle. */
	private static final int SETTLE_TICKS = 6;
	/** No verdict is possible any more after this long; the action is silently forgotten. */
	private static final int TIMEOUT_TICKS = 60;
	/** A contradiction is only reported while the expectation is plausibly its cause. */
	private static final int MISMATCH_MAX_AGE_TICKS = 40;
	private static final int MAX_CHECKS = 32;

	private static final List<Check> CHECKS = new ArrayList<>();

	/**
	 * A menu whose interactions the server drops (a not-yet-confirmed predicted open sends them
	 * with the placeholder container id). Nothing written on it can be answered by a reply, so
	 * its predictions are not registered; the predicted open itself is reported directly.
	 */
	private static AbstractContainerMenu suppressedMenu;

	private static int ticks;
	private static int serverUpdateDepth;
	private static int nextCheckId;

	private PredictionReconciler() {
	}

	/** Marks the start of a client-bound packet that applies authoritative menu contents. */
	public static void beginServerUpdate() {
		serverUpdateDepth++;
	}

	public static void endServerUpdate() {
		if (serverUpdateDepth > 0) {
			serverUpdateDepth--;
		}
	}

	/** True while a server packet is writing authoritative menu data; predictions then expect no reply. */
	public static boolean isApplyingServerUpdate() {
		return serverUpdateDepth > 0;
	}

	/**
	 * Marks (or clears) the one menu whose interactions the server currently drops. Predictions
	 * made on it are local only, so registering them would always end in an unconfirmed check.
	 */
	public static void suppress(AbstractContainerMenu menu) {
		suppressedMenu = menu;
	}

	private static boolean isSuppressed(AbstractContainerMenu menu) {
		return menu == suppressedMenu || isApplyingServerUpdate();
	}

	/**
	 * Registers the stacks a prediction wrote into the given menu slots. Empty stacks are kept:
	 * "the server should now have nothing here" is a prediction worth verifying too.
	 */
	public static void expectSlots(String action, AbstractContainerMenu menu, Map<Integer, ItemStack> expected) {
		if (isSuppressed(menu) || expected.isEmpty()) {
			return;
		}

		Map<Integer, ItemStack> copy = new HashMap<>();
		expected.forEach((slot, stack) -> copy.put(slot, stack.copy()));

		Check latest = latestFor(menu, action);
		if (latest != null && latest.slots != null && sameStacks(latest.slots, copy)) {
			// The same prediction is repeated (e.g. any inventory change re-runs the result slot);
			// keep the original wait instead of stacking identical checks.
			return;
		}

		add(new Check(action, menu, copy, null));
	}

	/**
	 * Registers the menu slots whose value changed between the two snapshots. A prediction only
	 * produces a server reply for the slots it actually modified (the server broadcasts diffs
	 * against what the client last acknowledged), so unchanged slots must not be waited for.
	 */
	public static void expectChangedSlots(String action, AbstractContainerMenu menu, Map<Integer, ItemStack> before, Map<Integer, ItemStack> after) {
		if (isSuppressed(menu) || after.isEmpty()) {
			return;
		}

		Map<Integer, ItemStack> changed = new HashMap<>();
		after.forEach((slot, stack) -> {
			ItemStack previous = before.get(slot);
			if (previous == null || !ItemStack.matches(previous, stack)) {
				changed.put(slot, stack.copy());
			}
		});

		expectSlots(action, menu, changed);
	}

	/**
	 * Registers the menu data slots whose value the prediction changed. Only changed values are
	 * sent back by the server, so only those can be verified; values the prediction did not touch
	 * were already authoritative and prove nothing. A prediction that overwrites the option rows
	 * supersedes the pending one (the player changed the input again), so the old check is dropped.
	 */
	public static void expectChangedData(String action, AbstractContainerMenu menu, Map<Integer, Integer> before, Map<Integer, Integer> after) {
		if (isSuppressed(menu) || after.isEmpty()) {
			return;
		}

		Map<Integer, Integer> changed = new HashMap<>();
		after.forEach((id, value) -> {
			Integer previous = before.get(id);
			if (previous == null || !previous.equals(value)) {
				changed.put(id, value);
			}
		});

		if (changed.isEmpty()) {
			// Nothing the player can see changed (e.g. adding lapis to the table): the previous
			// prediction is still the one the server will answer.
			return;
		}

		discardData(menu);
		expectData(action, menu, changed);
	}

	/** Registers the menu data slot values (costs, clues) a prediction wrote. */
	public static void expectData(String action, AbstractContainerMenu menu, Map<Integer, Integer> expected) {
		if (isSuppressed(menu) || expected.isEmpty()) {
			return;
		}

		add(new Check(action, menu, null, new HashMap<>(expected)));
	}

	/** Reports a prediction verified by something other than slot/data replies (the menu open). */
	public static void report(String action, ReconcileToast.Outcome outcome) {
		ReconcileToast.show(action, outcome);
	}

	/**
	 * The server explicitly refused a recipe fill ({@code ClientboundPlaceGhostRecipePacket}):
	 * an authoritative contradiction of what the client placed locally.
	 */
	public static void reportGhostRecipe(AbstractContainerMenu menu, String action) {
		Check check = latestFor(menu, action);
		if (check == null) {
			return;
		}

		ReconcileToast.show(check.action, ReconcileToast.Outcome.MISMATCH);
		CHECKS.remove(check);
	}

	/** Drops everything registered for a menu; used when a predicted menu is bound/rolled back. */
	public static void discard(AbstractContainerMenu menu) {
		CHECKS.removeIf(check -> check.menu == menu);
	}

	/**
	 * Drops the pending data slot checks of a menu. Used when an action makes the predicted option
	 * values obsolete (an enchant click reseeds the table), so there is nothing left to compare.
	 */
	public static void discardData(AbstractContainerMenu menu) {
		CHECKS.removeIf(check -> check.menu == menu && check.data != null);
	}

	/** Snapshot of the menu's slot contents, taken before a local click is applied. */
	public static Map<Integer, ItemStack> snapshot(AbstractContainerMenu menu) {
		Map<Integer, ItemStack> snapshot = new HashMap<>();

		for (int i = 0; i < menu.slots.size(); i++) {
			snapshot.put(i, menu.slots.get(i).getItem().copy());
		}

		return snapshot;
	}

	/** Ids of the checks that exist right now; predictions made later are not part of this set. */
	public static List<Integer> currentCheckIds() {
		List<Integer> ids = new ArrayList<>(CHECKS.size());

		for (Check check : CHECKS) {
			ids.add(check.id);
		}

		return ids;
	}

	/**
	 * Called after a local click was applied. Predictions that were waiting for the server on a
	 * slot this click changed can no longer be verified — the local state moved on before the
	 * answer arrived — so they are dropped quietly. Checks created by this very click (they are
	 * not in {@code olderCheckIds}) are unaffected.
	 */
	public static void onLocalClick(List<Integer> olderCheckIds, Map<Integer, ItemStack> before, AbstractContainerMenu menu) {
		List<Integer> changedSlots = new ArrayList<>();

		for (int i = 0; i < menu.slots.size(); i++) {
			ItemStack previous = before.get(i);
			ItemStack current = menu.slots.get(i).getItem();
			if (previous == null || !ItemStack.matches(previous, current)) {
				changedSlots.add(i);
			}
		}

		CHECKS.removeIf(check -> check.menu == menu
			&& olderCheckIds.contains(check.id)
			&& check.slots != null
			&& !check.slots.isEmpty()
			&& check.slots.keySet().stream().anyMatch(changedSlots::contains));
	}

	public static void onSlot(AbstractContainerMenu menu, int slot, ItemStack actual) {
		for (Check check : CHECKS) {
			if (check.menu == menu && check.slots != null && check.slots.containsKey(slot)) {
				// Keep the newest value; the verdict is taken after the replies settle.
				check.actualSlots.put(slot, actual.copy());
				check.lastReplyTicks = ticks;
			}
		}
	}

	public static void onData(AbstractContainerMenu menu, int id, int actual) {
		for (Check check : CHECKS) {
			if (check.menu == menu && check.data != null && check.data.containsKey(id)) {
				check.actualData.put(id, actual);
				check.lastReplyTicks = ticks;
			}
		}
	}

	/**
	 * Full container contents: authoritative for every slot of the menu at once, so every pending
	 * prediction of that menu records the values it was waiting for.
	 */
	public static void onContent(AbstractContainerMenu menu, List<ItemStack> items) {
		for (Check check : CHECKS) {
			if (check.menu != menu || check.slots == null || check.slots.isEmpty()) {
				continue;
			}

			for (int slot : check.slots.keySet()) {
				ItemStack actual = slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
				check.actualSlots.put(slot, actual.copy());
			}

			check.lastReplyTicks = ticks;
		}
	}

	/** Client tick: settles the pending checks and reports their verdict. */
	public static void tick() {
		ticks++;
		ReconcileToast.tick();

		// Safety net: a packet handler that aborted early must not leave the flag (and the
		// suppression it causes) stuck forever.
		serverUpdateDepth = 0;

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) {
			CHECKS.clear();
			suppressedMenu = null;
			return;
		}

		if (suppressedMenu != null && suppressedMenu != player.containerMenu) {
			suppressedMenu = null;
		}

		// Checks only make sense for the menus that can still receive replies.
		CHECKS.removeIf(check -> check.menu != player.containerMenu && check.menu != player.inventoryMenu);

		Iterator<Check> iterator = CHECKS.iterator();
		while (iterator.hasNext()) {
			Check check = iterator.next();
			boolean settled = check.lastReplyTicks >= 0 && ticks - check.lastReplyTicks >= SETTLE_TICKS;
			boolean expired = ticks - check.createdTicks >= TIMEOUT_TICKS;

			if (!settled && !expired) {
				continue;
			}

			if (settled) {
				if (hasContradiction(check)) {
					showIfFresh(check, ReconcileToast.Outcome.MISMATCH);
				} else if (check.data != null) {
					// Every value the server actually sent was predicted correctly. Values it left
					// untouched are unchanged, so there is nothing else to wait for.
					ReconcileToast.show(check.action, ReconcileToast.Outcome.MATCH);
				} else if (check.actualSlots.keySet().containsAll(check.slots.keySet())) {
					// The server answered for every slot the prediction wrote.
					ReconcileToast.show(check.action, ReconcileToast.Outcome.MATCH);
				}
			}

			iterator.remove();
		}
	}

	private static boolean hasContradiction(Check check) {
		if (check.data != null) {
			for (Map.Entry<Integer, Integer> entry : check.actualData.entrySet()) {
				Integer expected = check.data.get(entry.getKey());
				if (expected != null && !expected.equals(entry.getValue())) {
					return true;
				}
			}

			return false;
		}

		for (Map.Entry<Integer, ItemStack> entry : check.actualSlots.entrySet()) {
			ItemStack expected = check.slots.get(entry.getKey());
			if (expected != null && !ItemStack.matches(expected, entry.getValue())) {
				return true;
			}
		}

		return false;
	}

	private static void showIfFresh(Check check, ReconcileToast.Outcome outcome) {
		if (ticks - check.createdTicks <= MISMATCH_MAX_AGE_TICKS) {
			ReconcileToast.show(check.action, outcome);
		}
	}

	private static void add(Check check) {
		CHECKS.add(check);

		while (CHECKS.size() > MAX_CHECKS) {
			CHECKS.remove(0);
		}
	}

	private static Check latestFor(AbstractContainerMenu menu, String action) {
		for (int i = CHECKS.size() - 1; i >= 0; i--) {
			Check check = CHECKS.get(i);
			if (check.menu == menu && check.action.equals(action)) {
				return check;
			}
		}

		return null;
	}

	private static boolean sameStacks(Map<Integer, ItemStack> left, Map<Integer, ItemStack> right) {
		if (left.size() != right.size()) {
			return false;
		}

		for (Map.Entry<Integer, ItemStack> entry : left.entrySet()) {
			ItemStack other = right.get(entry.getKey());
			if (other == null || !ItemStack.matches(entry.getValue(), other)) {
				return false;
			}
		}

		return true;
	}

	/** One predicted action waiting for the server's authoritative values. */
	private static final class Check {
		final int id = nextCheckId++;
		final String action;
		final AbstractContainerMenu menu;
		/** Expected slot stacks; null for data checks. */
		final Map<Integer, ItemStack> slots;
		/** Expected data slot values; null for slot checks. */
		final Map<Integer, Integer> data;
		final Map<Integer, ItemStack> actualSlots = new HashMap<>();
		final Map<Integer, Integer> actualData = new HashMap<>();
		final int createdTicks = ticks;
		int lastReplyTicks = -1;

		Check(String action, AbstractContainerMenu menu, Map<Integer, ItemStack> slots, Map<Integer, Integer> data) {
			this.action = action;
			this.menu = menu;
			this.slots = slots;
			this.data = data;
		}
	}
}
