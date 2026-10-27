package yifei.terrain.recast.api.modifier;

import yifei.terrain.recast.api.CellDataView;
import yifei.terrain.recast.worldgen.cell.Cell;

/**
 * Cell 字段采样值变换器——附属模组改写/增强场地语义的开放点（开闭原则）。
 *
 * <p>作为<b>责任链</b>的一环：{@link ModifierPipeline} 按优先级从高到低逐个调用，
 * 首个返回非 null 的节点拍板终值；返回 null 表示"不关我事"，交给下一优先级节点。
 *
 * <p>函数式接口：附属可用 lambda。留存接口以便未来接入事件/上下文扩展。
 *
 * <p><b>线程契约</b>：{@code modify} 在世界生成线程上被调用，必须无状态、可重入；
 * 需要读其他字段请用 {@link ModifyContext#cell()} 的只读视图，
 * <b>禁止</b>再调 {@code CellLookup.sampleField(...)}——那会与责任链形成无限递归。
 */
@FunctionalInterface
public interface DataModifier {

    /**
     * 决定指定位置某字段的最终采样值。
     *
     * @param ctx 采样上下文（位置、原值、只读 Cell 视图、字段）
     * @return 覆盖后的值；null 表示不处理，交由更低优先级节点
     */
    Double modify(ModifyContext ctx);

    /** 采样上下文——不可变快照，跨线程安全。 */
    record ModifyContext(int blockX, int blockZ, Cell.Field field,
                          double original, CellDataView cell) {}
}