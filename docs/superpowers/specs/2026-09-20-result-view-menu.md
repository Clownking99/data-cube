# 查询结果工具栏浏览菜单

基线 main `e3b6c03`。Windows 合成窗口复现 Shift+F10 打开 JavaFX 结果菜单后，Down 唤出系统菜单。JavaFX 25 Windows GlassWindow 对 F10 仅在无修饰键时抑制原生默认处理；Microsoft 文档说明 F10 默认处理会进入系统菜单。此处只作为原因线索，不把 Java 事件消费当作原生修复，不引入窗口钩子、模拟按键或依赖升级。

本轮提供可发现的替代入口：将“查看单元格”变为分裂按钮，主区域保持单次查看；下拉列出查看当前单元格、查看当前行（可见列）、定位到行、恢复原始行序。Tab 聚焦后 Down 打开、方向键选择、Enter 执行、Escape 取消，保留原右键菜单，不增加全局快捷键。

- 打开菜单时刷新可用状态；无有效选择禁用单元格/整行，无行禁用定位，无排序或行序变化禁用恢复。
- 空查询仍可打开下拉，以便零匹配时清除排序。主按钮沿用选择提示，不新增查询。
- 复用既有浏览/定位/恢复实现；菜单动作仅捕获视图修订号，结果切换、筛选重建、关闭及忙碌时拒绝迟到动作。工具栏禁用或重绘时关闭菜单。
- 保持已加载结果、筛选、列布局、选区、SQL、文件和连接不变；定位与恢复仅有原有明确行为。
- 使用合成离线测试和独立 profile 的原生窗口验证，不读取真实数据或剪贴板。Shift+F10 限制未修复，必须如实记录。

参考：[JavaFX 25 GlassWindow.cpp](https://raw.githubusercontent.com/openjdk/jfx/jfx25/modules/javafx.graphics/src/main/native-glass/win/GlassWindow.cpp)、[Microsoft WM_KEYDOWN](https://learn.microsoft.com/en-us/windows/win32/inputdev/wm-keydown)。
