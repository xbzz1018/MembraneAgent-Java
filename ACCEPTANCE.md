# Java 资料服务：部署与验收

**本地验收的业务目标：** 设计人员上传同一工程的设计资料和材料规格，得到可查询的文件处理状态。处理完成后，业务人员能按项目、型号、版次查回正确原文，审查报告能定位到资料中的差异。文件处理中断或取消时，不应出现半份可检索结果。

## 在隔离环境复跑

需要 Java 17、Maven、Docker Compose、PowerShell 7、Python 3。Python 只启动验收用的 HTTP 替身并控制进程。所有命令从仓库根目录执行；只使用随项目提供的 `compose.test.yml`，集成测试会清理其测试 schema 的数据。

```powershell
docker compose -p membrane-java-check -f compose.test.yml up -d --wait
$env:JAVA_INFRA_TESTS = 'true'
mvn clean verify
if ($LASTEXITCODE -ne 0) { throw 'Maven 验证失败，请先检查 target/surefire-reports' }
python scripts/verify-process-recovery.py
if ($LASTEXITCODE -ne 0) { throw '打包应用验收失败，请检查对应 recovery 目录' }
```

`mvn clean` 会清理上次的 `target`。先保留需要对照的证据。故障脚本使用随机端口、独立 schema 和队列，自动启动和停止自己的 Java 进程；结果在 `target/recovery-*/result.json`，同目录有重启前后两份日志。单独演示时可以按 README 启动 `local-rabbit`，执行 `pwsh -NoProfile -File scripts/smoke.ps1`。核对结果后用 `docker compose -p membrane-java-check -f compose.test.yml down` 关闭隔离容器，不用于正式数据库。

## 看业务结果，不只看服务是否启动

| 场景 | 操作 | 通过标准 |
| --- | --- | --- |
| 文件入库与查询 | 上传同一版次的两份合成文件，设计值 3500、规格值 4000，单位 N/5cm；批量提交，再查询该型号、版次 | 批次完成且恰好两份；检索返回两份资料的 ID；规格查询只有一份，第二次读命中 Redis；规则审查报告含一项差异，完成复核后可导出 DOCX |
| Worker 突然退出 | 在第 1 次处理等待向量响应时强杀打包进程，使用同一库和队列重启；不改数据库中的租约或任务状态 | 第 2 次领取后任务完成，数据库只有 1 条片段、1 条向量，Outbox 终态为 done |
| 取消与迟到响应 | 等 RabbitMQ 确实观测到未确认消息，再走取消 API，随后放行下游响应 | 待确认消息归零；任务仍为 cancelled，该文件片段数为 0 |
| 下游只发响应头或无限发送数据 | ModelBoundaryTest 用本地 HTTP 服务分别卡住响应体、分块超过 2,000,000 字节 | 客户端在测试配置的时间内退出；超量时在对端结束响应前失败，不读完整包才检查 |
| 写入超期或争抢待投递记录 | BatchOutboxTest 在事务内模拟写完结果后认领到期；RabbitInfrastructureTest 在独立 PostgreSQL 事务中锁住第一条待投递记录 | 超期事务回滚，不发布片段；另一个发布器在锁未释放时可领取第二条记录 |

演练中的向量由本地 HTTP 替身返回，审查差异来自规则；没有借此验证模型质量。进程恢复演练保留了 PostgreSQL、RabbitMQ 和原件，不能代表整机丢失后的恢复。

## 从本地验收到内部试点

1. **校对真实业务结果。** 在独立预发布项目准备一份获准使用并脱敏的设计资料、一份同版规格、一份旧版规格。由资料负责人先标出型号、版次、参数、原文位置和预期差异；再走上传、批次、规格查询、审查和导出。逐项核对是否混用版次、引用位置是否正确。对扫描 PDF 单独测试：当前未配置 OCR，应显示可解释的处理失败。通过后保留输入摘要、文件 ID、批次 ID 和报告。
2. **换上实际依赖。** 使用 `postgres` profile、独立凭据、实际对象存储和获准的模型端点。分别验证原件写入再读回、向量维度相符、跨项目用户不能读取同一份证据、旧页面翻页后能看到第 21 份资料。现有前端、真实 MinIO 与 GPU 模型尚未完成这项联调。
3. **用预期业务量定容量。** 由业务方给出高峰批次数、文件大小和可接受等待时间。按这个负载逐步增加并行提交，同时保留在线规格查询。记录提交接口和查询接口的 P95、最老待处理任务年龄、未确认消息数及数据库连接等待；停压后检查队列是否清空、成功文件是否都能按版次查到。不要直接用单次恢复耗时确定服务承诺。
4. **验证可恢复再开放。** 在隔离环境从 PostgreSQL 和原件备份恢复到新实例，对一份历史资料核对文件摘要、可检索片段和导出报告。正式升级到 V5 前先停止接单并排空正在运行的任务：迁移会把旧认领的截止时间设为现有租约期限。多机部署要校时，并监控积压、失败任务和长期未完成的批次；这几项仍是试点准入条件。

