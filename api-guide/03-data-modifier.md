# 数据修改器（DataModifier）

这是 TRC 最强大的开放点：不碰核心代码，就能**改写任何地形字段的采样值**。

## 模型：责任链

```
RealCellLookup.sampleField(field, x, z)
  ├─ 算出原值 raw
  ├─ 链为空？ → 直接 return raw            （零开销）
  └─ 链非空 → 按 priority 从高到低依次调用 modifier.modify(ctx)
        ├─ 返回非 null → 拍板，立刻返回该值（后续节点不再执行）
        └─ 返回 null   → "不关我事"，交给下一个
        └─ 全都没拍板 → 返回 raw
```

**首个非 null 决定终值**，所以想"叠加别人算好的结果"是做不到的——链条不是流水线，而是一组互斥的候选。
需要叠加时，把逻辑写在同一个 modifier 里。

## 注册

```java
AutoCloseable register(int priority, DataModifier modifier)
AutoCloseable registerFirst(DataModifier modifier)   // = register(Integer.MAX_VALUE, ...)
AutoCloseable registerLast(DataModifier modifier)    // = register(Integer.MIN_VALUE, ...)
```

- 优先级**大的先执行**，数值相同按注册顺序。
- 想"覆盖默认行为"用 `registerFirst`（比如接管所有 `TERRAIN_HEIGHT`）。
- 想"兜底，别人都不管时才管"用 `registerLast`。
- 返回的 `AutoCloseable` 用于撤销，`close()` 幂等。

## 上下文

```java
record ModifyContext(int blockX, int blockZ, Cell.Field field,
                     double original, CellDataView cell)
```

| 成员 | 说明 |
|---|---|
| `blockX` / `blockZ` | 方块坐标（不是区块坐标） |
| `field` | 当前正在采样的字段 |
| `original` | 核心算出的原值 |
| `cell` | 最近板块的只读视图，**可能为 `null`**（区块未算过时） |

## 线程契约（必读）

1. **在世界生成线程上调用**，不是主线程。不要在里面读游戏状态（玩家、方块实体、主线程集合）。
2. **必须无状态、可重入**：同一个 modifier 会被多个区块线程并发调用，禁止写实例字段。
3. **禁止递归**：不要在 modifier 里再调 `CellLookup.sampleField(...)` 或 `queryCell(...)`——
   `sampleField` 出口就是调用你的地方，会造成无限递归。要用其他字段，就用 `ctx.cell()`，
   或自己用 `ctx.blockX()/blockZ()` 算（独立噪声）。
4. **极热**：每个区块每个采样列、每个字段都会走一遍。不要分配对象、不要拼接字符串、不要打日志。

## 示例 1：把高山整体再拔高 15%

```java
TerrainRecastApi.getPipeline().register(100, ctx -> {
    if (ctx.field() != Cell.Field.TERRAIN_HEIGHT) return null;
    double h = ctx.original();
    if (h <= 800) return null;              // 低于 800 不管
    return h * 1.15;                        // 拍板
});
```

注意：这样会让 h 超过 1860 时被密度函数的软上限压回来（`MAX_TERRAIN_HEIGHT=1860`），
实际效果是"更高的山更陡"，而不是无限长高。

## 示例 2：把深海填浅一点

```java
TerrainRecastApi.getPipeline().registerFirst(ctx -> {
    if (ctx.field() != Cell.Field.TERRAIN_HEIGHT) return null;
    double h = ctx.original();
    if (h >= 0) return null;                // 只管水下
    return h * 0.6;                         // 负值 ×0.6 = 变浅
});
```

## 示例 3：给某个坐标范围加一座人工山

```java
TerrainRecastApi.getPipeline().register(50, ctx -> {
    if (ctx.field() != Cell.Field.TERRAIN_HEIGHT) return null;
    double dx = ctx.blockX() - 1000, dz = ctx.blockZ() - 2000;
    double d2 = dx * dx + dz * dz;
    if (d2 > 400 * 400) return null;        // 400 格以外不管
    double falloff = 1.0 - Math.sqrt(d2) / 400.0;
    return ctx.original() + 350.0 * falloff * falloff;   // 平滑圆锥
});
```

这类"人造地形"逻辑完全由解析式决定，是确定性的，不会随区块加载顺序变化。

## 示例 4：拦截 LANDFORM 给群系分带加一档

`LANDFORM` 是给群系表用的 `weirdness` 分带码（见 [02](02-cell-data.md#landform-编码重要)）：

```java
TerrainRecastApi.getPipeline().register(10, ctx -> {
    if (ctx.field() != Cell.Field.LANDFORM) return null;
    // 只在玩家自定义的"圣地"区域把地貌改写成高原码
    if (isSacredRegion(ctx.blockX(), ctx.blockZ())) return RealCellLookup.LANDFORM_CODE_PLATEAU;
    return null;
});
```

要真正改变群系，你必须同时提供自己的 `dimension/overworld.json` 群系表（因为它按 weirdness 分带）。
只改 LANDFORM 而不改群系表，只会改变"哪一档 biome 参数"，效果取决于当前表。

## 调试

- `/terrainrecast info` 会打印当前配置与脚下地形字段，先确认你的 modifier 有没有被调用。
- 临时加 `if (ctx.field() == X) logOnce(...)` 计数（用 `AtomicLong` 到 1000 次打印一次），
  不要每列都打日志。
- 如果地形出现区块级台阶，说明你的返回值**跨区块不连续**——地形密度函数只做三线性插值，
  插值点是区块级的；返回不连续的函数就会得到断崖。

## 卸载

```java
AutoCloseable handle = TerrainRecastApi.getPipeline().register(100, mod);
// 你的附属被禁用/世界关闭（由你自己决定时机）
handle.close();
```

不要调用 `getPipeline().clear()`——那会清掉其他所有附属的注册。
