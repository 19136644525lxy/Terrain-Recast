package yifei.terrain.recast.api.ore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.world.level.block.state.BlockState;

import org.jspecify.annotations.Nullable;

/**
 * 矿床注册器——附属模组按岩性登记矿种，地质矿床地物据此做岩性-矿种绑定。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>线程安全</b>：注册表 CopyOnWriteArrayList（初始化期写、生成期读）；</li>
 *   <li><b>开闭原则</b>：core 提供内置铁/金/钻石/煤登记，附属随时追加自己的矿床不侵入 core；</li>
 *   <li><b>防内存泄漏</b>：生命周期由持有者（RegistryBootstrap）在 clear() 时释放，不搞后台线程。</li>
 * </ul>
 */
public final class OreRegistry {

    private final CopyOnWriteArrayList<OreDeposit> deposits = new CopyOnWriteArrayList<>();

    /** 注册矿床。返回撤销令牌（正反向都对称）。 */
    public AutoCloseable register(OreDeposit deposit) {
        deposits.add(deposit);
        return () -> deposits.remove(deposit);
    }

    /** 按岩性取全部对应矿床。不匹配返回空列表（非 null）。 */
    public List<OreDeposit> byLithology(int lithology) {
        return deposits.stream().filter(d -> d.lithology() == lithology).toList();
    }

    /**
     * 按矿块反查矿床定义——给 placed feature 声明的矿种套用注册表里的
     * 规模/丰度/目标岩性（地物只在 JSON 里写 {@code "ore"}，其余参数来自这里）。
     *
     * <p>遍历而非建索引：注册表只有个位数条目，且只在矿脉放置时命中一次，
     * 换取"附属随时热插拔"的简单性。
     *
     * @return 匹配的矿床定义；未登记返回 null（调用方回落到 JSON 默认值）
     */
    public @Nullable OreDeposit findByOre(BlockState ore) {
        for (OreDeposit d : deposits) {
            // BlockState 全部由方块注册表内部创建并复用，身份比较即可
            if (d.ore() == ore) return d;
        }
        return null;
    }

    public int size() {
        return deposits.size();
    }

    /** 清空全部（世界卸载/重载）。 */
    public void clear() {
        deposits.clear();
    }
}