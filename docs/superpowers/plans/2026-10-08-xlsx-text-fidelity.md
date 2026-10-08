# XLSX 文本导出保真

2026-10-08，承接维护者“继续推进产品”。本轮基线 main/远端均为 365dba304a5e198e72ae7633d88a1b5258d058fa，范围内干净，v3.2.9 未变化。GPT-6.1-sol 在复用的独立 worktree / codex/xlsx-text-fidelity-20261008 开发，root 独立审核和集成。

## 目标与限定范围

检查并修复 XLSX 表头及文本单元格的序列化失真。源码当前静默删除部分控制字符、直接写入 CR 和不能直接出现在 XML 的字符，且没有保护形似 OOXML 转义的原文。先用真实 writer 和安全发布路径生成可重复合成证据，再实施最小修复。普通 XML 导出已在上轮完成，不重复修改。

XLSX 文本采用 OOXML ST_Xstring，不能直接套用普通 XML 的字符拒绝规则。参考 [Microsoft 的 ST_Xstring 兼容说明](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/bd0aa042-434a-4ca7-b25f-4e1fd25a954d) 与 [ECMA-376](https://ecma-international.org/publications-and-standards/standards/ecma-376/)：可通过格式转义保存的内容应原样回读；原文中的转义外观须保护；不能静默用问号替换非法 UTF-16。具体边界在复现后的审核检查点固定。

保持 styled/un-styled 两种 writer 的文本行为一致，保留列宽、冻结表头、数值/布尔/NULL、行列顺序、分页与查询结果范围、显示值确认、安全发布及取消关闭契约。不在本轮扩大数值精度、超长单元格策略或其他导出格式，也不改变数据库读取或事务逻辑。

## 实施与验收

1. 独占合成 profile/temp 诊断并保留原文件、命令和退出结果。新回归须在旧产品上实际失败，固定期望和独立安全 XML 解析共同验证，不以生产编码器自证。
2. root 审查复现及契约后，开发最小修复。覆盖 C0、CR/CRLF/tab/LF、字面转义序列及相邻序列、Unicode/代理项边界、表头和单元格、真实安全发布的保留/清理/重试。用现有捆绑 openpyxl 的解码工具对合成 XLSX 原 XML 文本做独立回读；不新增运行时依赖，不将该证据称为原生 Excel 验收。
3. 审查后依次执行分支定向、clean 全量、强制 buildSrc:test、强制 jpackageImage 及镜像隔离/linked runtime 探针，统计实际任务和新 XML；失败与跳过分别记录。唯一 Gradle 执行者按检查点交接。
4. 本地提交、合并 main 后新复验并更新 README、交接、路线及待验项。按既有授权推送 main，核对精确 SHA CI、远端 main 与原 tag；不自动新 tag 或发布。

每检查点记录目标、改动、验证、失败/未验、下一步。上一轮 CI 的首次超时和单次重跑成功原件共 29 文件及 manifest 已按 SHA 归档到本轮 coordination/baseline-ci-365dba30，首次超时根因仍未知，旧证据不算本轮通过。

## 边界

.testagent 禁读禁改禁枚举；不读取原有凭据、连接、配置、SQL 历史或业务文件。只 mock/合成数据、独占 UUID 临时目录与实际 8.3 临时别名；无真库、原生输入、安装更新、fetch/force/PR、外部联系。本轮不修改既有 Oracle 专用表，不更改 PAUSED 跟进。

原生 Excel/桌面、OS 缩放多屏、真实数据库和慢/网络磁盘、安装升级回退/生产签名及完整 M8 继续单列待验。本轮结束即交付，不自动启动下一轮。

## 本地交付结果

最小产品改动仅 XlsxWriter，新增 142 项测试。源码 ce2a096a、开发证据 5f176b0b、main 集成 8fb039c；分支与 main 各实际定向 286/286、clean 全量 4303 通过/3 live 跳过、强制 buildSrc 8/8、强制 jpackageImage 通过。main 新镜像 183 文件隔离审计通过，linked runtime 实际生成 20 个 XLSX 包、40 个文本单元格经独立 openpyxl 解码一致，6 个孤立代理项明确拒绝；旧 XML 七组仍通过，三产物长度/SHA 与分支一致。详见[独立审核](../verification/2026-10-08-xlsx-text-fidelity-review.md)。

查询结果的失败保护使用真实 SafePublisher 验证；整表 TableExporter 仍直接写目标，本轮不声称它具有相同失败保护。首红 133 项失败、诊断编译/审计参数错误和三项跳过均保留身份。前轮 CI 助手超时原因仍未知。本轮最终 main 推送和精确 SHA CI 由 `evidence/xlsx-text-fidelity-20261008-coordination/delivery-intent.json` 指向的 `build/owned-ci-6bae6012662948499f1d674966f539c3` 回执记录；提交前尚未执行，不预报通过。
