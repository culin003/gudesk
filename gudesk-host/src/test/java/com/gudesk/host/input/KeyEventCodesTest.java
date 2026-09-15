package com.gudesk.host.input;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyEventCodes} 单测：典型键码转换（键名→VK 值、VK 值→键名、注入键码解析）。
 */
class KeyEventCodesTest {

    @Test
    void 键名转键码_典型按键() {
        assertEquals(KeyEvent.VK_A, KeyEventCodes.codeForName("VK_A"));
        assertEquals(KeyEvent.VK_A, KeyEventCodes.codeForName("A"));
        assertEquals(KeyEvent.VK_A, KeyEventCodes.codeForName("a"));
        assertEquals(KeyEvent.VK_A, KeyEventCodes.codeForName(" vk_a "));
        assertEquals(KeyEvent.VK_ENTER, KeyEventCodes.codeForName("VK_ENTER"));
        assertEquals(KeyEvent.VK_SPACE, KeyEventCodes.codeForName("VK_SPACE"));
        assertEquals(KeyEvent.VK_SHIFT, KeyEventCodes.codeForName("VK_SHIFT"));
        assertEquals(KeyEvent.VK_CONTROL, KeyEventCodes.codeForName("VK_CONTROL"));
        assertEquals(KeyEvent.VK_ALT, KeyEventCodes.codeForName("VK_ALT"));
        assertEquals(KeyEvent.VK_WINDOWS, KeyEventCodes.codeForName("VK_WINDOWS"));
        assertEquals(KeyEvent.VK_ESCAPE, KeyEventCodes.codeForName("ESCAPE"));
        assertEquals(KeyEvent.VK_Z, KeyEventCodes.codeForName("VK_Z"));
        assertEquals(KeyEvent.VK_0, KeyEventCodes.codeForName("VK_0"));
    }

    @Test
    void 键名转键码_未知与空值() {
        assertEquals(KeyEvent.VK_UNDEFINED, KeyEventCodes.codeForName("VK_NOT_EXIST"));
        assertEquals(KeyEvent.VK_UNDEFINED, KeyEventCodes.codeForName(""));
        assertEquals(KeyEvent.VK_UNDEFINED, KeyEventCodes.codeForName(null));
    }

    @Test
    void 键码转键名_反向查询() {
        assertEquals("VK_A", KeyEventCodes.nameForCode(KeyEvent.VK_A));
        assertEquals("VK_ENTER", KeyEventCodes.nameForCode(KeyEvent.VK_ENTER));
        assertEquals("VK_WINDOWS", KeyEventCodes.nameForCode(KeyEvent.VK_WINDOWS));
        // 未知键码格式化为十六进制形式（日志可读）；0 即 VK_UNDEFINED 常量本身
        assertEquals("VK_0x999", KeyEventCodes.nameForCode(0x999));
        assertEquals("VK_UNDEFINED", KeyEventCodes.nameForCode(0));
    }

    @Test
    void 映射表_规模与经典键码() {
        // KeyEvent 的 VK_* 常量数百个，映射表应完整构建
        assertTrue(KeyEventCodes.knownCodeCount() > 100);
        assertEquals(KeyEvent.VK_F1, KeyEventCodes.codeForName("VK_F1"));
        assertEquals(KeyEvent.VK_F12, KeyEventCodes.codeForName("VK_F12"));
        assertEquals(KeyEvent.VK_BACK_SPACE, KeyEventCodes.codeForName("VK_BACK_SPACE"));
    }

    @Test
    void 键码解析_keyCode优先() {
        // keyCode 与 VK_* 语义一致时直接使用
        assertEquals(KeyEvent.VK_A, KeyEventCodes.resolveKeyCode(KeyEvent.VK_A, null));
        assertEquals(KeyEvent.VK_A, KeyEventCodes.resolveKeyCode(KeyEvent.VK_A, "A"));
        assertEquals(KeyEvent.VK_WINDOWS, KeyEventCodes.resolveKeyCode(KeyEvent.VK_WINDOWS, null));
    }

    @Test
    void 键码解析_keyCode无效时回退字符() {
        // keyCode 无效（VK_UNDEFINED/0/负数）且 keyChar 非空：getExtendedKeyCodeForChar 推导
        assertEquals(KeyEvent.VK_A, KeyEventCodes.resolveKeyCode(KeyEvent.VK_UNDEFINED, "A"));
        assertTrue(KeyEventCodes.resolveKeyCode(KeyEvent.VK_UNDEFINED, "a") > 0);
    }

    @Test
    void 键码解析_均无效返回UNDEFINED() {
        assertEquals(KeyEvent.VK_UNDEFINED, KeyEventCodes.resolveKeyCode(KeyEvent.VK_UNDEFINED, null));
        assertEquals(KeyEvent.VK_UNDEFINED, KeyEventCodes.resolveKeyCode(KeyEvent.VK_UNDEFINED, ""));
        assertEquals(KeyEvent.VK_UNDEFINED, KeyEventCodes.resolveKeyCode(0, null));
        assertEquals(KeyEvent.VK_UNDEFINED, KeyEventCodes.resolveKeyCode(-1, null));
    }
}
