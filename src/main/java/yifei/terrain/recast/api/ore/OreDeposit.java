package yifei.terrain.recast.api.ore;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 矿床定义——把「岩性 ↔ 矿种」绑定关系的不可变描述。
 *
 * <p>为 7.3 地质逻辑矿床预留的最小单元：给出目标岩性、主矿方块、挖矿尺度与丰度系数。
 * 字段全 final，线程安全，可安全跨线程共享（附属在初始化期注册，世界生成期只读）。
 *
 * @param lithology  目标岩性编码（见 {@code StrataDensityFunction.Lithology}：1=花岗岩 …）
 * @param ore        矿块状态（石变体；深板岩变体由放置端按 Twin 表映射）
 * @param veinSize   典型矿脉规模（放置步数参考）
 * @param richness   成矿丰度系数（0~1，越高越易富集）
 */
public record OreDeposit(int lithology, BlockState ore, int veinSize, double richness) {

    /** 便捷构造：默认矿脉规模+丰度。 */
    public OreDeposit(int lithology, BlockState ore) {
        this(lithology, ore, 9, 0.72);
    }
}