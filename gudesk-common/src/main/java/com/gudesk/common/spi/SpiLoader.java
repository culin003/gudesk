package com.gudesk.common.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * SPI 适配器加载器：基于 {@link ServiceLoader} 发现实现，并支持按配置项指定优先实现。
 *
 * <p>配置读取顺序（configKey 如 "capturer"、"encoder"）：
 * <ol>
 *   <li>系统属性 {@code gudesk.adapter.<configKey>}
 *       （如 {@code gudesk.adapter.capturer=com.gudesk.host.capture.DxgiCapturer}）；</li>
 *   <li>环境变量（属性名大写并将 '.' 替换为 '_'，如 {@code GUDESK_ADAPTER_CAPTURER}）。</li>
 * </ol>
 *
 * <p>选择与降级策略：
 * <ul>
 *   <li>指定了实现：优先在 ServiceLoader 注册项中匹配，其次尝试反射加载（未注册但存在于
 *       classpath）；加载失败（类不存在、实例化失败等）则降级；</li>
 *   <li>未指定实现：取 ServiceLoader 发现的第一个实现；无任何实现则降级；</li>
 *   <li>降级时若调用方传入 defaultSupplier 则使用默认实现并 log warn 降级原因，
 *       否则抛出 {@link AdapterException}；</li>
 *   <li>成功路径 log info 最终选择了哪个实现。</li>
 * </ul>
 *
 * <p>注意：本加载器只负责实例化；{@link Adapter#init(AdapterConfig)} / start() 由调用方
 * 在取得实例后自行执行。
 */
public final class SpiLoader {

    private static final Logger LOG = LoggerFactory.getLogger(SpiLoader.class);

    private static final String PROPERTY_PREFIX = "gudesk.adapter.";

    private SpiLoader() {
    }

    /**
     * 加载指定 SPI 类型的实现（无默认实现可降级时，失败抛 {@link AdapterException}）。
     */
    public static <T extends Adapter> T load(Class<T> spiType, String configKey) {
        return load(spiType, configKey, null);
    }

    /**
     * 加载指定 SPI 类型的实现，加载/发现失败时降级到 defaultSupplier 提供的默认实现。
     *
     * @param spiType         SPI 接口类型
     * @param configKey       配置键（系统属性/环境变量名的一部分）
     * @param defaultSupplier 默认实现供给器，可为 null（等价于无默认）
     */
    public static <T extends Adapter> T load(Class<T> spiType, String configKey, Supplier<T> defaultSupplier) {
        String configured = readPreference(configKey);
        if (configured != null) {
            T adapter = tryLoad(spiType, configured);
            if (adapter != null) {
                LOG.info("SPI '{}' 选择指定实现: {}", configKey, adapter.getClass().getName());
                return adapter;
            }
            return fallback(spiType, configKey, defaultSupplier, "指定实现 " + configured + " 加载失败");
        }

        // 未指定：取 ServiceLoader 发现的第一个实现
        for (T adapter : ServiceLoader.load(spiType, spiType.getClassLoader())) {
            LOG.info("SPI '{}' 选择默认发现实现: {}", configKey, adapter.getClass().getName());
            return adapter;
        }
        return fallback(spiType, configKey, defaultSupplier, "未发现任何 " + spiType.getName() + " 实现");
    }

    /**
     * 尝试加载指定类名的实现：先在 ServiceLoader 注册项中按类型名匹配（避免绕过
     * 模块系统/封装），再退回反射加载未注册的 classpath 实现。失败返回 null。
     */
    private static <T extends Adapter> T tryLoad(Class<T> spiType, String className) {
        // stream() 只读类型名不实例化，匹配到目标后再实例化
        for (ServiceLoader.Provider<T> provider
                : ServiceLoader.load(spiType, spiType.getClassLoader()).stream().toList()) {
            if (provider.type().getName().equals(className)) {
                try {
                    return provider.get();
                } catch (Throwable t) {
                    LOG.warn("SPI 指定实现实例化失败: {}", className, t);
                    return null;
                }
            }
        }
        // 兼容存在于 classpath 但未注册 META-INF/services 的实现
        try {
            Class<?> clazz = Class.forName(className, true, spiType.getClassLoader());
            return spiType.cast(clazz.getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            LOG.warn("SPI 指定实现类加载失败: {}", className, t);
            return null;
        }
    }

    /**
     * 降级：有默认实现则使用并记录降级原因，否则抛出 AdapterException。
     */
    private static <T extends Adapter> T fallback(Class<T> spiType, String configKey,
                                                   Supplier<T> defaultSupplier, String reason) {
        if (defaultSupplier != null) {
            T adapter = defaultSupplier.get();
            LOG.warn("SPI '{}' 降级到默认实现 {}，原因: {}",
                    configKey, adapter.getClass().getName(), reason);
            return adapter;
        }
        throw new AdapterException("SPI '" + configKey + "' 加载失败且无默认实现可降级，原因: " + reason);
    }

    /**
     * 读取配置偏好：系统属性优先，其次环境变量（属性名大写、'.'→'_'）。
     */
    private static String readPreference(String configKey) {
        String property = System.getProperty(PROPERTY_PREFIX + configKey);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String envKey = (PROPERTY_PREFIX + configKey).toUpperCase(Locale.ROOT).replace('.', '_');
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
    }
}
