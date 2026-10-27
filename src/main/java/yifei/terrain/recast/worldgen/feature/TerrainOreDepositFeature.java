package yifei.terrain.recast.worldgen.feature;

import java.util.Map;

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
import yifei.terrain.recast.api.ore.OreDeposit;
import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.cell.CellLookup;

/**
 * 地质矿床地物——按 Cell 管线的地质省分布矿脉，而非原版全图均匀撒。
 *
 * <p>原理：CellLookup 尚无直接暴露 L5 岩性字段的采样路径，故用两个岩性"代理"给矿床分级：
 * <ul>
 *   <li>侵蚀度 EROSION：低侵蚀＝年轻构造带/火成岩侵入体，利于热液型金矿与岩浆型铜镍；</li>
 *   <li>湿度 MOISTURE：作为岩石含水/风化程度代理，调节成矿丰度。</li>
 * </ul>
 * 两者合成一个成矿门控系数（0~1 以上），极小化随机淘汰，形成"只在若干地质省内富集"的带。
 *
 * <p>岩性-矿种绑定走 {@link yifei.terrain.recast.api.ore.OreRegistry}：JSON 只声明放什么矿，
 * 脉体规模 / 丰度 / 目标岩性由注册表给出（附属可随时追加自己的矿床）。
 * 命中目标岩性 → 门控放宽；不在该岩性省 → 只保留少量异常富集，避免"花岗岩里到处钻石"。
 *
 * <p>矿块选择与深板岩：当前方块落在 {@link BlockTags#STONE_ORE_REPLACEABLES} 放石变体，
 * 落在 {@link BlockTags#DEEPSLATE_ORE_REPLACEABLES} 放对应深板岩变体（由 {@link TWIN} 映射）。
 * 深度带由 placed feature 的 height_range 决定（煤浅、铁中、金中深、钻石深）。
 */
public record TerrainOreDepositFeature(BlockState ore) implements Feature {

    public static final MapCodec<TerrainOreDepositFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BlockState.CODEC.fieldOf("ore").forGetter(TerrainOreDepositFeature::ore)
    ).apply(i, TerrainOreDepositFeature::new));

    /** 与植被一致：含 SOIL_REPAIR。 */
    private static final int BLOCK_UPDATE_FLAGS = 19;

    /** 命中注册岩性省时对成矿门控的加成（省内稳定出矿）。 */
    private static final double LITHOLOGY_BONUS = 0.25;
    /** 非目标岩性省保留的成矿概率：只留少量异常富集，主矿交给对应岩性省。 */
    private static final float OFF_LITHOLOGY_KEEP = 0.30f;

    /** 常见石矿石 → 深板岩变体映射。 */
    private static final Map<BlockState, BlockState> TWIN = Map.of(            Blocks.COAL_ORE.defaultBlockState(),    Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(),
            Blocks.COPPER_ORE.defaultBlockState(),  Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState(),
            Blocks.IRON_ORE.defaultBlockState(),    Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
            Blocks.GOLD_ORE.defaultBlockState(),    Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(),
            Blocks.REDSTONE_ORE.defaultBlockState(),Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState(),
            Blocks.LAPIS_ORE.defaultBlockState(),   Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState(),
            Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(),
            Blocks.EMERALD_ORE.defaultBlockState(), Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState());

    /**
     * 岩性落块规则会铺的宿主岩（花岗岩/玄武岩/方解石/砂岩/安山岩）。
     *
     * <p>它们不在原版 {@code #stone_ore_replaceables} 里（该 tag 只有 stone/granite/diorite/andesite），
     * 但同样是"石身体"；若把矿脉限制在岩性省却又不认这些宿主，注册表的地质绑定会自我抵消。
     */
    private static boolean isLithologyHost(BlockState state) {
        return state.is(Blocks.GRANITE) || state.is(Blocks.BASALT) || state.is(Blocks.CALCITE)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.ANDESITE);
    }

    @Override
    public MapCodec<? extends Feature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin) {
        CellLookup lookup = TerrainRecastApi.getCellLookup();
        if (lookup == null) {
            return false;
        }

        int x = origin.getX();
        int z = origin.getZ();
        // 地质省门控：低侵蚀（年轻/岩浆带）+ 中等湿度 → 倾向成矿。系数越靠近1越易成矿。
        double erosion = lookup.sampleField(Cell.Field.EROSION, x, z);
        double moist = lookup.sampleField(Cell.Field.MOISTURE, x, z);
        double gate = 0.42 + (1.0 - erosion) * 0.30 + (0.5 - Math.abs(moist - 0.5)) * 0.30;

        // ===== API：矿床注册表（岩性 ↔ 矿种 + 脉体规模/丰度）=====
        // JSON 只声明放什么矿；规模、丰度、目标岩性从注册表取（附属可热插拔）。
        int size = 7 + random.nextInt(9);
        double richness = 0.55 + random.nextDouble() * 0.35;
        OreDeposit spec = TerrainRecastApi.getOreRegistry().findByOre(ore);
        if (spec != null) {
            size = Math.max(1, spec.veinSize());
            richness = spec.richness();
            int targetLithology = spec.lithology();
            if (targetLithology != 0) {
                int here = (int) Math.round(lookup.sampleField(Cell.Field.LITHOLOGY, x, z));
                if (here == targetLithology) {
                    gate = Math.min(1.0, gate + LITHOLOGY_BONUS);
                } else if (random.nextFloat() > OFF_LITHOLOGY_KEEP) {
                    return false;
                }
            }
        }

        if (random.nextFloat() > gate) {
            return false;
        }
        BlockPos.MutableBlockPos pos = origin.mutable();
        boolean placed = false;
        int step = 0;
        while (step < size) {
            // 每步 1~3 轴向移动
            int moves = 1 + random.nextInt(3);
            for (int m = 0; m < moves; m++) {
                switch (random.nextInt(6)) {
                    case 0 -> pos.move(1, 0, 0);
                    case 1 -> pos.move(-1, 0, 0);
                    case 2 -> pos.move(0, 1, 0);
                    case 3 -> pos.move(0, -1, 0);
                    case 4 -> pos.move(0, 0, 1);
                    default -> pos.move(0, 0, -1);
                }
            }
            if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) {
                break;
            }
            if (random.nextFloat() <= richness) {
                BlockState cur = level.getBlockState(pos);
                // 深板岩先判（它同时也是"石身体"），保证放下的是深板岩变体
                if (cur.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) {
                    level.setBlock(pos, TWIN.getOrDefault(ore, ore), BLOCK_UPDATE_FLAGS);
                    placed = true;
                } else if (cur.is(BlockTags.STONE_ORE_REPLACEABLES) || isLithologyHost(cur)) {
                    level.setBlock(pos, ore, BLOCK_UPDATE_FLAGS);
                    placed = true;
                }
            }
            step++;
        }
        return placed;
    }
}