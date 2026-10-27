package yifei.terrain.recast.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.worldgen.biome.WhittakerBiome;
import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.cell.CellLookup;

/**
 * 程序化植被地物——由本项目 Cell 管线驱动、随气候/海拔自适应的自绘树木。
 *
 * <p>设计：不自定义 TrunkPlacer/FoliagePlacer（26.3 codec 限制 base_height≤32、树高
 * MAX_HEIGHT=80，配不上本项目可达 y≈1500+ 的超高山地），而是直接在此 Feature 内读取
 * {@link TerrainRecastApi} 的 CellLookup，按 温度/湿度/海拔/陡峭度 自行决定 树种+高度+
 * 密度，再用 {@code setBlock(…, 19)} 原语自绘树干与树冠。
 *
 * <p>放置时机：placed feature 经 {@code heightmap(WORLD_SURFACE_WG)} 把 origin 放到地表
 * 方块；RealCellLookup 已在 SERVER_STARTING（区块生成前）注入，故此处取数是真实值。
 */
public record TerrainVegetationFeature(float density) implements Feature {

    public static final MapCodec<TerrainVegetationFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("density", 1.0f).forGetter(TerrainVegetationFeature::density)
    ).apply(i, TerrainVegetationFeature::new));

    /** 与 TreeFeature.BLOCK_UPDATE_FLAGS 一致：含 SOIL_REPAIR，让树叶/树枝邻接自动纠正。 */
    private static final int BLOCK_UPDATE_FLAGS = 19;
    /** 海底/低地阈值：terrainHeight（相对海平面的 Cell 高度）低于此视为水下区域，不生成。 */
    private static final double MIN_LAND_CELL = 5.0;
    /** 陡坡阈值：steepness 高于此不在该列生树。 */
    private static final double MAX_STEEPNESS = 0.72;

    @Override
    public MapCodec<? extends Feature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin) {
        CellLookup lookup = TerrainRecastApi.getCellLookup();
        // 地表方块必须是空气（heightmap 可能落在含水/海面），水下直接失败
        if (lookup == null || !level.getBlockState(origin).isAir()) {
            return false;
        }
        // 支撑方块必须能长树：泥土/沙/沙砾/雪。防悬空、防石顶、防水下。
        BlockState below = level.getBlockState(origin.below());
        if (!isValidSupport(below)) {
            return false;
        }

        int x = origin.getX();
        int z = origin.getZ();
        double terrainH = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, x, z);
        double temp = lookup.sampleField(Cell.Field.TEMPERATURE, x, z);
        double moist = lookup.sampleField(Cell.Field.MOISTURE, x, z);
        double steep = lookup.sampleField(Cell.Field.STEEPNESS, x, z);
        if (terrainH < MIN_LAND_CELL || steep > MAX_STEEPNESS) {
            return false;
        }

        // L6：Whittaker 过渡带抽样决定群系，再按群系选树种与密度
        int biome = WhittakerBiome.pick(temp, moist, terrainH, random);
        if (random.nextFloat() > densityFor(biome) * this.density) {
            return false;
        }
        Species species = Species.forBiome(biome, random);
        // 高度随海拔线性压矮：高海拔（雪线之上）成矮针叶，避免在 y1500 的绝壁上长巨树。
        double altFactor = clamp(1.35 - 0.008 * Math.max(0, origin.getY() - 110.0), 0.55, 1.0);
        int height = species.height(random, altFactor);
        // 世界顶保护：树干+树冠不得超过 world top。
        int maxAllowed = level.getMaxY() - origin.getY() - 2;
        if (height > maxAllowed) {
            height = Math.max(2, maxAllowed);
        }
        if (height < 2) {
            return false;
        }

        draw(level, species, random, origin, height);

        // density 控制单次尝试额外产树数量（0~1 浮点→随机 0/1/2 棵）。
        int extra = density >= 1.0f ? 1 + random.nextInt(2) : (random.nextFloat() < density ? 1 : 0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < extra; i++) {
            int dx = random.nextInt(9) - 4;
            int dz = random.nextInt(9) - 4;
            pos.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
            if (level.getBlockState(pos).isAir() && isValidSupport(level.getBlockState(pos.below()))) {
                int h2 = Math.min(species.height(random, altFactor), level.getMaxY() - pos.getY() - 2);
                if (h2 >= 2) {
                    draw(level, species, random, pos, h2);
                }
            }
        }
        return true;
    }

    /** 自绘树干与树冠。 */
    private static void draw(WorldGenLevel level, Species s, RandomSource random, BlockPos origin, int height) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 树干：从地表向上直干
        for (int y = 0; y < height; y++) {
            pos.set(origin.getX(), origin.getY() + y, origin.getZ());
            set(level, pos, s.log);
        }

        // 树冠
        s.drawCrown(level, random, pos, origin, height);
    }

    private static void set(WorldGenLevel level, BlockPos.MutableBlockPos pos, BlockState state) {
        // 只覆盖空气或可被树替换的方块，避免填掉树身间隙里的其他地物
        BlockState cur = level.getBlockState(pos);
        if (cur.isAir() || cur.is(BlockTags.REPLACEABLE_BY_TREES)) {
            level.setBlock(pos, state, BLOCK_UPDATE_FLAGS);
        }
    }

    /** 支撑方块判定（包可见）：泥土/沙/沙砾/雪均可立足。供植被与地表覆盖物地物共用。 */
    static boolean isValidSupport(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SNOW_BLOCK);
    }

    /**
     * 各群系的成树密度（0~1）。苔原/荒漠稀疏，森林茂密——
     * 这是 L6 群系真正"可见"的地方：同样的放置次数，不同群系长出的树量差好几倍。
     */
    private static float densityFor(int biome) {
        return switch (biome) {
            case WhittakerBiome.OCEAN, WhittakerBiome.BEACH -> 0f;
            case WhittakerBiome.SNOW -> 0.05f;
            case WhittakerBiome.TUNDRA -> 0.25f;
            case WhittakerBiome.ALPINE -> 0.20f;
            case WhittakerBiome.DESERT -> 0.10f;
            case WhittakerBiome.SAVANNA -> 0.45f;
            case WhittakerBiome.GRASSLAND -> 0.60f;
            case WhittakerBiome.TAIGA -> 0.75f;
            default -> 1.0f; // FOREST
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 树种规格：方块 + 高度 + 树冠画法。 */
    private enum Species {
        SPRUCE(Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState(), 6) {
            @Override int rawHeight(RandomSource r) { return 6 + r.nextInt(5); }
            // 针叶树：上半逐层收窄成锥，直接到顶不越界
            @Override void drawCrown(WorldGenLevel level, RandomSource r, BlockPos.MutableBlockPos pos, BlockPos origin, int h) {
                int start = (int) Math.round(h * 0.4);
                for (int y = start; y < h; y++) {
                    crownDisc(level, r, pos, origin.getX(), origin.getY() + y, origin.getZ(), Math.max(1, h - y), 0.8, this.leaf);
                }
            }
        },
        OAK(Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), 3) {
            @Override int rawHeight(RandomSource r) { return 3 + r.nextInt(4); }
            @Override void drawCrown(WorldGenLevel level, RandomSource r, BlockPos.MutableBlockPos pos, BlockPos origin, int h) {
                int topY = origin.getY() + h;
                crownDisc(level, r, pos, origin.getX(), topY, origin.getZ(), 2, 0.85, this.leaf);
                crownDisc(level, r, pos, origin.getX(), topY - 1, origin.getZ(), 2, 0.75, this.leaf);
            }
        },
        ACACIA(Blocks.ACACIA_LOG.defaultBlockState(), Blocks.ACACIA_LEAVES.defaultBlockState(), 3) {
            @Override int rawHeight(RandomSource r) { return 2 + r.nextInt(2); }
            // 合欢伞形：树干顶端平铺
            @Override void drawCrown(WorldGenLevel level, RandomSource r, BlockPos.MutableBlockPos pos, BlockPos origin, int h) {
                int topY = origin.getY() + h;
                crownDisc(level, r, pos, origin.getX(), topY, origin.getZ(), 2, 0.95, this.leaf);
                crownDisc(level, r, pos, origin.getX(), topY - 1, origin.getZ(), 1, 0.9, this.leaf);
            }
        },
        JUNGLE(Blocks.JUNGLE_LOG.defaultBlockState(), Blocks.JUNGLE_LEAVES.defaultBlockState(), 5) {
            @Override int rawHeight(RandomSource r) { return 5 + r.nextInt(5); }
            @Override void drawCrown(WorldGenLevel level, RandomSource r, BlockPos.MutableBlockPos pos, BlockPos origin, int h) {
                int topY = origin.getY() + h;
                crownDisc(level, r, pos, origin.getX(), topY, origin.getZ(), 2, 0.85, this.leaf);
                crownDisc(level, r, pos, origin.getX(), topY - 1, origin.getZ(), 3, 0.7, this.leaf);
                crownDisc(level, r, pos, origin.getX(), topY - 2, origin.getZ(), 2, 0.8, this.leaf);
            }
        };

        final BlockState log;
        final BlockState leaf;
        private final int base;

        Species(BlockState log, BlockState leaf, int base) {
            this.log = log;
            this.leaf = leaf;
            this.base = base;
        }

        abstract int rawHeight(RandomSource r);

        abstract void drawCrown(WorldGenLevel level, RandomSource r, BlockPos.MutableBlockPos pos, BlockPos origin, int h);

        /** 实际高度 = 基部段 + 海拔矮化系数 × 生长段。高海拔处整树同比矮化。 */
        int height(RandomSource r, double altFactor) {
            return (int) Math.round(base + altFactor * rawHeight(r));
        }

        static Species forBiome(int biome, RandomSource random) {
            return switch (biome) {
                case WhittakerBiome.TUNDRA, WhittakerBiome.TAIGA, WhittakerBiome.ALPINE -> SPRUCE;
                case WhittakerBiome.SAVANNA, WhittakerBiome.DESERT -> ACACIA;
                case WhittakerBiome.FOREST -> random.nextFloat() < 0.30f ? JUNGLE : OAK;
                case WhittakerBiome.GRASSLAND -> random.nextFloat() < 0.25f ? OAK : ACACIA;
                default -> OAK;
            };
        }

        /** 画一层水平圆盘树冠，沿半径概率留洞，制造自然空隙。 */
        static void crownDisc(WorldGenLevel level, RandomSource r, BlockPos.MutableBlockPos pos,
                              int cx, int cy, int cz, int radius, double fill, BlockState leaf) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d <= radius + 0.5 && r.nextFloat() < fill) {
                        pos.set(cx + dx, cy, cz + dz);
                        set(level, pos, leaf);
                    }
                }
            }
        }
    }
}