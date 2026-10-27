# Terrain Recast 技术备忘 · Agent 交接手册

> 本文档面向接手本项目的新 AI Agent。设计理念、愿景、完整 L0–L6 管线规划见 `docs/设计文档.md`。
> 本文档只写**实战中踩过的坑、当前可工作的状态、下一步要做什么**。

---

## 一、构建环境（必须正确，否则编译不过）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
cd "f:\新·模组开发\Terrain Recast"
.\gradlew build --no-configuration-cache
```

- MC 26.3 / Fabric Loader 0.20.1 / Fabric API 0.165.0+26.3 / Loom 1.19-SNAPSHOT / Java 25
- 系统默认 JDK 是 17，**必须手动切 jdk-25**
- 构建不加 clean（用户全局约束）

---

## 二、当前可工作管线

```
DensityFunction.sampleValue(x, y, z)
  │
  ├─ TerrainRecastApi.getCellLookup()        // volatile 单例，FabricLifecycleHandler 注入
  │    注入时机：SERVER_STARTED（不是 ModInitializer！那时还没 worldSeed）
  │    释放时机：SERVER_STOPPED.close()
  │
  ├─ lookup.computeIfAbsent(cx, cz)           // LifecycleBoundCache + ObjectPool
  │    第一次访问时才触发以下四阶段：
  │
  │    ├─ L0 populateL0()  Worley 3×3 Voronoi → plateBoundaryDistance + 大陆/海洋类型
  │    ├─ L1 populateL1()  碰撞类型 → 褶皱山/海沟/洋中脊 + terrainHeight/steepness/landformType
  │    ├─ L5 populateL5()  landformType → GRANITE/BASALT/GNEISS/LIMESTONE 基底岩性
  │    └─ L2 populateL2()  纬度+海拔→temperature；海陆→moisture；板块类型→continentalness + erosion + weirdness
  │
  └─ CellFieldSampler(FIELD).sampleValue() → Cell.Field.get(cell) → float 输出
```

### NoiseRouter 覆盖（datapack 模式）

当前只覆盖了 `noise_router.depth`：

```json
// data/minecraft/worldgen/noise_settings/overworld.json
{ "noise_router": { "depth": "terrain_recast:overworld/depth_with_plates" } }

// data/terrain_recast/worldgen/density_function/overworld/depth_with_plates.json
{
  "type": "minecraft:add",
  "argument1": "minecraft:overworld/depth",
  "argument2": {
    "type": "minecraft:add",
    "argument1": { "type": "minecraft:mul", "argument1": 0.5, "argument2": "terrain_recast:plate_boundary" },
    "argument2": { "type": "minecraft:mul", "argument1": 0.1, "argument2": "terrain_recast:landform_height" }
  }
}
```

**为什么这样写**：Tectonic 模式——**只写一个字段**，不碰原版其他字段。如果用完整 overworld.json，任何 schema 漏项都会 Registry Loading 崩溃。

### 当前 Cell 字段填充状态

| 层级 | 字段 | 状态 | 填充位置 |
|------|------|------|----------|
| L0 | continentId | ✅ | populateL0 |
| L0 | plateBoundaryDistance | ✅ | populateL0 |
| L1 | terrainHeight | ✅ | populateL1 |
| L1 | steepness | ✅ | populateL1 |
| L1 | landformType | ✅ | populateL1 |
| L2 | temperature | ✅ | populateL2 |
| L2 | moisture | ✅ | populateL2 |
| L2 | continentalness | ✅ | populateL2 |
| L2 | erosion | ✅ | populateL2 |
| L2 | weirdness | ✅ | populateL2 |
| L3 | riverFlow | ❌ 未实现 | D8 算法 |
| L4 | erosionAmount | ❌ 未实现 | |
| L5 | lithology | ✅ | populateL5 |
| L6 | biomeCandidate | ❌ 未实现 | Whittaker 图 |

### DensityFunction 类型注册（8 个，全部注册完毕）

```
terrain_recast:config_constant           ← ConfigHandler.getValue(key)
terrain_recast:config_noise              ← 噪声 × ConfigHandler scale/shift
terrain_recast:cell_field                ← Cell.Field.get()，JSON 需带 "cell_field": "CONTINENTALNESS" 等
terrain_recast:plate_boundary_distance   ← Cell.plateBoundaryDistance（已从 stub 改成真实现）
terrain_recast:landform_height           ← Cell.terrainHeight（已从 stub 改成真实现）
terrain_recast:strata                    ← Y 方向层状岩性 + Cell.lithology（已从 stub 改成真实现）
terrain_recast:nether_volcano            ← ❌ stub（恒返回 0）
terrain_recast:end_shattered_continent   ← ❌ stub（恒返回 0）
```

**JSON 引用格式**：
```json
// plate_boundary.json
{ "type": "terrain_recast:plate_boundary_distance" }

