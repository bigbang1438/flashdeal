package com.flashdeal.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON 编解码器：缓存层把对象序列化成字符串存入 L2。
 *
 * <p>单独抽出来的原因：{@link CacheFacade} 不应该依赖 Spring 的 ObjectMapper Bean，
 * 这样缓存层可以脱离 Spring 容器被单测直接构造。</p>
 */
@Component
public class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 序列化；失败返回 null（调用方跳过缓存写入，不影响主流程）。 */
    public static String encode(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 反序列化；失败返回 null。调用方负责把结果转成目标类型。 */
    @SuppressWarnings("unchecked")
    public static <T> T decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return (T) MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 反序列化成指定类型。 */
    public static <T> T decode(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /** 反序列化，失败抛业务异常（幂等重放路径必须拿到结果，不能静默返回 null）。 */
    public static <T> T decodeOrThrow(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new com.flashdeal.common.BizException(com.flashdeal.common.ErrorCode.INTERNAL_ERROR,
                    "幂等结果反序列化失败: " + e.getMessage());
        }
    }

    /** 反序列化成列表。 */
    public static <T> List<T> decodeList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 暴露给需要自定义序列化的场景。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
