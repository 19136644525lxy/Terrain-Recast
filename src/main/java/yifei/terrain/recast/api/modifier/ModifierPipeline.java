package yifei.terrain.recast.api.modifier;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import yifei.terrain.recast.api.CellDataView;
import yifei.terrain.recast.worldgen.cell.Cell;

/**
 * DataModifier 责任链管道。附属注册变换器，按优先级递减逐个调用，首个非 null 拍板。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>线程安全</b>：注册表用 {@link CopyOnWriteArrayList}，生成期读多写少，读不用加锁；</li>
 *   <li><b>高优先级在前</b>：{@code Integer.MAX_VALUE} 既是最高也是"尽量后置"的兜底语义，
 *       不关心顺序的节点都用它，实际靠注册顺序；</li>
 *   <li><b>链式</b>：支持把老节点替换为新节点（{@link #replace}），等同 AOP 切面，利于附属覆盖默认行为。</li>
 * </ul>
 */
public final class ModifierPipeline {

    private final List<Node> nodes = new CopyOnWriteArrayList<>();

    private record Node(int priority, DataModifier modifier) {}

    /** 注册变换器，返回撤销令牌。 */
    public AutoCloseable register(int priority, DataModifier modifier) {
        Objects.requireNonNull(modifier, "modifier");
        Node node = new Node(priority, modifier);
        nodes.add(node);
        nodes.sort(Comparator.comparingInt(Node::priority).reversed());
        return () -> nodes.remove(node);
    }

    /** 便捷：以最高优先级注册（覆盖默认行为）。 */
    public AutoCloseable registerFirst(DataModifier modifier) {
        return register(Integer.MAX_VALUE, modifier);
    }

    /** 便捷：以最低优先级注册（兜底，不覆盖任何现存节点）。 */
    public AutoCloseable registerLast(DataModifier modifier) {
        return register(Integer.MIN_VALUE, modifier);
    }

    /**
     * 按责任链求最终值：任一节点拍板即返回，否则回落原值。
     *
     * <p>调用点在 {@code RealCellLookup.sampleField} 的出口。热路径应先判 {@link #isEmpty()}，
     * 空链直接跳过——这样未安装任何附属时零开销、零分配。
     */
    public double apply(Cell.Field field, int blockX, int blockZ,
                        double original, CellDataView cell) {
        for (Node node : nodes) {
            Double v = node.modifier().modify(
                    new DataModifier.ModifyContext(blockX, blockZ, field, original, cell));
            if (v != null) return v;
        }
        return original;
    }

    /** 是否有任何已注册节点。热路径短路用。 */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int size() {
        return nodes.size();
    }

    /** 清空全部节点（世界卸载/重载时）。 */
    public void clear() {
        nodes.clear();
    }
}