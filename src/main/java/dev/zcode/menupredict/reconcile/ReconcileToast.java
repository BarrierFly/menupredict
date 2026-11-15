package dev.zcode.menupredict.reconcile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * The short-lived overlay that flashes the verification result of the last predicted action:
 * green "prediction matched" or red "server result differed". It fades out quickly, so it stays
 * informational without covering the menu for long. Repeated confirmations of the same action
 * (a crafting spree) are collapsed; contradictions always show.
 */
public final class ReconcileToast {
	/** How the server's reply related to what was predicted. */
	public enum Outcome {
		MATCH,
		MISMATCH
	}

	private static final long VISIBLE_MS = 900L;
	private static final long FADE_MS = 300L;
	/** Consecutive confirmations of the same action are reported at most this often. */
	private static final long MATCH_COALESCE_MS = 1500L;

	private static long shownAt = -1L;
	private static String shownAction = "";
	private static Outcome shownOutcome = Outcome.MATCH;
	private static Component message = Component.empty();

	/** Last announced confirmation per action, used to collapse repeats. */
	private static String lastMatchAction = "";
	private static long lastMatchAt = -1L;

	private ReconcileToast() {
	}

	public static void show(String actionKey, Outcome outcome) {
		long now = Util.getMillis();
		if (outcome == Outcome.MATCH) {
			if (now - lastMatchAt < MATCH_COALESCE_MS && lastMatchAction.equals(actionKey)) {
				return;
			}

			lastMatchAction = actionKey;
			lastMatchAt = now;
		}

		shownAction = actionKey;
		shownOutcome = outcome;
		message = Component.translatable(translationKey(outcome), Component.translatable(actionKey));
		shownAt = now;
	}

	/** Expires the message once its display time is over. */
	public static void tick() {
		if (shownAt >= 0 && Util.getMillis() - shownAt >= VISIBLE_MS) {
			shownAt = -1L;
		}
	}

	/**
	 * Draws the message centered near the top of the screen; a no-op when nothing is showing.
	 * The game HUD and the open screen are separate render passes, so callers pass which pass
	 * owns the toast (the screen when one is open, the HUD otherwise).
	 */
	public static void render(GuiGraphics guiGraphics, boolean screenPass) {
		Minecraft minecraft = Minecraft.getInstance();
		if (shownAt < 0 || minecraft.player == null || minecraft.options.hideGui) {
			return;
		}

		if ((minecraft.screen != null) != screenPass) {
			return;
		}

		long elapsed = Util.getMillis() - shownAt;
		if (elapsed >= VISIBLE_MS) {
			shownAt = -1L;
			return;
		}

		float fade = elapsed <= VISIBLE_MS - FADE_MS ? 1.0F : (VISIBLE_MS - elapsed) / (float) FADE_MS;
		int alpha = Mth.clamp((int) (fade * 255.0F), 0, 255);
		int textColor = ARGB.color(alpha, shownOutcome == Outcome.MATCH ? 0x55FF55 : 0xFF5555);
		int backgroundColor = ARGB.color((int) (alpha * 0.4F), 0x000000);

		Font font = minecraft.font;
		int width = font.width(message);
		int left = guiGraphics.guiWidth() / 2 - width / 2;
		int top = 6;
		guiGraphics.fill(left - 4, top - 3, left + width + 4, top + 9, backgroundColor);
		guiGraphics.drawString(font, message, left, top, textColor, true);
	}

	private static String translationKey(Outcome outcome) {
		return outcome == Outcome.MATCH ? "menupredict.reconcile.match" : "menupredict.reconcile.mismatch";
	}
}
