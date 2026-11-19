# MembraneAgent-Java · 膜结构资料平台

这是同一业务的后续个人 Java 实现，主要实现**资料查询与批量处理**。Python 原版、前端和模型训练材料位于原项目，Java 版可独立运行。这里的新增代码、合成数据和验证结果，不代表实习期间已采用的技术栈或取得的指标。

Java 已改用 **Spring Boot 3.5.16、MyBatis-Plus 3.5.16、PostgreSQL、Redis、RabbitMQ**。Java 不再使用 Temporal；原 JdbcTemplate 业务读写改由 MyBatis 执行。Spring 事务与 DataSource 基础设施正常保留。

## 业务流程

业务人员上传设计资料、历史案例或材料规格，获得绑定原件摘要和版次的文档 ID，然后选择文档发起处理批次。

~~~mermaid
flowchart LR
  A[上传资料并记录版次] --> B[批量提交接口]
  B --> C[同一事务保存批次、任务和待投递记录]
  C --> D[返回批次编号]
  C --> E[Outbox 发布器]
  E --> F[RabbitMQ]
  F --> G[消费者认领并解析]
  G --> H[校验认领并事务写入片段]
  H --> I[可检索资料与原文位置]
  D --> J[查询进度和失败原因]
  I --> K[查询接口 / Agent / AI 辅助审查]
~~~

默认消费者在 Java 内解析 TXT、Markdown、DOCX 和带文本层的 PDF；开启模型服务后，向量/模型请求通过有超时的 HTTP 调用。没有接通 Python 解析服务、MinerU 或扫描件 OCR。AI 入口复用业务接口与项目权限，核心改动集中在 Java 任务、数据与消息链路。

| 责任 | 主要代码 | 实现 |
| --- | --- | --- |
| 提交、进度与重试 | BatchController、BatchService、TaskService | 每批 1–20 个文档，请求幂等，按文件返回状态与错误 |
| 事务与消息 | OutboxService、OutboxPublisher | 任务/本地消息表一起提交，发布确认、路由检查、失败补投 |
| 消费与恢复 | RabbitJobWorker、JobProcessor | 手动确认、并发/预取、有限重试、租约续期与过期恢复 |
| 查询与缓存 | DocumentMapper、CatalogService、SpecificationCache | MP 分页、批量关联查询、项目/型号/版次缓存、提交后失效 |
| 数据结构 | V4__batch_outbox.sql、V5__execution_deadline.sql | 批次、文件关联、Outbox、租约及单次执行截止时间 |

## 可靠性与一致性

- **提交事务**：批次、文件任务与待投递记录一起提交。任何文件校验或创建失败则整批回滚。相同 request_id 和文件集合返回同一批次，变更文件集合返回 409。
- **发送确认**：事务外发布持久消息，broker 正向确认且没有返回“无法路由”才标记 sent。失败保留补投，不消耗文件处理的重试次数。send_token 防止迟到确认覆盖消费者刚保存的重试意图。
- **消费幂等**：默认 2 个消费者、每个预取 2 条。活动认领和终态消息直接确认；正常消息在结果或重试意图持久化后确认。数据库失败不能提前 ack；短暂退避后显式 nack/requeue，避免手动确认模式下消息一直悬挂。
- **有限重试**：默认最多 3 次处理，间隔 5、10 秒；输入等不可重试错误直接失败。失败重试接口增加 generation；旧消息无法认领新一轮任务。
- **恢复与取消**：认领租约默认 60 秒，每 20 秒续期；续期不超过该次执行的截止时间。租约过期且仍在期限内可以重新处理；超过期限则标记失败。最终写入事务在保存结果前、发布状态前检查认领资格。取消或重新认领后，旧执行不能发布结果。
- **版本归属**：文档 ID 绑定原件摘要及业务版次；不同版分别存储。旧版结果只属于旧版 ID，未实现跨文件“当前版本”指针。
- **规格缓存**：按项目、型号、版次缓存，命中前检查权限。索引完成后在事务提交回调中失效；回滚不删除；默认 TTL 120 秒。Redis 失败回源并标记 unavailable。并发回填/删除失败可能短暂读旧值，因此是最终一致；审查从数据库取证。
- **等待与超时**：提交接口不等待解析。HTTP 默认连接 5 秒、整次请求 30 秒（包括等待响应体）；读取超过 2,000,000 字节则取消接收。每次认领默认有 600 秒期限，不包含排队及前一次处理的时间。取消不能保证下游已停止计算。

