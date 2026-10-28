# 事件总线

TRC 暴露一个极简的同步事件总线：`RecastEventBus<T>`。没有反射、没有注解、没有优先级，
就是"订阅 → 发布"的 `Consumer<T>` 列表。

## 内置事件

```java
RecastEvents.CHUNK_GENERATED          // RecastEventBus<ChunkGenerated>
RecastEvents.CELL_PIPELINE_APPLIED    // RecastEventBus<CellTick>
```

| 事件 | 载荷 | 触发时机 |
|---|---|---|
| `CHUNK_GENERATED` | `ChunkGenerated(int chunkX, int chunkZ)` | 区块**真正生成**完成（从盘加载不会触发） |
| `CELL_PIPELINE_APPLIED` | `CellTick(CellDataView view)` | 一个 Cell 的 L0–L6 推演完成、写入缓存时 |

两个事件都是**在主线程**（区块生成完成回调 / 缓存写入路径）同步发布的，不要在订阅者里做重活。

## 订阅

```java
import yifei.terrain.recast.api.event.RecastEvents;
import yifei.terrain.recast.api.event.RecastEventBus;

public final class MyAddon implements ModInitializer {
    private RecastEventBus.Subscription chunkSub;

    @Override
    public void onInitialize() {
        chunkSub = RecastEvents.CHUNK_GENERATED.subscribe(ev -> {
            // 每生成一个区块触发一次
        });
    }
}
```

`subscribe` 返回 `RecastEventBus<T>.Subscription`（实现 `AutoCloseable`），`close()` 幂等撤销。

## 性能：没有订阅者就没有开销

TRC 在两个发布点都做了短路：

```java
if (RecastEvents.CHUNK_GENERATED.subscriberCount() == 0) return;   // 不构造载荷
```

所以没人订阅时，区块生成路径**零成本**。但一旦你订阅了，载荷对象会为每个区块分配，
订阅者本身也会在主线程执行——保持轻量。

## 示例：统计生成速率

```java
private static final AtomicLong COUNTER = new AtomicLong();

RecastEvents.CHUNK_GENERATED.subscribe(ev -> COUNTER.incrementAndGet());

// 每 5 秒打一行
ServerTickEvents.END_SERVER_TICK.register(server -> {
    if (server.getTickCount() % 100 == 0 && COUNTER.get() > 0) {
        TerrainRecast.LOGGER.info("chunks generated: {}", COUNTER.getAndSet(0));
    }
});
```

## 示例：收集岩性直方图

```java
private static final int[] LITHO = new int[10];

RecastEvents.CELL_PIPELINE_APPLIED.subscribe(ev -> {
    int l = ev.view().getLithology();
    if (l >= 0 && l < LITHO.length) LITHO[l]++;
});
```

`CellTick.view()` 是只读快照，跨线程安全，可以安全地存下来或传给别的线程。

## 异常处理

一个订阅者抛 `RuntimeException` 只会被记日志（`事件订阅者异常（已隔离）`），
不会中断其他订阅者、不会污染生成线程。但**不要依赖这个兜底**——出现异常说明你的逻辑有 bug，
日志里会带完整堆栈。

## 生命周期

- 订阅在模组初始化期随时可做。
- 世界卸载时 TRC 会调 `RecastEvents.clearAll()`（仅在显式 `RegistryBootstrap.close()` 时），
  正常关服**不会**清订阅——你的订阅会跨世界保留。如果你的订阅持有世界对象，请自己管理生命周期。
- 不要订阅后忘记 `close()`：`CopyOnWriteArrayList` 里的 `Consumer` 会一直持有你的对象。
