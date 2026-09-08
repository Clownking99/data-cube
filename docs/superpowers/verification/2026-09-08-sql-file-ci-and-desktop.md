# SQL 文件跨平台与桌面验收（2026-09-08）

## CI 失败定位

上一轮 main `8e9f5a2` 的 [Verify #33617474643](https://github.com/Clownking99/data-cube/actions/runs/33617474643)
在 Windows 有 16 个失败、Linux 有 3 个失败；wrapper validation 和 Redis integration 通过。
不能以此前本机通过代替跨平台验证。

- Windows runner 的临时目录含 `RUNNER~1` 形式的 8.3 短路径。
  `RecentSqlFiles.ensureTrustedParent` 把展开后的真实路径与原始短路径做字符串比较，
  导致合法目录被拒绝，最近文件索引没有保存。改为比较两种链接解析方式的真实路径，
  保留逐级目录检查和符号链接拒绝策略。
- SQL store 的路径契约是 canonical path。测试中的路径回调与断言应遵守这一契约，
  不能因 Windows 长短路径表示不同而漏掉故障注入或误判保存失败。
- `findWitness` 同时可能找到临时文件的 owner 和 owner guard 两个硬链接，
  不能依赖 `Files.list` 的枚举顺序。现在先限定 owner 名称，再验证文件身份。
- Linux 提供 file key，guard 被替换后仍可能验证原目标。相关测试允许报告已证实的原目标，
  仍严格检查替换文件不被删除、不被报告为恢复文件。
- Gradle 测试失败日志改为完整断言信息，后续 CI 可直接看到 expected/actual。

## 本机复现

在独立临时目录上用 Windows `Scripting.FileSystemObject.GetFolder(...).ShortPath`
取得真实 8.3 路径，仅为测试进程设置 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<短路径>`。
不修改系统环境变量，不读取真实用户数据库配置。

修复前，`RecentSqlFilesTest` 和 `SqlScriptFileStoreTest` 共 93 个测试中有 12 个失败，
与 Windows CI 中这两个类的失败完全一致；修复后同一短路径环境 93 个全部通过。
扩大到全量后发现 controller 的两处 recent-path 断言也应比较 canonical path，已修正。

Windows CI 中曾有一次 `ConnectionDialogTest` 的 5 秒超时，本机全量未复现；
未改动连接对话框生产代码，也未通过跳过测试掩盖问题。

最终在同一短路径环境执行 `gradlew.bat test jpackageImage "-PappVersion=0.0.0" --no-daemon --console=plain`。
测试 XML 汇总为 1,724 tests、0 failures、0 errors、3 skipped（需要真实服务的既有用例）。
`0.0.0` 仅用于本机桌面验收，利用既有 dev-version 分支跳过启动更新请求；
不修改仓库默认版本或发布 tag。
