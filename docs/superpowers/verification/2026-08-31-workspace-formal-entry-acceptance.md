# 工作区正式便携入口验收

## 范围与隔离

本轮维护者认可继续补齐键盘操作和正式入口验收。沿用`codex/sql-workspace-recovery`，基线`f897faaa7453c34251b4c8d8fa12e0ecbe789518`，未改生产代码、测试、构建配置或主分支。原计划不启动更新入口的历史限制，本轮仅就已认可的正式便携入口验收扩展；不安装、不升级、不推送或发布。

沿用已标记合成profile `C:/Users/hetia/AppData/Local/Temp/datacube-workspace-ui-3a97aa5b837644eaaad0636a6eeed5cf`。启动前核对两个标记及不存在`connections.json`；没有重新播种、删除草稿或访问真实用户配置。Computer Use仅操作本轮拥有的窗口，不改变隐私开关，不执行SQL。

子代理distribution_launch_check只读核查启动路径。root独立核对正式配置与更新调用：实际入口为`com.datacube/com.datacube.DataCubeFx`，没有覆盖user.home；启动会匿名检查公开GitHub Releases，但不会自动下载或安装。每次启动仅在启动进程环境中替换`JAVA_TOOL_OPTIONS=-Duser.home=<合成profile>`，生成子进程后立即恢复；没有修改系统环境或镜像配置。

## 本轮键盘证据

隔离AppShell session5026/PID31192：初始焦点为新建连接；Shift+Tab后可访问性焦点明确为“恢复 SQL 工作区”；Space打开草稿对话框，稳定后显示共2/可用2/缺失0。关闭对话框后焦点回到入口；Alt+F4正常退出，native exit0，3m31s。

对话框稳定后可访问性报告焦点为只读预览编辑控件，向工具返回的主窗口发送一次Tab后仍报告相同控件；截图显示主窗口被激活、对话框标题栏失活。`list_windows`只列出主窗口，没有可单独选择的模态窗口。不能据此证明真实用户按Tab失效，也不能证明对话框完整键盘路径通过。本轮不再重复同一无效工具路径，不据此修改产品。

仍需独立人工确认：在实际“SQL 草稿”对话框内用Tab/Shift+Tab移动焦点、用Space激活恢复/刷新，以及Esc关闭。不要激活清空、删除或保护开关。该项未完成，P2完整桌面验收不勾选。

## 当前镜像构建

JDK25.0.1+8，作用域内移除并恢复JAVA_TOOL_OPTIONS，执行`./gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain`。session97263 native exit0，52s，14任务全部执行。JEP493工具链提示保留；不是构建失败。

新`runtime/lib/modules` SHA256：`39745372D9674114B8A8149C0E44866190B8A4A5D8AD66B77AE7DA8D65619468`。jimage检查包含DataCubeFx、SqlAutoComplete、SqlWorkspaceManagerPane及SqlWorkspaceRecoveryTabs；未发现SqlWorkspaceAcceptanceLauncher、SqlAutoCompleteFocusTest或DraftConnectionProbe。正式cfg没有隔离profile参数。此前两份忽略的验收helper SHA256不变，本轮正式启动不使用它们。

## 正式入口实际结果

直接启动刚生成的`build/jpackage/DataCube/DataCube.exe`，不是替代AppShell入口。

- 首次session58625，启动器PID24488，实际JVM子进程PID23828。第一次诊断误选外层启动器，jcmd返回“jvm.dll not loaded”；只读查明父子关系后，对23828查询成功，确证user.home为合成profile，java.home为镜像内置runtime，jpackage.app-path为本次镜像。没有记录其他系统属性。
- 两次启动均实际出现新版本提示，选择“稍后”；没有点击立即更新、下载或安装。启动不自动显示SQL标签，连接树为空。
- 显式恢复显示已打开2/已定位0/缺失0/失败0，当前布局已保存；重复恢复显示已打开0/已定位2/缺失0/失败0。立即及稳定截图均无SELECT补全浮层。
- 关闭管理页后第二张alpha为当前草稿，合成文本和可见选区保留。切换第一张确认beta文本及可见选区。两个编辑器均未绑定连接、尚未创建专用会话、执行禁用。截图证明可见选区范围，不证明精确anchor/caret方向。
- 保持beta当前后Alt+F4正常退出，session58625 native exit0。随后相同profile重启，session40045/启动器PID35456；显式恢复再次打开2，beta为当前第一张，文本和可见选区保留。再次Alt+F4正常退出，native exit0。
- 两次stdout均0字节；stderr均仅有JAVA_TOOL_OPTIONS采用记录及JavaFX NativeLibLoader对javafx.graphics的原生访问警告。后者未阻碍本轮启动/恢复/退出，留作发行兼容性跟踪；本轮没有修改native-access参数。
- 最终进程检查没有本次路径的DataCube.exe，Computer Use没有本轮目标窗口；没有强制结束进程。合成profile及两组formal-entry-20260831-a/b日志保留。

## 验收结论

补充镜像核验：按167个测试Java源文件的相对路径逐一映射同名class，与jimage清单精确比对，匹配0项。distribution_launch_check对本记录与启动源码核查结论做只读一致性复核，未发现阻塞性过度声称；该复核不是重做补全代码审查。

正式便携入口的启动、更新提示跳过、恢复、去重、正常退出和同profile重启已通过本轮实际验证。未测试安装程序、执行更新、真实数据库或全部键盘路径。本轮未重跑无源码变化的全量测试；上一轮1564通过/3既有跳过不冒充本轮结果。现有补全修复审查不变，未合并或推送新提交。
