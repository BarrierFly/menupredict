package dev.zcode.menupredict.mixin;

import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractCraftingMenu.class)
public interface AbstractCraftingMenuAccessor {
	@Accessor("craftSlots")
	CraftingContainer menupredict$craftSlots();

	@Accessor("resultSlots")
	ResultContainer menupredict$resultSlots();
}
