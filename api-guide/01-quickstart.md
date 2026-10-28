# 快速开始

## 1. 声明依赖

附属的 `src/main/resources/fabric.mod.json`：

```json
{
  "depends": {
    "fabricloader": ">=0.20.1",
    "minecraft": "~26.3",
    "fabric-api": "*",
    "terrain_recast": ">=1.0.0"
  }
}
```

编译期把 TRC 的 jar 作为依赖即可（开发环境用 `modImplementation files(...)` 或本地 maven）。

## 2. 拿到服务

在 `ModInitializer.onInitialize()` 里调用 `TerrainRecastApi` 的静态方法：

```java
package com.example.addon;

import net.fabricmc.api.ModInitializer;
import yifei.terrain.recast.api.TerrainRecastApi;

public final class MyAddon implements ModInitializer {
    @Override
    public void onInitialize() {
        TerrainRecastApi.getPipeline().register(100, ctx -> null);
        TerrainRecastApi.getOreRegistry().register(/* ... */);
        TerrainRecastApi.getFeatureInjection().add(/* ... */);
        TerrainRecastApi.getFeatureInjection().apply();   // 自己登记完补一次 apply
    }
}
```

**为什么在 TRC 可能还没初始化时也安全**：`TerrainRecastApi` 的四个服务字段是 `static` 初始化，
类一被加载（你第一次调用它）实例就已存在；`RegistryBootstrap.init()` 不会替换这些实例，只往里注册默认矿床。
所以 Fabric 的 entrypoint 顺序不影响你注册。

**唯一受影响的是 `CellLookup`**——它在服务器启动时才被注入，见下一节。

## 3. 生命周期与时序

| 时刻 | `getCellLookup()` 返回 | 能做什么 |
|---|---|---|
| 模组初始化 | `NoopCellLookup`（永远返回 0 / null） | 注册修改器、矿床、地物 |
| `SERVER_STARTING` 之后 | `RealCellLookup(seed)` | 查询真实地形数据；采样字段 |
| 区块生成期间 | 同上 | 修改器热路径被调用 |
| `SERVER_STOPPED` 之后 | `null` | 不要再采样；等下次启动 |

`SERVER_STARTING` 是 TRC 注入 CellLookup 的时机（**早于任何区块生成**），这样出生点区块也能正确生成。
如果你的代码在更早的阶段（如 `onInitialize`）就需要地形数据，请改用 `sampleField` 的返回值判断，
或延迟到 `ServerLifecycleEvents.SERVER_STARTED` 再查。

```java
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
    CellLookup lookup = TerrainRecastApi.getCellLookup();
    if (lookup == null) return;            // 防御：TRC 未安装/初始化失败
    double h = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, 0, 0);
    // ...
});
```

## 4. 什么时候该用哪个入口

| 需求 | 用哪个 |
|---|---|
| 已知区块坐标，要整块板块数据 | `TerrainRecastApi.queryCell(chunkX, chunkZ)` |
| 已知方块坐标，要连续场值（高度/湿润/地貌码…） | `getCellLookup().sampleField(field, x, z)` |
| 要改采样值 | `getPipeline().register(...)` |
| 要加自己的地物 | `getFeatureInjection().add(step, placedFeature, selector)` |
| 要加矿床 | `getOreRegistry().register(new OreDeposit(...))` |
| 要监听区块/Cell 完成 | `RecastEvents.*.subscribe(...)` |

> ⚠️ `getCellLookup()` 可以返回 `null`（服务器停止后）。所有调用点都要判空，
> `TerrainRecastApi.queryCell()` 已内置判空，直接用它更省事。

## 5. 撤销注册

所有注册方法返回 `AutoCloseable`，保留它并在不需要时 `close()`：

```java
AutoCloseable handle = TerrainRecastApi.getPipeline().register(100, modifier);
// ...
handle.close();   // 幂等，重复调用无副作用
```

注意：**不要**在世界停止时清空全局注册表（`getPipeline().clear()` 等）——那些是模组级注册，
清掉后下次进世界不会自动恢复。只在"你的附属自己被禁用"时才撤销自己的条目。
