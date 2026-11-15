# MenuPredict

针对《功能方块菜单「等服务端 vs 客户端可预知」分析报告》中的 **A 类动作**（要等服务端回包、但客户端信息已齐、完全可本地预知）的客户端侧预测 mod。**只支持 Minecraft 1.21.11 + Fabric，纯客户端。**

原版在这些动作上把计算放在服务端，客户端只能空等 1 个网络往返（RTT）；本 mod 从打开界面这一步起就在客户端替服务端把同样的确定性计算先算出来，让玩家可以立刻执行下一步合成，服务端回包到达后按原版机制对账。

## 预测范围（对应报告 A 类四项）

| A 类动作 | 本 mod 的做法 | 生效时机 |
|---|---|---|
| **打开工作台 / 附魔台界面本身** | 右键方块交互被消费后，立即用客户端已知的菜单结构（本地构造 `CraftingMenu`/`EnchantmentMenu` + 原版标题）打开界面，不再等服务端 `ClientboundOpenScreenPacket`；交互包照发，服务端仍拥有"是否打开"的决定权。服务端回包到达时把已打开的菜单**重绑**到服务端分配的容器 id（占位 id -1 不会与服务端 1~100 的分配冲突），后续 SetContent/SetData 直接落在同一界面上，无闪烁；服务端拒绝（如保护插件）时 5 秒超时回滚，预测期间移入格子的物品退回背包 | 右键当帧 |
| 工作台 / 背包 2x2 合成格的产物槽显示 | 网格每次变化时，用客户端同步的配方数据（`ClientRecipeBook` 中的 `RecipeDisplayEntry`：`RecipeDisplay` + 真实 `Ingredient` 列表）复刻服务端 `RecipeManager.getRecipeFor` 的匹配语义（trim 后尺寸/逐格 Ingredient 测试/镜像对称/无序多重匹配），解析产物后立即写入结果槽 | 网格变化当帧，不再等显式 SetSlot |
| 配方书点配方填格（placeRecipe） | 在 `ServerboundPlaceRecipePacket` 发出前，客户端完整复刻服务端 `ServerPlaceRecipe`：`testClearGrid` → `canCraft` → 最大合成数与堆叠上限钳制 → 清格余料回背包 → `PlaceRecipeHelper` 居中填充；缺料时本地直接显示幽灵配方 | 点击当帧，不再等填充回包 |
| 附魔台三个选项首次显示（费用 / 星星提示） | `useItemOn` 记录被点击的方块位置（预测打开则直接绑定）；物品放入网格后用同步的附魔种子（DataSlot）复算 `getEnchantmentCost` 与 clue。种子是纯服务端随机数，未收到首条种子 SetData 前不显示选项（避免用种子 0 算出错误数值） | 放入物品当帧，不再等 DataSlot 回包 |
| 附魔结果（点 1/2/3 按钮后物品实际附魔） | `clickMenuButton` 本地校验通过后，用 `seed + 按钮号` 确定性复算完整附魔列表并立即应用（附魔物品、书→附魔书转换、扣青金石、扣经验），随后可立刻继续下一步 | 点击当帧 |

## 对账模型（与服务端回包核对）

预测**只写菜单槽位、附魔公开字段，不改 `stateId`**（预测打开的容器 id 重绑除外，见上），因此服务端保留完整权威：

- 服务端正常执行同样的确定性逻辑后，其 `broadcastChanges`/显式 SetSlot/DataSlot/经验包按原样到达，把客户端状态覆盖为权威值（预测正确时内容一致，无感）。
- 服务端拒绝（距离校验、配方未解锁、并发改动等）时不产生槽位改动；客户端下一次点击的 `changedSlots` 哈希与服务端实际状态冲突，触发服务端 `broadcastFullState` 全量回滚——与原版对普通点击预测的兜底机制完全一致。
- **预测打开**：确认前用户的所有菜单交互都带占位 id -1 发出，服务端因 containerId 不匹配整包忽略，不会产生任何服务端状态；服务端接受则无缝重绑，拒绝则超时回滚。

### 一致性提示

每次预测都会把「本地写入了什么」登记下来，服务端对应的 SetSlot / SetContent / SetData 回包到达后按槽位比对，并在界面顶部闪过一条约 0.9 秒的提示：

| 提示 | 触发条件 |
|---|---|
| 绿色「xxx 与服务端一致」 | 服务端回包的值与本地预测完全相同（配方填充会比对整个网格 + 产物槽；附魔选项比对变化的费用/线索 DataSlot；附魔结果比对物品与青金石槽） |
| 红色「xxx 与服务端不一致」 | 回包值与预测矛盾；或服务端明确拒绝（`ClientboundPlaceGhostRecipePacket` 表示填格被拒；预测打开在 100 tick 内等不到 OpenScreen 回包、或回包类型不匹配时立即报不一致并回滚） |
| 无提示 | 服务端对该槽位/该数据没有回包（值本就相同无需同步、客户端未知配方的回退），或玩家在回包到达前又做了新操作使旧预测作废 |

为避免误报，比对不是「一条回包立刻定性」：同一动作的回包要等 6 tick 稳定后才判定（一次动作的结果可能分成多个包发送）；玩家再次点击并改动了仍待确认的槽位时，旧预测被静默作废而不是判为不一致；只有 40 tick 内的预测才会给出「不一致」。服务端根本没有回包（值未变化无需同步）时会静默超时，不会报红。预测打开走单独路径：确认时直接报一致，无回包时在 5 秒超时处报不一致。