RabbitMQ 使用“至少一次投递＋幂等写入”，没有宣称跨数据库和消息队列的 exactly-once。当前验收为单机隔离容器，未部署 RabbitMQ 集群高可用。

## 本地运行

需要 Java 17、Maven、Docker 和 PowerShell 7。从本目录执行：

~~~powershell
docker compose -p membrane-java-check -f compose.test.yml up -d
mvn clean verify
java -jar target/membrane-business-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local-rabbit
~~~

local-rabbit 明确连接隔离容器：PostgreSQL 55438、Redis 56388、RabbitMQ 56738。应用只监听 127.0.0.1:18081，使用 java_demo schema、membrane.java.demo.v1 队列和 runtime/rabbit-demo-objects。凭据仅供合成演示，不连接 Python 业务表。

演示账号：admin、employee、business、designer、reviewer；默认密码 local-demo-change-me。模型模式 rules、向量关闭，响应标记实际模式。Java 不启动或加载 GPU 模型。

另一终端执行：

~~~powershell
pwsh -NoProfile -File scripts/smoke.ps1
~~~

脚本使用 SYNTHETIC 资料验证：上传 → 批量提交 → 轮询完成 → 分页/规格缓存 → 检索 → 规则审查 → 原有复核/报告导出。输出批次编号、缓存状态及 Word 路径。

非演示使用 postgres profile，配置独立 PostgreSQL、RabbitMQ、Redis、MinIO、JWT 和首次管理员凭据。配置项见 application.yml/application-postgres.yml。非演示不允许 inline 或目录存储。旧 Java 运行库中 dispatch_mode=legacy 的未完成任务**不自动迁移**；本次使用新隔离库。

## 新接口

除登录外需要 Bearer Token，项目权限由后端核验。

| 接口 | 参数与用途 |
| --- | --- |
| POST /api/v1/documents/upload | multipart：file、project_id、version、doc_type、material_model |
| POST /api/v1/ingest-batches | JSON：project_id、document_ids、request_id；202 返回批次与文件任务 |
| GET /api/v1/ingest-batches/{id} | total、finished、counts、items；包含处理错误和投递状态 |
| GET /api/v1/tasks/{id} | 状态、generation、attempt、last_error、delivery；隐藏认领令牌 |
| POST /api/v1/tasks/{id}/retry | JSON：expected_generation；只重试失败的 Rabbit 任务 |
| POST /api/v1/tasks/{id}/cancel | 取消未终止任务并使认领失效 |
| GET /api/v1/documents/page | project_id、material_model、version、doc_type、page、size |
| GET /api/v1/specifications | project_id、material_model、version；返回原文依据与 cache 状态 |

分页最大 100 条，固定 created_at DESC, id DESC 排序；关联任务与切片数量通过两次批量查询获取。旧 `/documents` 仍返回数组，默认仅第一页 20 条；旧客户端若依赖全量列表，须按 `X-Total-Count`、`X-Page`、`X-Page-Size` 翻页，或改用 `/documents/page`。

~~~json
{
  "project_id": "demo-project",
  "document_ids": ["上传返回的文档ID-1", "上传返回的文档ID-2"],
  "request_id": "batch-request-0001"
}
~~~

