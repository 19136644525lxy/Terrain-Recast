# Terrain Recast 附属模组 API 指南

本目录是 Terrain Recast（下称 TRC）对外 API 的**完整用法说明**，面向写附属模组 / 兼容补丁的开发者。
所有内容对应源码目录 `src/main/java/yifei/terrain/recast/api/`，与当前版本行为一致。

## 你能用它做什么

| 能力 | 入口 | 说明 |
|---|---|---|
| 读地形数据 | `TerrainRecastApi.queryCell()` / `getCellLookup().sampleField()` | 查询任意坐标的板块、高度、气候、岩性 |
| 改地形数据 | `getPipeline().register()` | 责任链拦截字段采样值，重塑地形而不用碰核心代码 |
| 加地物 | `getFeatureInjection().add()` | 把 placed feature 注入 TRC 群系 |
| 加矿床 | `getOreRegistry().register()` | 按岩性绑定矿种 |
| 监听生命周期 | `RecastEvents.CHUNK_GENERATED` / `CELL_PIPELINE_APPLIED` | 区块生成完成、Cell 推演完成的回调 |

## 目录

1. [快速开始](01-quickstart.md) —— 依赖声明、entrypoint、初始化时序
2. [读取地形数据](02-cell-data.md) —— `CellDataView` 11 个字段的准确语义
3. [数据修改器](03-data-modifier.md) —— 责任链、优先级、线程契约、三个完整示例
4. [地物注入](04-feature-injection.md) —— 分组注入表、选择器、地物顺序坑
5. [矿床注册](05-ore-deposit.md) —— 岩性编码、`OreDeposit`、内置矿床表
6. [事件总线](06-events.md) —— 订阅/发布、两个内置事件
7. [踩坑清单](08-pitfalls.md) —— 时序、线程、递归、数据包、26.3 命名

## 30 秒示例

```java
public class MyAddon implements ModInitializer {
    @Override
    public void onInitialize() {
        // 1) 所有海拔 > 800 的地方再抬高 15%
        TerrainRecastApi.getPipeline().register(100, ctx -> {
            if (ctx.field() != Cell.Field.TERRAIN_HEIGHT) return null;
            double h = ctx.original();
            return h > 800 ? h * 1.15 : null;   // null = 不处理，交给下一个节点
        });

        // 2) 我的矿种绑定到花岗岩
        TerrainRecastApi.getOreRegistry().register(
            new OreDeposit(1, Blocks.EMERALD_ORE.defaultBlockState(), 6, 0.4));
    }
}
```

## 设计约定

- **唯一门面**：一切从 `TerrainRecastApi` 静态方法拿服务，不直接 new 内部类。
- **只读视图**：地形数据只以 `CellDataView` 接口暴露，附属改不到内部 `Cell` 字段。
- **责任链**：修改器的返回值 `null` 表示"不关我事"，`非 null` 即**拍板**终值。
- **对称撤销**：注册类方法都返回 `AutoCloseable`，`close()` 撤销，可安全重复调用。
- **空链零开销**：没人注册修改器时，热路径直接返回原值，不分配对象。

## 版本与兼容

TRC 目前不提供独立的 API 版本常量，API 与模组版本同步发布（当前 `1.0.0`）。
附属应在自己的 `fabric.mod.json` 里声明依赖：

```json
"depends": { "terrain_recast": ">=1.0.0" }
```

跨大版本升级时，`api` 包内的方法签名视为兼容承诺；内部包（`worldgen.*`、`core.*`）不保证。