本地结果见 [validation.json](validation.json)。试点数据应单独记录，不能替代为本地合成数据或实习线上指标。

下一阶段的故障场景、混合负载和试点开放条件见[生产场景与试点计划](PRODUCTION-PLAN.md)。

## 混合负载与依赖错误

启动同一组隔离容器并完成 Maven 打包后，在项目根目录运行 `python scripts/verify-process-recovery.py --mixed-load`。脚本操作自己新建的 schema、队列、回环 HTTP 替身和 Java 进程；输出为 `target/mixed-*/result.json`。脚本自动停止自己的 Java 和替身进程，容器按上文命令关闭。原有无参数的强杀/恢复验证仍可独立运行。

同一进程内依次执行仅查询、正常导入、慢 Embedding 导入三组。查询用户与提交账号不同；每组约每 100 毫秒发一次读请求，共 48 次。两组导入分别并发提交六个双文件批次；慢组每次向量请求人为延迟 500 毫秒。每次读都核对文档 ID 和版次；每个导入文件对账终态、尝试次数、Outbox、片段和向量行数。另注入一次持续凭据拒绝、一次限流后恢复，按下游实际调用次数验收。

结果表使用客户端成功请求的实测 P95；任一请求失败、串版、未完成或对账不一致均使本轮脚本失败。排空时间为最后一个 202 至轮询观测到全部任务完成；排队文件数为跨批次采样估计，不代表精确瞬时峰值。文件在计时前上传、单文件单切片、规格缓存已预热，两组并不测上传或缓存冷启动性能。实验提高演示限流额度以便测处理能力；没有验证生产限流策略。仅作单机短测，不作容量承诺。

## 项目间争用与丢响应重放

在本页的隔离容器与 Maven 验证完成后，用已安装 `reportlab` 的 Python 执行 `python scripts/verify-process-recovery.py --project-contention`。此模式生成合成的、带文本层的小 PDF，无需外部文档或 GPU。原始结果在 `target/contention-*/result.json`；本次汇总保存在 `validation.json` 的 `project_contention` 字段。

- 固定两个消费者、每个预取两条；先预热再轮换三种场景，每种测三次：项目 B 单独提交单片段 TXT；项目 A 一批提交六份八页 PDF；以及 A 的测试客户端一次只提交一份 PDF，轮询到终态才提交下一份。后两种情况下，B 在 A 的下游调用进行时提交急件。PDF 每页调用一次本地向量替身，注入 200 毫秒等待。顺序提交是客户端实验，不代表服务端已实现项目配额。
- `submit_http_ms` 是客户端提交到收到 202。`task_created_to_claim_ms` 使用同一 Java 主机生成的建单时间与第一次认领时间；验收确认只有一次尝试，本版本以持久化 `deadline_at` 减测试固定执行时限 120 秒还原认领时间。`submit_to_observed_queryable_ms` 是提交到首次实际查回正确原文，每 100 毫秒轮询一次。`bulk_first_submit_to_last_observed_terminal_ms` 从 A 首次提交开始，包括单文件模式下的轮询、再提交及发布调度等待，不含文件上传。中位数取自三次观测，不作为 SLA。
- 对每一份 PDF 核对项目、版次、页码、正文标记及片段/向量行数；对每个文件核对任务首次尝试成功、Outbox 已完成。另用仅监听本机的单请求代理丢弃已受理响应，再用相同请求编号反序提交两份文件，核对仍为一个批次、两个任务及两份结果。同键不同文件集必须返回 409。这覆盖 HTTP 接口，未验证真实浏览器保存请求编号的行为。
- 测试使用单独的项目或带运行编号的查询版次，避免上轮 Redis 缓存影响本轮读数。仅在当轮 PostgreSQL schema 加入观察触发器，保留任务从运行中转为重试或失败时的错误码。`retry_transitions` 只记录已提交的状态变化，不修改任务；正常负载中出现第二次执行仍判失败，失败结果和复跑结果分别保存。