| 配置项 | 默认 |
| --- | --- |
| JOB_CONCURRENCY / JOB_PREFETCH | 2 / 2 |
| JOB_MAX_ATTEMPTS / JOB_RETRY_SECONDS | 3 / 5 |
| JOB_LEASE_SECONDS / JOB_MAX_RUN_SECONDS | 60 / 600 |
| HTTP_CONNECT_TIMEOUT_SECONDS / HTTP_REQUEST_TIMEOUT_SECONDS | 5 / 30 |
| SPEC_CACHE_ENABLED / SPEC_CACHE_TTL_SECONDS | false / 120；postgres、local-rabbit 开启 |
| RABBIT_HOST / RABBIT_PORT / RABBIT_USER / RABBIT_PASSWORD | 默认本地 broker；正式环境显式配置 |

Redis 接口限流是原有独立能力，故障时拒绝请求；规格缓存故障回源，两者策略不同。

## 保留能力

原 Java 身份/项目权限、上传、检索、Agent 工具、审查、复核及报告导出保留。身份通过 Spring Security/JWT 校验；证据和审批状态保存在 PostgreSQL。

可选 RAG 使用 SQL 词项计分、pgvector、RRF 和 HTTP Reranker；词项计分不是 BM25。基础解析为 800 字符/100 重叠，最多 500 片段，扫描 PDF 返回 OCR 未配置。规格缓存不替代审查证据。

模型配置：MODEL_MODE、MODEL_BASE_URL、MODEL_API_KEY、MODEL_NAME、QUERY_ADAPTER、REVIEW_ADAPTER。Adapter 由外部 vLLM 注册，Java 按别名调用；Java 不训练权重。端点经确认本地处理后才设置 MODEL_LOCAL=true。Embedding/Reranker 使用独立端点和配置。

## 验证与复现

~~~powershell
docker compose -p membrane-java-check -f compose.test.yml up -d
$env:JAVA_INFRA_TESTS='true'
mvn clean verify
Get-Content -Raw scripts/explain-catalog.sql | docker compose -p membrane-java-check -f compose.test.yml exec -T postgres psql -U java_check -d membrane_java
~~~

未设置 JAVA_INFRA_TESTS 时基础设施测试明确跳过。开启后验证事务回滚、并发提交、重复投递、路由返回、有限重试、租约过期、取消、HTTP 慢响应、Redis TTL/事务失效及原业务流程。新集成测试使用独立 rabbit_check schema 和 membrane.java.integration 队列。

SQL 脚本仅在临时表生成 5 万条合成数据对比索引，最后回滚。2026-11-18 最新 Java 构建完整验证 71 项通过，零失败、零跳过。项目投递窗口限制提前发给 RabbitMQ 的数量；新增两项入库上限限制数据库内未结束的文件任务。三个开关默认均为 0。最新短时高峰对照见[高峰接单验收](ACCEPTANCE.md#高峰接单验收)及[验收记录](validation.json)的 `peak_admission`；投递窗口、强杀恢复等早期演练按各自构建保留。

演练使用单机隔离中间件、本地目录存储和可控的 HTTP 模型替身；尚未验收真实 GPU、MinIO、旧 Vue 前端、生产负载、集群高可用和整机灾难恢复。

结束演示后可停止本项目隔离容器：

~~~powershell
docker compose -p membrane-java-check -f compose.test.yml down
~~~

## 对应面试追问

1. 业务表已提交、RabbitMQ 未收到消息如何补偿？confirm 为什么还要配合 returned？
2. ack 放在哪里？结果已提交但 ack 失败怎么办？幂等为什么还需要 generation、claim_token 和租约？
3. R1 与 R2 同时处理时结果归谁？取消为什么不等于远程计算停止？
4. MP 分页有哪些 SQL？如何消除 N+1？联合索引如何对应过滤和排序？
5. 为什么事务提交后删 Redis？回滚、删除失败、并发回填时会发生什么？

以上可结合源码和测试讲解；历史实习贡献、团队分工、训练规模与公司指标单独核实。
