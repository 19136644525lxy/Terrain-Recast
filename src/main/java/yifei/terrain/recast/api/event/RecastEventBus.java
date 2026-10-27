package yifei.terrain.recast.api.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import yifei.terrain.recast.TerrainRecast;

/**
 * 轻量事件总线——附属模组在世界生成生命周期关键节点订阅/发布事件的唯一出口。
 *
 * <p>设计要点（遵循接口隔离 + 迪米特法则）：
 * <ul>
 *   <li><b>线程安全</b>：订阅列表用 {@link CopyOnWriteArrayList}，发布时遍历快照，天然免并发修改异常；</li>
 *   <li><b>单异常不崩溃</b>：一个订阅者抛异常只记日志，不中断其余订阅者、不污染生成线程；</li>
 *   <li><b>禁止 ThreadLocal</b>：事件对象入参传递，不依赖线程上下文，跨线程发布安全；</li>
 *   <li><b>附带/取消订阅</b>：{@link Subscription#close()} 幂等，生命周期末尾可撤销。</li>
 * </ul>
 *
 * @param <T> 事件载荷类型（不可变数据容器）
 */
public final class RecastEventBus<T> {

    /** 订阅令牌：close() 幂等撤销订阅，防重复解绑。 */
    public final class Subscription implements AutoCloseable {
        private final Consumer<T> listener;
        private volatile boolean active = true;

        private Subscription(Consumer<T> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            if (active) {
                active = false;
                listeners.remove(listener);
            }
        }
    }

    private final CopyOnWriteArrayList<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    /** 订阅事件。返回令牌，用 {@link Subscription#close()} 撤销。 */
    public Subscription subscribe(Consumer<T> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return new Subscription(listener);
    }

    /** 发布事件。按订阅顺序同步分发；任一订阅者异常被隔离，不中断后续。 */
    public void post(T payload) {
        List<Consumer<T>> snapshot = listeners;
        for (Consumer<T> l : snapshot) {
            try {
                l.accept(payload);
            } catch (RuntimeException e) {
                TerrainRecast.LOGGER.error("事件订阅者异常（已隔离）", e);
            }
        }
    }

    public int subscriberCount() {
        return listeners.size();
    }

    /** 清空全部订阅（模组卸载/世界重建时）。 */
    public void clear() {
        listeners.clear();
    }
}