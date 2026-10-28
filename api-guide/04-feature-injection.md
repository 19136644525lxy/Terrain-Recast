# 地物注入（GroupedFeatureInjection）

TRC 把"往群系里加 placed feature"统一成一张登记表，避免各个模组散落调用
`BiomeModifications.addFeature` 导致时序冲突。

## 基本用法

```java
TerrainRecastApi.getFeatureInjection().add(
        GenerationStep.Decoration.UNDERGROUND_ORES,   // 生成步骤
        myPlacedFeatureKey,                            // ResourceKey<PlacedFeature>
        GroupedFeatureInjection.TRC_BIOMES             // 选择器（可省，默认 OVERWORLD）
);
TerrainRecastApi.getFeatureInjection().apply();        // 应用（幂等）
```

`add` 返回 `AutoCloseable`（撤销登记）。

## 三个选择器

| 常量 | 选中范围 | 用在哪 |
|---|---|---|
| `OVERWORLD` | 带 `minecraft:is_overworld` 标签的全部群系 | 默认；想给所有主世界群系加东西 |
| `TRC_BIOMES` | 命名空间为 `terrain_recast` 的群系 | 本模组自定义群系（当前已下线，选择器仍保留） |
| 自定义 `Predicate<BiomeSelectionContext>` | 任意 | 按名字/标签精确筛选 |

## ⚠️ 最重要的坑：Feature order cycle

原版群系**自带**一批地物。如果你把同一个 placed feature 同时注入"原版群系"和"另一个已有该特性的群系"，
两边特性在生成步骤内部的排序会互相冲突，区块生成时抛：

```
Feature order cycle found, involved sources: [terrain_recast:rift_taiga, minecraft:plains]
```

**规则**：

1. 想加到**原版群系**上，选一个原版群系里**没有同类地物**的步骤，或用一个唯一的新 placed feature
   （不要和原版的 `minecraft:ore_*` 等混用同一 ID）。
2. 加到自定义群系时用 `TRC_BIOMES`，不要用 `OVERWORLD`——否则原版群系和自定义群系会互相污染。
3. 地物冲突只在 `runServer` 冒烟时才会暴露，`build` 通过不代表没问题。

## `apply()` 的时序

- `add` 只是登记，不生效；必须 `apply()`。
- `apply()` **幂等**：已下发的条目不会重复注入，可以放心多调。
- Fabric 各模组 entrypoint 顺序不保证。TRC 在自己的 `onInitialize` 末尾调一次 `apply()`；
  如果你的附属在 TRC 之后初始化，登记完请**自己再调一次** `apply()`。
- `clear()` 只清登记与"已下发"记录，**不会**把已注入 Fabric 的地物撤回（Fabric 没有反注册 API）。

## 完整示例：往地下加一条矿脉带

```java
public final class MyAddon implements ModInitializer {

    private static final ResourceKey<PlacedFeature> MY_VEIN = ResourceKey.create(
            Registries.PLACED_FEATURE,
            Identifier.fromNamespaceAndPath("my_addon", "deep_emerald_vein"));

    @Override
    public void onInitialize() {
        // placed feature 本体由数据包提供：
        // data/my_addon/worldgen/placed_feature/deep_emerald_vein.json
        // 这里只负责“注入到哪些群系”
        TerrainRecastApi.getFeatureInjection().add(
                GenerationStep.Decoration.UNDERGROUND_ORES, MY_VEIN);
        TerrainRecastApi.getFeatureInjection().apply();
    }
}
```

数据包侧（`data/my_addon/worldgen/placed_feature/deep_emerald_vein.json`）需要同时引用一个
`configured_feature`，两者都是纯 JSON，不需要写 Java。

## 查询现状

```java
int n = TerrainRecastApi.getFeatureInjection().size();   // 已登记条数
```

（`size()` 统计的是登记项，不是已下发项；已下发项内部另有记录。）
