# 品牌资产集成（C 组）设计

将 datacube「数据魔方」品牌（紫蓝渐变立方体 + 字标 + Slogan）落地到应用的图标、启动页、关于页与 CLI。

## 背景与约束

- 品牌资产位于 `datacube-brand-assets/`：5 个 SVG（icon/primary/horizontal/mono/splash）+ `brand-guide.html`。
- **所有 logo 均为纯几何图形**：3 个多边形（top/left/right 面）+ 线性渐变 + 低透明白棱线 + 文字。无位图、无复杂路径。
- 本机**没有** SVG→位图转换工具（ImageMagick/Inkscape/rsvg 均缺）。
- JavaFX 窗口图标与 jpackage 安装包图标**只吃位图**（PNG/ICO），不吃 SVG。

## 核心方案

**矢量原生复刻**：用 JavaFX `Polygon` + `LinearGradient`（`proportional=true`，映射到各面包围盒，与 SVG 逐面渐变一致）复刻立方体，任意 DPI 清晰、零外部资源。窗口/任务栏图标由该矢量节点 `snapshot()` 成 `WritableImage` 注入 `Stage.getIcons()`。唯一需要真实位图文件的安装包 `.ico` 用构建期 Java2D 生成。

### 品牌常量（来自 brand-guide.html）

- 色板：`#9B8CFF / #6C5CE7 / #5241CC`（紫）→ `#0984E3 / #0659A8`（蓝）；点缀 `#00D2D3`；深底 `#0A0A12 / #0E0E14 / #151520`；前景 `#E8E8ED / #A8A8B8 / #6B6B80 / #505068`；边框 `#252538`。
- 立方体（viewBox 0..100）：top `(50,14 83,33 50,52 17,33)` 渐变竖直 `#9B8CFF→#6C5CE7`；left `(17,33 50,52 50,88 17,69)` 渐变 `(1,0)→(0,1)` `#5241CC→#0659A8`；right `(83,33 50,52 50,88 83,69)` 渐变 `(0,0)→(1,1)` `#6C5CE7→#0984E3`；三条棱线自中心 `(50,52)` 出发，白色低透明。
- 字标 `datacube`（小写、Segoe UI/系统字体 SEMI_BOLD），中文副标 `数据魔方`。
- Slogan：`每一面，皆是数据新维度` / `Every Face, A New Dimension of Data`。

## 变更清单

### `fx/BrandLogo`（新增，纯矢量工厂）
- 品牌色常量 + Slogan 常量。
- `Group cube(double size)`：立方体缩放到 `size`。
- `Image icon(double px)`：`cube` 透明底 `snapshot`。
- `void applyIcons(Stage)`：注入 16/24/32/48/64/128/256 多尺寸图标。
- `Text wordmark(double)` / `Text subtitle(double)`：字标与副标（供关于/闪屏复用）。

### `fx/SplashScreen`（新增）
- `StageStyle.TRANSPARENT` 无边框窗口，深底圆角卡片 + 阴影：`cube(84)` + `datacube` + `数据魔方` + 分隔线 + Slogan。
- `show()` / `fadeAndClose(Runnable)`（淡出后关闭）/ `close()`。

### `fx/AboutDialog`（重写）
- 深色品牌卡片：头部 `cube(40)` + `datacube`/`数据魔方`；正文版本号、产品简介、数据库支持标签（PostgreSQL/Oracle）、Slogan、项目主页链接、检查更新/关闭按钮。保留 `UpdateService` 交互与 `PROJECT_URL`。

### `DataCubeFx`（改）
- `BrandLogo.applyIcons(primaryStage)`。
- 启动流程：先 `SplashScreen.show()`，构造 `AppShell`/`Scene`，`PauseTransition`（约 1.1s，保证最短展示）后 `primaryStage.show()` + 闪屏淡出 + 触发更新自检。构造异常时关闭闪屏并保留原兜底提示。

### `fx/AppShell`（改）
- 顶栏标题前加 `BrandLogo.cube(22)` 小图标，强化品牌（不改浅色主题）。

### `DataCube`（CLI，改）
- `printBanner()` 换为 ASCII 立方体 + `datacube · 数据魔方` + 版本 + Slogan。

### 安装包/进程图标 `.ico`
- `buildSrc/.../IcoGenerator`：Java2D（`Graphics2D` + `GradientPaint` + 抗锯齿）绘制同款立方体，多尺寸（256/128/64/48/32/16）→ PNG 内嵌式 `.ico`。
- `build.gradle`：`generateIcon` 任务产出 `packaging/DataCube.ico`；`jpackageImage`/`jpackage` `dependsOn` 之；沿用既有 `if (iconFile.exists()) icon = ...`（`.ico` 提交入库，构建期刷新）。

## 假设与不做

- 不引入外部字体（依赖系统 Segoe UI / Microsoft YaHei 回退）；JavaFX `Text` 无字间距，忽略 letter-spacing 细节。
- 不做 mono/水印变体的程序内集成（仅保留资产）。
- 闪屏为固定最短时长展示，不做进度联动。
- `.ico` 提交入库以保证 `iconFile.exists()` 在干净构建时为真；`generateIcon` 供再生。
