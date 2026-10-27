package yifei.terrain.recast.config;

/**
 * 预设配置状态接口。持有当前世界激活预设的所有数值选项。
 *
 * <p>与 UTF/RTF 的全局静态不同——ConfigState 实例由 {@code ConfigHandler} 持有，
 * 并注入 {@link yifei.terrain.recast.worldgen.densityfunction.ConfigConstant} / {@code ConfigNoise}。
 * 其生命周期与 MinecraftServer 绑定，世界卸载即释放，杜绝 ThreadLocal 全局开关串扰。
 *
 * <p>一期骨架：接口定义 + 极简实现。二期预设系统填充完整。
 */
public interface ConfigState {

    /** 根据选项 key 取当前值。未配置时返回 0（DensityFunction 节点 fallback）。 */
    double getValue(String key);

    /** 设置选项值（预设加载/切换时调）。 */
    void putValue(String key, double value);
}