## 高峰接单验收

先完成 V7 迁移，使用 PostgreSQL 默认 READ COMMITTED。全部写入实例需统一配置：`JOB_PROJECT_BACKLOG_LIMIT`、`JOB_TOTAL_BACKLOG_LIMIT` 分别限制单项目和整个平台已受理、未结束的 Rabbit 入库文件数，0 表示关闭对应上限。它们与 `JOB_PROJECT_DISPATCH_WINDOW` 独立；审查任务、原件上传、按字节或页数的占用均不计入该额度。切回 0 无需删除任务或回退 schema。若新上限低于现有积压，在存量自然下降前拒绝新增。

容量在入库事务提交前按短数据库锁串行核对，整批全收或全拒。单项目满时 429/`project_ingest_backlog_full`，总量满时 503/`platform_ingest_backlog_full`；这两类均返回 `Retry-After: 2`（最早建议重试时间，不是完成承诺）。调用方保留原请求号，使用有上限的退避与抖动；一次请求的新增文件数超过单批能容纳的上限则返回 422，应拆批而非原样重试。重复查询原批次、已经运行的自动重试不会额外占用名额。失败文件重新提交会重新申请名额。

在隔离 Compose 和已验证的 JAR 上顺序执行：

```text
python scripts/verify-process-recovery.py --peak baseline
python scripts/verify-process-recovery.py --peak protected
python scripts/verify-process-recovery.py --peak wave-baseline
python scripts/verify-process-recovery.py --peak wave-protected
```

前两组：两个消费者、预取 2、投递窗口 2；三个项目的 20 个单文件请求、8 个写线程、4 个独立读客户端。测试替身暂停向量响应直到取得积压样本。受保护组按项目 6、全局 9 受理；解除暂停后用原请求号重交拒绝项并逐文件核对。后两组：预上传 44 份单片段 TXT，三个项目按 2→12→2 份/秒的目标速率进件（4、36、4 份），模型每份等待 400 毫秒并持续工作，另有两个客户端查正确版次的热缓存规格。分别统计各阶段状态码、数据库采样的未结束数、从最后一次提交响应至观测到已受理批次全部完成的时间。波峰拒绝项保留未受理状态，不计入排空时间。验收对账每份已受理资料的任务次数、Outbox、原文片段与向量；拒绝不生成持久批次或任务。结果见 `validation.json.peak_admission`。本地秒级短测，非生产吞吐、长期公平或延迟承诺。

## 项目投递窗口

同一 JAR、相同合成资料、两个消费者与预取 2。顺序运行以下两条命令（Python 需安装 reportlab），不要并行压测。第一条交替测整批提交、客户端滑动窗口 2（两份中有一份完成就补下一份）；第二条仍一次受理六份文件，仅服务端使用窗口 2。每种方式跑三次，除指定故障文件外，每份文件必须一次成功。两次运行均验证丢响应重放和定向断连：指定 TXT 的首个向量请求读取后关闭连接，再次处理后只能有一条证据、一条向量，模型调用和任务尝试均为两次。

```text
python scripts/verify-process-recovery.py --dispatch-experiment baseline
python scripts/verify-process-recovery.py --dispatch-experiment window2
```

在 PostgreSQL 默认 READ COMMITTED 隔离级别下试用：完成 V6 迁移，所有发布实例统一设置 `JOB_PROJECT_DISPATCH_WINDOW=2` 并重启。`0` 是原调度方式。修改前先停旧发布实例；已发给 broker 的文件不会撤回，新投递按窗口补位。窗口统计同项目 Outbox 的 publishing/sent 记录（并非 GPU 或物理 HTTP 并发上限）。一个短数据库事务预留名额，发布确认和处理均在事务外；发布租约过期可以原位补投，完成、失败退避或取消后腾出名额。回退使用同一版本设 `0`，不删除已受理记录或回退数据库。单机实测不覆盖多机长期锁竞争、消息队列数据丢失、极大历史表或上游持续故障。
