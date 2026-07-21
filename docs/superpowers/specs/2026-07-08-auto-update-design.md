# 应用自动更新设计

## 目标

让已安装/已解压的 DataCube 无需"卸载再装"即可升级：应用能感知自身版本，向 GitHub Releases 查询最新版，发现新版后引导用户完成更新——**安装版**原地升级、**绿色版**自替换重启。

## 背景与约束

- **发布架构现状**（`.github/workflows/release.yml`）：推送 main 由 CI 用 Gradle + jpackage 打两种 Windows 产物并发 GitHub Release：
  - `DataCube-<tag>-win64-setup.exe`：jpackage exe 安装程序，**per-machine 全机器安装**（`--win-dir-chooser --win-menu --win-shortcut`，安装/卸载需管理员提权），WiX v5，内置 jlink 运行时。
  - `DataCube-<tag>-win64-portable.zip`：免安装绿色版（app-image 目录压缩）。
  - 版本号由 git tag 递增计算（`vMAJOR.MINOR.PATCH`），传入 `-PappVersion`；仓库 `Clownking99/data-cube`（公开）。
- **运行时无版本号**：源码中不存在任何版本常量或"关于"对话框——自动更新的首要前提是把版本号打进包并在运行时读到。
- **模块化**：`src/module-info.java` 模块 `com.datacube`；新增网络访问需 `requires java.net.http`。新包 `com.datacube.update` 属同模块，无需拆模块。
- **两种构建并存**：`build.gradle`（模块化 jlink/jpackage，CI 发布路径）与 `build.sh`（fat-jar，本地开发）。版本资源需在两者都生成。
- **Windows 自替换约束**：进程运行时其 exe / 运行时 dll 被占用，无法自我覆盖。成熟方案一律**先退出、由外部（安装包或辅助脚本）接力替换**。

### 澄清结论（brainstorming）

| 议题 | 决定 |
|---|---|
| 覆盖范围 / 路线 | A+B 都做：安装版走 setup.exe 原地升级；绿色版走辅助脚本自替换 |
| 检查时机 | 启动后台静默自检 + "关于"对话框内手动"检查更新" |
| UI 范围 | 更新提示展示 Release Notes；新增"关于"对话框；下载显示进度条 |
| 形态判定 | 由 jpackage 启动器路径 + 注册表卸载项判定；无法判定时不猜，打开 Releases 页兜底 |
| 开发构建 | 版本为 `0.0.0-dev` 时跳过启动自检（手动检查仍可用于联调） |

### 采用方案

**方案 A（安装包/脚本接力替换）+ 运行时形态自适应。** 应用只负责"发现新版、下载资产、拉起替换者、退出自己"，替换动作交给 WiX 安装包或辅助脚本——规避进程自我替换的 Windows 限制。（已否决：应用内直接覆盖自身文件——运行时文件被占用必然失败；引入 update4j 等框架——需改造 bootstrap 启动架构，成本过高、收益不匹配。）

## 设计

### 1. 版本号进包（基础前提）

运行时不依赖 jpackage 属性，改为**构建时生成类路径资源** `com/datacube/version.properties`（内容 `version=<X.Y.Z>`）：

- **Gradle**：新增任务 `generateVersionProperties`，把 `version=${project.version}` 写入 `build/generated/version/com/datacube/version.properties`；将该目录并入 `main` sourceSet 的 `resources.srcDirs`，`processResources` 依赖此任务 → 资源随 jlink/jpackage 进包。
- **build.sh**：在 `javac`（生成 `build-out/com/datacube/`）之后、`cp -r build-out/* staging`（第 63 行）之前，写入 `build-out/com/datacube/version.properties` 内容 `version=0.0.0-dev`（占位）。
- **`AppVersion`**（`com.datacube.update`）：`current()` 读该资源，缺失/读失败回退 `0.0.0-dev`；`isDev()` 判定是否开发构建。

### 2. 语义化版本解析与比较

- **`AppVersion.parse(String)`** → `(major, minor, patch)`，容忍前导 `v`；无法解析视为 `0.0.0`。
- **`AppVersion.isNewer(remote, local)`**：按 major→minor→patch 数值比较；仅当 remote 严格大于 local 才算有更新。
- 纯函数、无副作用，作为可单测点。

### 3. 查询最新版本

- **`UpdateChecker.fetchLatest()`**（`java.net.http.HttpClient`，超时 8s）：GET
  `https://api.github.com/repos/Clownking99/data-cube/releases/latest`，`Accept: application/vnd.github+json`、带 `User-Agent`。
- **JSON 解析**：不引入新库，手写极简解析只取需要的字段——`tag_name`、`body`、`assets[].name` + `assets[].browser_download_url`。
- 产出 **`ReleaseInfo`**：`tag` / `version` / `releaseNotes(body)` / `setupExeUrl`（名含 `setup.exe`）/ `portableZipUrl`（名含 `portable.zip`）。任一资产缺失字段置 null，由应用层兜底。
- 失败（无网络 / 限流 / 超时 / 非 200）抛受检异常或返回空 `Optional`，由调用方决定是否提示。

### 4. 运行形态判定

**`InstallMode.detect()`** → `INSTALLED` / `PORTABLE` / `UNKNOWN`：

