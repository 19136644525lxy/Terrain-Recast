package yifei.terrain.recast.worldgen.climate;

import yifei.terrain.recast.api.CellDataView;

/**
 * Whittaker 群系图 + ClimateBridge 工具——把 TRC 六维气候参数映射到原版七维 Climate.Parameter。
 *
 * <p>设计背景：
 * <ul>
 *   <li>TRC 用六维气候驱动 L6 Whittaker 群系图：纬度/海拔/湿度/侵蚀/怪异度/大陆性</li>
 *   <li>原版 Climate.Parameter 是七维：temperature/humidity/continentalness/erosion/depth/weirdness/offset</li>
 *   <li>两者不匹配 → 自定义群系能走 Whittaker 图，第三方群系（TerraBlender 注册的）需要桥接到原版七维空间</li>
 * </ul>
 *
 * <p>ClimateBridge 做的事：
 * <pre>
 * TRC 六维                             →  原版七维近似
 * temperature（纬度+海拔合成）           →  temperature
 * moisture（湿度）                     →  humidity
 * continentalness（大陆性）             →  continentalness
 * erosion（侵蚀）                      →  erosion
 * （TRC 无直接对应）                    →  depth ≈ -terrainHeight 归一化
 * weirdness（怪异度）                  →  weirdness
 * （TRC 无）                           →  offset = 0
 * </pre>
 *
 * <p>三期骨架：接口定义 + 简化实现。真正的 TerraBlender 兼容层在 ClimateBridge 之上。
 */
public final class ClimateBridge {

    private ClimateBridge() {}

    /**
     * TRC 六维气候参数 → 原版 Climate.Parameter 七维近似映射结果。
     * 记录为裸 double 数组（避免依赖原版 Climate.Parameter record，减少跨版本耦合）。
     *
     * <p>数组索引：[temperature, humidity, continentalness, erosion, depth, weirdness, offset]
     */
    public record BridgedClimate(double[] values) {
        public double temperature()    { return values[0]; }
        public double humidity()       { return values[1]; }
        public double continentalness(){ return values[2]; }
        public double erosion()        { return values[3]; }
        public double depth()          { return values[4]; }
        public double weirdness()      { return values[5]; }
        public double offset()         { return values[6]; }
    }

    /**
     * 从 Cell 只读视图桥接到原版气候参数。
     *
     * @param cell  Cell 视图
     * @param seaLevel 当前海平面（原版=63，TRC 保守档位=63）
     */
    public static BridgedClimate bridge(CellDataView cell, int seaLevel) {
        double terrainHeight = cell.getTerrainHeight();
        // depth ≈ (terrainHeight - seaLevel) / 最大可能高度 → 归一化到 [-1, 1]
        // 256 的假设：保守档位 height=1024，min_y=-256，范围 1280
        // 简化：depth 用 terrainHeight 相对于海平面的偏差
        double depthNorm = Math.max(-1, Math.min(1, (terrainHeight - seaLevel) / 256.0));

        double[] values = new double[]{
                cell.getTemperature(),           // temperature（TRC 已经是 [-0.5, 0.5] 语义）
                cell.getMoisture(),              // humidity [0, 1] → 原版 humidity 也 [0, 1]
                cell.getContinentalness(),       // continentalness [-1, 1] → 原版同
                cell.getErosion(),               // erosion [0, 1] → 原版同
                depthNorm,                       // depth（近似）
                cell.getWeirdness(),             // weirdness [-1, 1] → 原版同
                0.0                              // offset（TRC 无对应，固定 0）
        };
        return new BridgedClimate(values);
    }
}
