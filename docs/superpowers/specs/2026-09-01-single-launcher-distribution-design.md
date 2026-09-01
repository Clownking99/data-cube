# DataCube 单启动器发布设计

## 背景

Windows 免安装包当前同时包含 `DataCube.exe` 与 `DataCubeCli.exe`。后者是交互式 Oracle→PostgreSQL 迁移入口，但未形成可脚本化的 CLI 产品，并且其迁移能力已由 GUI 提供。额外的可执行文件会造成用户误解和重复维护。

## 决策

- Windows 发布包只生成并展示 `DataCube.exe`。
- 删除 jpackage 的 `DataCubeCli` secondary launcher 配置。
- 保留 `com.datacube.DataCube` 及底层迁移代码，避免在本次变更中删除仍可复用的实现。
- README 以 GUI 为唯一发布入口，并将迁移说明改为 GUI 工作流；不再承诺 `DataCubeCli.exe`。

## 验收标准

1. 构建配置不再声明 `secondaryLauncher` 或 `DataCubeCli`。
2. README 不再指导用户运行 `DataCubeCli.exe`。
3. `jpackageImage` 成功，生成的 app-image 包含 `DataCube.exe`，且不包含 `DataCubeCli.exe` 或对应配置。
4. 完整自动化测试通过。

## 非目标

- 不删除控制台迁移源码。
- 不重新设计独立的无交互 CLI。
- 不改变 GUI 数据迁移流程或数据库行为。
