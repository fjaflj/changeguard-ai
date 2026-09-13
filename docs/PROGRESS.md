# ChangeGuard AI 项目状态

## 当前能力

- 支持接收发布、配置和依赖升级的自然语言变更描述。
- 支持 Supervisor / Planner / Executor 多 Agent 变更风险分析流程。
- 支持 Prometheus 告警、Mock/CLS 日志和内部知识库取证。
- 支持 TXT/Markdown 文档分片、OpenAI Embedding、Milvus 检索和可选 Cross Encoder 精排。
- 支持普通问答、SSE 流式问答和 SSE 风险报告输出。
- 支持文件上传安全校验、文件保存与向量索引状态分离。

## 分析报告

当前报告包含：

- 变更摘要；
- 影响服务与依赖；
- 监控、日志和知识库证据；
- 风险等级；
- 发布前验证清单；
- 发布后观察指标；
- 回滚条件与建议；
- 无法确认的信息。

## 运行配置

- 对话模型默认使用 `gpt-5.6-terra`，向量模型默认使用 `text-embedding-3-small`。
- API Key 通过 `OPENAI_API_KEY` 提供，支持 `OPENAI_BASE_URL` 兼容网关。
- Rerank 默认关闭，可通过 `RAG_RERANK_ENABLED=true` 开启。
- 默认 Prometheus 和 CLS 使用 Mock，MCP profile 默认关闭。
- OpenAI 向量集合为 `oncall_knowledge_openai`，旧集合保留不动。

## 已验证与限制

- 已有单元测试覆盖文件上传安全、文档分片、Embedding、工具配置、Milvus 配置和 Rerank 排序逻辑。
- 真实 OpenAI、Prometheus、CLS、MCP 和 Rerank 端到端链路需要在对应环境中验证。
- 当前只分析自然语言变更描述，不解析真实 Git Diff。
- 当前不连接发布系统，不执行发布、回滚、重启、扩容或配置修改。
- 会话保存在单 JVM 内存，服务重启后清空。
- AIOps 报告是编排完成后的分块 SSE 输出，不是内部 Agent 步骤实时轨迹。

## 后续规划

- 接入真实 Git Diff、配置中心和服务依赖数据；
- 增加变更单、分析结果和证据链持久化；
- 增加人工审批、权限控制、审计和回滚保护；
- 将会话迁移到 Redis 并增加 TTL；
- 增加 RAG/Rerank 检索质量和风险报告可靠性评测；
- 增加请求限流、任务队列、取消和统一可观测性。
