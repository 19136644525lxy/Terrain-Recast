package yifei.terrain.recast.worldgen.cell;

/**
 * CellLookup 的 stub 实现。所有查询返回 null，让 DensityFunction 节点拿到 fallback（0）。
 * 二期 L0 板块推演完成后替换为真正实现。
 */
public final class NoopCellLookup implements CellLookup {

    @Override public Cell getCell(int chunkX, int chunkZ) { return null; }
    @Override public boolean isEmpty() { return true; }
    @Override public void close() {}
}
