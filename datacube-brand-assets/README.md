# DataCube 雾紫折页图标

2026-09-13 确认的方向：雾紫三层数据折页、四格表格、浅色圆角底板。
仅替换软件图标，不更改应用主题、字标、产品名称或 Slogan。

## 唯一输入与输出

| 用途 | 入库母版 | 输出 |
| --- | --- | --- |
| 标准尺寸 | `assets/icon-mist-violet.png` | 48 / 64 / 128 / 256 px；保留浅色底板 |
| 小尺寸 | `assets/icon-mist-violet-compact.png` | 16 / 24 / 32 px；无底板，折页占比更大 |
| 界面内标志 | 同上 | `mark-standard.png` 512 px / `mark-compact.png` 256 px，按逻辑尺寸 32 px 分界 |

`buildSrc/.../IcoGenerator.java` 同时驱动 PNG 和 ICO 输出，避免窗口与安装包两套样式。
标准母版是获选设计稿，包含灰色展示背景；构建以统一圆角遮罩去除外侧背景，
不应直接拿母版当作最终透明图标。小尺寸母版有真实 alpha 通道。
缩放采用逐级缩小和预乘 alpha。母版变更是 Gradle 的显式任务输入。

运行 `./gradlew generateBrandIcons generateIcon`：

- 运行时：`build/generated/brand/com/datacube/fx/`，经 `processResources` 打进模块。
- 安装包：`packaging/DataCube.ico`，含上述七个尺寸；每项 PNG 与运行时文件字节一致。
- `BrandLogo.mark` 使用高分辨率素材，供工具栏、关于页和闪屏显示。
- 图标资源缺失时明确报错，不静默回退成旧版立方体。
- 构建无需图像生成服务、API Key、Python 或额外图像处理依赖。

## 生成来源

使用 Codex 内置图像生成工具，非 CLI / API Key 模式。保留原始获选稿与小尺寸配套稿，
提示词及素材处理边界见 [生成记录](mist-violet-prompts.md)。
这些是 PNG 位图母版，不声明为手工矢量稿；修改时保留雾紫方向与四格折页语义。

## 旧版素材

`brand-guide.html` 和 `assets/logo-*.svg` 保留为早期立方体方案的历史参考，
不再是应用图标来源，也不作为新发布软件图标使用。

## 检查与限制

- `IcoGeneratorTest` 检查 ICO 目录、各尺寸 PNG 一致性、圆角透明、尺寸分界、更新输入和无效素材。
- 执行 `./gradlew :buildSrc:test test` 同时检查构建期和运行时；Verify / Release 工作流均已包含此任务，远端执行需推送后确认。
- `BrandLogoTest` 检查实际资源、重复装载、窗口间隔离、小尺寸/标准尺寸选择和雾紫色。
- 视觉验收还需查看真实窗口；测试不能替代对 16 px 折角与层间距的主观检查。
- Windows 已安装快捷方式可能缓存旧图标；新生成的 exe/ICO 不代表现有快捷方式缓存已刷新。
