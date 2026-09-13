# 雾紫折页图标验收

## 范围

- 用户选定雾紫配色并授权小尺寸适配、统一应用到软件图标。
- 隔离分支 `codex/brand-mist-violet`，基线 `94d2450`。
- 窗口 / 任务栏、主工具栏、关于页、闪屏、jpackage 进程/安装包共用图标输入。
- 16 / 24 / 32 px 使用无底板折页；48 / 64 / 128 / 256 px 使用标准底板。
- 不修改连接、SQL、持久化数据、主题颜色；不读取真实用户配置、不连接数据库、不发起更新检查。
- 不推送、不打 tag、不运行安装器、不操作图标缓存。

## 自动验证

- 定向：`gradlew.bat :buildSrc:test test --tests '*BrandLogoTest' generateIcon --no-daemon --console=plain`
  （`JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`），47 秒，exit 0。
- 构建期 8 项 + 运行时 7 项测试。
- 全量：`gradlew.bat clean :buildSrc:test test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`
  （`JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`），3 分 2 秒，exit 0。
  XML 汇总：应用 2442 项 / 2439 passed / 3 既有 live skipped / 0 failures/errors；
  构建期 8 项已在前述定向执行通过，本次 up-to-date。
- `jlink` 与 `jpackageImage` 实际执行成功；已有测试源码 unchecked 提示、
  环境变量引起的 javac/java 探测提示与 JEP 493 提示仍存在，不声明“无警告”。
- Verify / Release 的测试命令显式包含 `:buildSrc:test`；图标母版也计入其任务 inputs。
  尚未推送，不声明远端 CI 成功。
- 增补 CI 门禁时先运行既有工作流契约测试：3 项中 2 项因仍匹配旧 `clean test` 命令而失败
  （7 秒，exit 1）；随后将契约加强为同时要求构建期与应用测试，不删除检查。
- 最终定向回归：`gradlew.bat :buildSrc:test test --tests '*BrandLogoTest' --tests '*CiWorkflowContractTest' generateIcon --no-daemon --console=plain`，
  9 秒，exit 0；10 项应用/CI 契约通过，构建期 8 项 up-to-date；没有失败或跳过。

| 要求 | 证据 |
| --- | --- |
| 窗口与安装包图标一致 | `IcoGeneratorTest.icoContainsExactlyTheRuntimePngsWithValidDirectoryEntries` |
| 小尺寸版本与透明底板适配 | `IcoGeneratorTest.compactThresholdAndStandardMaskPreserveDifferentSourceColors` |
| 更新素材会更新输出 | `IcoGeneratorTest.changedInputReplacesExistingOutputsDeterministically`；两个生成任务声明母版为 inputs |
| 不接受假透明/无效素材 | `IcoGeneratorTest.opaqueCompactMasterIsRejectedBeforeWritingOutputs` / `invalidMastersFailWithoutCreatingOutput` |
| 界面尺寸选择与高 DPI 资源 | `BrandLogoTest.marksUseCompactThrough32AndStandardAboveWithIndependentNodes` |
| 窗口加载无重复/交叉污染 | `BrandLogoTest.applyIconsInstallsAllSevenTransparentSizesWithoutDuplicates` |
| 保留雾紫与四格浅色 | `BrandLogoTest.artworkIsVioletAndKeepsLightCellsAndTransparentExterior` |

## 桌面验证

已完成。使用只允许 `brand-desktop-profile` 的 `BrandIconDesktopFixture`，
加载实际 AppShell、BrandLogo 与关于页，不调用启动更新自检；真实用户目录不在验收范围。

- 从生产 jpackage 镜像复制专用验收镜像，仅副本配置加入测试入口、测试类 patch jar 和隔离 user.home；
  生产配置仍为 `com.datacube/com.datacube.DataCubeFx`。
- 真实主工具栏 20 px 与窗口标题栏显示雾紫无底板折页，旧立方体未出现。
- 实际“关于 DataCube”对话框显示带浅色底板的 44 px 图标和新版标题栏图标。
- 深色/浅色样本行展示实际产物 16 / 24 / 32 / 48 / 64 / 128 px，
  小尺寸能辨认折页与四格，未见棋盘格或灰色展示背景；16 px 的折角不承诺逐细节可辨。
- 点击真实“亮色”按钮后主工具栏图标保持清晰；未改变真实用户的主题配置。
- 通过实际关闭按钮正常退出验收实例；随后窗口列表为空，精确 exe 路径的存活进程数为 0。
- `jimage list` 确认生产模块包含 BrandLogo、icon-16.png、mark-compact.png、mark-standard.png，
  不包含 BrandIconDesktopFixture。闪屏调用同一标准版工厂，未单独录制其短暂启动画面。
- 不把标题栏与尺寸对照当作 Windows 已有快捷方式缓存或真实任务栏的独立验收。

## 产物摘要

- ICO SHA-256：`FF51C5DC35F9C553131451BBA78A648BA64CDA48D2BBAB6C8B7954963A0763F7`
- 验收构建的生产 exe SHA-256：`B6E87225695F176A042A202475AB908A06180AA2B4F5FBAD8D135F5A554F2169`
- icon-32.png SHA-256：`F444D62C9576BB138847624B0CF6111F0B53179579BF1E9706CCBE44479FE72C`
- 生产镜像在隔离工作区 `build/jpackage/DataCube/`。版本号 0.0.0 仅为本地验收，不用于正式发布。

## 本地集成

待最终差异审查与 main 快进；不推送、不打 tag。

## 已知边界

- 大小尺寸是同一折页方向的配套视觉，不承诺不同尺寸逐像素等比一致；
  同一尺寸的 PNG 和 ICO 必须字节一致。
- 标准母版保留设计稿背景，打包资源经过确定性圆角遮罩；不能直接拿原始设计稿作为 ICO。
- 已安装快捷方式的 Windows 图标缓存未刷新；本轮不卸载、不重新安装、不清缓存。
- 旧版品牌 SVG/指南明确标记为历史参考，不再被运行时代码使用。
