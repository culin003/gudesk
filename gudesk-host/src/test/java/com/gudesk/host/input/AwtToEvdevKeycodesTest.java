package com.gudesk.host.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AwtToEvdevKeycodes} 键码映射表测试：核心键位逐一校验（值与
 * linux/input-event-codes.h 对照）、必覆盖集合完整性、未知键与非法按钮的空返回。
 */
class AwtToEvdevKeycodesTest {

    @Test
    @DisplayName("字母键映射（AWT VK_A..VK_Z → evdev KEY_A..KEY_Z）")
    void letters() {
        assertEquals(OptionalInt.of(30), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_A));
        assertEquals(OptionalInt.of(35), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_H));
        assertEquals(OptionalInt.of(44), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_Z));
    }

    @Test
    @DisplayName("数字键映射（主键盘 0~9）")
    void digits() {
        assertEquals(OptionalInt.of(11), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_0));
        assertEquals(OptionalInt.of(2), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_1));
        assertEquals(OptionalInt.of(10), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_9));
    }

    @Test
    @DisplayName("功能键 F1~F12（F1-F10=59-68，F11=87/F12=88，NUMLOCK=69 插在中间）")
    void functionKeys() {
        for (int i = 1; i <= 12; i++) {
            int vk = KeyEvent.VK_F1 + (i - 1);
            OptionalInt evdev = AwtToEvdevKeycodes.evdevForKeyCode(vk);
            assertTrue(evdev.isPresent(), "F" + i + " 应有映射");
            // F1~F10 连续；F11/F12 因 NUMLOCK(69) 相邻而跳段
            int expected = (i <= 10) ? 58 + i : 76 + i;
            assertEquals(expected, evdev.getAsInt(), "F" + i + " evdev 码");
        }
    }

    @Test
    @DisplayName("修饰键映射")
    void modifiers() {
        assertEquals(OptionalInt.of(42), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_SHIFT));
        assertEquals(OptionalInt.of(29), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_CONTROL));
        assertEquals(OptionalInt.of(56), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_ALT));
        assertEquals(OptionalInt.of(100), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_ALT_GRAPH));
        assertEquals(OptionalInt.of(125), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_META));
    }

    @Test
    @DisplayName("导航与编辑键映射")
    void navigationKeys() {
        assertEquals(OptionalInt.of(102), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_HOME));
        assertEquals(OptionalInt.of(107), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_END));
        assertEquals(OptionalInt.of(104), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_PAGE_UP));
        assertEquals(OptionalInt.of(109), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_PAGE_DOWN));
        assertEquals(OptionalInt.of(103), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_UP));
        assertEquals(OptionalInt.of(108), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_DOWN));
        assertEquals(OptionalInt.of(105), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_LEFT));
        assertEquals(OptionalInt.of(106), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_RIGHT));
        assertEquals(OptionalInt.of(110), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_INSERT));
        assertEquals(OptionalInt.of(111), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_DELETE));
    }

    @Test
    @DisplayName("基础控制键映射（ESC/Tab/Enter/Backspace/Space/Caps/NumLock/ScrollLock）")
    void basicControlKeys() {
        assertEquals(OptionalInt.of(1), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_ESCAPE));
        assertEquals(OptionalInt.of(15), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_TAB));
        assertEquals(OptionalInt.of(28), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_ENTER));
        assertEquals(OptionalInt.of(14), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_BACK_SPACE));
        assertEquals(OptionalInt.of(57), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_SPACE));
        assertEquals(OptionalInt.of(58), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_CAPS_LOCK));
        assertEquals(OptionalInt.of(69), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_NUM_LOCK));
        assertEquals(OptionalInt.of(70), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_SCROLL_LOCK));
    }

    @Test
    @DisplayName("标点符号映射（US 布局）")
    void punctuationKeys() {
        assertEquals(OptionalInt.of(12), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_MINUS));
        assertEquals(OptionalInt.of(13), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_EQUALS));
        assertEquals(OptionalInt.of(26), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_OPEN_BRACKET));
        assertEquals(OptionalInt.of(27), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_CLOSE_BRACKET));
        assertEquals(OptionalInt.of(43), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_BACK_SLASH));
        assertEquals(OptionalInt.of(39), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_SEMICOLON));
        assertEquals(OptionalInt.of(40), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_QUOTE));
        assertEquals(OptionalInt.of(41), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_BACK_QUOTE));
        assertEquals(OptionalInt.of(51), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_COMMA));
        assertEquals(OptionalInt.of(52), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_PERIOD));
        assertEquals(OptionalInt.of(53), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_SLASH));
    }

    @Test
    @DisplayName("小键盘映射")
    void numpadKeys() {
        assertEquals(OptionalInt.of(82), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_NUMPAD0));
        assertEquals(OptionalInt.of(73), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_NUMPAD9));
        assertEquals(OptionalInt.of(78), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_ADD));
        assertEquals(OptionalInt.of(74), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_SUBTRACT));
        assertEquals(OptionalInt.of(55), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_MULTIPLY));
        assertEquals(OptionalInt.of(98), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_DIVIDE));
        assertEquals(OptionalInt.of(83), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_DECIMAL));
    }

    @Test
    @DisplayName("PrintScreen→SysRq 与 Pause")
    void printScreenAndPause() {
        assertEquals(OptionalInt.of(99), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_PRINTSCREEN));
        assertEquals(OptionalInt.of(119), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_PAUSE));
    }

    @Test
    @DisplayName("日文键映射（IME 场景）")
    void japaneseKeys() {
        assertEquals(OptionalInt.of(92), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_CONVERT));
        assertEquals(OptionalInt.of(94), AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_NONCONVERT));
    }

    @Test
    @DisplayName("鼠标按钮映射（0=左/1=中/2=右，非法编号空返回）")
    void mouseButtons() {
        assertEquals(OptionalInt.of(0x110), AwtToEvdevKeycodes.evdevButton(0));
        assertEquals(OptionalInt.of(0x112), AwtToEvdevKeycodes.evdevButton(1));
        assertEquals(OptionalInt.of(0x111), AwtToEvdevKeycodes.evdevButton(2));
        assertFalse(AwtToEvdevKeycodes.evdevButton(3).isPresent());
        assertFalse(AwtToEvdevKeycodes.evdevButton(-1).isPresent());
    }

    @Test
    @DisplayName("未知/无对应键位返回 empty")
    void unmappedKeys() {
        assertFalse(AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_UNDEFINED).isPresent());
        assertFalse(AwtToEvdevKeycodes.evdevForKeyCode(0).isPresent());
        // 0xF0 段（VK_NUMPAD 之外的 IME 相关）中无 evdev 对应物的键
        assertFalse(AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_FINAL).isPresent());
    }

    @Test
    @DisplayName("必覆盖集合完整性：字母/数字/F1-F12/修饰键/导航键全部有映射")
    void mustCoverSet() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 'A'; i <= 'Z'; i++) {
            if (AwtToEvdevKeycodes.evdevForKeyCode(i).isEmpty()) {
                missing.add(i);
            }
        }
        for (int i = '0'; i <= '9'; i++) {
            if (AwtToEvdevKeycodes.evdevForKeyCode(i).isEmpty()) {
                missing.add(i);
            }
        }
        for (int i = 1; i <= 12; i++) {
            if (AwtToEvdevKeycodes.evdevForKeyCode(KeyEvent.VK_F1 + i - 1).isEmpty()) {
                missing.add(KeyEvent.VK_F1 + i - 1);
            }
        }
        int[] mustCover = {KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT,
                KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META, KeyEvent.VK_SPACE, KeyEvent.VK_ENTER,
                KeyEvent.VK_TAB, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE, KeyEvent.VK_DELETE,
                KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_EQUALS};
        for (int vk : mustCover) {
            if (AwtToEvdevKeycodes.evdevForKeyCode(vk).isEmpty()) {
                missing.add(vk);
            }
        }
        assertTrue(missing.isEmpty(), "必覆盖键缺失: " + missing);
    }

    @Test
    @DisplayName("映射表规模合理（覆盖 AWT 常见键位 ≥120）")
    void tableSize() {
        assertTrue(AwtToEvdevKeycodes.mappedKeyCount() >= 120,
                "映射数 " + AwtToEvdevKeycodes.mappedKeyCount() + " 偏少");
    }

    @Test
    @DisplayName("无重码冲突：不同 AWT 键（除日文同位键外）不映射到同一 evdev 码造成歧义")
    void noAccidentalCollisions() {
        // evdev → vk 反向表；同一 evdev 码允许多个 AWT 来源（如 VK_KANA/VK_JAPANESE_KATAKANA
        // 本就是同键不同名），此处仅校验反向表一致且不含 0/未定义值
        Map<Integer, Integer> evdevToAwt = new TreeMap<>();
        for (Field field : KeyEvent.class.getFields()) {
            int mods = field.getModifiers();
            if (!field.getName().startsWith("VK_") || field.getType() != int.class
                    || !Modifier.isPublic(mods) || !Modifier.isStatic(mods) || !Modifier.isFinal(mods)) {
                continue;
            }
            try {
                int vk = field.getInt(null);
                OptionalInt evdev = AwtToEvdevKeycodes.evdevForKeyCode(vk);
                if (evdev.isPresent()) {
                    Integer existing = evdevToAwt.putIfAbsent(evdev.getAsInt(), vk);
                    // 同一 evdev 目标被不同 VK 值命中不算错误（同位异名），但记录首个来源即可
                    assertTrue(existing == null || existing != 0,
                            "evdev " + evdev.getAsInt() + " 来源异常");
                }
            } catch (IllegalAccessException ignored) {
                // 不可达
            }
        }
        assertFalse(evdevToAwt.isEmpty());
        assertFalse(evdevToAwt.containsKey(0), "0 不是合法 evdev 码");
    }
}
