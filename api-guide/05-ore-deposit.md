# 矿床注册（OreRegistry / OreDeposit）

TRC 的矿床是**岩性驱动**的：先在 `Cell` 里算出这层岩石是什么岩性，再按岩性绑矿种。
附属不需要改地形，只要登记"哪种岩性出什么矿"。

## `OreDeposit`

```java
public record OreDeposit(int lithology, BlockState ore, int veinSize, double richness)

public OreDeposit(int lithology, BlockState ore)   // 便捷：veinSize=9, richness=0.72
```

| 参数 | 含义 |
|---|---|
| `lithology` | 目标岩性编码（见下表） |
| `ore` | 矿块状态（石变体；深板岩变体由放置端按映射表处理） |
| `veinSize` | 典型矿脉规模（放置步数参考） |
| `richness` | 成矿丰度 0–1，越高越易富集 |

## 岩性编码

对应 `StrataDensityFunction.Lithology`（`getLithology()` 返回同一套编号），
常量定义在 `StrataDensityFunction.Lithology`：

| 值 | 常量 | 岩性 |
|---|---|---|
| 0 | `NONE` | 未定（默认） |
| 1 | `GRANITE` | 花岗岩 |
| 2 | `BASALT` | 玄武岩 |
| 3 | `LIMESTONE` | 石灰岩 |
| 4 | `SANDSTONE` | 砂岩 |
| 5 | `SHALE` | 页岩 |
| 6 | `GNEISS` | 片麻岩 |
| 值 | 常量 | 岩性 |
|---|---|---|
| 7 | `SLATE` | 板岩（深层玄武岩替换） |
| 8 | `DEEPSLATE` | 深板岩 |
| 9 | `BEDROCK` | 基岩 |

> 写代码时直接引用 `StrataDensityFunction.Lithology.GRANITE` 等常量，别硬编码数字。

## 注册

```java
AutoCloseable register(OreDeposit deposit)

List<OreDeposit> byLithology(int lithology)    // 空列表，不返回 null
@Nullable OreDeposit findByOre(BlockState ore) // 按矿块反查（引用比较）
int size()
```

`findByOre` 用 `==` 比较 `BlockState`——方块注册表里的 `BlockState` 是复用实例，所以安全且快。

## 内置矿床（TRC 默认注册）

| 岩性 | 矿 | veinSize | richness |
|---|---|---|---|
| 1 花岗岩 | 金矿 | 9 | 0.72 |
| 3 石灰岩 | 铁矿 | 9 | 0.72 |
| 4 砂岩 | 煤矿 | 9 | 0.72 |
| 4 砂岩 | 钻石矿 | 7 | 0.55 |

## 示例：给花岗岩加祖母绿

```java
TerrainRecastApi.getOreRegistry().register(
        new OreDeposit(1, Blocks.EMERALD_ORE.defaultBlockState(), 6, 0.35));
```

## 示例：按岩性批量生成

```java
var reg = TerrainRecastApi.getOreRegistry();
reg.register(new OreDeposit(2, Blocks.IRON_ORE.defaultBlockState(), 12, 0.8));   // 玄武岩富铁
reg.register(new OreDeposit(2, Blocks.COPPER_ORE.defaultBlockState(), 10, 0.6));
reg.register(new OreDeposit(6, Blocks.REDSTONE_ORE.defaultBlockState(), 8, 0.5)); // 片麻岩红石
```

## 注意

- **不要在生成线程里注册**：注册表是 `CopyOnWriteArrayList`，写很贵。只在 `onInitialize` /
  `SERVER_STARTING` 里注册。
- **不要 `clear()`**：会清掉 TRC 内置矿床，导致默认矿物消失。
- 矿块状态必须来自 `Blocks.*`（已注册的方块），不要自己 `new BlockState`。
- 放置由 `TerrainOreDepositFeature` 消费；它只在**自定义群系**上运行（当前群系已回原版，
  因此该地物实际不产出——登记仍安全，见 [08](08-pitfalls.md)）。
