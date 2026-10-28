# 读取地形数据

## 两种读法

| 方法 | 用途 | 返回 |
|---|---|---|
| `TerrainRecastApi.queryCell(chunkX, chunkZ)` | 按区块取整块板块数据 | `CellDataView`，没算过就是 `null` |
| `getCellLookup().sampleField(field, blockX, blockZ)` | 按方块坐标取**连续场**值 | `double` |

`queryCell` 只读缓存，**不会**触发计算——区块没生成过就返回 `null`。
需要"任意坐标都能算"时用 `sampleField`，它是纯解析计算，不生成区块（`/terrainrecast peak` 就是靠它扫 2 万格）。

```java
// 区块级：整块数据
CellDataView cell = TerrainRecastApi.queryCell(0, 0);
if (cell != null) {
    System.out.println(cell.getTerrainHeight() + " / " + cell.getLithology());
}

// 方块级：连续场，随时可算
double h = TerrainRecastApi.getCellLookup()
        .sampleField(Cell.Field.TERRAIN_HEIGHT, 1234, -5678);
```

## `CellDataView` 字段表

全部方法无副作用、线程安全（快照语义）。

| 方法 | 类型 | 实际区间 | 语义 |
|---|---|---|---|
| `getContinentId()` | int | `-1` 或 `0+` | 深海为 -1，其余是板块编号 |
| `getPlateBoundaryDistance()` | double | 0–1 | 0 = 正压在板块边界，1 = 板块深处（除以 `EDGE_SPAN`） |
| `getTerrainHeight()` | double | 见下 | **相对海平面**的地形高度，单位方块 |
| `getSteepness()` | double | 0–1 | 陡峭度，0 平地 1 悬崖 |
| `getTemperature()` | double | -1–1 | 温度；纬度带 + 扰动 − 海拔递减（`(h-100)×0.0015`） |
| `getMoisture()` | double | 0–1 | 湿度 |
| `getContinentalness()` | double | -1–1 | **直接绑定高度**：`h / coast_span`，>0 陆地、<0 海洋 |
| `getErosion()` | double | -1–1 | 侵蚀：-1 山高谷深，+1 平坦 |
| `getWeirdness()` | double | -1–1 | 怪异度噪声 |
| `getLithology()` | int | 1–9 | 岩性编码，见 [05](05-ore-deposit.md) |
| `getBiomeCandidate()` | int | 0–8 | Whittaker 群系粗分类，见 `Cell.Biome` |

### 高度怎么换算成 Y

`getTerrainHeight()` 是**相对海平面**（Y=63）的高度：

```
地表 Y ≈ getTerrainHeight() + 63
```

`/terrainrecast peak` 输出里的"高度"就是这个值，"地表 Y"是加上 63 之后的结果。
实测最高峰 h≈1728（Y≈1791），世界上限 Y=2544。

### 高度不是任何地方都"平滑"

`sampleField` 对 `TERRAIN_HEIGHT` 走的是解析高度场，与真实方块地形一致（误差 ≤9 格）。
但要注意：**地形高度受出生点保护影响**，出生点 900 格内被压平成平原（h≈20 左右），
这不是 bug，是设计。

## `Cell.Field` 全部取值

```java
public enum Field {
    PLATE_BOUNDARY_DISTANCE, TERRAIN_HEIGHT, STEEPNESS,
    TEMPERATURE, MOISTURE, CONTINENTALNESS, EROSION, WEIRDNESS,
    RIVER_FLOW, RIVER_BAND, EROSION_AMOUNT,
    LITHOLOGY, BIOME,
    LANDFORM, BIOME_FAMILY
}
```

其中四个字段**不是从 `Cell` 读的**，而是采样时现算（这些字段在 `Cell` 里恒为 0）：

| 字段 | 现算内容 | 消费方 |
|---|---|---|
| `LANDFORM` | 六类地貌编码，见下表 | 覆写的 `overworld/ridges` → 群系表的 weirdness 分带 |
| `BIOME_FAMILY` | 700 格低频噪声 | 覆写的 `overworld/erosion`（当前群系表已回原版，仅保留给自定义群系用） |
| `CONTINENTALNESS` | `clamp(h/coast_span, -1, 1)` | 群系表海陆闸门 |
| `BIOME` | `WhittakerBiome.classify(t, m, h)` | 调试/附属查询 |

### LANDFORM 编码（重要）

| 常量 | 值 | 含义 |
|---|---|---|
| `LANDFORM_CODE_RIFT` | -0.6 | 裂谷 |
| `LANDFORM_CODE_BASIN` | -0.15 | 盆地 |
| `LANDFORM_CODE_PLAIN` | 0.0 | 平原 |
| `LANDFORM_CODE_HILLS` | 0.10 | 丘陵 |
| `LANDFORM_CODE_PLATEAU` | 0.35 | 高原 |
| `LANDFORM_CODE_ARC` | 0.7 | 火山弧 / 山墙 |

判定顺序（先命中先返回）：海面以下 → PLAIN；`riftMask>0.40` → RIFT；`arcMask>0.30` → ARC；
`plateauMask>0.45` → PLATEAU；`basinMask>0.40` → BASIN；`|relief|>0.25` 且非边界带 → HILLS；否则 PLAIN。

## 性能提示

`sampleField` 每次调用会做 Worley 邻域 + 多个 fbm，单次约 **0.5–1 µs**（2 万格扫描 12 ms 可推算）。
在每 tick 循环里对几千个方块调用就会明显卡顿，请缓存结果或降到低频（例如按 16 格步长采样）。
`queryCell` 是纯缓存读，开销可忽略。
