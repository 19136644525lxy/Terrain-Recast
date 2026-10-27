# 任务清单 — 程序化树木与地物（第一版）

- [x] T1 实现 `TerrainVegetationFeature`
  - [TerrainVegetationFeature.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/worldgen/feature/TerrainVegetationFeature.java)
  - record implements Feature；CODEC 字段 `"density"`（默认 1.0）；
  - place() 读 Cell(temperature/moisture/terrainHeight/steepness)→门槛→选树种→高度随海拔压矮+世界顶上限→自绘树干/树冠；setBlock 旗标 19。
- [x] T2 注册 Feature 类型
  - [RegistryBootstrap.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/core/RegistryBootstrap.java) 增 `FEATURE_TYPE`「terrain_recast:vegetation」。
- [x] T3 挂群系
  - [VegetationWorldgen.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/worldgen/feature/VegetationWorldgen.java) + [TerrainRecast.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/TerrainRecast.java)
  - FabricBiomeModifications 注入主世界群系；用 `context.hasTag(BiomeTags.IS_OVERWORLD)`。
- [x] T4 添加 placed feature 数据
  - [terrain_vegetation.json](file:///f:/新·模组开发/Terrain%20Recast/src/main/resources/data/terrain_recast/worldgen/placed_feature/terrain_vegetation.json)
- [x] T5 构建验证
  - `gradlew build` → BUILD SUCCESSFUL。

## 程序化树木与地物（第二版：地表覆盖物）

- [x] T1 实现 `TerrainGroundCoverFeature`
  - [TerrainGroundCoverFeature.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/worldgen/feature/TerrainGroundCoverFeature.java)
  - 读 Cell(temperature/moisture/terrainHeight/steepness)→门槛→按气候铺设 草/花/蕨/仙人掌/枯丛；沙地出仙人掌+枯丛，冷地仅稀疏矮草，暖湿出花+蕨，温带草+花点缀。复用 `TerrainVegetationFeature.isValidSupport`(改包可见)。
- [x] T2 注册 Feature 类型
  - [RegistryBootstrap.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/core/RegistryBootstrap.java) 增 `FEATURE_TYPE`「terrain_recast:ground_cover」。
- [x] T3 挂群系
  - [VegetationWorldgen.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/worldgen/feature/VegetationWorldgen.java) 增加 `GROUND` placed key 并同植被步骤注入主世界。
- [x] T4 数据
  - [ground_cover.json](file:///f:/新·模组开发/Terrain%20Recast/src/main/resources/data/terrain_recast/worldgen/placed_feature/ground_cover.json)
- [x] T5 构建验证
  - `gradlew build` → BUILD SUCCESSFUL。

## 结构与地质矿床（第一版：矿床带偏置）

- [x] T1 实现 `TerrainOreDepositFeature`
  - [TerrainOreDepositFeature.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/worldgen/feature/TerrainOreDepositFeature.java)
  - 参数 `ore`(BlockState)；CODEC `FieldOf("ore")`。place() 读 CellLookup(EROSION/MOISTURE) 作地质省门控 → 门控失败 return false → 随机走 7~15 步矿脉，按 STONE/DEEPSLATE 可替换 tag 放石/深板岩变体（TWIN 映射）。
- [x] T2 注册 Feature 类型
  - [RegistryBootstrap.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/core/RegistryBootstrap.java) 增 `FEATURE_TYPE`「terrain_recast:ore_deposit」。
- [x] T3 挂 UNDERGROUND_ORES
  - [OreDepositWorldgen.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/worldgen/feature/OreDepositWorldgen.java) + [TerrainRecast.java](file:///f:/新·模组开发/Terrain%20Recast/src/main/java/yifei/terrain/recast/TerrainRecast.java) 注入 4 组矿脉到主世界 UNDERGROUND_ORES。
- [x] T4 数据
  - [terrain_ore_coal.json](file:///f:/新·模组开发/Terrain%20Recast/src/main/resources/data/terrain_recast/worldgen/placed_feature/terrain_ore_coal.json) / iron / gold / diamond：height_range trapezoid 分深度带。
- [x] T5 构建验证
  - `gradlew build` → BUILD SUCCESSFUL。
- 说明：CellLookup 已通过 `sampleField(Cell.Field.LITHOLOGY, x, z)` 暴露岩性（离散量，最近板块整数值），
  矿床门控现已使用真实岩性而非代理；`EROSION`/`MOISTURE` 仍作为次级地质省权重。

## 附属 API 打通（第八章）· 2026-10-26

- [x] T1 DataModifier 责任链接进采样热路径
  - `RealCellLookup.sampleField` 拆为 `sampleRawField` + `pipeline.apply`；
    `ModifierPipeline.isEmpty()` 空链短路 —— 无附属时零开销、零分配。
  - `DataModifier` 补充线程/重入契约：禁止在 `modify()` 内回调 `sampleField`（会无限递归）。
- [x] T2 事件总线从"永不触发"变为"真正驱动"
  - `CELL_PIPELINE_APPLIED`：`plateCell` **入缓存之后**发布，避免订阅者回调重入 `populatePlate`。
  - `CHUNK_GENERATED`：挂 Fabric `ServerChunkEvents.CHUNK_LOAD` 的第三个布尔（generated）；
    `CHUNK_GENERATE` 在 fabric-lifecycle 4.1.9 已 `@Deprecated`，故不用。
  - 两处均先判 `subscriberCount() == 0` 短路，不构造载荷对象。
- [x] T3 矿床注册表接进地物
  - `OreRegistry.findByOre(BlockState)`（身份比较，BlockState 全注册表单例）。
  - `TerrainOreDepositFeature`：脉体规模 / 丰度 / 目标岩性改由注册表提供；
    命中岩性省门控 +0.25，省外保留 30% 异常富集。
  - 新增岩性宿主判定（花岗岩/玄武岩/方解石/砂岩/安山岩）——这些不在
    `#stone_ore_replaceables` 里，若不认就会"绑定岩性却放不下矿"自我抵消。
- [x] T4 分组地物注入表统一接线
  - `TerrainRecastApi.getFeatureInjection()`；两个 `*Worldgen` 改为登记项，不再直接调 `BiomeModifications`。
  - `GroupedFeatureInjection.apply()` 改为**幂等**（已下发集合），附属晚于 TRC 初始化登记可自行再 apply；
    新增 `clear()`；`TerrainRecast.onInitialize()` 末尾统一 apply。
- [x] T5 生命周期清理
  - `RegistryBootstrap.close()` 补 injection / events 清理，并注明**不得**在 SERVER_STOPPED 调用
    （模组级注册清掉后不会自动恢复）。
- [x] T6 运行时验证（build 测不出的问题）
  - **首跑 `runServer` 即暴露真 bug**：4 个矿脉 placed_feature 的 `height_range/trapezoid`
    schema 非法（26.3 的 `min_inclusive/max_inclusive` 必须是 VerticalAnchor 对象）→
    改为 `{"absolute": y}`，并清掉 PowerShell 写入的 UTF-8 BOM。
  - 二跑：注册表加载通过、世界生成 100%、`CellLookup ready before chunk generation`。
  - 临时自检探针：单次生成中 DataModifier 被调用 500 万+ 次（覆盖 8 种字段），
    `CHUNK_GENERATED` 在 (0,0) 触发；验证后探针已删除。
  - 复用命令：`$env:JAVA_HOME="C:\Program Files\Java\jdk-25"; .\gradlew runServer --no-configuration-cache`
    （无 stdin 时走完 spawn 区就停；**注意占用 25565**，重跑前确认没有残留 java 进程）。

## 创建世界界面 · 地形预览页签 · 2026-10-26

> 参考 `temp/community-mods/world-preview`，但**不抄它的架构**：那边要 DummyServer + 真的生成区块
> 再采样（90 个文件、4 个 Mixin、1.20 API）。TRC 的地形本身就是连续场，一个 `RealCellLookup`
> 后台跑一遍就能出图，20ms 级，且完全不碰原版生成内部结构。

- [x] T1 采样器 `client/preview/TerrainPreviewSampler`
  - 320×180 像素、每像素 8 格（覆盖 2560×1440 格），中心为世界原点。
  - 只采 `TERRAIN_HEIGHT`：陆地七段色带（沙滩→草原→林地→草甸→裸岩→雪线）+ 海洋深浅渐变
    + 用左/上邻格高差做的左上光源山体阴影（让山脉走向可见）。
  - 无全局状态，任意后台线程可调；`RealCellLookup` 用完即 `close()`。
- [x] T2 贴图 `PreviewTextures`
  - 全屏只保留一张预览贴图，新图上传即释放旧图，避免反复开关界面在 TextureManager 堆积。
- [x] T3 画布 `TerrainPreviewMap`
  - `AbstractWidget` 子类，不参与交互；渲染帧末尾回调供页签做防抖轮询（页签没有 tick 事件）。
- [x] T4 页签 `TerrainPreviewTab`
  - 布局：种子框 → 画布 → 重新生成/随机种子 → 状态行。
  - 种子与世界创建界面双向同步；**空种子会生成一个具体种子并落回输入框**，
    保证"预览所见 = 世界所用"；输入 350ms 防抖；请求带序号，过期结果丢弃。
  - 世界类型守卫：非"默认/放大生物群系"（超平坦、放大化、调试）直接提示不可用。
- [x] T5 Mixin `mixin/client/CreateWorldScreenMixin`
  - `@ModifyArg` 改写 `CreateWorldScreen.init()` 里 `addTabs(...)` 的变长数组，追加第 4 个页签；
    页签栏按数量均分宽度，无需改布局。
  - 已在编译产物中静态核对：`uiState` 字段、`init()`、`addTabs` 描述符三者均与目标一致。
  - `terrain_recast.mixins.json` 新增 `client` 段（服务端不加载）。
- [x] T6 i18n：zh_cn / en_us 各 10 条。
- [x] T7 验证
  - `gradlew build` → BUILD SUCCESSFUL（含 Mixin 类）。
  - **GUI 与 Mixin 应用需进游戏点验**（自动化测不到）。
- [x] T8 首版观感返工（用户反馈"不如参考模组"）
  - 定位到五个问题，全部已修：
    1. **取景在原点上** → 项目在出生点半径 1400 内强制造陆，整张图全陆无海；
       改到 `CENTER=(6000, 0)`（避开造陆，且同纬度不落入极地温度带）。
    2. **按海拔涂棕** → 真实地表是"植被铺满 + 陡坡露岩"（实机截图佐证），
       改为**坡度露岩**：坡度取本图 p70→p98 归一化，只有山脊/断崖才出岩色。
    3. **色带按海平面标定** → 实测高度 p50 约 340~410、p90 约 750，
       旧色带把 90% 陆地刷成岩石；改为**按本图 p5→p97 分位数归一化**，
       任何种子都铺满"沙滩→草原→林地→岩壁→雪峰"的完整层次。
    4. **光照公式饱和** → 旧版用邻格高差×0.02，在本模组 600+ 格坡度上瞬间饱和成死白三角面；
       改为法线 Lambert（坡度夸张 1/55）+ 洼地遮蔽 AO + 轻微颗粒。
    5. **图太小** → 384×216 → **640×300**（贴近页签可用区），顶部行改为
       种子框+两个按钮同行；`doLayout` 里按页签尺寸自适应收缩，小窗口不溢出。
  - 新增离线调色工具 `PreviewDump` + `gradlew dumpPreview`：导出 PNG 并打印
    高度/坡度分位数（调参用，不参与运行时）。
  - 采样耗时（640×300，含湿度）：约 55–75 ms/帧。
- 限制：仍是"高度+湿度"的二维示意图，没有群系色/结构/缩放；与参考模组的
  biome 渲染（需要真区块或群系分类）不是同一实现路径。

> **[已停用] 2026-10-26 22:35** 用户决定不使用该功能：已从 `terrain_recast.mixins.json`
> 的 `client` 段摘除接入点——页签不再出现，Mixin 数回到 0，服务端不受影响。
> 代码文件**保留未删**（没有 git，删了不可恢复）。若要彻底删除，需同时清理：
> `client/preview/*`（4 个类）、`mixin/client/CreateWorldScreenMixin`、`PreviewDump`、
> `build.gradle` 的 `dumpPreview` 任务、两份 lang 的 `*preview*` 键。

---

## P1 · L3 水系（D8 流量累积）· 2026-10-26

> 目标：把"噪声零等值线当河"换成真正的**地形推演**——水往低处流，流量沿程累积，
> 河宽/切深随流量增长。设计依据：设计文档 5.4/5.5 的 L3。

- [x] T1 `worldgen/hydrology/Hydrology` 区域网格
  - 16 格/格、96² 为一区（1536×1536 方块），四周额外 16 格**上游边距**，
    让区外的水能汇进来，缓解边界截断。
  - 高度取自 `RealCellLookup.baseHeightAt`（**未切河**的基面高度，避免递归）。
- [x] T2 priority-flood 填洼
  - 从边界起最小堆淹没，把内部洼地填到溢出高程；填洼时叠加 `0.01` 的微倾，
    否则填出来的平台处处等高，D8 找不到下坡邻居，河会断在平台中央（第一版就是这样）。
- [x] T3 D8 流向 + 汇水累积
  - 按填洼高程从高到低，每格把面积推给最陡下坡邻居；湖/海可以穿流（水会流过盆地），
    但**只在陆地格绘制河道**，所以河到海岸就止。
- [x] T4 河道栅格与下切曲线
  - 河宽 `3 + 0.55·a^0.40`、切深 `2 + 0.35·a^0.35`（a = 汇水格数，单次上限 22 格深），
    沿"格心→下游格心"线段以 6 格/像素栅格化，任意方块坐标双线性采样。
  - 成为河流的阈值 150 格 ≈ 196×196 方块的流域。
- [x] T5 接入地形与 API
  - `terrainHeight = baseTerrainHeight − hydrology.carve(...)`；旧 `carveRiver`/`riverBand` 已删除。
  - `Cell.Field.RIVER_FLOW` / `RIVER_BAND` 改由真实流量驱动（河带再乘 landness，水下不标河）。
  - 两个旧配置项复用为曲线系数：`stream.river_bed`（-10 → 1.0 倍切深）、
    `stream.river_width`（0.07 → 1.0 倍河宽），lang 文案已同步。
- [x] T6 验证
  - 离线河网图（`dumpPreview` 额外输出 `rivers_<seed>.png`）：河道占 0.4~2.6%，
    从山地贯通到海岸；`deadEnds` 仅 0.1%。
  - `runServer` 通过：注册表加载、spawn 区 100%、无异常。
- **性能**：单区构建 8~30 ms（16384 格基面高度 + 排序 + 栅格化）。
  开世界从 ~2.4 s 变成 ~3.2–4.4 s（spawn 选址会横扫 180+ 个区）。
  - 踩坑记录：一开始用 `LifecycleBoundCache`，它"满了就丢弃不淘汰"，
    导致每次采样都重建整区——421 次构建、开世界 8.4 s；换成 LRU(16) 后降到 ~3 s。
- 已知限制（下一步 L3b）：
  - 高于海平面的河谷目前是**干谷**——水面需要含水层或地物注水，而本模组还没有注水机制；
  - 区域边界仍有截断（边距只缓解 256 格）；
  - 地形碎片化（多盆地、多内海）导致河网偏短，要等地形平衡后才能长出大流域。

---

## P1 · L4 侵蚀（droplet + 热力松弛）· 2026-10-26

- [x] T1 `worldgen/hydrology/Erosion`：水滴水力侵蚀
  - 每格 0.45 颗水滴、单颗最多 24 步；惯性 0.06、容量 = 坡度×速度×水量×5、
    冲刷/沉积速度 0.30、蒸发 0.015。
  - 双线性采样 + 双线性投放，保证既平滑又守恒。
- [x] T2 热力松弛：格间高差 > 48（≈3 方块/方块的休止角）时按高差比例搬物质，
  3 次迭代、每次搬走超出部分的 12%——把过陡崖面磨圆，但不会把山磨平
  （实测坡度 p90 由 6.7 降到 5.7、max 13 降到 9.9）。
- [x] T3 与水文合并到同一张区域网格
  - 顺序改为 **基面 → 侵蚀 → 填洼/D8 → 切河**：河流顺着侵蚀出来的谷地走。
  - `terrainHeight` 改为取区域高度场（16 格双线性）− 河道下切；
    本模组噪声波长都在 620 格以上，16 格采样误差可忽略。
- [x] T4 验证
  - 合成坡面单测（`dumpPreview` 里的 `EROSION demo`）：
    `64×64 / 16 ms`，`maxCut=-11.98 maxFill=26.85 netSum=0.45` → **质量守恒**成立。
  - 修 bug：水滴出界时曾把携带泥沙直接丢弃，整片地形被压低 **3+ 格/格**
    （netSum −14172）；改为"出界/走完步数就地卸沙"后归零。
  - `runServer`：开世界 3.9–4.7 s（无侵蚀时 3.2–4.4 s），无异常。

## P1 · L6 群系（Whittaker 查表 + 过渡带）· 2026-10-26

- [x] T1 `worldgen/biome/WhittakerBiome`
  - 10×10 的 温度×湿度 查表（格心定基础群系），温度轴按本模组实际分布标定
    （陆地温度多在 -0.2 以下，"冷"档阈值不能按原版直觉取）。
  - 海拔独立覆盖：海岸 <6、高山草甸 >850、雪线 >1150。
  - 群系：OCEAN / BEACH / TUNDRA / TAIGA / GRASSLAND / FOREST / SAVANNA / DESERT / ALPINE / SNOW。
- [x] T2 过渡带：`pick()` 用双线性权重在相邻四格之间抽样，
  边界附近两个群系的物种自然交错，不是一条硬切线。
- [x] T3 接入
  - `Cell.Field.BIOME`：按本点 温度/湿度/海拔 实时分类（不再是板块级粗分类）。
  - 植被：群系决定树种（苔原/针叶林→云杉、稀树草原/荒漠→金合欢、森林→橡木/丛林）
    **和密度**（雪 5% / 荒漠 10% / 苔原 25% / 森林 100%）。
  - 地表覆盖物：群系决定 草/蕨/花/仙人掌/枯丛，雪线与高山草甸不铺。
- [x] T4 验证
  - 离线群系图（`biomes_<seed>.png`）：**垂直地带性明显**——低地森林、中段针叶林、
    高处苔原、峰顶雪/高山。
  - 分布（4 个种子）：海洋 14–50%、苔原 21–45%、森林 12–16%、针叶林 9–14%、
    高山 4–13%、雪 0–1%、草原 1–3%；**沙漠/稀树草原 ≈0**。
  - 原因：温度随海拔递减极强（`-terrainHeight*0.0032`），暖干低地几乎不存在。
    这是地形平衡（P0）待解决的问题，不是 Whittaker 表的问题。
- 范围说明：L6 目前是**分类 + 过渡带 + 地物接线**；
  真正替换原版 `MultiNoiseBiomeSource` 需要自定义 BiomeSource 或 TerraBlender（P2）。

---

## P1 · L3b 河流注水 · 2026-10-26

> 补掉 L3 的"干谷"问题：`final_density` 只决定固体/空，原版含水层只给海平面以下的水，
> 所以山上切出来的河谷全是干的。

- [x] T1 `worldgen/feature/TerrainRiverWaterFeature`
  - 每区块扫 16×16 列：`RIVER_BAND ≥ min_strength`（默认 0.05）且 `RIVER_DEPTH ≥ 0.5` 才灌。
  - 水面 = `地形面 + min(6, 河道下切深度 × 0.8)`。**恒低于原河岸**（原河岸 = 地形面 + 下切深度），
    所以不会溢到两岸；河道边缘下切趋 0，水面自然收窄，不需要额外宽度判断。
  - 只填空气格；用 `Heightmap.WORLD_SURFACE_WG` 取地形面（此时 L3 下切已生效）。
- [x] T2 接线
  - `RegistryBootstrap` 注册 FEATURE_TYPE `terrain_recast:river_water`；
  - `RiverWaterWorldgen` 注入 `GenerationStep.Decoration.LAKES`（雕刻之后、植被之前）；
  - `placed_feature/terrain_river_water.json`：`count 1 + in_square + biome`。
- [x] T3 验证
  - 首版阈值（0.12 / 1.0）只有 12 格水 → 太窄；放宽到 0.05 / 0.5 后
    **6 个出生点区块分别放下 27 / 352 / 34 / 379 / 15 / 352 格水**（固定种子 42 实测）。
### L3c：湖泊 + 平滑水面 + 边缘补灌（2026-10-26 23:50）

**改法**：注水不再按"每列地形面 + 下切深度"算，改为 L3 直接产出一张**绝对水面场**
（`Cell.Field.WATER_SURFACE`，每 16 格一个高程，跨格双线性）。三件事一起解决：

1. **台阶 → 平滑**：水面按格变化而不是按方块列变化，陡坡上的台阶从"每格一级"
   变成"每 16 格一级"，视觉上就是连续的河。
2. **湖泊**：priority-flood 的填洼高程直接当水面（填高 ≥3 格才算湖），
   同一个湖共享一个高程 → 天然水平面。
3. **边缘补灌**：宽度由「水面 − 地形」自动决定——边缘地形抬到水面以上就不放水，
   所以再宽的河段也不会缺边，也不需要一个宽度参数。

**验证**（用户存档种子，强制生成 6 个河道区块，逐块数水方块）：
`770 / 747 / 821 / 834 / 474 / 2529`，合计 **6175** 格，6/6 区块都有水
（改造前 3981 格，说明水面抬高/加宽了）。

**第二轮调参（用户反馈"河太少、湖太大"）**：
- 河流阈值 150 → **70 格**；流量饱和 24× → **6×**
  （饱和太慢时 river 群系翻不过来，`/locate` 自然检索不到）。
- 河宽曲线 `3+0.55·a^0.40` → **`4+0.90·a^0.42`**，切深 `2+0.35·a^0.35` → `2.5+0.45·a^0.35`。
- 湖泊加**双重筛选**：连通面积 ≤120 格（≈175×175 方块）且水深 ≤30 格。
  单看深度会把湖心判成干地、只剩一圈岸线；单看面积又会留下区域性假巨湖。
- 结果（用户种子，出生点 3840×1920 范围）：河道像素 **2.0% → 5.3%**，
  实际水面 3.1%，最深水面 11 格，无假巨湖。

**仍缺**：三角洲、独立瀑布；陡崖处仍是"水沿崖面逐级下泄"，不是飞流直下的瀑布结构。

### L3d：注水"水墙"事故修复（2026-10-27 00:20）

**现象**（用户实机截图，坐标 -5772 / 3417）：几十格高的**水墙**，村庄和树被泡。

**定位**（实测数据）：
- 那一带地形**不随 h 抬升**：`Y ≈ 263 − 0.031·h`，h 从 176 到 555，地面都停在 240–267。
  说明 `depth` 覆写在这个区域没有线性生效（被密度样条压住了）。
- 而注水把 `水面h − 地面h` 当成**方块数**直接加到地面上 → 差异被放大成水墙。
- 高度图本身是准的（`heightmapY` 与逐格扫描只差 1），所以不是高度图的问题。

**修复**：
1. 深度换算保守化：`depth = min(5, round(rise × 0.5))`，`rise ≥ 2` 才灌。
   （原来直接用 `rise`，最大 24 格。）
2. 河道水面从 `h − 0.20·d` 降到 `h − 0.45·d`，水更浅。
3. **必须是局部低洼处**才灌水：拿同区块内 ±4 格的 4 个邻点比高度，
   `surfaceY ≥ 最低邻点` 就不灌——平地上不再铺水，村庄/树不会被泡。
4. 湖泊同样受 5 格上限约束。

**调参定稿（用户要求"调几格高度"）**：
- 水深改为 **2–4 格**（下限 2、上限 4）：`depth = min(4, max(2, round(rise × 2)))`。
  原因：这类被样条压平的区域 h 差只有 1 左右，按比例算永远是 1 格（水膜）。
- 删除"必须比邻点低"的判据（它把坡面上的河全排除了），改成**只排除局部最高点**。
- 实测（用户坐标 -5772/3417 附近 ±4 区块，144×144）：
  `cols=321 total=568 max=4 depth1=211 depth2-3=59 depth4+=51`
  —— 我们的河道是 2–4 格深；1 格的那些是原版水（含水层/涌泉），不是本模组的水。

**验证**（用户截图区域 ±2 区块，逐列数水）：
`waterColumns=137 totalBlocks=137 maxColumn=1 columnsOver6=0`
—— 全是 1 格深的水膜，水墙消失；离线水面像素 2.5%，`maxRise` 从 179 降到 10。

### L3e：改成「自己挖河道再灌水」（参考社区模组，2026-10-27 08:00）

**参考实现**（`temp/` 源码）：
- `streams`：河流是**结构**，组件自己把河床挖下去、铺岸、再灌水（`RiverComponent`）。
- `BigGlobe`：自建 `ChunkGenerator`，逐列判断"在不在水面以下 → 放水"。

我们用的是原版 `NoiseBasedChunkGenerator` + 密度函数覆写，**没法在 generator 里逐列塞水**，
所以采用 streams 的思路：**在地物里自己挖**。

**为什么非改不可**：密度覆写在被样条压平的地区（`Y ≈ 263 − 0.031·h`）根本切不出河谷。
"在地面之上堆水"就必然二选一——要么水墙，要么水膜。

**新做法**：
1. 把最高的 `depth` 格**实心方块换成水**：水面正好落在原地面，四周高出一格天然成岸，
   完全不依赖密度场是否生效。
2. 宽度用**河道栅格的下切深度**（`RIVER_DEPTH`，2~30 格），不是 16 格尺度的河带强度
   ——后者会把河裁成一格宽。
3. 深度 = `clamp(2, round(下切深度 × 0.6), 4)`。
4. `RIVER_DEPTH` 从 peek 改为建区版本：地物阶段本就该建区；
   用 peek 未命中缓存返回 0，实测水柱从 478 掉到 244。

**实测**（同区域 ±4 区块）：`cols=244→478`，2–3 格深的河道水柱 `40→249`，最深 3 格。

### 追加：卡顿修复（2026-10-26 23:45）

**现象**（用户实测）：进游戏后 `/locate` 卡住、感觉"河流不生成"。

**定位**：
1. `/locate`、出生点选址、群系查询会大量采样 climate 参数，而
   `depth / erosion / ridges` 三条链都挂在 L3 水文上 → **每碰到一个新区域就同步建区**
   （当时 20–50 ms，还要跑侵蚀），扫描几十个区就是十秒级卡顿。
2. 更糟的是 `LifecycleBoundCache` 式的"满了丢弃"会在大范围扫描时**反复重建**同一区域。

**修复**：
- `CONTINENTALNESS` / `TEMPERATURE` 改用**解析基面高度**（不建区）。
- `EROSION` / `RIVER_BAND` / `RIVER_FLOW` / `RIVER_DEPTH` 改用 **peek**：
  只有该区域已被地形采样建过才返回真实值，否则按 0 处理——群系查询绝不触发建区。
- 建区加**单飞锁**（同一区域并发只建一次，防 c2me 并行重复建）。
- 区域缓存 16 → 32；侵蚀水滴 0.45→0.20、步数 24→16；下切栅格 6→8 格/像素。
- 结果：开世界 **3.1–4.7 s → 2.5–2.9 s**；`/locate` 不再触发连锁建区。

**回归验证**（用户存档种子 `6850787115435967640`）：
- 出生点附近河网正常（离线出图，河道占 2.0% 像素）；
- 强制生成 6 个河道区块，逐块数水方块：**270 / 889 / 687 / 1377 / 532 / 226**，合计 3981 格，
  6/6 区块都有水 → 注水链路完好。

---

## 待办 · 自定义群系（把现实群系搬进来）· 需求已记录

> 用户需求（2026-10-26）：新创一批生物群系，把现实中的群系（地中海、温带雨林、
> 萨瓦纳、红土高原、喀斯特等）搬进游戏。**先挂起，等主线补完再做。**

- 现状：L6 的 Whittaker 只做**分类 + 过渡带 + 地物接线**，实际群系仍由原版
  `MultiNoiseBiomeSource` 决定（它吃的是 TRC 的五维气候参数）。
- 两条落地路径：
  1. **TerraBlender Region**（P2 已规划）：把 TRC 气候参数映射进原版 MultiNoise 空间，
     第三方群系模组也能共存；
  2. **自定义 BiomeSource + world preset**：完全可控（可以做真正的 Whittaker 群系选择），
     但要自己接管群系源，改动大、兼容成本高。
- 每个群系还要配：地表规则（草/沙/雪/红土）、树种与地物、水色/雾色/天空色、lang、地图色。
- 前置依赖：**地形平衡**（P0，用户已跳过）+ **TerraBlender 兼容**（P2）。
  尤其温度随海拔递减过强，现实群系的温度带现在根本铺不开。

---

## 水系回退（2026-10-27）· 用户决定整体下线

多轮调整后仍不达预期（水墙 / 水膜 / 宽度不对），用户要求把水系改动整体去掉。

**删除**：
- `worldgen/hydrology/{Hydrology,Erosion}.java`
- `worldgen/feature/{TerrainRiverWaterFeature,RiverWaterWorldgen}.java`
- `data/terrain_recast/worldgen/placed_feature/terrain_river_water.json`
- `Cell.Field` 的 `RIVER_DEPTH` / `WATER_SURFACE` / `RIVER_STRENGTH_EXACT`
- 配置项 `stream.river_bed` / `stream.river_width` 与 `Category.STREAM`

**保留（刻意的）**：
- `RIVER_BAND` / `RIVER_FLOW` 两个字段仍在，但**恒返回 0**。
  因为 `data/minecraft/worldgen/density_function/overworld/ridges.json` 覆写引用了 `RIVER_BAND`，
  删字段会让数据包加载失败。
- `terrainHeight` 回到**逐点解析基面**（不再走 16 格网格）。
- `EROSION` 只保留山脊项，不再掺河道强度。

**备份**：删除的 5 个文件在 `temp/removed-water/`（该目录不入版本库）。
L6 群系、矿床、植被等其它功能不受影响。
