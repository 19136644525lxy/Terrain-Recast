# 踩坑清单

按"最容易踩"排序。每条都来自实际开发中真实出现过的问题。

## 1. `getCellLookup()` 可能是 `null` 或空实现

| 阶段 | 返回 |
|---|---|
| 模组初始化 ~ 服务器启动前 | `NoopCellLookup`（`sampleField` 全返回 0，`getCell` 返回 null） |
| 服务器运行中 | `RealCellLookup` |
| 服务器停止后 | `null` |

**对策**：所有采样点判空；初始化期注册服务（修改器/矿床/地物）不受影响，只有"读数据"要用对时机。

## 2. `queryCell` 返回 `null`

它只读缓存，不触发计算。新区块没生成过就是 `null`。
需要任意坐标可算时用 `sampleField`。

## 3. 在 DataModifier 里递归采样 → 栈溢出

```java
// ❌ 死循环
double h = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, x, z);
```

`sampleField` 出口就是调用你的地方。要用别的字段：

- 用 `ctx.cell()`（可能为 null，注意判空）；
- 或自己用 `ctx.blockX()/blockZ()` 算独立噪声。

## 4. 生成线程里碰了主线程状态

`modify` 在世界生成线程执行。这里 `Minecraft.getInstance()`、玩家、`Level` 方块读写、
`HashMap` 非线程安全容器——全都不安全。

**对策**：modifier 必须纯函数；需要状态就只读不可变快照。

## 5. Feature order cycle

把同一个 placed feature 注入到多个已有该地物的群系，会抛：

```
Feature order cycle found, involved sources: [...]
```

**对策**：见 [04](04-feature-injection.md)。只 `build` 不 `runServer` 是发现不了的。

## 6. 注册表冻结

`Registry.register(BuiltInRegistries.X, ...)` 必须在模组初始化阶段完成。
在 `SERVER_STARTING` 之后注册会抛 `IllegalStateException: Registry is already frozen`。

**对策**：方块/物品/地物类型/密度函数类型都在 `onInitialize` 里注册；数据包内容走 JSON。

## 7. 世界高度被改过

TRC 把世界高度改成 **2544**（原版 384）。任何假设"世界高 384 / 建筑上限 320"的代码都会失效：

- 高度图、`Level.MIN_Y/MAX_Y` 相关缓存；
- 结构生成的高度范围；
- 自定义 Feature 的 `getY` 取样。

**对策**：用 `level.getMinY()` / `getMaxY()` 而不是常量。

## 8. 高度有软上限

地形高度超过 `1200` 后会以指数方式逼近 `1860`（`MAX_TERRAIN_HEIGHT`），
再高的输入不会得到等比例输出。写"把山拔高 3 倍"的修改器时注意收益递减。

## 9. 群系表只能有一个

`data/minecraft/dimension/overworld.json` 是**全文件覆写**：最后加载的那个数据包生效。
如果两个模组都提供这个文件，只有一个会赢（加载顺序决定），另一个的群系表完全消失。

**对策**：附属不要动 `overworld.json`；要加群系请用 `MultiNoiseBiomeSource` 的其它字段，
或与 TRC 协商合并。TRC 当前提供的是 48 条原版群系表，没有兜底项——
**表末加 `{"biome":"minecraft:plains","parameters":{"temperature":[-2,2]}}` 这种兜底项会让全图变平原**。

## 10. 26.3 的命名差异

| 旧写法 | 26.3 |
|---|---|
| `Identifier.of(ns, path)` | `Identifier.fromNamespaceAndPath(ns, path)` |
| `key.location()` | `key.identifier()` |
| `ResourceKey.create(registryKey, id)` | 不变 |
| `BiomeModifications.addFeature` 的 `BiomeSelectionContext` | 不变，但 tag 判断用 `ctx.hasTag(BiomeTags.IS_OVERWORLD)` |

## 11. 不要 `clear()` 全局注册表

`getPipeline().clear()`、`getOreRegistry().clear()`、`getFeatureInjection().clear()`
会清掉 **TRC 自己的**注册项（默认矿床、植被注入……），而且下次进世界不会自动恢复
（`RegistryBootstrap.init()` 只跑一次）。

**对策**：只 `close()` 你自己拿到的 `AutoCloseable`。

## 12. 性能：`sampleField` 不是免费的

单次约 0.5–1 µs。每 tick 扫 4096 个方块 ≈ 2–4 ms，已经能感到卡顿。

**对策**：缓存、降频、或只在区块生成/事件里算一次。

## 13. `/terrainrecast peak` 的半径与步长

- 半径 > 20000 会进入自适应粗扫（`step = radius/96`），可能漏掉窄峰，属正常。
- 输出高度是**相对海平面**，地表 Y ≈ 高度 + 63。
- 默认 `minHeight=800`，某种子可能没有达标峰——命令会回报"实际最高点"而不是报错。

## 14. 附属打包别把 TRC 类打进去

只 `compileOnly` / `modImplementation` 依赖 TRC，不要 `include` 它的类，
否则会出现两份 `TerrainRecastApi`（不同 ClassLoader），注册互不可见。

## 15. 客户端/服务端边界

TRC 的地形逻辑全在服务端。附属如果只在客户端加载，注册的修改器**不会生效**（客户端不跑世界生成）。
`fabric.mod.json` 里地形相关的 entrypoint 应放在 `main`，不是 `client`。
