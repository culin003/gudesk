package com.gudesk.host.input;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link KeyEvent} VK_* 键码工具：键名（如 "VK_A"/"A"）→ java.awt.event.KeyEvent 键码 int 值
 * 的映射，供协议/日志/测试侧转换使用。
 *
 * <p>GuDesk 协议（common 的 KeyEvent.key_code）传输的键码定义为与
 * {@code java.awt.event.KeyEvent.VK_*} 常量值一致，{@link java.awt.Robot#keyPress(int)} /
 * {@link java.awt.Robot#keyRelease(int)} 直接接受该值，无需二次映射；本工具类的映射表通过
 * 反射扫描 {@link KeyEvent} 的 {@code public static final int VK_*} 常量在类加载时构建。
 *
 * <p>另见 {@link #resolveKeyCode(int, String)}：keyCode 无效时，回退用
 * {@link KeyEvent#getExtendedKeyCodeForChar(char)} 从字符推导扩展键码。
 */
public final class KeyEventCodes {

    /** 键名（VK_ 全名）→ VK 键码 */
    private static final Map<String, Integer> NAME_TO_CODE;
    /** VK 键码 → 规范键名（同值多名时保留首个） */
    private static final Map<Integer, String> CODE_TO_NAME;

    static {
        Map<String, Integer> nameToCode = new HashMap<>();
        try {
            for (Field field : KeyEvent.class.getFields()) {
                int mods = field.getModifiers();
                if (field.getName().startsWith("VK_")
                        && field.getType() == int.class
                        && Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)) {
                    nameToCode.put(field.getName(), field.getInt(null));
                }
            }
        } catch (IllegalAccessException e) {
            // 理论上不可达：VK_* 均为 public static final，反射读取不受访问限制
            throw new IllegalStateException("读取 KeyEvent.VK_* 常量失败", e);
        }
        NAME_TO_CODE = Collections.unmodifiableMap(nameToCode);

        Map<Integer, String> codeToName = new HashMap<>();
        nameToCode.forEach((name, code) -> codeToName.putIfAbsent(code, name));
        CODE_TO_NAME = Collections.unmodifiableMap(codeToName);
    }

    private KeyEventCodes() {
    }

    /** 已知 VK_* 键码总数 */
    public static int knownCodeCount() {
        return NAME_TO_CODE.size();
    }

    /**
     * 键码 → 规范键名（如 65 → "VK_A"）；未知键码格式化为 "VK_0x41" 形式（日志用）。
     */
    public static String nameForCode(int code) {
        String name = CODE_TO_NAME.get(code);
        return name != null ? name : String.format("VK_0x%X", code);
    }

    /**
     * 键名 → 键码：接受全名 "VK_A" 或短名 "A"（大小写不敏感、容忍首尾空白）；
     * 未知或空白返回 {@link KeyEvent#VK_UNDEFINED}。
     */
    public static int codeForName(String name) {
        if (name == null || name.isBlank()) {
            return KeyEvent.VK_UNDEFINED;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        String fullName = upper.startsWith("VK_") ? upper : "VK_" + upper;
        return NAME_TO_CODE.getOrDefault(fullName, KeyEvent.VK_UNDEFINED);
    }

    /**
     * 解析实际注入键码：keyCode 有效（&gt; 0 且非 VK_UNDEFINED）时直接使用（语义与
     * KeyEvent.VK_* 常量值一致）；keyCode 无效且 keyChar 非空时，回退用
     * {@link KeyEvent#getExtendedKeyCodeForChar(char)} 从字符推导扩展键码
     * （覆盖仅能用 Unicode 表示的字符）。
     *
     * @return 可用于 robot.keyPress/keyRelease 的键码；均无法解析时返回
     * {@link KeyEvent#VK_UNDEFINED}（调用方应忽略该事件）
     */
    public static int resolveKeyCode(int keyCode, String keyChar) {
        if (keyCode > 0 && keyCode != KeyEvent.VK_UNDEFINED) {
            return keyCode;
        }
        if (keyChar != null && !keyChar.isEmpty()) {
            int extended = KeyEvent.getExtendedKeyCodeForChar(keyChar.charAt(0));
            if (extended != KeyEvent.VK_UNDEFINED) {
                return extended;
            }
        }
        return KeyEvent.VK_UNDEFINED;
    }
}
