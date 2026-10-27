package yifei.terrain.recast.worldgen.materialrule;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

import org.jspecify.annotations.Nullable;

import yifei.terrain.recast.api.TerrainRecastApi;
import yifei.terrain.recast.worldgen.cell.Cell;
import yifei.terrain.recast.worldgen.densityfunction.StrataDensityFunction;
import yifei.terrain.recast.worldgen.densityfunction.StrataDensityFunction.Lithology;

/**
 * 岩性落块规则——把 {@link StrataDensityFunction} 判定的岩性编码映射为具体方块。
 *
 * <p>实现为 {@link MaterialRule} + {@link RuleEvaluator}：按方块坐标调用
 * {@link StrataDensityFunction#evaluate} 求得岩性编码，再映射为方块状态。
 *
 * <p>关键设计（放在原版 overworld 顶层序列<b>末尾</b>）：
 * 玄武岩/花岗岩/砂岩等只替换原版留给 default_block=stone 的石身体；
 * 地表草土、深板岩、基岩、矿脉会先被序列中前面的规则命中，故不会被覆盖。
 *
 * <p>岩性→方块映射（因 26.3 无 LIMESTONE 方块，用 CALCITE 代理）：
 * 1 花岗岩→GRANITE，2 玄武岩→BASALT，3 石灰岩→CALCITE，
 * 4 砂岩→SANDSTONE，5 页岩→ANDESITE(代理)，6 片麻岩→STONE，7 板岩→DEEPSLATE(代理)。
 * 0/8/9（NONE/深板岩/基岩）返回 null，交还原版规则接住。
 */
public record LithologyRule() implements MaterialRule {

    public static final MapCodec<LithologyRule> CODEC = MapCodec.unit(new LithologyRule());

    @Override
    public MapCodec<? extends MaterialRule> codec() {
        return CODEC;
    }

    @Override
    public RuleEvaluator compile(MaterialRuleContext context) {
        // 每区块一个独立求值器，内部持有列缓存（x,z 层始终内嵌在 y 外层循环，
        // 故同列会连续求值）；各区块线程各持实例，无共享状态，线程安全。
        return new ColumnCachedEvaluator();
    }

    /** 列缓存求值器：三个 cell 字段仅依赖 XZ，每列只查一次，避免高域下逐块重复。 */
    private static final class ColumnCachedEvaluator implements RuleEvaluator {
        private boolean cached;
        private int cacheX;
        private int cacheZ;
        private double surface;
        private double marine;
        private double weird;

        @Override
        public @Nullable BlockState tryApply(int blockX, int blockY, int blockZ) {
            if (!(cached && blockX == cacheX && blockZ == cacheZ)) {
                var lookup = TerrainRecastApi.getCellLookup();
                if (lookup == null) {
                    surface = 0;
                    marine = 0;
                    weird = 0;
                } else {
                    surface = lookup.sampleField(Cell.Field.TERRAIN_HEIGHT, blockX, blockZ);
                    marine = lookup.sampleField(Cell.Field.CONTINENTALNESS, blockX, blockZ);
                    weird = lookup.sampleField(Cell.Field.WEIRDNESS, blockX, blockZ);
                }
                cacheX = blockX;
                cacheZ = blockZ;
                cached = true;
            }
            int lith = Math.round(StrataDensityFunction.evaluate(blockX, blockY, blockZ, surface, marine, weird));
            return switch (lith) {
            case Lithology.GRANITE -> Blocks.GRANITE.defaultBlockState();
            case Lithology.BASALT -> Blocks.BASALT.defaultBlockState();
            case Lithology.LIMESTONE -> Blocks.CALCITE.defaultBlockState();
            case Lithology.SANDSTONE -> Blocks.SANDSTONE.defaultBlockState();
            case Lithology.SHALE -> Blocks.ANDESITE.defaultBlockState();
            case Lithology.GNEISS -> Blocks.STONE.defaultBlockState();
            case Lithology.SLATE -> Blocks.DEEPSLATE.defaultBlockState();
            default -> null; // NONE / DEEPSLATE / BEDROCK：交还原版接住
        };
        }
    }
}