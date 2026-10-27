package yifei.terrain.recast.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * TRC1 分享码编解码：把当前配置状态压缩为可复制分享的短字符串。
 *
 * <p>格式：{@code TRC1:}<base64(gzip(json))><crc16 hex>。
 * crc16 为 payload 的 CRC32 低 16 位（2 字节），置于末尾做"首位校验"，
 * 内容错误/被篡改可立即检出。decode 逆流程：未知 key 跳过，数值夹取到选项范围。
 * 仅用 JDK 自带 gzip/base64/crc32，不引外部依赖。
 */
public final class PresetCodec {

    public static final String PREFIX = "TRC1:";
    private static final int CRC_MASK = 0xFFFF;

    private PresetCodec() {}

    /** 把当前配置所有选项压缩为分享码。 */
    public static String encode(ConfigState state) {
        JsonObject root = new JsonObject();
        for (ConfigOption opt : ConfigOption.values()) {
            root.addProperty(opt.key(), roundTo(state.getValue(opt.key())));
        }

        byte[] json = root.toString().getBytes(StandardCharsets.UTF_8);
        byte[] gz = gzip(json);
        long crc = crc32(json);
        String b64 = Base64.getEncoder().withoutPadding().encodeToString(gz);
        return PREFIX + b64 + String.format("%04x", crc & CRC_MASK);
    }

    /**
     * 分享码还原为配置状态。校验失败抛可读的 IllegalArgumentException。
     */
    public static ConfigState decode(String code) {
        String body = code == null ? "" : code.trim();
        if (!body.startsWith(PREFIX)) {
            throw new IllegalArgumentException("不是有效的 TRC1 分享码（缺少前缀 " + PREFIX + "）");
        }
        body = body.substring(PREFIX.length());
        if (body.length() < 5) {
            throw new IllegalArgumentException("分享码过短或已损坏");
        }

        // 末尾 4 个 hex 字符 = crc16 校验，中间为 base64 正文
        String hex = body.substring(body.length() - 4);
        String b64 = body.substring(0, body.length() - 4);

        byte[] gz;
        try {
            gz = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("分享码 Base64 部分无效");
        }

        byte[] json = gunzip(gz);
        int expect;
        try {
            expect = Integer.parseInt(hex, 16) & CRC_MASK;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("分享码校验位格式错误");
        }
        int actual = (int) (crc32(json) & CRC_MASK);
        if (expect != actual) {
            throw new IllegalArgumentException("分享码校验失败（内容被篡改或复制不完整）");
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("分享码负载不是有效 JSON", e);
        }

        ConfigState state = ConfigHandler.createDefaultState();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            ConfigOption opt = ConfigOption.byKey(e.getKey());
            if (opt == null) continue; // 未知/废弃 key 跳过
            double v = clamp(e.getValue().getAsDouble(), opt.min(), opt.max());
            state.putValue(opt.key(), v);
        }
        return state;
    }

    // ===== 内部工具 =====

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("GZIP 压缩失败", e);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] data) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 4)) {
            byte[] buf = new byte[256];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("分享码内容解压失败（可能未全部复制或格式不符）", e);
        }
    }

    private static long crc32(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return c.getValue();
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    /** 保留最多 4 位小数，分享码更紧凑且与原配置读法一致。 */
    private static double roundTo(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}