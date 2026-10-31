package com.decibelmeter.app;

import android.view.KeyEvent;

/**
 * 按键到 RDP 扫描码的映射（set 1 .make 码，扩展键再或上 {@link #EXT}）。
 *
 * 为什么还要扫描码通道：软键盘输入的字符走 Unicode 事件最省事，也免掉了远端键盘布局的
 * 问题，但那条路**不参与修饰键组合**——按住 Ctrl 再发 Unicode 的 'c'，Windows 不会当成
 * 复制。所以只要有修饰键按着，就必须把字符翻译成扫描码发出去。
 *
 * 表里的值按 PC/AT set 1 硬编码，不查任何库：这一段错了不会崩，只会"某个键按下去没反应"，
 * 所以每个键都要在真机上按过一遍才算数（本轮实测覆盖：Esc/Tab/Win/方向/Home/End/PgUp/
 * PgDn/Del/Backspace/Enter/F1/F5/F11/F12 与 Ctrl/Alt/Shift 组合）。
 */
final class RdpKeys {

    /** 扩展键前缀，与 IronRDP 的 {@code Scancode::from_u16} 约定一致（0xE000 位）。 */
    static final int EXT = 0xE000;

    static final int CTRL_LEFT = 0x1D;
    static final int ALT_LEFT = 0x38;
    static final int SHIFT_LEFT = 0x2A;
    static final int WIN_LEFT = 0x5B | EXT;

    /** 一个功能键：显示名 + 扫描码。 */
    static final class Key {
        final String label;
        final int scancode;

        Key(String label, int scancode) {
            this.label = label;
            this.scancode = scancode;
        }
    }

    private RdpKeys() {
    }

    /**
     * 功能键面板的键位表，按行优先排（面板每行 5 个）。
     */
    static final Key[] FUNCTION_KEYS = {
            new Key("Esc", 0x01),
            new Key("Tab", 0x0F),
            new Key("Enter", 0x1C),
            new Key("Space", 0x39),
            new Key("Bksp", 0x0E),
            new Key("Del", 0x53 | EXT),
            new Key("Ins", 0x52 | EXT),
            new Key("Home", 0x47 | EXT),
            new Key("End", 0x4F | EXT),
            new Key("PgUp", 0x49 | EXT),
            new Key("PgDn", 0x51 | EXT),
            new Key("Win", WIN_LEFT),
            new Key("↑", 0x48 | EXT),
            new Key("↓", 0x50 | EXT),
            new Key("←", 0x4B | EXT),
            new Key("→", 0x4D | EXT),
            new Key("Menu", 0x5D | EXT),
            new Key("Caps", 0x3A),
            new Key("F1", 0x3B),
            new Key("F2", 0x3C),
            new Key("F3", 0x3D),
            new Key("F4", 0x3E),
            new Key("F5", 0x3F),
            new Key("F6", 0x40),
            new Key("F7", 0x41),
            new Key("F8", 0x42),
            new Key("F9", 0x43),
            new Key("F10", 0x44),
            new Key("F11", 0x57),
            new Key("F12", 0x58),
    };

    /**
     * 物理键盘（OTG / 蓝牙）的 Android 键码翻译成 RDP 扫描码；不认识的返回 0。
     */
    static int forKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
                return 0x01;
            case KeyEvent.KEYCODE_TAB:
                return 0x0F;
            case KeyEvent.KEYCODE_ENTER:
                return 0x1C;
            case KeyEvent.KEYCODE_SPACE:
                return 0x39;
            case KeyEvent.KEYCODE_DEL:
                return 0x0E;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return 0x53 | EXT;
            case KeyEvent.KEYCODE_INSERT:
                return 0x52 | EXT;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return 0x47 | EXT;
            case KeyEvent.KEYCODE_MOVE_END:
                return 0x4F | EXT;
            case KeyEvent.KEYCODE_PAGE_UP:
                return 0x49 | EXT;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return 0x51 | EXT;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 0x48 | EXT;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 0x50 | EXT;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 0x4B | EXT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 0x4D | EXT;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return 0x3A;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return CTRL_LEFT;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return ALT_LEFT;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return SHIFT_LEFT;
            case KeyEvent.KEYCODE_WINDOW:
                return WIN_LEFT;
            case KeyEvent.KEYCODE_F1:
                return 0x3B;
            case KeyEvent.KEYCODE_F2:
                return 0x3C;
            case KeyEvent.KEYCODE_F3:
                return 0x3D;
            case KeyEvent.KEYCODE_F4:
                return 0x3E;
            case KeyEvent.KEYCODE_F5:
                return 0x3F;
            case KeyEvent.KEYCODE_F6:
                return 0x40;
            case KeyEvent.KEYCODE_F7:
                return 0x41;
            case KeyEvent.KEYCODE_F8:
                return 0x42;
            case KeyEvent.KEYCODE_F9:
                return 0x43;
            case KeyEvent.KEYCODE_F10:
                return 0x44;
            case KeyEvent.KEYCODE_F11:
                return 0x57;
            case KeyEvent.KEYCODE_F12:
                return 0x58;
            default:
                return letterScancode(keyCode);
        }
    }

    /**
     * 字符翻译成扫描码，只在有修饰键按着时用。
     *
     * 覆盖字母、数字与几个常用符号；不认识的返回 0，调用方回退到 Unicode 通道
     * （宁可这次组合键无效，也不要把一个错的键位送进远端）。
     */
    static int forChar(char value) {
        char lower = Character.toLowerCase(value);
        if (lower >= 'a' && lower <= 'z') {
            return LETTERS[lower - 'a'];
        }
        if (lower >= '1' && lower <= '9') {
            // 数字行：1..9 是 0x02..0x0A，0 是 0x0B
            return 0x02 + (lower - '1');
        }
        if (lower == '0') {
            return 0x0B;
        }
        switch (lower) {
            case '-':
                return 0x0C;
            case '=':
                return 0x0D;
            case '[':
                return 0x1A;
            case ']':
                return 0x1B;
            case ';':
                return 0x27;
            case '\'':
                return 0x28;
            case '`':
                return 0x29;
            case '\\':
                return 0x2B;
            case ',':
                return 0x33;
            case '.':
                return 0x34;
            case '/':
                return 0x35;
            default:
                return 0;
        }
    }

    /** KEYCODE_A..Z 正好连续排在 0x1D 之后，按 set 1 的排布查表。 */
    private static final int[] LETTERS = {
            0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24,
            0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14,
            0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,
    };

    private static int letterScancode(int keyCode) {
        if (keyCode < KeyEvent.KEYCODE_A || keyCode > KeyEvent.KEYCODE_Z) {
            return 0;
        }
        return LETTERS[keyCode - KeyEvent.KEYCODE_A];
    }
}
