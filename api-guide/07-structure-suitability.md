# 结构适宜性（StructureSuitability）

> **现状警告**：`StructureSuitability` 目前**只有接口定义，没有任何实现类，也没有接入结构放置管线**。
> 它是一个为未来准备开放点，现在实现它**不会生效**。本文说明契约与预期用法，避免误用。

## 接口

```java
@FunctionalInterface
public interface StructureSuitability {
    boolean isSuitable(CellView cell, PlacementContext config);

    record PlacementContext(String structureId, int blockX, int blockZ, int surfaceY) {}

    interface CellView {
        int lithology();
        double steepness();
        double terrainHeight();
        double moisture();
        double temperature();
    }
}
```

## 契约解读

| 成员 | 说明 |
|---|---|
| `isSuitable` | 返回 `true` 表示该地理位置适合放置此结构 |
| `PlacementContext.structureId` | 结构命名空间 id，如 `minecraft:village_plains` |
| `PlacementContext.surfaceY` | 已算好的地表 Y，可直接用于判定"是否在地表" |
| `CellView` | 只暴露判定所需的 5 个字段（接口隔离），不含完整 `CellDataView` |

设计意图（来自设计文档 7.2）：

- 村庄只在低侵蚀平原、神殿只在沙漠、要塞只在特定岩性……
- 把地学约束从"原版 flatten 检查"提升到"板块/气候层"，避免结构卡在山壁上。

## 未来会怎么被调用

预期形态（尚未实现）：

```java
StructureSuitability suit = registry.get(structId);
if (suit != null && !suit.isSuitable(new MyCellView(cell), ctx)) {
    // 跳过这个候选位置
}
```

接入点应在结构放置阶段的"候选位置筛选"处（`StructurePlacement` / chunk generator 的
`getNearestStructurePos`）。TRC 目前没有改这部分。

## 现在能做什么替代

如果你的附属需要在 TRC 地形上放置结构：

1. **用原版结构集 + 自己写 `StructureSuitability` 风格的检查**，挂在 Fabric 的
   `ServerLifecycleEvents` 或 `ServerChunkEvents` 上做后处理（例如放置后检测并替换）。
2. **用自定义 `StructurePlacement`**（26.3 支持 `concentric_rings` / `random_spread` 等），
   在地形平坦度上用 `terrain_height` 密度函数做过滤。
3. **直接读数据自己判定**：

```java
CellLookup lookup = TerrainRecastApi.getCellLookup();
double h = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, x, z);
double steep = lookup.sampleField(Cell.Field.STEEPNESS, x, z);
if (h > 60 && steep < 0.2) {
    // 平原候选
}
```

## 什么时候会补上

见交接文档 P2 列表。补上时会在此文档标注 API 版本与调用时机；在那之前请用上面的替代方案。