1. 用 `System.getProperty("jpackage.app-path")` 取本启动器 exe 路径，向上定位 app-image 根目录（`appDir`）。取不到该属性 → 直接 `UNKNOWN`（非 jpackage 环境，如 IDE/开发）。
2. 查询注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall`（`reg query ... /s`），寻找 `InstallLocation` 指向 `appDir` 的卸载项：
   - 命中 → `INSTALLED`
   - 未命中 → `PORTABLE`
   - 查询异常 → `UNKNOWN`
3. `UNKNOWN` 时更新动作退化为"打开 Releases 页让用户手动下载"，避免误判把绿色版升级成安装版。

### 5. 下载与更新应用

**`UpdateApplier`**：

- **下载**：`download(url, destFile, ProgressListener)` 用 HttpClient 流式写盘，按 `Content-Length` 回调进度百分比；下载完成校验落盘大小与 `Content-Length` 一致，不一致则删档报错。目标目录用系统临时目录。
- **INSTALLED**：下载 `setup.exe` → `Runtime.exec` 启动它（用户走安装向导，WiX 同 UpgradeCode 原地升级）→ `Platform.exit()`。
- **PORTABLE**：下载 `portable.zip` → 生成 `update.cmd` 到临时目录，以分离进程启动后 `Platform.exit()`。脚本步骤（崩溃安全，避免半成品）：
  1. `timeout` 轮询等本进程 PID 退出（PID 由 `ProcessHandle.current().pid()` 取得并传入脚本）。
  2. 解压 zip 到临时 `staging`。注意 zip 内根为 `DataCube/` 单层目录（`Compress-Archive -Path build/jpackage/DataCube` 所致），实际新版文件在 `staging\DataCube\`。
  3. **备份换入**：将现 `appDir` 重命名为 `appDir.bak`，把 `staging\DataCube` 移动/复制为新的 `appDir`；成功后删 `appDir.bak`，失败则回滚（把 `.bak` 改回）——规避 `robocopy /mir` 中途中断导致目录不完整。
  4. 重启 `appDir\DataCube.exe`，清理临时文件。
- **UNKNOWN**：`Desktop.browse` 打开该 Release 的网页地址。

### 6. UI 对接（`com.datacube.fx`）

- **`AboutDialog.show(...)`**：展示当前版本（`AppVersion.current()`）、项目仓库链接、"检查更新"按钮（点按触发手动检查，结果始终反馈：有新版→提示弹窗；已最新→"已是最新版本"；失败→"检查失败，请稍后重试"）。
- **`AppShell.topBar()`**：在"⚙ 设置"按钮旁新增"ℹ 关于"按钮，打开 `AboutDialog`。
- **更新提示弹窗**：显示 `新版本 <tag>`、Release Notes（滚动文本区）、"立即更新" / "稍后"两个按钮。
- **下载进度弹窗**：`ProgressBar` + 百分比标签，绑定 `UpdateApplier` 的进度回调；下载中禁用关闭。
- **启动自检**：`DataCubeFx.start()` 在主窗口显示后，起后台线程调 `UpdateChecker`；`isDev()` 为真则跳过。发现新版才 `Platform.runLater` 弹提示；任何失败静默（仅记日志），不打扰用户。

### 7. 编排与线程

- **`UpdateService`**：聚合 `UpdateChecker` / `InstallMode` / `UpdateApplier`，对 UI 暴露两个入口——`checkInBackground(onNewVersion)`（静默）与 `checkManually(callbacks)`（结果始终回调）。所有网络/IO 在后台线程，UI 更新经 `Platform.runLater`。

### 8. 依赖与构建

- `module-info.java` 增 `requires java.net.http;`。
- `build.gradle`：新增 `generateVersionProperties` 任务并接入 resources；`build.sh`：写入 dev 版本占位。
- 无新增第三方库（JSON 手写解析、HttpClient 为 JDK 自带）。

## 测试计划

- **单元**：`AppVersion` 的版本解析与 `isNewer` 比较（等值 / 各位递增 / 前导 v / 非法串 / dev 判定）——纯函数。
- **手动（安装版）**：装 v(N) → CI 出 v(N+1) → 启动应用→自检弹提示→"立即更新"→下载进度→安装向导升级→重启后"关于"显示 v(N+1)。
- **手动（绿色版）**：解压 v(N) → 手动"检查更新"→下载→`update.cmd` 覆盖并重启→版本号更新；确认无残留临时文件、原有配置（连接）保留。
- **降级/异常**：断网启动（自检静默无弹窗）；手动检查断网（提示失败）；IDE 直接运行（形态 UNKNOWN，走打开网页）。
- `gradlew compileJava` 编译验证；确认 `version.properties` 已进 jpackage 产物。

## 假设

- 目标平台为 Windows x64（现有 CI 唯一产物平台）；其它平台暂不支持自动更新。
- GitHub Releases API 匿名访问（公开仓库，60 次/小时/IP 足够）。
- Release 资产命名遵循现有约定（`...setup.exe` / `...portable.zip`），JSON 解析据此匹配。
- 安装包同 UpgradeCode 支持运行新版 setup.exe 原地升级（jpackage 依 app 名生成稳定 UpgradeCode）。
- 绿色版目录对当前用户可写（自替换脚本需写权限）。