## 构成

- Mixin（全部注册在客户端段，仅客户端加载）：
  - `CraftingMenuMixin` / `InventoryMenuMixin` — `slotsChanged` 注入产物预测（以 `containerMenu`/`inventoryMenu` 身份校验过滤同进程的整合服务端菜单）
  - `MultiPlayerGameModeMixin` — `handlePlaceRecipe` 注入填格预测；`useItemOn` 尾部记录方块位置并触发预测打开；`handleInventoryMouseClick` 前后快照菜单，作废被新点击取代的待确认预测
  - `EnchantmentMenuMixin` — `slotsChanged` 复算选项；`clickMenuButton` 尾部应用附魔
  - `ClientPacketListenerMixin` — OpenScreen 到达前重绑预测菜单（跳过原版建屏）、打开后绑定预测状态、SetData 标记附魔种子已同步；SetSlot/SetContent/SetData/PlaceGhostRecipe 回包送入对账
  - `AbstractContainerScreenMixin` — 驱动预测打开的确认/回滚计时；玩家提前关屏时清理待确认状态
  - `GuiMixin` / `ScreenMixin` — 推进对账超时，并在 HUD / 容器界面顶部绘制一致性提示
  - `AbstractCraftingMenuAccessor` / `EnchantmentMenuAccessor` / `ClientRecipeBookAccessor`
- Access Widener：`AbstractContainerMenu.containerId` 去 final（预测菜单重绑服务端 id 用）
- 预测实现：`RecipeMatcher`（网格↔配方匹配、产物解析）、`CraftingResultPredictor`、`PlaceRecipePredictor`、`EnchantPredictor`、`PredictedOpen`（预测打开）
- 对账实现：`PredictionReconciler`（登记预测、收集回包、定性并防误报）、`ReconcileToast`（淡出提示绘制）；文案在 `assets/menupredict/lang`（`en_us` / `zh_cn`）

## 构建

```
./gradlew build
```

产物：`build/libs/menupredict-1.2.0.jar`。依赖仅 Fabric Loader（不依赖 Fabric API）；Gradle 9.5.1 + Loom 1.13.3 + 官方 mojang 映射，Java 21。

## 已知边界（如实说明）

- **配方可见范围**：1.21.2+ 协议只向客户端同步「已解锁进配方书」的配方条目（`ClientRecipeBook.known`），未解锁或特殊配方（`craftingRequirements` 为空）不在本地匹配范围内——这些场景自动回退原版「等服务端」行为；服务端产物 SetSlot 到达后照常显示。
- **预测打开的窗口期**：服务端确认（OpenScreen 回包）前，用户在预测界面里的交互都带占位 id -1，服务端整包忽略；确认时界面无缝重绑，但重绑前的本地操作会被服务端初始 SetContent 覆盖（服务端从未执行过这些操作），用户需重做一次——严格优于"盯着空白界面干等"。服务端明确拒绝（无回包）时 5 秒（100 tick）超时回滚并退回物品。
- **附魔种子**：种子是服务端随机数、纯服务端持有，预测打开的界面在收到首条种子 SetData 前不显示三个选项（原版在正常打开时随初始 DataSlot 一并同步，不受影响）。
- **结果歧义**：产物显示解析出多个候选堆叠（`resolveForStacks` 多结果）时不预测，交由服务端。
- **背包放不下的清格**：清格余料若连背包都装不下（服务端会直接掉地上），客户端不预测该场景，避免本地生成幽灵掉落物，由服务端回包修正。
- **附魔种子时差**：附魔后服务端立即换新种子，新种子经 DataSlot 约 1 RTT 后同步；在此间隔内放入物品的本地复算会沿用旧种子，服务端 DataSlot 到达后覆盖纠正。
- **服务端权威校验失败**（距离、快照差异等）时同样依赖下一次交互的全量回滚，与原版点击预测一致。
- 本 mod 通过了编译级验证（`gradlew build` 成功、mixin 目标与 access widener 均已 remap 到 intermediary），**尚未在真实游戏内做过运行期测试**，首次使用请在测试存档中先行验证。
- **一致性提示的判定边界**：提示只能反映「服务端实际发回了什么」。服务端若只是静默忽略某个槽位同步（值没变、或包被插件拦掉），对应槽位不会得到单独确认，判定以「服务端发出的部分是否全部吻合」为准；预测打开被服务端拒绝（无回包）时按 5 秒超时给出红色提示并回滚，不会长时间沉默。

## 分析依据

所有行为均逐行核对工作区 `analysis/` 与 guardian 仓库 1.21.11（mojmap/vineflower）反编译源码得出，无凭记忆断言。

## 版本

- **1.2.0** — 新增：预测结果一致性提示（服务端回包与本地预测一致/不一致时在界面顶部短暂提示，含防误报的定性策略与中英文案）。
- **1.1.0** — 新增：预测打开工作台/附魔台界面（右键当帧开屏、重绑服务端容器 id、拒绝超时回滚）；附魔选项预测加种子同步门控。
- **1.0.0** — 首版：合成产物槽、配方书填格、附魔选项与结果预测。
