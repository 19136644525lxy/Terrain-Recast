package yifei.terrain.recast.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 预设配置处理器。持有当前世界的 {@link ConfigState} 实例。
 *
 * <p>设计取舍：Tectonic 的 ConfigHandler.getState() 是全局静态——TRC 同样需要静态入口
 * 让 {@code ConfigConstant} / {@code ConfigNoise} 的 MapCodec（可序列化 DensityFunction）
 * 在反序列化后能重新拿到配置值；但 ConfigState 实例由 {@code RegistryBootstrap}
 * 注入，生命周期绑定 MinecraftServer（世界卸载即 GC）。
 *
 * <p>**不使用 ThreadLocal**——UTF 在这踩过坑：线程池复用时 ThreadLocal 值残留 → 串扰。
 * 改用简单的 volatile 静态引用，单 MinecraftServer 环境下足够安全。
 *
 * <p>配置项集中在 {@link ConfigOption} 枚举，做默认值/范围夹取/双语文案单一事实来源。
 * 读写走 JSON 文件（世界级，由加载器注入路径），预设 = 覆盖一批 key 的快捷方式。
 */
public final class ConfigHandler {

    private static volatile ConfigState current;

    private ConfigHandler() {}

    /** ServerStartup 事件时注入。 */
    public static void setState(ConfigState state) {
        current = state;
    }

    /** 世界卸载时清空引用，让 ConfigState 可 GC。 */
    public static void clear() {
        current = null;
    }

    /** 取当前状态，可能返回 null（模组未启用）。 */
    public static ConfigState getState() {
        return current;
    }

    /** 按 key 取数值。未配置或无状态时返回紧邻的默认值（fallback）。 */
    public static double getValue(String key) {
        ConfigState s = current;
        return s != null ? s.getValue(key) : fallbackOf(key);
    }

    /**
     * 配置化采样取值入口（供 RealCellLookup 等热路径读取）。
     * 取值并按选项范围夹取，保证外部 JSON 改动也不会产生非法地形参数。
     */
    public static double tuning(String key, double fallback) {
        ConfigState s = current;
        ConfigOption opt = ConfigOption.byKey(key);
        double min = opt != null ? opt.min() : Double.NEGATIVE_INFINITY;
        double max = opt != null ? opt.max() : Double.POSITIVE_INFINITY;
        double v = s != null ? s.getValue(key) : fallback;
        return clamp(v, min, max);
    }

    // ===== 构造 / 持久化 =====

    /** 创建带全部默认值的状态。 */
    public static ConfigState createDefaultState() {
        DefaultConfigState s = new DefaultConfigState();
        for (ConfigOption opt : ConfigOption.values()) {
            s.putRaw(opt.key(), opt.defaultValue());
        }
        return s;
    }

    /** 从 JSON 文件加载值到状态，未知 key 忽略。返回是否有文件。 */
    public static boolean load(ConfigState state, Path file) {
        if (!Files.isRegularFile(file)) return false;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            for (java.util.Map.Entry<String, JsonElement> e : root.entrySet()) {
                ConfigOption opt = ConfigOption.byKey(e.getKey());
                if (opt == null) continue; // 未知/废弃 key 静默跳过
                double v = e.getValue().getAsDouble();
                state.putValue(opt.key(), clamp(v, opt.min(), opt.max()));
            }
            return true;
        } catch (RuntimeException | IOException e) {
            fallbackLogger().warn("Failed to load config from {}, using defaults", file, e);
            return false;
        }
    }

    /** 把状态写回 JSON 文件。 */
    public static void save(ConfigState state, Path file) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (ConfigOption opt : ConfigOption.values()) {
                root.addProperty(opt.key(), roundTo(state.getValue(opt.key())));
            }
            Files.writeString(file, gsonPretty(root), StandardCharsets.UTF_8);
        } catch (RuntimeException | IOException e) {
            fallbackLogger().error("Failed to save config to {}", file, e);
        }
    }

    private static String gsonPretty(JsonObject root) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /** 保留最多 4 位小数的紧凑值，配置文件更可读。 */
    private static double roundTo(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double fallbackOf(String key) {
        ConfigOption opt = ConfigOption.byKey(key);
        return opt != null ? opt.defaultValue() : 0;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    /** 无日志依赖时的降级 logger（避免 config 包直接依赖 Fabric/Mojang 日志）。 */
    private static org.slf4j.Logger fallbackLogger() {
        return org.slf4j.LoggerFactory.getLogger("terrain_recast/config");
    }

    // ===== ConfigState 实现 =====

    /** 线程安全、带范围夹取的实现。 */
    private static final class DefaultConfigState implements ConfigState {
        private final ConcurrentMap<String, Double> values = new ConcurrentHashMap<>();

        @Override
        public double getValue(String key) {
            ConfigOption opt = ConfigOption.byKey(key);
            double v = values.getOrDefault(key, opt != null ? opt.defaultValue() : 0.0);
            if (opt != null) v = clamp(v, opt.min(), opt.max());
            return v;
        }

        @Override
        public void putValue(String key, double value) {
            ConfigOption opt = ConfigOption.byKey(key);
            values.put(key, opt != null ? clamp(value, opt.min(), opt.max()) : value);
        }

        /** 内部直写（构造默认值时用，跳过夹取仍安全，因默认值都在范围内）。 */
        void putRaw(String key, double value) { values.put(key, value); }
    }
}