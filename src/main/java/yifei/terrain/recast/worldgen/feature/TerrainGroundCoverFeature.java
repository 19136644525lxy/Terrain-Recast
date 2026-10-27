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
 * 地表覆盖物地物——在平缓的草/沙地上按 Cell 气候铺设 草/花/蕨/仙人掌/枯丛。
 *
 * <p>与 {@link TerrainVegetationFeature} 形成互补：树照顾上层，本类负责脚下灌木层。
 * 同样读 Cell 管线（温度/湿度/海拔），避免在高山、深海、陡坡铺植被；只放 1×1 顶层方块，
 * 不引入 TrunkPlacer 等原版树高约束。
 *
 * <p>覆盖物分类（温度/湿度五档，与树分类对齐）：
 * <ul>
 *   <li>干（moist 低）：仙人掌+枯丛（沙地）；</li>
 *   <li>冷（temp 低）：稀疏矮草，不铺花；</li>
 *   <li>暖湿：花+高草丛（蕨/花点缀）；</li>
 *   <li>默认（温带/平原）：草+蒲公英/虞美人。</li>
 * </ul>
 */
public record TerrainGroundCoverFeature(float density) implements Feature {

    public static final MapCodec<TerrainGroundCoverFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("density", 1.0f).forGetter(TerrainGroundCoverFeature::density)
    ).apply(i, TerrainGroundCoverFeature::new));

    /** 与植被地物一致：含 SOIL_REPAIR。 */
    private static final int BLOCK_UPDATE_FLAGS = 19;
    private static final double MIN_LAND_CELL = 5.0;
    private static final double MAX_STEEPNESS = 0.6;

    @Override
    public MapCodec<? extends Feature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin) {
        CellLookup lookup = TerrainRecastApi.getCellLookup();
        if (lookup == null || !level.getBlockState(origin).isAir()) {
            return false;
        }
        BlockState support = level.getBlockState(origin.below());
        if (!TerrainVegetationFeature.isValidSupport(support)) {
            return false;
        }

        int x = origin.getX();
        int z = origin.getZ();
        double moist = lookup.sampleField(Cell.Field.MOISTURE, x, z);
        double temp = lookup.sampleField(Cell.Field.TEMPERATURE, x, z);
        double terrainH = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, x, z);
        double steep = lookup.sampleField(Cell.Field.STEEPNESS, x, z);
        if (terrainH < MIN_LAND_CELL || steep > MAX_STEEPNESS) {
            return false;
        }

        boolean onSand = support.is(BlockTags.SAND);
        // 铺一条覆盖带：从 origin 辐射 0~3 格，每格随机成败免生硬方阵
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cluster = 1 + random.nextInt(3);
        boolean placedAny = false;
        for (int i = 0; i < cluster; i++) {
            int dx = random.nextInt(5) - 2;
            int dz = random.nextInt(5) - 2;
            pos.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            if (!TerrainVegetationFeature.isValidSupport(level.getBlockState(pos.below()))) {
                continue;
            }
            // 局地再采样一次决定是否落覆盖，形成疏密交错
            double lm = lookup.sampleField(Cell.Field.MOISTURE, pos.getX(), pos.getZ());
            if (random.nextFloat() < coverChance(lm)) {
                // L6：按 Whittaker 群系挑覆盖物（而不是只按温度/湿度分档）
                int biome = WhittakerBiome.classify(temp, lm, terrainH);
                BlockState cover = pickCover(onSand, biome, random);
                if (cover != null && placeSingle(level, pos, cover)) {
                    placedAny = true;
                }
            }
        }
        return placedAny;
    }

    /** 湿度越高，覆盖越密（0~1）。 */
    private static double coverChance(double moist) {
        return Math.max(0.15, Math.min(0.85, moist * 0.95 + 0.1));
    }

    /** 按支撑面 + L6 群系挑覆盖物；不可覆盖的平地返回 null。 */
    private static BlockState pickCover(boolean onSand, int biome, RandomSource r) {
        if (onSand || biome == WhittakerBiome.BEACH) {
            // 沙地：干枯环境长仙人掌簇或枯丛
            return r.nextInt(3) == 0 ? Blocks.CACTUS.defaultBlockState()
                    : (r.nextInt(4) == 0 ? Blocks.DEAD_BUSH.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState());
        }
        return switch (biome) {
            case WhittakerBiome.SNOW, WhittakerBiome.ALPINE -> null;
            case WhittakerBiome.DESERT -> r.nextInt(2) == 0 ? Blocks.DEAD_BUSH.defaultBlockState() : null;
            case WhittakerBiome.TUNDRA -> r.nextInt(4) == 0 ? Blocks.SHORT_GRASS.defaultBlockState() : null;
            case WhittakerBiome.TAIGA ->
                    r.nextInt(3) == 0 ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
            case WhittakerBiome.FOREST -> switch (r.nextInt(6)) {
                case 0 -> Blocks.FERN.defaultBlockState();
                case 1, 2 -> pickFlower(r);
                default -> Blocks.SHORT_GRASS.defaultBlockState();
            };
            case WhittakerBiome.SAVANNA ->
                    r.nextInt(3) == 0 ? pickFlower(r) : Blocks.SHORT_GRASS.defaultBlockState();
            default -> r.nextInt(5) == 0 ? pickFlower(r) : Blocks.SHORT_GRASS.defaultBlockState();
        };
    }

    private static BlockState pickFlower(RandomSource r) {
        return switch (r.nextInt(4)) {
            case 0 -> Blocks.DANDELION.defaultBlockState();
            case 1 -> Blocks.POPPY.defaultBlockState();
            case 2 -> Blocks.AZURE_BLUET.defaultBlockState();
            default -> Blocks.OXEYE_DAISY.defaultBlockState();
        };
    }

    private static boolean placeSingle(WorldGenLevel level, BlockPos.MutableBlockPos pos, BlockState state) {
        // 只覆盖空气或可被替换方块；用最高位旗标不论高处光照
        level.setBlock(pos, state, BLOCK_UPDATE_FLAGS);
        return true;
    }
}