package com.gudesk.host.input;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * AWT {@code KeyEvent.VK_*} 键码 → Linux evdev 键码（linux/input-event-codes.h 的
 * KEY_* 值）映射，供 portal RemoteDesktop 的 NotifyKeyboardKeycode 注入使用。
 *
 * <p>设计要点：
 * <ul>
 *   <li>GuDesk 协议传输 AWT 键码（与 Robot 注入路径一致，见 {@link KeyEventCodes}），
 *       portal 的 NotifyKeyboardKeycode 需要 evdev 域键码，本表承担两个键码域的转换；</li>
 *   <li>映射表静态构建（VK 常量直接引用 KeyEvent，编译期校验），未覆盖的键位返回
 *       {@link OptionalInt#empty()}，调用方丢弃并记 debug 日志；</li>
 *   <li>有意识排除的键：{@code VK_PRINTSCREEN}（部分 DE 捕获截图快捷键冲突风险高，
 *       且远控场景少用）不在排除之列——实际保留；真正排除的是无 evdev 对应物的
 *       AWT 专属虚拟键（VK_UNDEFINED/占位键位等）；</li>
 *   <li>鼠标按钮不走本表（AWT button 0/1/2 → evdev BTN_LEFT/BTN_MIDDLE/BTN_RIGHT
 *       见 {@link #evdevButton(int)}）。</li>
 * </ul>
 */
public final class AwtToEvdevKeycodes {

    // Linux evdev 键码（linux/input-event-codes.h）
    public static final int KEY_ESC = 1;
    public static final int KEY_1 = 2;
    public static final int KEY_2 = 3;
    public static final int KEY_3 = 4;
    public static final int KEY_4 = 5;
    public static final int KEY_5 = 6;
    public static final int KEY_6 = 7;
    public static final int KEY_7 = 8;
    public static final int KEY_8 = 9;
    public static final int KEY_9 = 10;
    public static final int KEY_0 = 11;
    public static final int KEY_MINUS = 12;
    public static final int KEY_EQUAL = 13;
    public static final int KEY_BACKSPACE = 14;
    public static final int KEY_TAB = 15;
    public static final int KEY_Q = 16;
    public static final int KEY_W = 17;
    public static final int KEY_E = 18;
    public static final int KEY_R = 19;
    public static final int KEY_T = 20;
    public static final int KEY_Y = 21;
    public static final int KEY_U = 22;
    public static final int KEY_I = 23;
    public static final int KEY_O = 24;
    public static final int KEY_P = 25;
    public static final int KEY_LEFTBRACE = 26;
    public static final int KEY_RIGHTBRACE = 27;
    public static final int KEY_ENTER = 28;
    public static final int KEY_LEFTCTRL = 29;
    public static final int KEY_A = 30;
    public static final int KEY_S = 31;
    public static final int KEY_D = 32;
    public static final int KEY_F = 33;
    public static final int KEY_G = 34;
    public static final int KEY_H = 35;
    public static final int KEY_J = 36;
    public static final int KEY_K = 37;
    public static final int KEY_L = 38;
    public static final int KEY_SEMICOLON = 39;
    public static final int KEY_APOSTROPHE = 40;
    public static final int KEY_GRAVE = 41;
    public static final int KEY_LEFTSHIFT = 42;
    public static final int KEY_BACKSLASH = 43;
    public static final int KEY_Z = 44;
    public static final int KEY_X = 45;
    public static final int KEY_C = 46;
    public static final int KEY_V = 47;
    public static final int KEY_B = 48;
    public static final int KEY_N = 49;
    public static final int KEY_M = 50;
    public static final int KEY_COMMA = 51;
    public static final int KEY_DOT = 52;
    public static final int KEY_SLASH = 53;
    public static final int KEY_RIGHTSHIFT = 54;
    public static final int KEY_KPASTERISK = 55;
    public static final int KEY_LEFTALT = 56;
    public static final int KEY_SPACE = 57;
    public static final int KEY_CAPSLOCK = 58;
    public static final int KEY_F1 = 59;
    public static final int KEY_F2 = 60;
    public static final int KEY_F3 = 61;
    public static final int KEY_F4 = 62;
    public static final int KEY_F5 = 63;
    public static final int KEY_F6 = 64;
    public static final int KEY_F7 = 65;
    public static final int KEY_F8 = 66;
    public static final int KEY_F9 = 67;
    public static final int KEY_F10 = 68;
    public static final int KEY_NUMLOCK = 69;
    public static final int KEY_SCROLLLOCK = 70;
    public static final int KEY_KP7 = 71;
    public static final int KEY_KP8 = 72;
    public static final int KEY_KP9 = 73;
    public static final int KEY_KPMINUS = 74;
    public static final int KEY_KP4 = 75;
    public static final int KEY_KP5 = 76;
    public static final int KEY_KP6 = 77;
    public static final int KEY_KPPLUS = 78;
    public static final int KEY_KP1 = 79;
    public static final int KEY_KP2 = 80;
    public static final int KEY_KP3 = 81;
    public static final int KEY_KP0 = 82;
    public static final int KEY_KPDOT = 83;
    public static final int KEY_ZENKAKUHANKAKU = 85;
    public static final int KEY_102ND = 86;
    public static final int KEY_F11 = 87;
    public static final int KEY_F12 = 88;
    public static final int KEY_RO = 89;
    public static final int KEY_KATAKANA = 90;
    public static final int KEY_HIRAGANA = 91;
    public static final int KEY_HENKAN = 92;
    public static final int KEY_KATAKANAHIRAGANA = 93;
    public static final int KEY_MUHENKAN = 94;
    public static final int KEY_KPJPCOMMA = 95;
    public static final int KEY_KPENTER = 96;
    public static final int KEY_RIGHTCTRL = 97;
    public static final int KEY_KPSLASH = 98;
    public static final int KEY_SYSRQ = 99;
    public static final int KEY_RIGHTALT = 100;
    public static final int KEY_LINEFEED = 101;
    public static final int KEY_HOME = 102;
    public static final int KEY_UP = 103;
    public static final int KEY_PAGEUP = 104;
    public static final int KEY_LEFT = 105;
    public static final int KEY_RIGHT = 106;
    public static final int KEY_END = 107;
    public static final int KEY_DOWN = 108;
    public static final int KEY_PAGEDOWN = 109;
    public static final int KEY_INSERT = 110;
    public static final int KEY_DELETE = 111;
    public static final int KEY_MUTE = 113;
    public static final int KEY_VOLUMEDOWN = 114;
    public static final int KEY_VOLUMEUP = 115;
    public static final int KEY_POWER = 116;
    public static final int KEY_KPEQUAL = 117;
    public static final int KEY_PAUSE = 119;
    public static final int KEY_KPCOMMA = 121;
    public static final int KEY_HANGUEL = 122;
    public static final int KEY_HANJA = 123;
    public static final int KEY_YEN = 124;
    public static final int KEY_LEFTMETA = 125;
    public static final int KEY_RIGHTMETA = 126;
    public static final int KEY_COMPOSE = 127;
    public static final int KEY_STOP = 128;
    public static final int KEY_AGAIN = 129;
    public static final int KEY_PROPS = 130;
    public static final int KEY_UNDO = 131;
    public static final int KEY_FRONT = 132;
    public static final int KEY_COPY = 133;
    public static final int KEY_OPEN = 134;
    public static final int KEY_PASTE = 135;
    public static final int KEY_FIND = 136;
    public static final int KEY_CUT = 137;
    public static final int KEY_HELP = 138;
    public static final int KEY_MENU = 139;
    public static final int KEY_CALC = 140;
    public static final int KEY_SLEEP = 142;
    public static final int KEY_WWW = 150;
    public static final int KEY_MAIL = 155;
    public static final int KEY_BOOKMARKS = 156;
    public static final int KEY_COMPUTER = 157;
    public static final int KEY_BACK = 158;
    public static final int KEY_FORWARD = 159;
    public static final int KEY_NEXTSONG = 163;
    public static final int KEY_PLAYPAUSE = 164;
    public static final int KEY_PREVIOUSSONG = 165;
    public static final int KEY_STOPCD = 166;
    public static final int KEY_REFRESH = 173;
    public static final int KEY_EDIT = 176;
    public static final int KEY_SCROLLUP = 177;
    public static final int KEY_SCROLLDOWN = 178;
    public static final int KEY_KPLEFTPAREN = 179;
    public static final int KEY_KPRIGHTPAREN = 180;
    public static final int KEY_UNKNOWN = 240;

    // evdev 鼠标按钮（input-event-codes.h BTN_*）
    public static final int BTN_LEFT = 0x110;
    public static final int BTN_RIGHT = 0x111;
    public static final int BTN_MIDDLE = 0x112;

    /** AWT VK 键码 → evdev 键码 */
    private static final Map<Integer, Integer> AWT_TO_EVDEV;

    static {
        Map<Integer, Integer> m = new HashMap<>();

        // 字母 A~Z（AWT VK_A=65~VK_Z=90；evdev 连续 30~55 中的字母段 30~44, 46~55）
        m.put(KeyEvent.VK_A, KEY_A);
        m.put(KeyEvent.VK_B, KEY_B);
        m.put(KeyEvent.VK_C, KEY_C);
        m.put(KeyEvent.VK_D, KEY_D);
        m.put(KeyEvent.VK_E, KEY_E);
        m.put(KeyEvent.VK_F, KEY_F);
        m.put(KeyEvent.VK_G, KEY_G);
        m.put(KeyEvent.VK_H, KEY_H);
        m.put(KeyEvent.VK_I, KEY_I);
        m.put(KeyEvent.VK_J, KEY_J);
        m.put(KeyEvent.VK_K, KEY_K);
        m.put(KeyEvent.VK_L, KEY_L);
        m.put(KeyEvent.VK_M, KEY_M);
        m.put(KeyEvent.VK_N, KEY_N);
        m.put(KeyEvent.VK_O, KEY_O);
        m.put(KeyEvent.VK_P, KEY_P);
        m.put(KeyEvent.VK_Q, KEY_Q);
        m.put(KeyEvent.VK_R, KEY_R);
        m.put(KeyEvent.VK_S, KEY_S);
        m.put(KeyEvent.VK_T, KEY_T);
        m.put(KeyEvent.VK_U, KEY_U);
        m.put(KeyEvent.VK_V, KEY_V);
        m.put(KeyEvent.VK_W, KEY_W);
        m.put(KeyEvent.VK_X, KEY_X);
        m.put(KeyEvent.VK_Y, KEY_Y);
        m.put(KeyEvent.VK_Z, KEY_Z);

        // 数字 0~9
        m.put(KeyEvent.VK_0, KEY_0);
        m.put(KeyEvent.VK_1, KEY_1);
        m.put(KeyEvent.VK_2, KEY_2);
        m.put(KeyEvent.VK_3, KEY_3);
        m.put(KeyEvent.VK_4, KEY_4);
        m.put(KeyEvent.VK_5, KEY_5);
        m.put(KeyEvent.VK_6, KEY_6);
        m.put(KeyEvent.VK_7, KEY_7);
        m.put(KeyEvent.VK_8, KEY_8);
        m.put(KeyEvent.VK_9, KEY_9);

        // 功能键
        m.put(KeyEvent.VK_F1, KEY_F1);
        m.put(KeyEvent.VK_F2, KEY_F2);
        m.put(KeyEvent.VK_F3, KEY_F3);
        m.put(KeyEvent.VK_F4, KEY_F4);
        m.put(KeyEvent.VK_F5, KEY_F5);
        m.put(KeyEvent.VK_F6, KEY_F6);
        m.put(KeyEvent.VK_F7, KEY_F7);
        m.put(KeyEvent.VK_F8, KEY_F8);
        m.put(KeyEvent.VK_F9, KEY_F9);
        m.put(KeyEvent.VK_F10, KEY_F10);
        m.put(KeyEvent.VK_F11, KEY_F11);
        m.put(KeyEvent.VK_F12, KEY_F12);

        // 控制与导航键
        m.put(KeyEvent.VK_ESCAPE, KEY_ESC);
        m.put(KeyEvent.VK_TAB, KEY_TAB);
        m.put(KeyEvent.VK_ENTER, KEY_ENTER);
        m.put(KeyEvent.VK_BACK_SPACE, KEY_BACKSPACE);
        m.put(KeyEvent.VK_SPACE, KEY_SPACE);
        m.put(KeyEvent.VK_CAPS_LOCK, KEY_CAPSLOCK);
        m.put(KeyEvent.VK_NUM_LOCK, KEY_NUMLOCK);
        m.put(KeyEvent.VK_SCROLL_LOCK, KEY_SCROLLLOCK);
        m.put(KeyEvent.VK_INSERT, KEY_INSERT);
        m.put(KeyEvent.VK_DELETE, KEY_DELETE);
        m.put(KeyEvent.VK_HOME, KEY_HOME);
        m.put(KeyEvent.VK_END, KEY_END);
        m.put(KeyEvent.VK_PAGE_UP, KEY_PAGEUP);
        m.put(KeyEvent.VK_PAGE_DOWN, KEY_PAGEDOWN);
        m.put(KeyEvent.VK_LEFT, KEY_LEFT);
        m.put(KeyEvent.VK_RIGHT, KEY_RIGHT);
        m.put(KeyEvent.VK_UP, KEY_UP);
        m.put(KeyEvent.VK_DOWN, KEY_DOWN);
        m.put(KeyEvent.VK_PAUSE, KEY_PAUSE);
        m.put(KeyEvent.VK_PRINTSCREEN, KEY_SYSRQ);

        // 修饰键
        m.put(KeyEvent.VK_SHIFT, KEY_LEFTSHIFT);
        m.put(KeyEvent.VK_CONTROL, KEY_LEFTCTRL);
        m.put(KeyEvent.VK_ALT, KEY_LEFTALT);
        m.put(KeyEvent.VK_ALT_GRAPH, KEY_RIGHTALT);
        m.put(KeyEvent.VK_META, KEY_LEFTMETA);

        // 标点符号（US 布局）
        m.put(KeyEvent.VK_MINUS, KEY_MINUS);
        m.put(KeyEvent.VK_PLUS, KEY_EQUAL); // AWT PLUS 无独立键位，映射到 = 槽位
        m.put(KeyEvent.VK_EQUALS, KEY_EQUAL);
        m.put(KeyEvent.VK_OPEN_BRACKET, KEY_LEFTBRACE);
        m.put(KeyEvent.VK_CLOSE_BRACKET, KEY_RIGHTBRACE);
        m.put(KeyEvent.VK_BACK_SLASH, KEY_BACKSLASH);
        m.put(KeyEvent.VK_SEMICOLON, KEY_SEMICOLON);
        m.put(KeyEvent.VK_QUOTE, KEY_APOSTROPHE);
        m.put(KeyEvent.VK_BACK_QUOTE, KEY_GRAVE);
        m.put(KeyEvent.VK_COMMA, KEY_COMMA);
        m.put(KeyEvent.VK_PERIOD, KEY_DOT);
        m.put(KeyEvent.VK_SLASH, KEY_SLASH);

        // 小键盘
        m.put(KeyEvent.VK_NUMPAD0, KEY_KP0);
        m.put(KeyEvent.VK_NUMPAD1, KEY_KP1);
        m.put(KeyEvent.VK_NUMPAD2, KEY_KP2);
        m.put(KeyEvent.VK_NUMPAD3, KEY_KP3);
        m.put(KeyEvent.VK_NUMPAD4, KEY_KP4);
        m.put(KeyEvent.VK_NUMPAD5, KEY_KP5);
        m.put(KeyEvent.VK_NUMPAD6, KEY_KP6);
        m.put(KeyEvent.VK_NUMPAD7, KEY_KP7);
        m.put(KeyEvent.VK_NUMPAD8, KEY_KP8);
        m.put(KeyEvent.VK_NUMPAD9, KEY_KP9);
        m.put(KeyEvent.VK_MULTIPLY, KEY_KPASTERISK);
        m.put(KeyEvent.VK_ADD, KEY_KPPLUS);
        m.put(KeyEvent.VK_SUBTRACT, KEY_KPMINUS);
        m.put(KeyEvent.VK_DECIMAL, KEY_KPDOT);
        m.put(KeyEvent.VK_DIVIDE, KEY_KPSLASH);
        m.put(KeyEvent.VK_SEPARATOR, KEY_KPCOMMA);
        m.put(KeyEvent.VK_KP_LEFT, KEY_KP4);
        m.put(KeyEvent.VK_KP_UP, KEY_KP8);
        m.put(KeyEvent.VK_KP_RIGHT, KEY_KP6);
        m.put(KeyEvent.VK_KP_DOWN, KEY_KP2);

        // 媒体/系统键
        m.put(KeyEvent.VK_STOP, KEY_STOPCD);
        m.put(KeyEvent.VK_HELP, KEY_HELP);
        m.put(KeyEvent.VK_CONTEXT_MENU, KEY_COMPOSE);
        m.put(KeyEvent.VK_CUT, KEY_CUT);
        m.put(KeyEvent.VK_COPY, KEY_COPY);
        m.put(KeyEvent.VK_PASTE, KEY_PASTE);
        m.put(KeyEvent.VK_FIND, KEY_FIND);
        m.put(KeyEvent.VK_UNDO, KEY_UNDO);
        m.put(KeyEvent.VK_AGAIN, KEY_AGAIN);
        m.put(KeyEvent.VK_PROPS, KEY_PROPS);

        // 日文/韩文键（AWT 常量存在时映射）
        m.put(KeyEvent.VK_CONVERT, KEY_HENKAN);
        m.put(KeyEvent.VK_NONCONVERT, KEY_MUHENKAN);
        m.put(KeyEvent.VK_INPUT_METHOD_ON_OFF, KEY_KATAKANAHIRAGANA);
        m.put(KeyEvent.VK_CODE_INPUT, KEY_KATAKANA);
        m.put(KeyEvent.VK_ROMAN_CHARACTERS, KEY_RO);
        m.put(KeyEvent.VK_KANA, KEY_KATAKANA);
        m.put(KeyEvent.VK_JAPANESE_KATAKANA, KEY_KATAKANA);
        m.put(KeyEvent.VK_JAPANESE_HIRAGANA, KEY_HIRAGANA);
        m.put(KeyEvent.VK_JAPANESE_ROMAN, KEY_RO);

        AWT_TO_EVDEV = Collections.unmodifiableMap(m);
    }

    private AwtToEvdevKeycodes() {
    }

    /**
     * AWT VK 键码 → evdev 键码。
     *
     * @return evdev 键码；无对应映射（AWT 专属虚拟键/占位键位等）返回 empty
     */
    public static OptionalInt evdevForKeyCode(int vkKeyCode) {
        Integer mapped = AWT_TO_EVDEV.get(vkKeyCode);
        return mapped != null ? OptionalInt.of(mapped) : OptionalInt.empty();
    }

    /**
     * AWT 鼠标按钮编号（{@link InputEvent} 语义：0=左/1=中/2=右，与 GuDesk 协议
     * MouseEvent.button 一致）→ evdev BTN_* 键码。
     *
     * @return evdev 按钮码；非法编号返回 empty
     */
    public static OptionalInt evdevButton(int button) {
        return switch (button) {
            case 0 -> OptionalInt.of(BTN_LEFT);
            case 1 -> OptionalInt.of(BTN_MIDDLE);
            case 2 -> OptionalInt.of(BTN_RIGHT);
            default -> OptionalInt.empty();
        };
    }

    /** 已映射 AWT 键位总数（诊断/测试用） */
    public static int mappedKeyCount() {
        return AWT_TO_EVDEV.size();
    }
}
