package com.datacube.fx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Windows 原生标题栏明暗跟随（DWM immersive dark mode）。
 *
 * <p>JavaFX 无法用 CSS 改变系统窗口标题栏（最上方带最小化/关闭按钮的那条）的配色，
 * 本类通过 Java FFM（JDK 22+ 稳定）调用 Win32 API：
 * {@code user32!FindWindowW} 按窗口标题取回 HWND，再用
 * {@code dwmapi!DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)} 切换标题栏明暗。
 *
 * <p>仅 Windows 生效；非 Windows、老系统无该属性、库缺失或原生访问受限时一律静默降级（no-op），
 * 不影响应用运行。
 */
public final class NativeTitleBar {

    private NativeTitleBar() {
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Windows 10 1809+/11：设为非零使标题栏采用深色。 */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

    /**
     * 让标题为 {@code windowTitle} 的原生窗口标题栏跟随明暗。
     *
     * @param windowTitle 主窗口标题（须与 {@code Stage.setTitle} 一致，用于定位 HWND）
     * @param dark        是否深色标题栏
     */
    public static void apply(String windowTitle, boolean dark) {
        if (!WINDOWS || windowTitle == null || windowTitle.isEmpty()) return;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
            SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi.dll", Arena.global());

            MethodHandle findWindow = linker.downcallHandle(
                    user32.find("FindWindowW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle dwmSet = linker.downcallHandle(
                    dwmapi.find("DwmSetWindowAttribute").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment title = toWide(arena, windowTitle);
                MemorySegment hwnd = (MemorySegment) findWindow.invoke(MemorySegment.NULL, title);
                if (hwnd == null || hwnd.address() == 0) return;
                MemorySegment value = arena.allocate(ValueLayout.JAVA_INT);
                value.set(ValueLayout.JAVA_INT, 0, dark ? 1 : 0);
                dwmSet.invoke(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4);
            }
        } catch (Throwable ignored) {
            // 老系统 / 库缺失 / 原生访问受限：静默降级，不影响主题切换与应用运行
        }
    }

    /** 将 Java 字符串编码为以 NUL 结尾的 UTF-16LE 宽字符串（Win32 LPCWSTR）。 */
    private static MemorySegment toWide(Arena arena, String s) {
        char[] chars = s.toCharArray();
        MemorySegment seg = arena.allocate((chars.length + 1) * 2L);
        for (int i = 0; i < chars.length; i++) {
            seg.set(ValueLayout.JAVA_CHAR, i * 2L, chars[i]);
        }
        seg.set(ValueLayout.JAVA_CHAR, chars.length * 2L, (char) 0);
        return seg;
    }
}
