# Terrain Recast — 程序化树木与地物（第一版）实现规格

## 目标
为不同群系/气候/海拔生成**由本项目 Cell 管线驱动的自适应程序化树**，并挂到原版主世界群系生成。
异地物机制建好后，后续可扩展灌丛、花、仙人掌等地表覆盖物。

## 技术决策（原理）
- **不自定义 TrunkPlacer/FoliagePlacer**：26.3 里 `TrunkPlacer` 的 codec 限制
  `base_height`∈[0,32] 且 `MAX_HEIGHT=80`（见合并源码 TrunkPlacer.java），配不上本项目
  可达 y≈1500+ 的超高山地。故实现一个**自定义 `Feature` 自绘**树干+树冠，读取 Cell 管线
  决定树种/高度/密度，完全脱离原版树高约束。
- **注册时机**：`Feature` 类型注册到 `BuiltInRegistries.FEATURE_TYPE`（= `Registries.FEATURE_TYPE`
  ="worldgen/feature_type"），与 DensityFunction 类型同批在 ModInitializer 冻结前注册。
- **placed_feature**：26.3 无 `configured_feature` 注册表；`placed_feature` 的 `"feature"` 字段
  用 `Feature.CODEC`（holder codec），可**内联** feature 对象，故只需注册类型 + 数据 JSON，
  无需额外注册实例到 `worldgen/feature`。
- **挂群系**：项目沿用原版 MultiNoiseBiomeSource + 原版群系，无法用数据包直接向原版群系加
  地物（覆写会损其原版 features）。项目已依赖 fabric-api，用
  `net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature` 把 placed feature 注入
  主世界群系——最标准、零群系 JSON 覆写。
- **运行时取数**：植被 Feature 用 `TerrainRecastApi.getCellLookup().sampleField(field,x,z)`
  读 `TERRAIN_HEIGHT/TEMPERATURE/MOISTURE/STEEPNESS`。RealCellLookup 已在 SERVER_STARTING
  （区块生成前）注入，故放置时可取到真实值。

## 新增文件
1. `src/main/java/yifei/terrain/recast/worldgen/feature/TerrainVegetationFeature.java`
   - `record TerrainVegetationFeature(float density)` implements `Feature`。
   - CODEC：`RecordCodecBuilder.mapCodec`，字段 `"density"`（Codec.FLOAT，可省，默认 1.0f）。
   - `place()` 逻辑：
     a. 读取 (x,z) 处 Cell：terrainHeight h、temperature t、moisture m、steepness s；
     b. 门槛：lookup 为空→false；h<5（海底）→false；s 过高（陡坡）→false；
        下方支撑方块必须属于 `#minecraft:dirt`/`#minecraft:sand`/沙砾（防悬空/水下/石顶）；
     c. 树种判定（t,m）：冷(t<-0.25)→云杉针叶；干(m<0.2)→合欢/桦木矮树；
        暖湿(t>0.2&&m>0.5)→橡木/丛林阔叶；其余→橡木；
     d. 高度模型：`基底+random`，随海拔`h`增高线性压低（`height*=clamp(1.35-0.008*max(0,h-110),0.55,1.0)`）
        并对世界顶做上限保护（`origin.y+height <= level.getMaxY()`）；初始齐扫码顶/高度码。
     e. 用 `level.setBlock(pos,state,19)`（@Block.UpdateFlags 同原版 TreeFeature）自绘树干与
        树冠（球/锥/伞三类小体积画法）；单次放置量 `1 + int(density)`。
   - 单文件收敛，<500 行；只加必要中文注释。
2. `src/main/java/yifei/terrain/recast/worldgen/feature/VegetationWorldgen.java`
   - 常量：`PLACED = ResourceKey.create(Registries.PLACED_FEATURE, TerrainRecast.id("terrain_vegetation"))`。
   - `register()`：`BiomeModifications.addFeature(ctx -> ctx.canGenerateIn(BiomeTags.IS_OVERWORLD),
     GenerationStep.Decoration.VEGETAL_DECORATION, PLACED)`。

## 修改文件
3. `src/main/java/yifei/terrain/recast/core/RegistryBootstrap.java`
   - 在 DensityFunction/MaterialRule 注册处追加：
     `Registry.register(BuiltInRegistries.FEATURE_TYPE, TerrainRecast.id("vegetation"), TerrainVegetationFeature.CODEC);`
4. `src/main/java/yifei/terrain/recast/TerrainRecast.java`
   - `onInitialize()` 中追加 `VegetationWorldgen.register();`

## 新增数据（JSON）
5. `src/main/resources/data/terrain_recast/worldgen/placed_feature/terrain_vegetation.json`
   ```
   {
     "feature": { "type": "terrain_recast:vegetation", "density": 1.0 },
     "placement": [
       { "type": "minecraft:in_square" },
       { "type": "minecraft:rarity_filter", "chance": 4 },
       { "type": "minecraft:count", "count": 1 },
       { "type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG" },
       { "type": "minecraft:biome" }
     ]
   }
   ```
   - `heightmap` 值用 `Heightmap.Types.getSerializedName()` 返回的大写键（已验证 codec 为
     `StringRepresentable.fromEnum`）。若数据加载报错该项，则以编译/运行日志改键。

## 命名约定（照抄项目现有代码）
- `TerrainRecast.id(p)`；`net.minecraft.resources.Identifier`；
- `net.minecraft.world.level.block.Blocks` / `BlockState`；`net.minecraft.tags.BlockTags`。
- fabric 群系注入类：`net.fabricmc.fabric.api.biome.v1.*`。

## 验证步骤
- `gradlew build`（环境需 `JAVA_HOME=x"..."`）→ 预期 **BUILD SUCCESSFUL**。
- 若 fabric biome API 类名/方法名在此映射下不适配，以编译错误为准调整 import/方法签名。

## 范围外（后续项，本期不做）
- 非树的地表覆盖物（灌丛/花/仙人掌/草）。
- 自绘根须与垂藤；树叶自然脱落 BFS（直接用 19 旗标 SOIL_REPAIR 由引擎补齐）。
- 高度列采样性能复用（本期 feature 按列少量调用 sampleField，量级可接受）。