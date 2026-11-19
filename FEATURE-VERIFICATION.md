# 资料批处理与查询：实现及验证

此文档对应后续个人 Java 实现，用于复现和讲解；实习贡献、线上配置和生产性能不由本地代码或合成测试反推。

| 工作内容 | 当前实现 | 验证入口 |
| --- | --- | --- |
| 批量提交、单文件进度与失败重试 | `BatchService.submit/view/retryFile`、`BatchController`；任务按文档保存，批次只组织文件关联。新增批次内指定文件重试接口。 | `BatchOutboxTest.batchRetryHttpOnlyRestartsTheFailedFileAndRejectsStaleExecution`：一成功一失败，重试后仍为原批次、仅失败文件进入新一轮。 |
| 同一事务保存任务与待投递记录 | `TaskService.index`、`OutboxService.created` 使用同一 Spring 事务；事务外由 `OutboxPublisher` 发布。 | `postgresConcurrentBatchAndRollbackKeepOutboxAtomic`：四个并发提交复用一批次；中途文件校验失败整批回滚，未留下消息记录。 |
| 发布确认、失败补投 | `OutboxPublisher.publishOne` 同时检查 confirm 与无法路由的 returned 消息；失败恢复 pending，下一次调度补投。 | `unroutableMessageRemainsPendingEvenWhenBrokerAcknowledgesPublish`：真实 broker 无绑定时保留待投递，修正路由后成功。 |
| 消费并发、有限重试与旧执行隔离 | `RabbitConfiguration` 限制消费者数量及预取；`ModelGateway` 有 HTTP 请求期限；`OutboxService` 维护代数、认领与租约，`JobProcessor` 最终事务检查认领后写结果。 | `ModelBoundaryTest`、`finiteRetriesAndManualRetryRejectOldGeneration`、`postgresRecoveryRequeuesExpiredOwnerAndFencesItsLateResult`，以及新增加的批次并发重试测试。 |
| 按当前页批量获取关联数据 | `CatalogService.page` 先分页，再用 `DocumentMapper.taskStates/chunkCounts` 分别批量查询任务状态、片段数量。 | `pagingLoadsAssociatedStateAndCountsAndKeepsProjectScope` 检查关联结果及权限；`scripts/explain-catalog.sql` 用 5 万条合成数据对比索引执行计划。 |
| 项目、型号、版次规格缓存 | `SpecificationCache` 对三元组生成键并设置 TTL；规格资料入库成功后在事务提交回调中失效。查询权限校验先于缓存命中。 | `redisCacheHonorsScopeExpiryCommitAndRollback` 使用真实 Redis 检查空结果失效、命中、项目隔离、回滚不删除、提交删除和到期回源。 |

## 需要讲清的边界

- 幂等写入防止重复结果发布；故障恢复仍可能再次解析文件，不能承诺解析只执行一次。
- `business_version` 是资料版次，`generation` 是失败任务人工重试后的执行代数；两者用途不同。当前无跨文档的“最新版本”指针。
- 新批次内重试入口复用已有按任务重试逻辑及准入门限；文件任务可被多个批次引用，重试状态会在这些批次中同步反映。
- 同一个重试请求可幂等重放；这不允许成功文件发起新一轮执行，也不允许用旧代数持续重试新一轮失败。
- Redis 提交后删除与 TTL 提供最终一致性；并发回填或删除失败可能短暂读旧值。审查依据从数据库读取。
- 文件解析由 Java 后台消费者完成；向量化等下游调用可走 HTTP。没有接通独立 Python 解析服务或扫描件 OCR。
- 74 项回归覆盖合成数据的功能与集成行为；持续压测、真实 GPU 和浏览器联调仍按 `PRODUCTION-PLAN.md` 执行。
