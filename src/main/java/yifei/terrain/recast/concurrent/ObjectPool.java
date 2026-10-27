package yifei.terrain.recast.concurrent;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * 通用对象池。线程安全，生命周期绑定——持有者释放池时调 {@link #close()}。
 *
 * <p>为避免 GC 压力，Cell 等高频创建对象统一经此池复用。
 *
 * @param <T> 池中对象类型
 */
public final class ObjectPool<T> implements AutoCloseable {

    private final Queue<T> idle = new ConcurrentLinkedQueue<>();
    private final Supplier<T> factory;
    private final java.util.function.Consumer<T> resetter;
    private volatile boolean closed;

    /**
     * @param factory 无参构造函数，生产新对象
     * @param resetter 归还时重置字段到初始状态，避免脏数据泄漏
     */
    public ObjectPool(Supplier<T> factory, java.util.function.Consumer<T> resetter) {
        this.factory = Objects.requireNonNull(factory);
        this.resetter = Objects.requireNonNull(resetter);
    }

    /** 借一个对象，空闲队列空则新建。 */
    public T borrow() {
        if (closed) throw new IllegalStateException("ObjectPool 已关闭");
        T t = idle.poll();
        return t != null ? t : factory.get();
    }

    /** 归还对象，先 reset 再入队。 */
    public void giveBack(T t) {
        if (closed) return; // 池已关，允许直接丢弃
        resetter.accept(t);
        idle.offer(t);
    }

    /** 预填充 N 个对象，降低后续首次创建开销。 */
    public void prefill(int count) {
        if (closed) return;
        for (int i = 0; i < count; i++) idle.offer(factory.get());
    }

    /** 关闭池，丢弃所有空闲对象。生命周期末尾调。 */
    @Override
    public void close() {
        closed = true;
        idle.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    public int idleCount() {
        return idle.size();
    }
}