// continentalness.json（cell_field 需要带参数）
{ "type": "terrain_recast:cell_field", "cell_field": "CONTINENTALNESS" }
```

---

## 三、MC 26.3 关键 API 变化（已踩过的坑）

### 3.1 DensityFunction 新接口（最关键！）

旧版 `compute/fillArray/minValue/maxValue/mapAll/codec` **全部消失**。新接口：

```java
public interface DensityFunction {
    DensitySampler compileSampler(CompileContext context);
    Interval range();
    @Axes int domainAxes();       // NO_AXES=0, AXIS_X=1, AXIS_Y=2, AXIS_Z=4 可组合，ALL_AXES=7
    DensityFunction rewriteChildren(DfRewriteRule rule);
    MapCodec<? extends DensityFunction> codec();
}

public interface DensitySampler {
    void sampleVolume(SamplerContext ctx, DensityBuffer buf, DensityVolume vol);
    float sampleValue(SamplerContext ctx, int x, int y, int z);
    // 辅助：DensitySampler.sampleVolumeNaive(ctx, buf, vol, this)
}
```

注册方式：
```java
Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
    TerrainRecast.id("type_name"), MyDensityFunction.CODEC);
```

### 3.2 Fabric ServerLifecycleEvents

```java
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
    long seed = server.getWorldGenSettings().options().seed(); // 注意：不是 getWorldData().getSeed()
    TerrainRecastApi.setCellLookup(new RealCellLookup(seed));
});
ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
    CellLookup lookup = currentLookup;
    if (lookup != null) try { lookup.close(); } catch (Exception e) { /* */ }
    TerrainRecastApi.setCellLookup(null);
});
```

**时序注意**：ModInitializer 阶段**还没有世界**，没有 worldSeed。CellLookup 必须在 SERVER_STARTED 注入。

### 3.3 record 组件访问

`WorleyDistance.PlateSample` 是 record，组件访问**必须用 `ps.id1()` 不是 `ps.id1`**（Java 强制 private 组件，生成的是访问器方法）。

### 3.4 DimensionType JSON schema（MC 26.3 版）

**必须**有以下字段（缺任何一个 Registry Loading 崩溃）：

```json
{
  "ultrawarm": false,
  "natural": true,
  "piglin_safe": false,
  "respawn_anchor_works": false,
  "bed_works": true,
  "has_raids": true,
  "has_skylight": true,
  "has_ceiling": false,
  "coordinate_scale": 1.0,
  "ambient_light": 0.0,
  "logical_height": 1024,
  "effects": "minecraft:overworld",
  "infiniburn": "#minecraft:infiniburn_overworld",
  "min_y": -256,
  "height": 1024,
  "monster_spawn_light_level": {
    "type": "minecraft:uniform",
    "value": { "min_inclusive": 0, "max_inclusive": 7 }
  },
  "monster_spawn_block_light_limit": 0
}
```

**踩过的坑**：把 `monster_spawn_light_level` 和 `monster_spawn_block_light_limit` 套在嵌套 `monster_settings` 对象里→解析失败。MC 26.3 这两个是**根级字段**。

### 3.5 NoiseSettings 的 noise_router 只有 8 个字段

```
temperature / vegetation / continents / erosion /
depth / ridges / chunk_surface_level / final_density
```

原版旧版有 barrier/lava/veinXxx/aquifers 等字段→**全部移除了**，拆到别处了。写 overworld.json 时只能写这 8 个。

---

## 四、线程安全设计（已确认）

| 组件 | 策略 | 原因 |
|------|------|------|
| ConfigHandler | volatile 单例 | 之前 UTF 的 ThreadLocal 在跨线程池复用时值不清理→串扰 |
| TerrainRecastApi.cellLookup | volatile 引用 | SERVER_STARTED 注入 / SERVER_STOPPED 置 null |
| LifecycleBoundCache | ConcurrentHashMap，显式 close() | RTF 后台线程清理与生命周期解耦→内存泄漏 |
| ObjectPool | ConcurrentLinkedQueue | Cell 高频创建，池化复用 |
| WorleyDistance.sample() | 无状态 + 种子化确定性 | 可被任意线程同时调用 |

---

## 五、参考源码位置

所有参考源码在 `f:\新·模组开发\Terrain Recast\temp\`：

| 目录 | 内容 |
|------|------|
| `minecraft-merged-1b0498e74c-26.3-sources` | 原版 26.3 decompile 源码 |
| `tectonic-3/` | 最关键参考——26.3 Fabric 端 NoiseRouter datapack 模式 |
| `BigGlobe-all-versions/` | 另一个 Globe 级地形参考 |
| 其他（bclib, Terralith-1.20, streams, UTF...） | 已分析过，详见设计文档 |

**原版 DensityFunction key 清单**（来自 NoiseRouterData.java）：
```
overworld/temperature, vegetation, continents, erosion,
offset, factor, jaggedness, depth, sloped_cheese,
preliminary_surface_level, chunk_surface_level, final_density,
ridges, ridges_folded, base_3d_noise
```

---

## 六、当前遗留 stub 和待办（按优先级）

### 高优先级（让 TRC 真正起作用）

1. **NetherVolcanoDensityFunction** stub → OpenSimplexNoise 火山锥群
2. **EndShatteredContinentFunction** stub → BetterEnd 26.3 IslandTerrainDensityFunction 参考
3. **continentalness / temperature** 串进 NoiseRouter（overworld.json 里加 continents 和 temperature 字段，指向 TRC CellFieldSampler）
4. **L3 D8 水系** riverFlow 填充 → 为后续侵蚀/地形二次塑形打基础

### 中优先级

5. **TerraBlender 兼容层**：ClimateBridge 把 Cell 五维→原版 Climate.Parameter 七维，让第三方群系能吃到 TRC 气候
6. **L6 Whittaker 群系图**：temperature × moisture × continentalness × erosion → 选群系
7. **StrataDensityFunction 优化**：加一点 XZ 方向的噪声扰动，地层不会太规整

### 低优先级（玩法/体验）

8. ModMenu 配置界面 + Language 文件同步
9. F3 调试屏五维气候显示
10. 预设 GUI / 分享码 TRC1 / 异步 locate

### 性能红线

- Cell 采样是 NoiseRouter 每次 chunk 构建都会调的热点，**不要在 CellFieldSampler.sampleValue 里做昂贵计算**
- Worley 已经合并成一次 3×3 遍历，别再拆回去
- CellLookup 缓存上限 4096 ≈ 16×16 区块，实际不会超；如过要加大要同步调 ObjectPool.prefill

---

## 七、文件速查表

```
src/main/java/yifei/terrain/recast/
├── TerrainRecast.java                    # 主入口
├── api/
│   ├── TerrainRecastApi.java            # 静态门面（volatile CellLookup）
│   └── CellDataView.java                # Cell 只读接口
├── concurrent/
│   ├── ObjectPool.java                   # ConcurrentLinkedQueue + resetter
│   └── LifecycleBoundCache.java          # ConcurrentHashMap，显式 close()
├── config/
│   ├── ConfigHandler.java                # volatile 单例（不用 ThreadLocal）
│   └── ConfigState.java
├── core/RegistryBootstrap.java          # 8 个 DensityFunctionType 注册
├── loaders/fabric/FabricLifecycleHandler.java  # SERVER_STARTED → 注入 RealCellLookup
├── util/WorleyDistance.java              # WorleyDistance.sample() 一次遍历返回 PlateSample
├── worldgen/
│   ├── cell/
│   │   ├── Cell.java                     # 14 字段 L0–L6 + Field 枚举
│   │   ├── CellLookup.java               # 接口 extends AutoCloseable
│   │   ├── NoopCellLookup.java           # 初始占位
│   │   └── RealCellLookup.java           # L0→L1→L2→L5 四阶段 populate
│   ├── climate/ClimateBridge.java        # TRC 六维→原版 Climate.Parameter 七维近似
│   └── densityfunction/                  # 8 个 DensityFunction 实现
└── resources/
    ├── data/minecraft/dimension_type/overworld.json        # min_y=-256, height=1024
    ├── data/minecraft/worldgen/noise_settings/overworld.json  # 只覆盖 depth
    ├── data/terrain_recast/worldgen/density_function/     # 5 个 datapack 条目
    ├── fabric.mod.json
    └── terrain_recast.mixins.json                           # mixins=[], 保持
```
