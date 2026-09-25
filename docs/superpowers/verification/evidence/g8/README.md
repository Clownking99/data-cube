# G8 可复查本地证据

本目录不含真实连接、凭据、历史或业务文件。截图源为合成窗口；截图工具返回 JPEG，归档时使用 .jpg 扩展名，字节未改。desktop-manifest.json 记录全部截图/观察树和桌面进程输出摘要。编号 27 是未成功输入的观察，31 无文件，不能作为通过证据。

重跑时先把本目录脚本复制到一个全新临时目录，设置当前进程 JAVA_HOME/PATH 为本机已安装 JDK；不得安装工具。run-check.ps1 的 Repository 指向独立 worktree，Name 与 ProfileName 每次唯一；参数示例：

```powershell
& ./run-check.ps1 -Name branch-full -Repository $repo -ProfileName branch-full-profile -GradleArgs @('clean','test')
& ./run-check.ps1 -Name branch-buildSrc -Repository $repo -ProfileName buildsrc-profile -GradleArgs @(':buildSrc:test','--rerun-tasks')
& ./run-check.ps1 -Name branch-image -Repository $repo -ImageBuild -GradleArgs @('jpackageImage')
```

所有命令包含 --offline；缺依赖时停止，不自动下载。run-check 仅在日志证明测试任务实际执行后采集 XML。各 live skip 单列。

桌面重现需本地可见窗口授权，设置 DATACUBE_G8_SCRATCH 为新临时目录，用 desktop.gradle 注册 g8DesktopClasspath（依赖 testClasses），生成临时 desktop-classpath.txt。launch-desktop.ps1 的 Scale 为 100 或 150，Mode 为 migration/cancellation/discovery/workflow/shell；脚本拒绝复用同名运行目录。脚本中的 JDK 路径是本轮实测路径，其他机器需明确指向其已有 JDK。不要运行正式启动器或接入已保存 profile。

G8RuntimeShellProbe.java 在临时 classes 目录编译，classpath 用 desktop-classpath.txt；运行仅用镜像 runtime/bin/javaw.exe，参数 --add-modules com.datacube --add-exports com.datacube/com.datacube.fx=ALL-UNNAMED --enable-native-access=ALL-UNNAMED,com.datacube，classpath 只含探针 classes。-Duser.home 必须指向新建 g8-shell-desktop-profile，-Dglass.win.uiScale=150%。探针不调用启动更新自检；因此此路径不代表正式启动器/更新验收。复现使用 launch-image-shell.ps1 可生成独占目录和启动记录。

原生输入使用 computer-use 工具，先定位实际窗口并观察截图，再逐步执行；工具连续失败时停止该路径。不要在脚本中触发控件来冒充原生输入。
