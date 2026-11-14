# MenuPredict

针对《功能方块菜单「等服务端 vs 客户端可预知」分析报告》中的 **A 类动作**（要等服务端回包、但客户端信息已齐、完全可本地预知）的客户端侧预测 mod。**只支持 Minecraft 1.21.11 + Fabric，纯客户端。**

原版在这些动作上把计算放在服务端，客户端只能空等 1 个网络往返（RTT）；本 mod 从打开界面这一步起就在客户端替服务端把同样的确定性计算先算出来，让玩家可以立刻执行下一步合成，服务端回包到达后按原版机制对账。

## 预测范围（对应报告 A 类四项）

| A 类动作 | 本 mod 的做法 | 生效时机 |
|---|---|---|
| 工作台 / 背包 2x2 合成格的产物槽显示 | 网格每次变化时，用客户端同步的配方数据（`ClientRecipeBook` 中的 `RecipeDisplayEntry`：`RecipeDisplay` + 真实 `Ingredient` 列表）复刻服务端 `RecipeManager.getRecipeFor` 的匹配语义（trim 后尺寸/逐格 Ingredient 测试/镜像对称/无序多重匹配），解析产物后立即写入结果槽 | 网格变化当帧，不再等显式 SetSlot |
| 配方书点配方填格（placeRecipe） | 在 `ServerboundPlaceRecipePacket` 发出前，客户端完整复刻服务端 `ServerPlaceRecipe`：`testClearGrid` → `canCraft` → 最大合成数与堆叠上限钳制 → 清格余料回背包 → `PlaceRecipeHelper` 居中填充；缺料时本地直接显示幽灵配方 | 点击当帧，不再等填充回包 |
| 附魔台三个选项首次显示（费用 / 星星提示） | `useItemOn` 时记录被点击的方块位置；界面打开即绑定该位置并本地数书架（`isValidBookShelf`），物品放入网格后立即用同步的附魔种子（DataSlot）复算 `getEnchantmentCost` 与 clue | 放入物品当帧，不再等 DataSlot 回包 |
| 附魔结果（点 1/2/3 按钮后物品实际附魔） | `clickMenuButton` 本地校验通过后，用 `seed + 按钮号` 确定性复算完整附魔列表并立即应用（附魔物品、书→附魔书转换、扣青金石、扣经验），随后可立刻继续下一步 | 点击当帧 |

## 对账模型（与服务端回包核对）

预测**只写菜单槽位与附魔公开字段，不碰 `stateId`**，因此服务端保留完整权威：

- 服务端正常执行同样的确定性逻辑后，其 `broadcastChanges`/显式 SetSlot/DataSlot/经验包按原样到达，把客户端状态覆盖为权威值（预测正确时内容一致，无感）。
- 服务端拒绝（距离校验、配方未解锁、并发改动等）时不产生槽位改动；客户端下一次点击的 `changedSlots` 哈希与服务端实际状态冲突，触发服务端 `broadcastFullState` 全量回滚——与原版对普通点击预测的兜底机制完全一致。

## 构成

- Mixin（全部注册在客户端段，仅客户端加载）：
  - `CraftingMenuMixin` / `InventoryMenuMixin` — `slotsChanged` 注入产物预测（以 `containerMenu`/`inventoryMenu` 身份校验过滤同进程的整合服务端菜单）
  - `MultiPlayerGameModeMixin` — `handlePlaceRecipe` 注入填格预测；`useItemOn` 记录方块位置
  - `EnchantmentMenuMixin` — `slotsChanged` 复算选项；`clickMenuButton` 尾部应用附魔
  - `ClientPacketListenerMixin` — `handleOpenScreen` 打开后绑定预测状态（从点开界面这步开始）
  - `AbstractCraftingMenuAccessor` / `EnchantmentMenuAccessor` / `ClientRecipeBookAccessor`
- 预测实现：`RecipeMatcher`（网格↔配方匹配、产物解析）、`CraftingResultPredictor`、`PlaceRecipePredictor`、`EnchantPredictor`

## 构建

```
./gradlew build
```

产物：`build/libs/menupredict-1.0.0.jar`。依赖仅 Fabric Loader（不依赖 Fabric API）；Gradle 9.5.1 + Loom 1.13.3 + 官方 mojang 映射，Java 21。

## 已知边界（如实说明）

- **配方可见范围**：1.21.2+ 协议只向客户端同步「已解锁进配方书」的配方条目（`ClientRecipeBook.known`），未解锁或特殊配方（`craftingRequirements` 为空）不在本地匹配范围内——这些场景自动回退原版「等服务端」行为；服务端产物 SetSlot 到达后照常显示。
- **结果歧义**：产物显示解析出多个候选堆叠（`resolveForStacks` 多结果）时不预测，交由服务端。
- **背包放不下的清格**：清格余料若连背包都装不下（服务端会直接掉地上），客户端不预测该场景，避免本地生成幽灵掉落物，由服务端回包修正。
- **附魔种子时差**：附魔后服务端立即换新种子，新种子经 DataSlot 约 1 RTT 后同步；在此间隔内放入物品的本地复算会沿用旧种子，服务端 DataSlot 到达后覆盖纠正。
- **服务端权威校验失败**（距离、快照差异等）时同样依赖下一次交互的全量回滚，与原版点击预测一致。
- 本 mod 仅通过了编译级验证（`gradlew build` 成功、mixin 目标已 remap 到 intermediary），**尚未在真实游戏内做过运行期测试**，首次使用请在测试存档中先行验证。

## 分析依据

所有行为均逐行核对工作区 `analysis/` 与 guardian 仓库 1.21.11（mojmap/vineflower）反编译源码得出，无凭记忆断言。
