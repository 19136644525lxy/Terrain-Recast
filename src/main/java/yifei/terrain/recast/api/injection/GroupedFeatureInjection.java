package yifei.terrain.recast.api.injection;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * 分组注入 API——把 placed feature 按 generation 步骤批量注入主世界群系。
 *
 * <p>定位：替代散落的 {@code BiomeModifications.addFeature} 零散调用，统一成一个注册表，
 * 用一次 apply 应用全部登记项。附属模组可据此在不动 core 的情况下追加自己的地物。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>线程安全</b>：登记表 CopyOnWriteArrayList，初始化期写、ModInitializer 一次性读；</li>
 *   <li><b>开闭原则</b>：追加新项不触及既有逻辑；撤销令牌对称反注册；</li>
 *   <li><b>生命周期</b>：apply 幂等——同一登记项只下发一次；clear() 释放全部引用。</li>
 * </ul>
 *
 * <p>用法：core 与附属都往这里 {@link #add} 登记，最后调一次 {@link #apply()}。
 * 因为 Fabric 各模组 entrypoint 顺序不保证，附属若在 TRC 初始化之后登记，
 * 可自行再调一次 {@link #apply()}——已下发的条目不会重复注入。
 */
public final class GroupedFeatureInjection {

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    /** 已下发到 BiomeModifications 的条目，保证 apply() 幂等。 */
    private final Set<Entry> applied = ConcurrentHashMap.newKeySet();

    private record Entry(GenerationStep.Decoration step,
                         ResourceKey<PlacedFeature> placed,
                         Predicate<BiomeSelectionContext> selector) {}

    /** 主世界群系选择器（含海洋/山地等全部主世界生物群系）。 */
    public static final Predicate<BiomeSelectionContext> OVERWORLD =
            ctx -> ctx.hasTag(BiomeTags.IS_OVERWORLD);

    /** 登记一个注入项。返回撤销令牌。 */
    public AutoCloseable add(GenerationStep.Decoration step,
                             ResourceKey<PlacedFeature> placed,
                             Predicate<BiomeSelectionContext> selector) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(placed, "placed");
        Entry e = new Entry(step, placed, selector != null ? selector : OVERWORLD);
        entries.add(e);
        // 撤销时同时清"已下发"记录，允许同一条目被重新登记并再次 apply
        return () -> {
            entries.remove(e);
            applied.remove(e);
        };
    }

    /** 登记一个注入项（默认主世界全选）。 */
    public AutoCloseable add(GenerationStep.Decoration step, ResourceKey<PlacedFeature> placed) {
        return add(step, placed, OVERWORLD);
    }

    /** 应用全部登记项到 Fabric biome 注入系统。可重复调用，已下发的条目不会重复注入。 */
    public void apply() {
        for (Entry e : entries) {
            if (applied.add(e)) {
                BiomeModifications.addFeature(e.selector(), e.step(), e.placed());
            }
        }
    }

    public int size() {
        return entries.size();
    }

    /** 清空登记与已下发记录（世界卸载/模组重载时释放引用）。 */
    public void clear() {
        entries.clear();
        applied.clear();
    }
}