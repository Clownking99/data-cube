# DataCube 开源许可证设计

## 目标

为 DataCube 自有源码建立可被 GitHub 和开源项目申请流程识别的明确开源许可，同时避免将第三方组件误表述为由本项目重新授权。

## 决策

- 根目录新增标准 Apache License 2.0 全文，文件名为 `LICENSE`。
- 公开版权标识使用 `Copyright 2026 Clownking99`，与 GitHub 仓库 owner、贡献记录和发布身份保持一致。
- README 新增“开源许可”章节，说明 DataCube 自有源码按 Apache-2.0 授权。
- JavaFX、Oracle/PostgreSQL JDBC、RichTextFX 及其他第三方组件继续遵循各自许可证和分发条款，不因 DataCube 的许可证而改变。
- 不批量修改 Java 源文件头，不改变构建、运行、发布或数据库行为。

## 选择 Apache-2.0 的理由

Apache-2.0 是 OSI 认可的宽松许可证，允许使用、修改和再分发，并提供明确的专利授权及贡献条款。相较 MIT，它更适合可能长期接受第三方贡献的数据库开发工具；相较 GPL-3.0，它不会要求衍生项目采用相同许可证。

## 验证

- `LICENSE` 与 Apache License 2.0 标准文本一致。
- README 的项目许可证和第三方组件边界表述清楚且不冲突。
- `git diff --check` 无格式错误。
- `git status` 仅包含本任务文件和既有未跟踪 `.testagent/`；不读取、修改或暂存 `.testagent/`。
- 许可证属于文档与元数据变更，不运行产品测试；通过 Gradle 的静态项目识别和文件检查确认没有构建输入被修改。
