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
     * SetWindowPos 组合标志：SWP_NOSIZE|SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED|SWP_NOOWNERZORDER
     * = 0x0001|0x0002|0x0004|0x0010|0x0020|0x0200，用于只强制框架重绘而不改动窗口位置/尺寸/层级/焦点。
     */
    private static final int SWP_REFRESH_FRAME = 0x0237;

    /** uxtheme!SetPreferredAppMode 枚举：0=Default 1=AllowDark 2=ForceDark 3=ForceLight。 */
    private static final int APPMODE_FORCE_DARK = 2;
    private static final int APPMODE_FORCE_LIGHT = 3;
    /** uxtheme.dll 中 SetPreferredAppMode 的导出序号（无名字导出，只能按序号取址）。 */
    private static final int ORD_SET_PREFERRED_APP_MODE = 135;

    /**
     * 让标题为 {@code windowTitle} 的原生窗口标题栏跟随明暗。
     *
     * @param windowTitle 窗口标题（须与 {@code Stage.setTitle} 一致，用于定位 HWND）
     * @param dark        是否深色标题栏
     * @return 是否成功定位到窗口句柄并应用（非 Windows / 未找到窗口 / 异常均返回 false）
     */
    public static boolean apply(String windowTitle, boolean dark) {
        if (!WINDOWS || windowTitle == null || windowTitle.isEmpty()) return false;
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
            MethodHandle setWindowPos = linker.downcallHandle(
                    user32.find("SetWindowPos").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment title = toWide(arena, windowTitle);
                MemorySegment hwnd = (MemorySegment) findWindow.invoke(MemorySegment.NULL, title);
                long addr = hwnd == null ? 0 : hwnd.address();
                if (hwnd == null || addr == 0) return false;
                MemorySegment value = arena.allocate(ValueLayout.JAVA_INT);
                value.set(ValueLayout.JAVA_INT, 0, dark ? 1 : 0);
                dwmSet.invoke(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4);
                // 强制非客户区(标题栏)重绘：对已显示窗口设置深色属性后，若不触发框架变更
                // 标题栏往往不会立即换色（模态对话框尤其明显，因其后续无激活/重绘事件）。
                // SWP_FRAMECHANGED 会重算并重绘窗口框架，使 DWM 以新属性重绘标题栏。
                setWindowPos.invoke(hwnd, MemorySegment.NULL, 0, 0, 0, 0, SWP_REFRESH_FRAME);
                return true;
            }
        } catch (Throwable ignored) {
            // 老系统 / 库缺失 / 原生访问受限：静默降级，不影响主题切换与应用运行
            return false;
        }
    }

    /**
     * 设置进程级首选应用模式（uxtheme!SetPreferredAppMode，序号 135，Win10 1809+）。
     *
     * <p>设为深色后，此后新建的窗口标题栏默认即为深色，从根本上避免弹窗“浅色→深色”的切换闪烁；
     * 配合 {@link #apply(String, boolean)} 对已打开窗口做实时切换。该导出无名字，只能按序号取址；
     * 非 Windows / 老系统 / 取址失败时静默降级。
     *
     * @param dark 是否强制深色应用模式
     */
    public static void setAppDarkMode(boolean dark) {
        if (!WINDOWS) return;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
            // 确保 uxtheme 已载入本进程，后续 GetModuleHandleW 才能取到其句柄
            SymbolLookup.libraryLookup("uxtheme.dll", Arena.global());

            MethodHandle getModuleHandle = linker.downcallHandle(
                    kernel32.find("GetModuleHandleW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle getProcAddress = linker.downcallHandle(
                    kernel32.find("GetProcAddress").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment name = toWide(arena, "uxtheme.dll");
                MemorySegment hmod = (MemorySegment) getModuleHandle.invoke(name);
                if (hmod == null || hmod.address() == 0) return;
                // MAKEINTRESOURCEA(135)：以序号（高位为 0、低位为序号的“伪指针”）作为 lpProcName
                MemorySegment ordinal = MemorySegment.ofAddress(ORD_SET_PREFERRED_APP_MODE);
                MemorySegment proc = (MemorySegment) getProcAddress.invoke(hmod, ordinal);
                if (proc == null || proc.address() == 0) return;
                MethodHandle setPreferredAppMode = linker.downcallHandle(
                        proc, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                // 返回值为上一次模式，无需处理
                setPreferredAppMode.invoke(dark ? APPMODE_FORCE_DARK : APPMODE_FORCE_LIGHT);
            }
        } catch (Throwable ignored) {
            // 老系统 / 序号变更 / 原生访问受限：静默降级
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
