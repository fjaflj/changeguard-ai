# ChangeGuard AI 项目学习与面试复习全指南

> 适用对象：做过这个项目、但已经基本忘记，希望在 2–4 周内重新达到“能运行、能读代码、能讲架构、能回答追问”的 Java 后端 + AI 应用候选人。

> 文档依据：当前仓库源码、配置、测试和前端实现。凡是 README/简历描述与代码不完全一致的地方，本文以代码为准并显式标注。

---

## 0. 如何使用这份文档

不要从头背到尾。正确方式是沿着三条真实链路学习：

1. **普通聊天链路**：HTTP 请求如何进入 Controller，如何构造 ReactAgent，模型如何调用工具，答案如何返回；
2. **RAG 链路**：文档如何上传、分片、向量化、写入 Milvus，再被召回和精排；
3. **变更风险链路**：Supervisor 如何调度 Planner/Executor，如何根据变更描述收集告警、日志和知识库证据并生成报告。

每学一个模块，都强制回答下面八个问题：

| 问题 | 你需要回答到什么程度 |
| --- | --- |
| 它解决什么问题？ | 说明业务痛点，不只复述类名 |
| 输入是什么？ | HTTP、方法参数、配置、外部数据分别是什么 |
| 输出是什么？ | Java 对象、JSON、SSE、Milvus 记录或 Agent 状态 |
| 它调用谁？ | 明确下游类、模型、数据库或外部 API |
| 谁调用它？ | 明确 Controller、Service、Agent 或 Spring 生命周期 |
| 为什么这样设计？ | 说出收益与代价 |
| 失败时怎么办？ | 当前代码行为与生产建议都要说 |
| 面试官会怎么追问？ | 准备并发、可靠性、性能和替代方案 |

本文使用三种状态标签：

- **[主链路]**：从当前公开接口确实能够到达；
- **[条件能力]**：只有配置满足时可用，例如 Rerank、MCP、真实 Prometheus；
- **[未接入/辅助]**：代码存在，但当前公开接口没有调用，不能说成已上线主功能。

推荐第一次阅读顺序：第 1、2、4、5、6、7、10、13、14、15 章。第二遍再学习细节和面试题。

---

## 1. 项目一句话、业务背景与边界

### 1.1 一句话介绍

ChangeGuard AI 是一个基于 Java 17、Spring Boot、Spring AI、Milvus 和 Spring AI Alibaba Agent Framework 的生产变更风险分析平台：它接收发布、配置或依赖升级描述，检索内部知识库、查询 Prometheus 告警和 Mock/MCP 日志，并通过多 Agent 编排生成带证据的风险决策建议。

### 1.2 解决的业务问题

发布风险判断通常需要人工在多个系统之间切换：

- 去 Prometheus 看当前告警；
- 去日志平台搜索错误、慢请求、OOM、Pod 重启；
- 去内部 Wiki 或 Runbook 查处理步骤；
- 把零散证据拼成根因判断和操作建议。

本项目把这些数据源包装成 Agent 工具，并把运维文档做成 RAG 知识库。模型不再只依靠参数中的通用知识，而是可以按问题主动获取当前证据和内部知识。

### 1.3 当前明确边界

当前系统定位为**诊断辅助**，不是自动执行平台：

- 不执行重启、扩容、回滚、删库等高风险动作；
- 会话保存在单 JVM 内存，重启即丢失；
- 默认 Prometheus 和 CLS 使用 Mock；
- Prometheus 有真实 HTTP 查询代码，但需要关闭 Mock 并提供真实地址；
- CLS 的 Java `QueryLogsTools` 只在 Mock 模式注册，真实日志能力依赖 MCP profile；
- 变更风险报告会分块通过 SSE 发送，但内部 Planner/Executor 步骤不是实时展示；
- 现有测试主要是单元/配置测试，不证明所有外部依赖端到端可用。

面试时宁可主动说清边界，也不要把演示能力包装成生产能力。

---

## 2. 项目结构与整体架构

### 2.1 目录职责

```text
src/main/java/org/example
├─ Main.java                    Spring Boot 入口
├─ controller                   HTTP、SSE、上传、健康检查
├─ service                      Agent、RAG、索引、检索、精排
├─ agent/tool                   暴露给模型的方法工具
├─ config                       Spring Bean 与配置绑定
├─ client                       Milvus 客户端和 Collection 初始化
├─ constant                     Milvus schema 常量
├─ dto                          文档分片、上传结果等数据对象
└─ tool/DropCollection.java     手工删除 Collection 的危险辅助程序

src/main/resources
├─ application.yml              默认配置
├─ application-mcp.yml          MCP profile 配置
└─ static                       单页前端

src/test/java                   JUnit 5 + Mockito 测试
aiops-docs                      示例运维知识文档
vector-database.yml             Milvus 本地依赖编排
```

### 2.2 运行时组件图

```text
┌──────────────┐     HTTP / SSE      ┌──────────────────────┐
│ Browser/app.js│ ──────────────────> │ Spring MVC Controller│
└──────────────┘                      └──────────┬───────────┘
                                                │
                     ┌──────────────────────────┼─────────────────────────┐
                     │                          │                         │
              ┌──────▼──────┐           ┌──────▼──────┐          ┌──────▼──────┐
              │ ChatService │           │AiOpsService │          │VectorIndex  │
              └──────┬──────┘           └──────┬──────┘          │Service      │
                     │                          │                  └──────┬──────┘
              ┌──────▼──────┐         Supervisor/Planner/               │
              │ ReactAgent  │         Executor                           │
              └──────┬──────┘                 │                         │
                     └──────────────┬──────────┘                         │
                                    │ tools                              │
           ┌────────────────────────┼──────────────────────┐             │
           │                        │                      │             │
   ┌───────▼──────┐        ┌────────▼───────┐      ┌──────▼─────┐       │
   │InternalDocs  │        │Prometheus Tool │      │CLS/MCP Tool│       │
   │Tool           │        └────────────────┘      └────────────┘       │
   └───────┬──────┘                                                      │
           │                                                             │
   ┌───────▼────────────┐       ┌──────────────┐                         │
   │VectorSearchService │──────>│RerankService │                         │
   └───────┬────────────┘       └──────────────┘                         │
           │                                                             │
           └───────────────────────┬─────────────────────────────────────┘
                                   ▼
                              ┌─────────┐
                              │ Milvus  │
                              └─────────┘
```

### 2.3 三条必须背熟的链路

普通聊天 [主链路]：

```text
POST /api/chat
  -> ChatController.chat
  -> SessionInfo 获取历史
  -> ChatService.buildSystemPrompt
  -> ChatService.createReactAgent
  -> ReactAgent.call
  -> 可选调用 Java/MCP 工具
  -> 完整答案
  -> SessionInfo.addMessage
  -> JSON 响应
```

RAG 入库与查询 [主链路]：

```text
POST /api/upload
  -> FileUploadController
  -> 保存文件
  -> VectorIndexService
  -> DocumentChunkService
  -> VectorEmbeddingService
  -> Milvus insert

Agent 调用 queryInternalDocs
  -> InternalDocsTools
  -> VectorSearchService
  -> query Embedding
  -> Milvus recall
  -> 可选 RerankService
  -> JSON 工具结果
  -> Agent 生成回答
```

变更风险分析 [主链路]：

```text
POST /api/ai_ops  {"userRequest":"..."}
  -> ChatController.aiOps
  -> AiOpsService.executeAiOpsAnalysis(userRequest)
  -> SupervisorAgent
  -> Planner Agent
  -> Executor Agent 调工具收集证据
  -> Planner 再规划/结束
  -> 从 OverAllState.planner_plan 提取风险报告
  -> Controller 按 50 字符分块发送 SSE
```

---

## 3. 技术栈：每项技术在项目中真正做什么

| 技术 | 当前版本/配置 | 项目中的实际职责 | 面试重点 |
| --- | --- | --- | --- |
| Java | 17 | 后端语言、并发容器、锁、线程池 | 线程安全、异常、资源生命周期 |
| Spring Boot | 3.2.0 | 启动、自动配置、Bean、MVC、静态资源 | Bean 创建顺序、配置注入、分层 |
| Spring AI | 1.1.0 | 自动装配 ChatModel/EmbeddingModel、工具抽象 | 模型与业务解耦、配置和失败传播 |
| Spring AI Alibaba | 1.1.0.0-RC2 | ReactAgent、SupervisorAgent、图状态和流输出 | 单 Agent 与多 Agent 的取舍 |
| OpenAI ChatModel | 配置默认 `gpt-5.6-terra` | 普通聊天、工具选择、AIOps Agent 推理 | 以仓库配置为准，不声称所有账号都可用 |
| OpenAI EmbeddingModel | `text-embedding-3-small` | 文档和查询向量化 | 同模型、同维度、重建索引 |
| Milvus Java SDK | 2.6.10 | Collection、FloatVector、检索、插入、删除 | schema、IVF_FLAT、L2、nlist/nprobe |
| Reranker | 默认 BGE 兼容接口 | 二阶段精排，默认关闭 | 召回与精排、降级策略 |
| Spring MVC SSE | `SseEmitter` | 聊天增量输出和 AIOps 报告分块 | 生命周期、取消、代理缓冲 |
| Reactor | `Flux` | 接收 Agent 流式 NodeOutput | 订阅三回调、背压/取消概念 |
| MCP Client | WebFlux starter | profile 开启后获取外部日志工具 | Java 方法工具与远程工具差异 |
| OkHttp | 传递依赖可用 | 真实 Prometheus `/api/v1/alerts` 查询 | 超时、响应解析、错误 JSON |
| JUnit 5/Mockito | Spring Boot Test | 本地逻辑和配置行为测试 | 单元测试不等于 E2E |
| Docker Compose | `vector-database.yml` | 启动 Milvus 依赖 | 应用与外部依赖启动顺序 |

`pom.xml` 还显式管理 Jackson 2.17.0、Gson 2.10.1、Lombok 1.18.30 和 JSON Schema Generator。代码里同时存在 Spring 提供的 `ObjectMapper` Bean 与工具类自行 `new ObjectMapper()` 的做法，说明序列化配置目前并未完全统一。

---

## 4. 启动过程、配置加载与 Bean 关系

### 4.1 启动入口

[`Main.java`](../../src/main/java/org/example/Main.java) 使用 `@SpringBootApplication`。它组合了组件扫描、自动配置和配置类能力。因为主类位于 `org.example`，其子包中的 Controller、Service、Component、Configuration 都会被扫描。

简化启动过程：

```text
Main.main
  -> SpringApplication.run
  -> 读取 application.yml 和激活的 profile
  -> 扫描 org.example.*
  -> 创建 ConfigurationProperties
  -> Spring AI 自动配置 ChatModel/EmbeddingModel
  -> OpenAiConfig 校验 Key、创建 RestClient.Builder
  -> MilvusConfig 创建 MilvusServiceClient
  -> MilvusClientFactory 连接并检查 Collection
  -> 创建各 Service、Tool、Controller
  -> 启动 9900 端口
```

### 4.2 OpenAI 配置

[`OpenAiConfig.java`](../../src/main/java/org/example/config/OpenAiConfig.java)：

- **解决问题**：启动前发现缺少 API Key，并为 `RestClient` 配置连接/读取超时；
- **输入**：`spring.ai.openai.api-key`、`spring.ai.openai.chat.options.timeout`；
- **输出**：校验结果和 `RestClient.Builder` Bean；
- **调用谁**：不直接调用模型，模型 Bean 由 Spring AI Starter 自动配置；
- **谁调用它**：Spring 容器在启动时执行 `@PostConstruct` 和 `@Bean`；
- **设计原因**：尽早暴露必填配置错误；
- **失败行为**：Key 为空时整个应用启动失败；
- **追问**：为什么启动阶段校验 Key，却不在启动阶段联网校验模型？联网校验会让应用启动强依赖外部服务可用性。

注意：这里的 `RestClient.Builder` 也被 `RerankService` 使用，因此配置中的 chat timeout 实际同时影响精排 HTTP 请求，职责命名并不完全准确。

### 4.3 配置来源与默认值

核心环境变量：

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `OPENAI_API_KEY` | 空，必填 | Chat/Embedding 调用凭证 |
| `OPENAI_BASE_URL` | `https://api.openai.com` | 兼容网关 |
| `OPENAI_CHAT_MODEL` | 仓库配置值 | Chat 和 Agent 模型 |
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | 向量模型 |
| `RAG_RERANK_ENABLED` | `false` | 是否精排 |
| `RAG_RERANK_API_KEY` | 空 | 精排凭证 |
| `RAG_RECALL_TOP_K` | `12` | 精排开启时初始召回数 |
| `PROMETHEUS_MOCK_ENABLED` | `true` | Prometheus Mock 开关 |
| `CLS_MOCK_ENABLED` | `true` | Java Mock 日志工具开关 |
| `SPRING_PROFILES_ACTIVE` | 无 | `mcp` 时开启 MCP 配置 |
| `TENCENT_MCP_SSE_ENDPOINT` | 空 | 腾讯 CLS MCP endpoint |

默认 `server.address=127.0.0.1`，只监听本机；端口为 9900。上传目录是 `./uploads`，单文件限制 2 MB、请求限制 3 MB。

### 4.4 Milvus Bean 与 Collection 初始化

[`MilvusConfig.java`](../../src/main/java/org/example/config/MilvusConfig.java) 创建单例 `MilvusServiceClient`，并在 `@PreDestroy` 中关闭连接。

[`MilvusClientFactory.java`](../../src/main/java/org/example/client/MilvusClientFactory.java) 执行：

```text
读取 MilvusProperties
  -> ConnectParam(host, port, timeout, optional auth)
  -> hasCollection(oncall_knowledge_openai)
  -> 不存在：创建 schema
  -> 创建 IVF_FLAT / L2 索引，nlist=128
  -> 返回 client
```

Collection schema：

| 字段 | 类型 | 限制/含义 |
| --- | --- | --- |
| `id` | VarChar 主键 | 最大 256 |
| `vector` | FloatVector | 固定 1536 维 |
| `content` | VarChar | 最大 8192 |
| `metadata` | JSON | 动态字段关闭，元数据放在此字段 |

当前不足：配置里有 `milvus.database`，常量里也有 `MILVUS_DB_NAME`，但创建连接时没有显式使用 database；如果目标不是默认库，这个配置目前不会生效。Collection 已存在时也没有校验其 schema 和索引参数是否与当前代码一致。

### 4.5 Web 配置

[`WebConfig.java`](../../src/main/java/org/example/config/WebConfig.java) 增加 UTF-8 String/Jackson 转换器并提供 `ObjectMapper` Bean。

[`WebMvcConfig.java`](../../src/main/java/org/example/config/WebMvcConfig.java) 配置：

- 所有路径允许所有来源跨域；
- 允许 GET/POST/PUT/DELETE/OPTIONS；
- 静态资源从 `classpath:/static/` 暴露。

开发演示方便，但 `allowedOrigins("*")` 不适合直接用于生产，应该按环境配置允许来源，并配合认证、CSRF/Token 策略。

---

## 5. 对外接口总览

| 方法 | 路径 | 输入 | 输出 | 当前真实性 |
| --- | --- | --- | --- | --- |
| POST | `/api/chat` | `Id`、`Question` JSON | 完整 JSON 答案 | [主链路] |
| POST | `/api/chat_stream` | `Id`、`Question` JSON | SSE `content/error/done` | [主链路] |
| POST | `/api/ai_ops` | 可选 `{"userRequest":"变更描述"}` | SSE 风险报告 | [主链路，默认 Mock 数据] |
| POST | `/api/upload` | Multipart `file` | 文件与索引状态 | [主链路] |
| POST | `/api/chat/clear` | `Id` JSON | 清理结果 | [主链路] |
| GET | `/api/chat/session/{sessionId}` | path 参数 | 会话 ID、消息对数、创建时间 | [主链路] |
| GET | `/milvus/health` | 无 | Collection 列表或 503 | [主链路] |

HTTP 语义注意点：`/api/chat` 的空问题和执行异常通常仍返回 HTTP 200，只是在内部 `ChatResponse.success=false`；`clear` 的业务错误也可能是 HTTP 200 + envelope code 500。这会让监控、网关和客户端错误处理复杂，生产上应统一 HTTP 状态与业务错误码约定。

---

## 6. 普通聊天：从请求到最终答案

### 6.1 请求 DTO

`ChatController.ChatRequest` 使用 Jackson：

```text
Id       支持 id / ID 别名
Question 支持 question / QUESTION 别名
```

前端固定发送当前 `sessionId` 和用户消息。若调用方不传 ID，后端会生成 UUID，但当前响应没有把新 ID 返回给调用方，因此无 ID 客户端无法自然延续下一轮；浏览器前端自己总会先生成 ID，所以正常 UI 不受影响。

### 6.2 `ChatController.chat()` [主链路]

简化伪代码：

```java
if (question is blank) return nested business error;
session = sessions.computeIfAbsent(id, SessionInfo::new);
history = session.getHistory();
chatModel = chatService.getChatModel();
prompt = chatService.buildSystemPrompt(history);
agent = chatService.createReactAgent(chatModel, prompt);
answer = chatService.executeChat(agent, question);
session.addMessage(question, answer);
return success(answer);
```

关键设计：Controller 负责 HTTP 与会话编排，Agent 构造细节放在 `ChatService`。不过 Controller 当前仍承担大量 SSE、会话内部类和响应 DTO，文件超过 600 行，后续可拆成会话服务、SSE 适配器和全局异常处理器。

### 6.3 会话实现

```text
ConcurrentHashMap<String, SessionInfo> sessions
  └─ SessionInfo
      ├─ List<Map<String,String>> messageHistory
      ├─ createTime
      └─ ReentrantLock
```

- Map 保证 session ID 到对象映射的并发安全；
- 每个 `SessionInfo` 的锁保护历史列表；
- `getHistory()` 返回副本，避免调用方遍历时被并发修改；
- `addMessage()` 成对追加 user/assistant；
- 最多保留 6 对，超出后从头成对删除。

这只保证数据结构层面的线程安全。两个同 session 请求仍可能同时读取相同旧历史、并行调用模型，最后按完成顺序写回，形成语义乱序。生产上应按 session 串行化、增加版本号/乐观锁，或明确禁止同一会话并发提问。

### 6.4 `ChatService` [主链路]

`ChatService` 注入：

- `InternalDocsTools`：知识库查询；
- `DateTimeTools`：当前时间；
- `QueryMetricsTools`：Prometheus 告警；
- 可选 `QueryLogsTools`：仅 Mock 模式存在；
- 可选 `ToolCallbackProvider`：MCP 工具；
- `ChatModel`：Spring AI 自动配置。

`buildMethodToolsArray()` 根据 `QueryLogsTools` Bean 是否存在，组装 Java 方法工具。`getToolCallbacks()` 在没有 MCP Provider 时返回空数组，避免空指针。

`createReactAgent()`：

```java
ReactAgent.builder()
  .name("intelligent_assistant")
  .model(chatModel)
  .systemPrompt(systemPrompt)
  .methodTools(javaTools)
  .tools(mcpCallbacks)
  .build();
```

Agent 收到问题后，不是 Java 手写 `if/else` 选择工具，而是模型根据系统提示、工具名称、描述和参数 schema 决定调用哪个工具。

代码事实：系统提示中写了“查询天气信息”，但项目没有天气工具。这是提示词与能力不一致，应在面试中作为可改进点，而不能声称系统已经支持天气查询。

`logAvailableTools()` 只打印 MCP callbacks，不会列出 `methodTools`，所以日志中的“可用工具列表”不是所有工具的完整列表。

### 6.5 一次内部文档问答示例

```text
用户：CPU 使用率高应该怎么排查？
  -> ReactAgent 阅读工具定义
  -> 选择 queryInternalDocs(query)
  -> InternalDocsTools 调 VectorSearchService
  -> 返回 Top-K 文档 JSON
  -> JSON 成为 Agent 的工具观察结果
  -> 模型结合证据生成答案
  -> Controller 保存完整问答
```

工具结果是 JSON 字符串，不是直接返回给浏览器。最终文本仍由 Agent 模型组织。

---

## 7. SSE 流式聊天与前端消费

### 7.1 后端 `/api/chat_stream`

[`ChatController.java`](../../src/main/java/org/example/controller/ChatController.java) 声明 `produces=text/event-stream;charset=UTF-8`，创建 5 分钟超时的 `SseEmitter`，再把工作提交给 `Executors.newCachedThreadPool()`。

```text
创建 emitter
  -> 校验问题
  -> executor.execute
  -> 构建 session / prompt / ReactAgent
  -> agent.stream(question) 得到 Flux<NodeOutput>
  -> subscribe(onNext, onError, onComplete)
```

`onNext` 只把 `AGENT_MODEL_STREAMING` 的文本发给前端，同时追加到 `StringBuilder`。模型完成、工具完成、Hook 完成目前只记日志，不作为可视化事件发给浏览器。

SSE 业务消息：

```json
{"type":"content","data":"增量文本"}
{"type":"error","data":"错误说明"}
{"type":"done","data":null}
```

所有 SSE `event` 名都叫 `message`，真实业务类型在 JSON 的 `type` 字段。

正常结束时才把完整答案写入后端会话，然后发送 `done` 并 `complete()`。异常时尝试发送 `error`，再 `completeWithError()`。

### 7.2 前端为什么用 `fetch` 而不是 `EventSource`

[`app.js`](../../src/main/resources/static/app.js) 要通过 POST body 发送 session ID 和问题；原生 `EventSource` 主要用于 GET，难以直接携带 JSON body。因此前端采用：

```javascript
fetch('/api/chat_stream', { method: 'POST', body: JSON.stringify(...) })
  -> response.body.getReader()
  -> TextDecoder
  -> buffer
  -> 按行解析 event:/data:
```

网络 chunk 不等于完整 SSE 事件。一个 JSON 可能跨两次 `reader.read()`，所以必须用 buffer 保存未完成的最后一行。前端收到 `content` 后累加 `fullResponse`、重新渲染 Markdown并滚动到底部；收到 `done` 后保存前端历史；收到 `error` 后显示错误。

### 7.3 当前 SSE 风险

- `newCachedThreadPool()` 无明确上限，高并发可能创建过多线程；
- ExecutorService 没有关闭生命周期；
- 没有注册 emitter timeout/completion 回调用于取消下游 Flux；
- 客户端断开后，模型或工具调用可能继续消耗资源；
- 没有心跳、事件 ID、断线续传；
- 反向代理可能缓冲响应，应用发送不代表用户立刻收到；
- 每个 chunk 都以 INFO 记录，可能造成日志量和敏感数据问题；
- 前端用 `innerHTML` 渲染模型 Markdown，未看到 DOMPurify 等净化库，存在模型输出触发 XSS 的风险。

生产建议：有界线程池、统一总超时、Flux cancellation、心跳、限流、事件 ID、网关禁用 buffering、输出净化和敏感日志治理。

---

## 8. 文档上传、分片、Embedding 与向量入库

### 8.1 `FileUploadController` [主链路]

**解决问题**：接收 TXT/Markdown，安全保存，并同步触发知识库索引。

**输入**：`multipart/form-data` 中名为 `file` 的 `MultipartFile`。

**输出**：成功时返回文件名、路径、大小；参数不合法返回 400；文件 I/O 失败返回 500；文件已保存但索引失败返回 503。

**调用谁**：`FileUploadConfig`、Java NIO、`VectorIndexService.indexSingleFile()`。

**谁调用它**：浏览器上传功能或 `curl -F`。

**为什么这样设计**：文件上传后立即索引，用户操作简单；使用原文件名便于同名覆盖和按来源去重。

**当前失败行为**：

1. 空文件、空文件名、非法后缀直接拒绝；
2. 文件名包含 `/`、反斜杠、冒号时拒绝；
3. 目标路径规范化后不在上传目录，或目标是符号链接时拒绝；
4. 已存在文件先删除再复制；
5. 索引失败时文件仍保留，响应明确为 503。

**追问**：为什么“文件保存成功”不等于“知识库可用”？因为检索依赖分片、Embedding 和 Milvus 写入，任何一步失败都会导致已保存文件无法被正确搜索。

安全校验还不完整：当前主要按文件名后缀判断，没有检测真实内容类型、恶意内容、编码、病毒或用户权限。生产系统还需隔离存储目录、随机内部文件名、内容扫描、配额和鉴权。

### 8.2 `DocumentChunkService` [主链路]

**解决问题**：把长文档切成适合向量检索的语义片段。

**输入**：完整文本和文件路径。

**输出**：`List<DocumentChunk>`，每个元素包含内容、起止位置、全局 chunkIndex 和标题。

**调用谁**：`DocumentChunkConfig(maxSize=800, overlap=100)`。

**谁调用它**：`VectorIndexService.indexSingleFile()`。

**设计步骤**：

```text
空文本 -> 返回空列表
  -> 正则识别 Markdown # 到 ###### 标题
  -> 按章节切分并保留标题
  -> 章节 <= 800：直接一个 chunk
  -> 章节 > 800：按双换行切段落
  -> 累加段落，超限时落一个 chunk
  -> 从上一个 chunk 尾部取最多 100 字符作为 overlap
  -> 尽量在中文句号/问号/感叹号后截断 overlap
```

**为什么按标题、段落优先**：纯固定字符切割可能把症状、原因和操作步骤拆开；结构化边界更可能保持语义完整。

**为什么需要 overlap**：当答案所需信息刚好跨 chunk 边界时，相邻分片共享上下文可以提高召回后信息完整性。

**代价**：overlap 会产生重复文本，增加向量数量、调用费用和检索重复结果。

**代码边界**：如果单个段落本身超过 800，当前算法不会继续按句子或固定长度切这个段落，最终 chunk 可能超过 maxSize。起止索引在 trim、标题包含和 overlap 后也更偏向追踪信息，并非严格可逆的原文字符坐标。这是很好的面试改进点。

### 8.3 `VectorEmbeddingService` [主链路]

**解决问题**：用统一模型把文档片段和查询映射到同一向量空间，并校验 Milvus schema 兼容性。

**输入**：非空字符串，或非空字符串列表。

**输出**：`List<Float>` 或 `List<List<Float>>`，每个向量固定 1536 维。

**调用谁**：Spring AI 自动配置的 `EmbeddingModel`。

**谁调用它**：入库时 `VectorIndexService`；查询时 `VectorSearchService`。

**设计原因**：查询和文档必须使用同一个 EmbeddingModel，否则向量不在同一语义空间；首次真正调用后校验维度，避免启动阶段因网络调用失败。

**失败行为**：空内容抛 `IllegalArgumentException`；空向量、返回数量不一致、维度不是 1536 抛 `IllegalStateException`；其他运行异常包装成“生成 OpenAI 向量失败”。

**重要事实**：当前入库按 chunk 逐个调用 `generateEmbedding()`，批量方法虽然实现并测试，但没有被 `VectorIndexService` 使用。`calculateCosineSimilarity()` 也是辅助方法，当前 Milvus 主检索使用 L2，并不调用它。

### 8.4 `VectorIndexService` [主链路 + 未接入辅助入口]

`indexSingleFile()` 是上传后的真实入口；`indexDirectory()` 虽存在，但没有 Controller 调用，属于未接入的批处理能力。

单文件流程：

```text
验证文件存在
  -> Files.readString
  -> deleteExistingData(_source)
  -> chunkDocument
  -> 对每个 chunk：
       embeddingService.generateEmbedding
       buildMetadata
       insertToMilvus
```

metadata：

```json
{
  "_source": "规范化为正斜杠的文件路径",
  "_extension": ".md",
  "_file_name": "cpu_high_usage.md",
  "chunkIndex": 0,
  "totalChunks": 5,
  "title": "处理建议"
}
```

主键用 `_source + "_" + chunkIndex` 计算 name-based UUID，目标是让同一来源同一序号稳定。

**失败与一致性风险**：旧向量删除失败只记录 warning 并继续，可能产生重复/主键冲突；旧数据先删，新数据逐 chunk 插入，如果中途 Embedding 或插入失败，可能出现“旧索引已删、新索引只有一部分”的非原子状态。当前没有事务、批量写入、临时版本或回滚。

更稳妥的生产方案：先为新版本完整生成并写入临时版本，校验成功后原子切换 active version/alias，再异步清理旧版本。

### 8.5 `MilvusClientFactory` [启动主链路]

**输入**：host、port、timeout、可选用户名密码。

**输出**：已连接且确保目标 Collection 存在的 `MilvusServiceClient`。

**Collection**：`oncall_knowledge_openai`；FloatVector 1536；IVF_FLAT；L2；`nlist=128`；2 shards。

**为什么 IVF_FLAT**：把向量分桶后只搜索部分桶，在准确率和查询速度之间折中。`nlist` 决定聚类/分桶数量，查询 `nprobe=10` 决定搜索多少桶；nprobe 越大通常召回更好但更慢。

**当前不足**：Collection 已存在时不验证 schema 和索引；索引创建使用异步模式，代码没有显式等待索引构建完成；database 配置没有真正传入连接。

---

## 9. 向量召回、Reranker 与直接 RAG

### 9.1 `VectorSearchService` [主链路]

**解决问题**：把自然语言查询转换为向量，从 Milvus 召回候选，并可选精排。

**输入**：query 和最终 `topK`。

**输出**：`List<SearchResult>`，包含 id、content、Milvus score、metadata。

**调用谁**：`VectorEmbeddingService`、`MilvusServiceClient`、`RerankService`。

**谁调用它**：`InternalDocsTools`；独立 `RagService` 也会调用。

查询参数：Collection 名、`vector` 字段、L2、outFields 为 id/content/metadata、`nprobe=10`。

启用 Rerank 时：

```text
最终 topK=3
  -> getRecallTopK 返回 max(3, 12)=12
  -> Milvus 召回 12
  -> Reranker 返回 3
```

Milvus 返回非 0 状态或任何异常时，方法包装成 `RuntimeException("搜索失败: ...")`。工具层再把异常转换为错误 JSON，避免直接让 Agent 调用栈崩溃。

### 9.2 L2 距离

L2 是欧氏距离：

```text
d(x,y) = sqrt(sum((xi - yi)^2))
```

距离越小通常越相似。当前 `SearchResult.score` 来自 Milvus IDScore，在 L2 语义下不要简单说成“分数越大越相关”。代码没有在返回 DTO 中重命名为 distance，面试时要解释这一点。

### 9.3 `RerankService` [条件能力]

**解决问题**：向量检索擅长快速召回，但可能把语义大致相关、实际不能回答问题的文档排在前面；Cross Encoder 联合读取 query 和 candidate，重新判断相关性。

**输入**：query、Milvus candidates、最终 topK。

**输出**：按 `relevance_score` 排序后的原候选对象。

**调用谁**：Spring `RestClient`、兼容 `/v1/rerank` 的外部服务。

**谁调用它**：`VectorSearchService`。

请求体包含 model、query、documents、top_n、return_documents=false。响应结果通过 candidate index 映射回原始对象。

默认降级：

- 未启用：直接取候选前 topK；
- candidates 少于等于 1：无需精排；
- 缺 Key、请求失败、空结果：默认回退 Milvus 前 topK；
- `fail-on-error=true`：抛异常，不回退。

**细节陷阱**：精排只改变结果顺序，没有把 `relevance_score` 写回 `SearchResult.score`；返回对象中的 score 仍是 Milvus 距离/分数。因此不能拿返回 score 解释精排依据，生产上应同时保留 recall distance 与 rerank score。

### 9.4 为什么两阶段而不是只用一种？

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 只用向量检索 | 快、可扩展、成本低 | 精细相关性不足 |
| 全库 Cross Encoder | query-document 判断更精细 | 全库计算成本不可接受 |
| 向量召回 + 精排 | 平衡规模、延迟和相关性 | 多一个服务和失败点 |

### 9.5 `InternalDocsTools` [主链路]

它把向量检索包装成模型工具：接收 query，按 `rag.top-k` 查询，结果序列化为 JSON。无结果时返回 `status=no_results`；异常时返回 `status=error`。

工具描述会参与模型决策，因此它明确说明适用于内部流程、最佳实践和分步指南。描述是 Agent 路由的一部分，不是普通注释。

### 9.6 `RagService` [未接入/辅助]

`RagService.queryStream()` 实现标准直接 RAG：

```text
VectorSearchService
  -> 拼接【参考资料 N】
  -> Prompt 要求有依据、无依据不编造
  -> ChatModel.stream
  -> StreamCallback 输出
```

但仓库中没有 Controller 或其他类引用它。当前 `/api/chat` 的知识库主链路是 ReactAgent → InternalDocsTools → VectorSearchService，而不是 Controller → RagService。面试时必须准确区分“代码存在”和“当前接口已接入”。

---

## 10. Agent 工具与数据源

### 10.1 Tool Calling 的真实机制

模型不能直接执行 Java 或访问 Prometheus。框架把 `@Tool` 方法转换成工具定义，包括工具名、描述和参数 schema。模型生成工具调用意图，框架执行方法，再把工具返回值作为上下文交回模型。

```text
用户问题
  -> 模型看到工具定义
  -> 输出 tool name + arguments
  -> 框架调用 Java/MCP 工具
  -> 工具结果进入模型上下文
  -> 模型继续推理/最终回答
```

工具描述越模糊，误选、漏选和参数错误概率越高。生产工具还必须做鉴权、参数校验、超时、幂等和审计，不能只相信模型传参。

### 10.2 `DateTimeTools` [主链路]

- **问题**：模型参数知识没有可靠“当前时间”；
- **输入**：无；
- **输出**：用户时区下的 ISO 时间字符串；
- **调用谁**：`LocaleContextHolder` 和 Java Time；
- **谁调用**：ReactAgent/AIOps Agent；
- **失败**：基本是本地异常；
- **追问**：时区从哪里来？当前依赖 Spring LocaleContext，实际部署要确认请求时区如何设置。

### 10.3 `QueryMetricsTools` [主链路，Mock/真实可切换]

默认 `prometheus.mock-enabled=true`，返回 HighCPUUsage、HighMemoryUsage、SlowResponse 三条模拟告警。

关闭 Mock 后，它通过 OkHttp 请求：

```text
GET {prometheus.base-url}/api/v1/alerts
```

解析 Prometheus 响应后，按 `alertname` 去重，只保留告警名、描述、状态、activeAt 和持续时间。

**代价**：按 alertname 去重可能隐藏同一告警在多个实例上的影响范围；简化 DTO 也丢弃部分 labels、annotations 和 value。生产诊断通常应保留 instance、namespace、severity 等关键标签。

错误不会直接抛给 Agent，而是返回 `success=false` 的 JSON，便于模型如实说明查询失败。

### 10.4 `QueryLogsTools` [仅 Mock 方法工具]

这个 Bean 有：

```java
@ConditionalOnProperty(name="cls.mock-enabled", havingValue="true")
```

因此正常 Spring 运行中，它只在 Mock 开启时存在。`queryLogs()` 代码里的 `mockEnabled=false` 分支虽然返回“真实查询尚未实现”，但因为 Bean 在 false 时不会注册，正常配置下不会走到这个分支。

Mock 提供四类主题：system-metrics、application-logs、database-slow-query、system-events；limit 默认 20、最大 100；地域预期为 ap-guangzhou 等。

### 10.5 MCP 真实日志 [条件能力]

激活 `mcp` profile 后：

```text
application-mcp.yml
  -> spring.ai.mcp.client.enabled=true
  -> SSE connection 到 Tencent MCP
  -> ToolCallbackProvider 提供远程工具
  -> ChatService.tools(mcpCallbacks)
```

同时 `cls.mock-enabled=false`，Java Mock Bean 不注册。因此真实日志路径是 MCP 回调，不是 `QueryLogsTools` 直接调用 CLS SDK。

### 10.6 Mock、真实和未完成能力表

| 能力 | 默认 | 真实模式 | 不应夸大的点 |
| --- | --- | --- | --- |
| ChatModel | 真实外部调用 | OpenAI/兼容网关 | 需要 Key 和模型权限 |
| Embedding | 真实外部调用 | OpenAI/兼容网关 | 测试使用 Mock 模型 |
| Milvus | 真实本地依赖 | 可接远程 Milvus | 应用启动依赖其可用 |
| Prometheus | Mock | 代码支持真实 `/api/v1/alerts` | 未在单测中做真实 E2E |
| CLS 日志 | Java Mock | MCP profile | Java 类没有真实 CLS 实现 |
| Reranker | 默认关闭 | 外部兼容服务 | Key 与 OpenAI Key 不同 |
| 天气 | 不存在 | 无 | Prompt 提到但没有工具 |

---

## 11. 变更风险分析：Supervisor、Planner、Executor

### 11.1 为什么需要多 Agent

普通 ReactAgent 适合“一次问题中动态选择工具”。变更风险分析是多步闭环：先理解变更描述，再决定查询哪类监控、日志和知识，根据证据调整后续方向，最后形成发布决策建议。

拆分角色：

| 角色 | 核心职责 | outputKey |
| --- | --- | --- |
| Supervisor | 选择调用 Planner、Executor 或结束 | 图状态调度 |
| Planner/Replanner | 拆任务、读反馈、决定 PLAN/EXECUTE/FINISH | `planner_plan` |
| Executor | 只执行 Planner 当前第一步并整理证据 | `executor_feedback` |

### 11.2 `AiOpsService.executeAiOpsAnalysis()` [主链路]

```java
planner = buildPlannerAgent(model, callbacks);
executor = buildExecutorAgent(model, callbacks);
supervisor = SupervisorAgent.builder()
  .subAgents(List.of(planner, executor))
  .build();
return supervisor.invoke(taskPrompt);
```

Planner 和 Executor 都注入与普通聊天相同的 Java 方法工具以及 MCP callbacks。

### 11.3 为什么 Executor 只执行第一步

执行一个步骤后让 Planner 重新看证据，可以动态改变方向。例如先发现 CPU 高，日志却显示数据库超时，下一步应转向慢 SQL，而不是机械执行原计划全部步骤。

优点：适应不确定诊断；缺点：模型调用次数增加、延迟更高、状态和终止更难保证。

### 11.4 输出与报告提取

Planner 在执行阶段被 Prompt 要求输出 JSON；FINISH 时改为直接输出 Markdown 报告。Executor 返回 status、summary、evidence、nextHint。最终 `extractFinalReport()` 只从 `OverAllState` 的 `planner_plan` 取 `AssistantMessage`。

如果框架最终结果没有按预期写入该 key，Controller 会提示没有报告。当前没有强类型 schema 校验，主要依靠 Prompt 约束格式。

### 11.5 重试与防幻觉

Prompt 要求同一工具连续 3 次失败后停止，并禁止编造数据。但 Prompt 是软约束，不等于程序保证。代码没有显式失败计数器、总步骤上限、重复计划检测或独立总预算。

面试标准表达：

> Prompt 负责告诉模型应该怎么做，程序级状态机负责保证最多做多少次、超时多久、哪些状态允许转换。生产系统必须两层都有。

### 11.6 `/api/ai_ops` 的“流式”真相

Controller 先发一条“正在读取告警并拆解任务”，随后同步等待 `executeAiOpsAnalysis()` 完成；拿到最终报告后按 50 个字符切块发送。它提供了渐进式 UI，但不是 Planner/Executor 每一步实时事件流。

`AIOpsRequest.userRequest` 现在由 Controller 接收并传入分析服务。服务会把用户的发布、配置或依赖变更描述放入任务 Prompt；无请求体时使用默认演示变更，以保持旧调用方式兼容。

### 11.7 ChangeGuard 风险报告的诚实边界

报告中的风险等级是基于变更描述和工具证据生成的辅助判断，不是自动化发布审批。当前系统不解析真实 Git Diff，不读取发布系统状态，也不执行发布、回滚、重启、扩容或配置修改。面试时应说明“提出验证清单和回滚条件”与“实际执行变更”是两件事。

---

## 12. 前端实现与前后端状态

### 12.1 页面职责

静态页面由 Spring Boot 直接提供。`app.js` 固定 API 基址为 `http://localhost:9900/api`，负责：

- 生成前端 session ID；
- 发送普通/流式聊天；
- 手动解析 SSE；
- 上传文档；
- 提交变更描述并触发风险分析；
- 用 localStorage 保存浏览器聊天列表；
- 使用 marked 渲染 Markdown、highlight.js 高亮代码。

### 12.2 两套“历史”不要混淆

```text
后端 SessionInfo：
  给模型构建上下文，最多 6 对，重启丢失

前端 localStorage：
  给 UI 展示历史，保存在当前浏览器
```

两者不是同一个存储，也没有完整同步协议。清浏览器数据会丢 UI 历史；重启服务会丢模型上下文，即使页面仍显示旧聊天。

### 12.3 前端风险

- API 地址硬编码，部署到其他主机需要改代码；
- 依赖 CDN 加载 marked/highlight.js，离线可能不可用；
- Markdown 直接写入 `innerHTML`，需做 HTML 净化；
- SSE 解析代码重复且 AIOps 使用正则匹配 JSON，遇到复杂转义内容可能脆弱；
- 没有 AbortController 向后端传播用户取消；
- localStorage 没有容量、敏感数据和多标签页冲突治理。

---

## 13. README/简历描述与当前代码差异清单

| 描述或容易产生的理解 | 当前代码事实 | 面试正确说法 |
| --- | --- | --- |
| “RAG 服务负责普通聊天” | `/api/chat` 未调用 `RagService` | 主链路通过 `InternalDocsTools` 使用检索；`RagService` 是未接入的直接 RAG 实现 |
| “CLS 工具可真实直连” | `QueryLogsTools` 仅 Mock 注册，false 分支不构成正常真实路径 | 真实日志依赖 MCP profile |
| “AIOps 全过程流式” | 编排完成后才分块报告 | 入口提示 + 最终报告分块，内部步骤非实时 |
| “支持天气查询” | System Prompt 提到天气，但没有天气工具 | 当前未实现天气能力 |
| “测试 17 个” | 最近实际 Maven 结果为 18 个 | 以当前 `mvn verify` 为准，文档数字已过时 |
| “所有可用工具会记录日志” | `logAvailableTools()` 只记录 MCP callback | Java method tools 仍可用，但不在该日志列表 |
| “rag.model 控制 RAG 模型” | 配置存在，但代码未读取 | ChatModel 使用 Spring AI 的 openai chat 配置 |
| “milvus.database 可切库” | 属性存在，但连接构造未使用 | 当前实际依赖默认数据库 |
| “目录批量索引已提供 API” | `indexDirectory()` 无 Controller 调用 | 方法存在，未暴露公开入口 |
| “余弦相似度用于检索” | 方法存在但未被调用，Milvus 用 L2 | 当前检索指标是 L2 |

---

## 14. 按真实调用顺序逐文件导读

这一章用于你打开 IDE 跟读。顺序按运行时调用，而不是按目录字母顺序。

### 14.1 启动与配置组

#### `Main.java`

- 入口：`main()`；
- 输入：命令行参数、环境变量、YAML；
- 输出：Spring ApplicationContext 和 Web 服务；
- 下游：整个 Spring Boot 自动配置；
- 追问：`@SpringBootApplication` 组合了哪些能力？为什么主类包位置影响扫描？

#### `application.yml`

- 不是 Java Bean，但决定服务地址、上传限制、模型、Milvus、RAG、Rerank、Prometheus、CLS；
- `${ENV:default}` 表示优先环境变量，否则用默认值；
- 追问：敏感信息为什么只能来自环境变量/Secret，而不能提交 YAML？

#### `application-mcp.yml`

- 只有激活 `mcp` profile 时合并；
- 开启 MCP SSE connection，同时关闭 CLS Mock；
- 追问：profile 配置如何覆盖默认配置？真实环境缺 endpoint 时会怎样？

#### `OpenAiConfig.java`

- 启动阶段校验 Key；
- 创建 `RestClient.Builder`；
- 不负责直接创建 ChatModel/EmbeddingModel；
- 追问：为什么 `@PostConstruct` 抛异常会阻止应用启动？

#### `MilvusProperties.java`

- 用 `@ConfigurationProperties(prefix="milvus")` 把 YAML 映射为类型化属性；
- 比散落的 `@Value` 更适合成组配置；
- database 当前没有真正被客户端工厂使用。

#### `MilvusClientFactory.java`

- 连接、检查 Collection、创建 schema、创建向量索引；
- 失败时关闭已创建 client 并抛出 RuntimeException；
- 追问：为什么启动强依赖 Milvus？如何改为懒加载或健康降级？

#### `MilvusConfig.java`

- 把工厂结果暴露为 `MilvusServiceClient` Bean；
- `@PreDestroy` 关闭连接；
- 追问：Bean 默认 scope 是什么？为什么外部客户端要管理生命周期？

#### `WebConfig.java` 与 `WebMvcConfig.java`

- 前者处理 UTF-8 转换器和 ObjectMapper；
- 后者处理 CORS 与静态资源；
- 追问：为什么生产不应允许任意 Origin？两个 WebMvcConfigurer 能否同时生效？可以，Spring 会组合其配置回调。

### 14.2 HTTP 入口组

#### `ChatController.java`

真实公开入口最多，建议分段读：

1. `chat()`：同步 ReactAgent；
2. `clearChatHistory()`：按 ID 清理；
3. `chatStream()`：Agent Flux → SSE；
4. `aiOps()`：多 Agent + 报告分块；
5. `getSessionInfo()`：会话统计；
6. `SessionInfo`：锁和滑动窗口；
7. 请求/响应/SSE envelope 内部类。

追问重点：Controller 为什么过重？如何拆分？HTTP 200 + 业务失败是否合理？同 session 并发会怎样？

#### `FileUploadController.java`

读取顺序：输入检查 → 后缀检查 → 上传目录 → 文件名/路径检查 → 覆盖文件 → 索引 → 响应。

特别注意：先删除旧文件再 copy，如果 copy 失败，旧文件已经丢失；保存成功后索引失败时文件保留。文件状态和索引状态需要分开建模。

#### `MilvusCheckController.java`

调用 `showCollections()`，成功返回 `message=ok` 和 Collection 名单，失败返回 HTTP 503。它证明客户端能执行一个基础 RPC，但不证明向量写入和检索正常。

### 14.3 RAG 入库组

#### `DocumentChunkConfig.java`

类型化绑定 `document.chunk.max-size` 和 `overlap`，默认 800/100。配置可调不等于已经自动评测出最优值。

#### `DocumentChunk.java`

纯数据对象：content、startIndex、endIndex、chunkIndex、title。面试时解释 metadata 的价值：来源追踪、答案引用、更新删除和调试。

#### `DocumentChunkService.java`

先读 `chunkDocument()` 建立骨架，再读 `splitByHeadings()`、`chunkSection()`、`splitByParagraphs()`、`getOverlapText()`。

伪代码：

```java
sections = splitMarkdownHeadings(content);
for section in sections:
    if section.length <= max: emit(section)
    else:
        for paragraph in splitBlankLines(section):
            if appendWillOverflow: emit(current); current = overlap(current)
            append(paragraph)
```

#### `VectorEmbeddingService.java`

单条/批量 Embedding、空输入检查、维度检查。重点理解为什么不在启动阶段调用 `dimensions()`：有些实现可能联网，导致外部网络短暂故障阻断启动。

#### `VectorIndexService.java`

先读 `indexSingleFile()`；再读删除旧数据、metadata、insert；最后看未接入的 `indexDirectory()`。关注“每个 chunk 单独 Embedding/insert”和“更新非原子”。

### 14.4 RAG 查询组

#### `VectorSearchService.java`

query Embedding → Milvus SearchParam → 解析 RowRecords → Rerank。记住 L2、nprobe=10、outFields 和 topK 语义。

#### `RerankService.java`

先看 `getRecallTopK()`；再看 `rerank()` 请求体；最后看 `rankCandidates()` 与 `handleFailure()`。区分召回 distance 和 relevance score。

#### `InternalDocsTools.java`

这是当前 Agent 使用知识库的桥。它把检索结果序列化为 JSON，错误也返回 JSON，让模型能观察失败。

#### `RagService.java`

标准直接 RAG 示例，但当前没有上游调用。可以学习 Prompt 增强、历史 Message 组装和 ChatModel Flux，不要说成 `/api/chat` 的主实现。

### 14.5 Agent 与工具组

#### `ChatService.java`

系统提示、历史拼接、Java method tools、MCP callbacks、ReactAgent 构造和同步执行。核心追问：为什么每次请求新建 Agent？当前为了注入本轮历史 Prompt；代价是构造开销和状态管理。

#### `DateTimeTools.java`

最简单的 Tool Calling 示例，适合用来解释 `@Tool` 如何把普通 Java 方法暴露给模型。

#### `QueryMetricsTools.java`

Mock 与真实 Prometheus 双路径；OkHttp 超时；JSON 数据类；按 alertname 去重。真实查询不是 PromQL，而是 Prometheus Alerts API。

#### `QueryLogsTools.java`

只在 Mock 开启时注册；提供主题发现和日志查询两个工具；大量内置数据用于演示告警关联。真实 CLS 不在这个类中实现。

#### `AiOpsService.java`

阅读顺序：`executeAiOpsAnalysis()` → Planner/Executor 构造 → 三段 Prompt → `extractFinalReport()`。重点分清框架调度能力和 Prompt 约束。

### 14.6 前端组

#### `index.html`

页面结构，并通过 CDN 加载 marked/highlight.js。外部 CDN 是运行依赖之一。

#### `app.js`

建议只追踪四个函数：普通请求、`sendStreamMessage()`、`uploadFile()`、`sendAIOpsRequest()`。然后再看 localStorage 会话和 Markdown 渲染。

### 14.7 测试与辅助组

#### 测试

- `OpenAiConfigTest`：Key 校验；
- `MilvusConstantsTest`：Collection 与维度；
- `FileUploadControllerTest`：路径攻击、成功、索引失败、后缀/空文件；
- `DocumentChunkServiceTest`：标题元数据与空文档；
- `VectorEmbeddingServiceTest`：单条、批量、维度失败；
- `RerankServiceTest`：分数排序与 Top-K；
- `ToolConfigurationTest`：MCP provider 缺失、CLS 条件 Bean。

#### 未接入/辅助代码

- `AIOpsRequest`：接收自然语言变更描述并传入风险分析任务；
- `VectorIndexService.indexDirectory()`：没有公开调用入口；
- `VectorEmbeddingService.calculateCosineSimilarity()`：主链路不用；
- `RagService`：没有上游引用；
- `DropCollection`：手工危险工具，直连 localhost 删除整个目标 Collection，不属于 Web 功能。

---

## 15. 核心概念白话教程

### 15.1 Spring Boot

它像应用的“装配工厂”：读取配置、扫描注解、创建对象、把依赖注入进去、启动 Web 服务器。Controller 不需要自己 new Service，因为 Spring 容器负责对象生命周期。

### 15.2 IoC 与依赖注入

IoC 表示对象创建权交给容器；依赖注入表示容器把一个对象需要的另一个对象放进去。当前项目大量使用字段 `@Autowired`，能工作但测试和不可变性较弱；生产代码通常更推荐构造器注入。

### 15.3 Spring AI

它把模型调用抽象为 `ChatModel`、`EmbeddingModel` 等接口，让业务不直接绑定某个 HTTP SDK。抽象不代表完全无差异：模型名、工具调用能力、流格式、超时和厂商兼容性仍要验证。

### 15.4 ChatModel

输入是 Message/Prompt，输出是完整或流式 ChatResponse。它负责生成文本和工具调用决策，不负责向量检索本身。

### 15.5 Embedding

Embedding 把文本映射为固定维度向量。语义相近文本通常在向量空间更近。它不是答案，也不是加密；无法从“相近”直接保证“能正确回答”。

### 15.6 RAG

Retrieval-Augmented Generation：先从外部知识库检索证据，再把证据交给生成模型。解决模型参数知识不含内部文档、知识更新慢和需要来源依据的问题。

### 15.7 Chunk

向量检索的最小文档单位。过大导致噪声多，过小导致语义断裂。800/100 是当前默认，不是普适最优值；应通过 Recall@K 和回答质量实验选择。

### 15.8 Milvus

专门管理向量和近似最近邻搜索的数据库。本项目每条记录不是整篇文档，而是一个 chunk，并保留 content/metadata 供检索后还原证据。

### 15.9 向量召回

目标是尽量不漏掉可能相关文档，允许候选中有噪声。它强调 recall，不等于最终排序质量。

### 15.10 L2 距离

两个向量各维差值平方和再开方。越小通常越相似。它与余弦相似度、Rerank relevance score 不同，指标含义不能混用。

### 15.11 IVF_FLAT、nlist、nprobe

IVF 先把向量划分到多个簇；查询时只搜索部分簇。`nlist=128` 是建库分区数量，`nprobe=10` 是查询探测簇数。增大 nprobe 通常提高召回但增加延迟。

### 15.12 Reranker/Cross Encoder

它同时读取 query 与每个候选文档，做更精细的相关性判断。比独立 Embedding 比较更贵，所以放在小规模召回之后。

### 15.13 Tool Calling

模型先输出结构化的“调用哪个工具、参数是什么”，框架执行真实代码，把结果交回模型。模型不能绕过工具权限；工具自身仍要验证参数和身份。

### 15.14 ReactAgent

适合开放式“思考—行动—观察”循环。用户不需要指定工具，模型动态决定。代价是行为不完全确定，需要步骤上限、审计和可观测性。

### 15.15 Supervisor/Planner/Executor

把复杂诊断分成调度、规划和执行角色。更容易表达“根据新证据重规划”，但引入更多模型调用、状态和失败点。

### 15.16 SSE

基于 HTTP 的服务器单向事件流。适合服务端持续推文本。当前因为需要 POST JSON，前端使用 fetch + ReadableStream 手动解析，而不是原生 EventSource。

### 15.17 Mock、单元、集成与端到端

- Mock：用假的依赖返回可控结果；
- 单元测试：隔离验证一个类/方法；
- 集成测试：验证多个真实组件协作；
- 端到端：从 HTTP 到真实模型、数据源和响应全链路。

当前 18 个测试通过只说明被覆盖的本地行为成立，不证明真实云服务链路全部正常。

---

## 16. 故障模型、当前行为与生产改进

| 故障 | 当前表现 | 风险 | 建议 |
| --- | --- | --- | --- |
| 缺 OpenAI Key | 启动失败 | 无服务可用 | 保留快速失败，使用 Secret 管理 |
| 模型无权限/超时 | chat 返回业务错误；SSE error | HTTP 语义不统一 | 错误分类、重试预算、熔断 |
| Milvus 启动失败 | 应用 Bean 创建失败 | 整站无法启动 | 按需求选择 fail-fast 或降级禁用 RAG |
| Collection schema 旧 | 写入/维度失败 | 索引不可用 | schema version、迁移与 alias |
| 上传后 Embedding 失败 | 文件保留，返回 503 | 文件和索引状态分离 | 持久化索引状态、重试队列 |
| 分片中途写失败 | 可能只写入部分新版本 | 检索不一致 | 版本化写入、原子切换 |
| Reranker 失败 | 默认回退 | 相关性下降 | 指标告警、明确 degraded 状态 |
| Prometheus 不可用 | 工具返回 success=false JSON | Agent 可能继续诊断 | 证据状态、熔断和报告标记 |
| MCP 不可用 | 无远程工具/调用失败 | 无真实日志证据 | readiness、工具目录校验 |
| SSE 客户端断开 | send 失败，下游未必取消 | 资源浪费 | completion/timeout + cancel |
| 同 session 并发 | 历史可能语义乱序 | 上下文不一致 | session 级串行化/版本控制 |
| 服务重启 | 后端会话丢失 | 多轮对话断裂 | Redis + TTL |
| 恶意 Markdown | 前端 innerHTML 渲染 | XSS | sanitize HTML、CSP |
| 高并发流请求 | cached thread pool 膨胀 | 内存/线程耗尽 | 有界池、队列、限流 |

生产化优先级建议：

1. 认证授权、Secret、CORS、限流和审计；
2. 有界并发、总超时、取消、熔断和错误规范；
3. Redis 会话与 session 级并发控制；
4. 文档版本、异步索引任务、原子切换；
5. RAG/工具/报告评测体系；
6. 全链路 tracing、成本和质量指标；
7. 审批、幂等、回滚完备后再考虑自动执行动作。

---

## 17. 动手实验：把“看懂”变成“真的会”

### 17.1 环境检查

```powershell
java -version
mvn -version
docker version
```

目标版本：Java 17、Maven 3.9+、可用的 Docker Compose。API Key 只设置到当前终端：

```powershell
$env:OPENAI_API_KEY = "你的 Key"
```

如使用兼容网关：

```powershell
$env:OPENAI_BASE_URL = "https://你的网关"
```

不要把真实 Key 写入 YAML、命令历史截图或学习笔记。

### 17.2 启动 Milvus

```powershell
docker compose -f vector-database.yml up -d
docker compose -f vector-database.yml ps
```

观察 etcd、对象存储和 Milvus 相关容器是否正常。理解：Milvus 不是应用内部数据库，它是独立外部依赖。

### 17.3 启动应用

```powershell
mvn spring-boot:run
```

按顺序观察日志：

1. OpenAI Key 校验；
2. Milvus 连接；
3. Collection 已存在或创建；
4. 向量索引创建；
5. QueryMetricsTools/QueryLogsTools Mock 状态；
6. 9900 端口启动。

### 17.4 健康检查

```powershell
Invoke-RestMethod http://localhost:9900/milvus/health
```

预期：返回 `message=ok` 和 Collection 列表。复盘：这只验证 showCollections，不验证 Embedding、insert、search 或 ChatModel。

### 17.5 上传知识库

```powershell
curl.exe -X POST http://localhost:9900/api/upload `
  -F "file=@aiops-docs/cpu_high_usage.md"
```

在 IDE 依次设置断点：

```text
FileUploadController.upload
VectorIndexService.indexSingleFile
DocumentChunkService.chunkDocument
VectorEmbeddingService.generateEmbedding
VectorIndexService.insertToMilvus
```

记录每个 chunk 的 title、长度、index、Embedding 维度和 metadata。

### 17.6 普通聊天

```powershell
$studyBody = '{"Id":"study-1","Question":"如何排查 CPU 使用率过高？"}'
Invoke-RestMethod http://localhost:9900/api/chat `
  -Method Post `
  -ContentType "application/json" `
  -Body $studyBody
```

断点：`ChatController.chat` → `ChatService.buildSystemPrompt` → `createReactAgent` → `InternalDocsTools.queryInternalDocs` → `VectorSearchService.searchSimilarDocuments`。

同一个 ID 再问：

```powershell
$followBody = '{"Id":"study-1","Question":"刚才那些步骤里应该先看哪三个指标？"}'
Invoke-RestMethod http://localhost:9900/api/chat `
  -Method Post `
  -ContentType "application/json" `
  -Body $followBody
```

观察第二次 system Prompt 中出现上一轮历史。

### 17.7 流式聊天

```powershell
curl.exe -N -X POST http://localhost:9900/api/chat_stream `
  -H "Content-Type: application/json" `
  -d '{"Id":"stream-1","Question":"如何排查内存使用率过高？"}'
```

观察 `event:message`、`data:`、JSON `type=content` 和最后的 `done`。浏览器 DevTools 中查看 Fetch 请求的 Response/Timing，确认内容是否增量到达。

### 17.8 变更风险分析

```powershell
curl.exe -N -X POST http://localhost:9900/api/ai_ops
```

也可以提交变更描述：

```powershell
curl.exe -N -X POST http://localhost:9900/api/ai_ops `
  -H "Content-Type: application/json" `
  -d '{"userRequest":"将 payment-service 升级到 v1.5.0，并修改数据库连接池配置"}'
```

默认使用 Mock 告警和日志。观察第一条提示与最终风险报告之间是否长时间无内部事件，从而验证它不是完整内部步骤流。

### 17.9 Rerank 实验

只在有独立 Rerank Key 时执行：

```powershell
$env:RAG_RERANK_ENABLED = "true"
$env:RAG_RERANK_API_KEY = "你的 Rerank Key"
$env:RAG_RECALL_TOP_K = "12"
```

重启后观察日志中的“向量召回数量、最终返回数量、rerank=true”。对同一问题分别关闭/开启精排，记录结果顺序、延迟和答案差异。

### 17.10 故障演练

每次只改变一个条件：

| 操作 | 预期 | 必答复盘 |
| --- | --- | --- |
| 不设置 Key 启动 | `OPENAI_API_KEY is required` | 为什么在启动期失败 |
| 停止 Milvus | 应用启动或 RPC 失败 | 是否应该整站 fail-fast |
| 上传 `.exe` | 400 | 仅后缀校验够不够 |
| 上传 `../a.md` | 400 | normalize + startsWith 的作用 |
| Mock 一个索引异常 | 文件存在、接口 503 | 文件状态与索引状态 |
| 开 Rerank 不设 Key | 默认回退 | 如何观测 degraded |
| 设置 fail-on-error | 直接失败 | 可用性与质量取舍 |
| 关闭 CLS Mock 不启 MCP | 没有 Java 日志工具 | 条件 Bean 与 MCP |
| 同 session 并发两问 | 可能乱序 | 锁为何不能解决语义竞态 |
| 流中途关闭客户端 | 后端可能继续 | 如何传递取消 |

### 17.11 测试

```powershell
mvn --batch-mode --no-transfer-progress verify
```

当前应看到 18 tests、0 failures。逐个测试回答“它防止什么回归”，而不是只记通过数量。

---

## 18. 四周、20 个学习日的详细计划

每天建议 90–120 分钟：40 分钟读代码，25 分钟动手，20 分钟画图/笔记，15 分钟脱稿回答。若只有两周，可每天完成两个学习日。

### 第 1 周：恢复整体认知和普通问答链路

#### 第 1 天：业务与仓库地图

- **阅读**：`README.md`、`docs/resume.md`、`pom.xml`；
- **目标**：说清项目解决什么问题、依赖哪些系统、当前边界；
- **动手**：用纸画 Browser、Controller、Agent、Tools、Milvus、OpenAI；
- **输出**：100 字项目介绍 + 一张组件图；
- **自测**：这个项目为何不是普通 ChatGPT 套壳？为什么不是自动修复平台？

#### 第 2 天：Spring Boot 启动和配置

- **阅读**：`Main`、`application.yml`、`OpenAiConfig`、`WebConfig`、`WebMvcConfig`；
- **目标**：理解组件扫描、自动装配、配置覆盖和启动校验；
- **动手**：列出所有环境变量及默认值；
- **输出**：启动时序图；
- **自测**：ChatModel 在哪里创建？为什么代码里没有 `new ChatModel()`？

#### 第 3 天：Milvus 初始化

- **阅读**：`MilvusProperties`、`MilvusConfig`、`MilvusClientFactory`、`MilvusConstants`；
- **目标**：理解客户端 Bean、Collection schema、IVF_FLAT 和 L2；
- **动手**：启动 Milvus，调用健康接口；
- **输出**：Collection 字段表；
- **自测**：Collection 已存在时当前代码还会验证什么？database 配置是否生效？

#### 第 4 天：同步聊天

- **阅读**：`ChatController.chat()`、`ChatService`；
- **目标**：追踪请求、历史、Prompt、Agent、工具和响应；
- **动手**：调用 `/api/chat` 并打断点；
- **输出**：同步问答时序图；
- **自测**：工具选择是 Java if/else 吗？错误为什么可能仍是 HTTP 200？

#### 第 5 天：会话与并发

- **阅读**：`SessionInfo`、前端 session/localStorage 代码；
- **目标**：区分数据结构线程安全和业务顺序一致性；
- **动手**：同 session 连续问两次，再模拟并发请求；
- **输出**：内存会话改 Redis 的设计草图；
- **自测**：ConcurrentHashMap 和 ReentrantLock 各保护什么？为什么还可能乱序？

**第 1 周验收**：3 分钟讲清项目；不看代码画出启动和同步聊天链路；能指出天气提示不匹配、内存会话和 HTTP 错误语义问题。

### 第 2 周：RAG 入库、检索和精排

#### 第 6 天：文件上传与安全

- **阅读**：`FileUploadController`、`FileUploadConfig`、multipart 配置和相关测试；
- **目标**：理解上传、路径规范化、符号链接和索引状态；
- **动手**：上传合法、空、非法后缀和路径攻击文件；
- **输出**：安全检查与遗漏清单；
- **自测**：为何索引失败返回 503 但保留文件？覆盖文件有什么数据丢失风险？

#### 第 7 天：分片算法

- **阅读**：`DocumentChunkService`、`DocumentChunk`、测试；
- **目标**：能手工执行标题、段落、overlap 算法；
- **动手**：对 `aiops-docs/cpu_high_usage.md` 预测 chunk；
- **输出**：三个示例 chunk 及 metadata；
- **自测**：单个超长段落会怎样？800/100 是最优值吗？

#### 第 8 天：Embedding

- **阅读**：`VectorEmbeddingService` 和测试；
- **目标**：理解文档/查询同模型、维度和语义空间；
- **动手**：观察一次向量维度；阅读维度失败测试；
- **输出**：Embedding 与生成模型差异表；
- **自测**：相同维度的不同模型能否混用？为何不在启动时联网测维度？

#### 第 9 天：向量入库和更新

- **阅读**：`VectorIndexService`；
- **目标**：掌握 delete old → chunk → embed → insert；
- **动手**：重复上传同一文件，观察删除/写入日志；
- **输出**：更新一致性风险与版本化方案；
- **自测**：中途第 3 个 chunk 写失败后数据是什么状态？稳定 UUID 有何价值？

#### 第 10 天：召回与精排

- **阅读**：`VectorSearchService`、`RerankService`、测试；
- **目标**：区分 Recall Top-K、Final Top-K、L2、Rerank score；
- **动手**：有条件时对比开关 Rerank；
- **输出**：两阶段检索图和降级决策表；
- **自测**：为什么不对全库做 Cross Encoder？返回 score 是否是 rerank score？

**第 2 周验收**：8 分钟讲完整 RAG；能解释 1536、IVF_FLAT、nlist/nprobe、800/100；能指出非原子索引和 Rerank 分数未回写。

### 第 3 周：工具、SSE 和多 Agent

#### 第 11 天：Tool Calling

- **阅读**：`ChatService`、`DateTimeTools`、`InternalDocsTools`；
- **目标**：理解工具定义、参数 schema、执行与观察结果；
- **动手**：设计三个问题分别触发时间、文档、告警；
- **输出**：工具选择表；
- **自测**：工具描述为何影响路由？工具为什么仍需参数校验？

#### 第 12 天：Prometheus 与日志

- **阅读**：`QueryMetricsTools`、`QueryLogsTools`、`application-mcp.yml`；
- **目标**：准确区分 Prometheus Mock/真实、CLS Mock/MCP；
- **动手**：整理每个工具输入输出 JSON；
- **输出**：数据源真实性矩阵；
- **自测**：为什么 QueryLogsTools 的真实分支不是正常真实路径？按 alertname 去重损失什么？

#### 第 13 天：SSE

- **阅读**：`chatStream()`、`SseMessage`、前端 `sendStreamMessage()`；
- **目标**：理解 SseEmitter、Flux、fetch reader、buffer；
- **动手**：curl `-N` 观察事件；
- **输出**：SSE 生命周期图；
- **自测**：为什么不用 EventSource？网络 chunk 为什么不能直接 JSON.parse？

#### 第 14 天：变更风险编排

- **阅读**：`AiOpsService`；
- **目标**：理解三个 Agent、outputKey、再规划和报告提取；
- **动手**：手工模拟一次 Planner/Executor 对话；
- **输出**：状态流图；
- **自测**：为什么 Executor 只做一步？Prompt 约束为何不等于硬限制？

#### 第 15 天：风险报告输出与安全边界

- **阅读**：`ChatController.aiOps()`、前端变更风险 SSE；
- **目标**：知道“最终报告分块”与“内部实时流”的区别；
- **动手**：提交一段变更描述，执行 Mock 风险分析并核对证据；
- **输出**：未来自动执行动作的安全清单；
- **自测**：为什么系统只给出回滚建议而不执行回滚？`AIOpsRequest` 如何进入 Planner？

**第 3 周验收**：能对比 ReactAgent 与 Planner/Executor；能解释 Java 方法工具和 MCP callbacks；能完整回答 SSE 10 个追问。

### 第 4 周：工程化、测试和模拟面试

#### 第 16 天：测试地图

- **阅读**：全部 7 个测试类；
- **目标**：把测试与风险对应；
- **动手**：运行 `mvn verify`；
- **输出**：测试覆盖/未覆盖矩阵；
- **自测**：18 个测试为什么不证明真实系统可用？缺哪些集成测试？

#### 第 17 天：故障与可观测性

- **阅读**：所有 catch、logger、timeout 配置；
- **目标**：区分配置错误、依赖故障、业务失败和部分成功；
- **动手**：执行至少三个故障演练；
- **输出**：故障响应表和建议指标；
- **自测**：哪些故障 fail-fast，哪些降级？为什么？

#### 第 18 天：系统设计升级

- **阅读**：本文第 16 章；
- **目标**：设计 Redis 会话、异步索引、限流和评测；
- **动手**：画生产版架构；
- **输出**：按 P0/P1/P2 排序的改进计划；
- **自测**：为什么先做权限/可靠性，再做自动执行？

#### 第 19 天：表达训练

- **阅读**：快速复习章与简历；
- **目标**：形成 1 分钟、3 分钟、8 分钟三种版本；
- **动手**：录音并检查是否夸大未接入能力；
- **输出**：个人负责内容的 STAR 案例；
- **自测**：每个“我做了”能否指出代码、问题、取舍和验证？

#### 第 20 天：模拟面试

- **阅读**：第 19 章题库；
- **目标**：连续 45 分钟回答；
- **动手**：随机抽项目、Java、RAG、Agent、故障题各两道；
- **输出**：不会题清单和最后补漏；
- **自测**：能否主动区分当前实现、局限和未来方案？

**第 4 周验收**：3 分钟项目介绍不卡顿；10 分钟技术深挖有代码依据；故障题先讲当前行为再讲改进；所有个人贡献真实可证。

---

## 19. 面试题库（60 题，含考察点、答案、项目结合与追问）

### A. 项目介绍（1–5）

#### 1. 请介绍一下这个项目

- **考察**：能否用业务问题组织技术，而不是罗列名词。
- **参考答案**：这是一个生产变更风险分析与证据决策平台，把发布/配置变更描述、内部文档、监控告警和日志包装成 Agent 可调用的证据源；普通问题由 ReactAgent 动态选工具，变更分析由 Supervisor/Planner/Executor 收集证据并生成风险报告；文档通过分片、Embedding、Milvus 召回和可选 Rerank 提供知识。
- **结合项目**：强调 Java 17、Spring Boot、Spring AI、Milvus、SSE；说明默认 Prometheus/CLS 是 Mock，会话在内存，不执行高风险变更。
- **可能追问**：你具体负责什么？和普通聊天机器人相比价值在哪里？

#### 2. 为什么要做这个项目？

- **考察**：是否理解用户痛点。
- **参考答案**：发布负责人要在变更单、监控、日志和内部规范之间切换，风险判断慢且依赖经验；Agent 能统一工具入口，RAG 提供内部知识，报告把证据、风险和验证建议结构化。
- **结合项目**：Prometheus 查活跃告警、CLS/MCP 查日志、Milvus 查发布规范，ChangeGuard 汇总风险报告。
- **可能追问**：为什么不用搜索引擎？为什么不直接让大模型回答？

#### 3. 项目的核心难点是什么？

- **考察**：能否识别工程难点而非只谈模型。
- **参考答案**：知识检索质量、工具路由与失败处理、多 Agent 终止、流式连接生命周期、外部依赖可靠性和索引一致性。
- **结合项目**：两阶段检索、Rerank 回退、Prompt 三次失败规则、SSE content/error/done、文件已保存但索引失败返回 503。
- **可能追问**：哪个难点你真正解决过？如何验证效果？

#### 4. 项目当前完成度如何？

- **考察**：是否诚实区分演示和生产。
- **参考答案**：主流程和本地演示完整，支持变更描述接收、RAG、Agent、Mock/真实 Prometheus 切换、MCP 配置和风险报告；但真实 Git Diff、发布系统接入、鉴权、Redis 会话、系统化评测、原子索引和生产限流未完成。
- **结合项目**：真实 CLS 依赖 MCP；当前风险分析使用自然语言变更描述和可用工具证据，不执行真实发布动作。
- **可能追问**：如果给你两周上线，先补什么？

#### 5. 你的个人贡献怎么讲？

- **考察**：真实性和 ownership。
- **参考答案**：用“问题—方案—关键实现—验证—结果”表达，只覆盖自己真实做过的部分。例如：为了减少向量召回噪声，引入候选召回 + Rerank，并设计失败回退，使用单测验证排序和 Top-K。
- **结合项目**：必须能指出对应类、方法、配置和测试；不把仓库全部代码说成个人独立完成。
- **可能追问**：你遇到的最大 bug 是什么？如果重做会改变什么？

### B. Spring Boot（6–10）

#### 6. `@SpringBootApplication` 做了什么？

- **考察**：Spring Boot 基础。
- **参考答案**：组合配置声明、自动配置和组件扫描；从主类所在包向下扫描 Bean，并依据 classpath 和配置自动创建常用组件。
- **结合项目**：`Main` 位于 `org.example`，因此 controller/service/config/agent 子包被扫描。
- **可能追问**：如果 Tool 类放到 `com.other` 会怎样？如何配置额外扫描路径？

#### 7. ChatModel 和 EmbeddingModel 在哪里创建？

- **考察**：是否理解 Starter 自动装配。
- **参考答案**：由 Spring AI OpenAI Starter 根据 `spring.ai.openai.*` 自动配置，业务类只注入接口。
- **结合项目**：`ChatService` 注入 ChatModel，`VectorEmbeddingService` 注入 EmbeddingModel；`OpenAiConfig` 主要校验 Key 和提供 RestClient Builder。
- **可能追问**：自动装配失败如何排查？如何替换测试 Bean？

#### 8. `@ConfigurationProperties` 和 `@Value` 有什么区别？

- **考察**：配置管理。
- **参考答案**：ConfigurationProperties 适合成组、类型化、可校验配置；Value 适合少量单值注入。前者更容易维护和测试。
- **结合项目**：Milvus、上传、分片使用 ConfigurationProperties；RAG topK、Rerank 参数等使用 Value。
- **可能追问**：如何增加 Bean Validation？如何处理配置热更新？

#### 9. 为什么推荐构造器注入？

- **考察**：依赖设计和可测试性。
- **参考答案**：依赖明确、可设 final、对象创建即完整、单元测试可直接传 Mock，也能避免某些循环依赖被隐藏。
- **结合项目**：多数类使用字段注入，`InternalDocsTools` 和 `RerankService` 已使用构造器；这是可重构点。
- **可能追问**：字段注入有什么实际风险？Spring 如何处理循环依赖？

#### 10. `@PostConstruct` 和 `@PreDestroy` 在这里有什么作用？

- **考察**：Bean 生命周期。
- **参考答案**：PostConstruct 在依赖注入后初始化/校验，PreDestroy 在容器关闭前释放资源。
- **结合项目**：OpenAI Key 校验、Embedding 初始化日志、OkHttp 初始化使用 PostConstruct；Milvus client 用 PreDestroy 关闭。
- **可能追问**：PostConstruct 中联网有什么风险？异常会怎样？

### C. Java 并发（11–15）

#### 11. 为什么 sessions 使用 `ConcurrentHashMap`？

- **考察**：并发容器。
- **参考答案**：多个请求线程会并发获取或创建会话；ConcurrentHashMap 支持线程安全访问，`computeIfAbsent` 能原子创建映射。
- **结合项目**：Map 只保护 session 映射，不自动保护 SessionInfo 内部 ArrayList。
- **可能追问**：为什么不能用 HashMap？computeIfAbsent 的映射函数有哪些注意点？

#### 12. 为什么 SessionInfo 还需要 `ReentrantLock`？

- **考察**：复合对象的线程安全。
- **参考答案**：messageHistory 是 ArrayList，读副本、成对写入、成对淘汰都属于复合操作，需要在同一临界区完成。
- **结合项目**：`addMessage/getHistory/clearHistory/getMessagePairCount` 都加锁并在 finally 解锁。
- **可能追问**：可以换 synchronized、CopyOnWriteArrayList 或读写锁吗？

#### 13. 当前会话实现是否完全线程安全？

- **考察**：数据安全与业务一致性的区别。
- **参考答案**：数据结构不会轻易损坏，但同 session 两个请求可同时读取同一旧历史、并行生成，再按完成顺序写回，可能语义乱序。
- **结合项目**：锁没有覆盖“读历史—调用模型—写结果”整个事务，因为模型调用不能长时间持锁。
- **可能追问**：如何按 session 串行？如何避免锁住慢调用？

#### 14. `newCachedThreadPool()` 有什么风险？

- **考察**：线程池参数和过载保护。
- **参考答案**：任务增长时可以创建大量线程，慢模型调用会导致线程、内存和上下文切换压力；缺少队列上限和拒绝策略。
- **结合项目**：聊天 SSE 和 AIOps 共用 Controller 内的 cached pool，且没有显式关闭。
- **可能追问**：你会如何设置 core/max/queue/rejection？虚拟线程是否合适？

#### 15. `StringBuilder` 在流式回调中安全吗？

- **考察**：线程归属与同步。
- **参考答案**：StringBuilder 本身不安全；如果 Flux 保证信号串行且同一订阅不会并发 onNext，则可用。若引入并行 scheduler 或多源合并，需要串行化或线程安全累加策略。
- **结合项目**：当前每个请求一个本地 builder，按 Reactor 订阅约定通常串行，但代码没有显式防御未来并行改造。
- **可能追问**：为什么不用 StringBuffer？Reactor 是否允许并发 onNext？

### D. SSE（16–20）

#### 16. 为什么项目选择 SSE？

- **考察**：技术选型。
- **参考答案**：模型文本主要是服务器单向持续推送，SSE 基于 HTTP、实现和调试简单，可降低首字等待；无需 WebSocket 的双向协议复杂度。
- **结合项目**：`/api/chat_stream` 把 Agent 模型增量转成 content 事件。
- **可能追问**：什么场景必须用 WebSocket？SSE 支持二进制吗？

#### 17. `SseEmitter` 生命周期是什么？

- **考察**：Spring MVC 异步响应。
- **参考答案**：Controller 创建并返回 emitter；后台任务 send 多次；正常 complete，异常 completeWithError，超时需要处理回调。
- **结合项目**：聊天 5 分钟，AIOps 10 分钟；当前没有显式 completion/timeout 取消下游。
- **可能追问**：客户端断开后如何停止模型调用？

#### 18. 为什么前端不用 EventSource？

- **考察**：浏览器 API 限制。
- **参考答案**：接口是 POST，需要 JSON body 传 Question/Id；EventSource 主要发 GET，所以使用 fetch + ReadableStream 手动解析。
- **结合项目**：`app.js` 使用 `response.body.getReader()`、TextDecoder、buffer。
- **可能追问**：手动解析为什么要 buffer？如何正确识别事件边界？

#### 19. 为什么一次 `reader.read()` 不能直接 JSON.parse？

- **考察**：网络流边界。
- **参考答案**：TCP/HTTP chunk 与 SSE 事件没有一一对应关系，一个 JSON 可被拆开，多个事件也可合并；应缓冲并按 SSE 行/空行规则解析。
- **结合项目**：前端保留 `lines.pop()` 作为未完成尾部。
- **可能追问**：当前按行解析对多行 data 是否完整？如何使用成熟 parser？

#### 20. SSE 生产化要补什么？

- **考察**：可靠性和容量。
- **参考答案**：心跳、超时一致性、客户端取消、断线重连/事件 ID、限流、有界执行器、代理禁用缓冲、指标、鉴权和输出净化。
- **结合项目**：当前无 heartbeat/id/cancel；AIOps 也不是内部步骤实时流。
- **可能追问**：Nginx 如何影响流式输出？Last-Event-ID 怎么用？

---

### E. RAG（21–25）

#### 21. 什么是 RAG，为什么项目需要它？

- **考察**：基本原理和业务价值。
- **参考答案**：RAG 在生成前检索外部知识，把证据加入上下文；适合模型参数中没有的企业文档和经常更新的 Runbook。
- **结合项目**：文档经分片、Embedding、Milvus 召回，结果通过 InternalDocsTools 给 ReactAgent。
- **可能追问**：RAG 能完全消除幻觉吗？资料错误怎么办？

#### 22. 为什么要分片？

- **考察**：检索粒度和上下文质量。
- **参考答案**：整篇文档太大且主题混杂；分片提高定位粒度、减少无关上下文。太小会失去语义，太大则噪声和 token 成本高。
- **结合项目**：先按 Markdown 标题，再按段落，默认最大 800 字符。
- **可能追问**：如何选择 chunk size？单个超长段落怎么办？

#### 23. overlap 为什么是 100？越大越好吗？

- **考察**：边界召回与成本取舍。
- **参考答案**：重叠缓解答案跨分片边界的问题；越大会产生更多重复、存储、Embedding 成本和检索重复。100 是当前经验默认，不是最优结论。
- **结合项目**：从上一 chunk 尾部取最多 100 字符，并尝试中文句末边界。
- **可能追问**：如何通过实验调参？如何去重相邻召回结果？

#### 24. RAG 质量如何评测？

- **考察**：是否超越“肉眼看答案”。
- **参考答案**：构建问题—标准证据—答案集；检索测 Recall@K、MRR、NDCG，生成测事实一致性、引用正确性、拒答准确率，再结合延迟和成本。
- **结合项目**：当前只有逻辑单测，没有系统化检索/答案评测，这是后续重点。
- **可能追问**：没有标注集怎么办？如何做线上反馈闭环？

#### 25. `RagService` 和 Agent RAG 有什么区别？

- **考察**：是否真正读过代码。
- **参考答案**：直接 RAG 固定执行“检索—拼 Prompt—ChatModel”；Agent RAG 把检索作为可选工具，由模型决定是否调用。
- **结合项目**：`RagService` 当前无上游引用；`/api/chat` 走 ReactAgent → InternalDocsTools。
- **可能追问**：哪种更稳定？何时不应让 Agent 自由选择？

### F. Milvus（26–30）

#### 26. 为什么使用 Milvus？

- **考察**：向量数据库选型。
- **参考答案**：Milvus 面向大规模向量存储和近似最近邻检索，支持向量索引、标量字段和 metadata，适合把 chunk 向量与原文一起管理。
- **结合项目**：每条记录含 id、1536 维 vector、content、JSON metadata。
- **可能追问**：为什么不使用关系数据库 pgvector？规模较小时如何选择？

#### 27. Collection schema 是什么？

- **考察**：数据模型。
- **参考答案**：类似表结构，规定字段名、类型、主键和向量维度。向量维度必须与 Embedding 输出严格匹配。
- **结合项目**：`oncall_knowledge_openai`，id VarChar 主键、FloatVector 1536、content、metadata。
- **可能追问**：为什么禁用 dynamic field？content 最大长度有什么影响？

#### 28. IVF_FLAT、nlist、nprobe 分别是什么？

- **考察**：向量索引基础。
- **参考答案**：IVF 把向量按中心划分到多个桶，FLAT 表示桶内精确扫描；nlist 是桶数，nprobe 是查询扫描桶数。更大 nprobe 一般提高召回但变慢。
- **结合项目**：创建索引 nlist=128，搜索 nprobe=10。
- **可能追问**：如何根据数据量调参？索引未构建完成能否查询？

#### 29. L2 和余弦相似度有什么区别？

- **考察**：距离度量。
- **参考答案**：L2 看欧氏距离，余弦看方向夹角；向量归一化后两者排序可能相关，但不能默认等价。必须与模型和索引配置一致。
- **结合项目**：Milvus 明确使用 L2；`calculateCosineSimilarity()` 只是未使用辅助方法。
- **可能追问**：L2 score 越大还是越小越相关？切换 metric 是否要重建索引？

#### 30. 同名文档更新怎么处理？

- **考察**：索引一致性。
- **参考答案**：根据来源删除旧 chunk，再生成和插入新 chunk；简单但非原子，中途失败会形成部分状态。
- **结合项目**：metadata `_source` 用于 delete 表达式，ID 由 source + chunkIndex 生成稳定 UUID。
- **可能追问**：如何实现零停机版本切换？删除失败继续写会怎样？

### G. Embedding（31–35）

#### 31. Embedding 和 ChatModel 有什么区别？

- **考察**：AI 应用基础。
- **参考答案**：Embedding 把文本编码为向量用于比较和检索；ChatModel 基于消息生成文本、推理和选择工具。两者输入输出与目标不同。
- **结合项目**：EmbeddingModel 服务 Milvus 入库/查询，ChatModel 服务 ReactAgent/AIOps/RagService 生成。
- **可能追问**：能用 ChatModel 直接生成向量吗？能用 Embedding 回答问题吗？

#### 32. 为什么文档和查询必须用同一个 Embedding 模型？

- **考察**：向量空间一致性。
- **参考答案**：不同模型学习的坐标系不同，即使维度相同，数值语义也不兼容；比较距离没有可靠意义。
- **结合项目**：`generateQueryVector()` 直接调用与文档相同的 `generateEmbedding()`。
- **可能追问**：换模型如何迁移？如何双写和回滚？

#### 33. 为什么更换 Embedding 后要重建 Collection？

- **考察**：schema 与语义兼容性。
- **参考答案**：可能维度不同导致 schema 不匹配，也可能维度相同但空间不同；旧文档向量必须用新模型重算。
- **结合项目**：新 Collection 名 `oncall_knowledge_openai`，旧 `biz` 保留，维度固定 1536。
- **可能追问**：如何避免迁移期间停服？如何验证新索引质量？

#### 34. 为什么维度校验放在首次调用而非启动？

- **考察**：启动可靠性和外部依赖。
- **参考答案**：某些 dimensions 查询可能联网；放在启动会让网络短暂故障阻断应用。首次实际 Embedding 返回后校验更直接。
- **结合项目**：PostConstruct 只检查 Bean 非空并记录配置维度，`validateDimension()` 在生成后执行。
- **可能追问**：延迟发现错误有什么代价？如何通过离线 readiness 改善？

#### 35. 为什么当前逐 chunk Embedding 可能低效？

- **考察**：吞吐和批处理。
- **参考答案**：每个 chunk 一次网络往返，吞吐低、费用和限流压力大；可以分批调用、控制 batch size、重试和并发。
- **结合项目**：批量 `generateEmbeddings()` 已实现并测试，但索引服务未使用。
- **可能追问**：批量失败如何定位单条？如何做幂等重试？

### H. Reranker（36–40）

#### 36. 为什么向量召回后还要 Rerank？

- **考察**：两阶段检索。
- **参考答案**：Embedding 相似度适合快速召回，但不总能精确判断文档是否回答 query；Cross Encoder 联合读取两者，排序更细。
- **结合项目**：默认召回 12，精排后 3；功能默认关闭。
- **可能追问**：为什么不是召回 100？如何权衡延迟？

#### 37. Reranker 请求和响应是什么？

- **考察**：是否读过实现。
- **参考答案**：请求包含 model、query、documents、top_n、return_documents=false；响应返回 candidate index 与 relevance_score，代码映射回原候选并倒序。
- **结合项目**：使用 RestClient 和 SiliconFlow 兼容 URL/默认 BGE 模型。
- **可能追问**：非法 index 如何处理？重复 index 怎么办？

#### 38. Reranker 失败为什么默认回退？

- **考察**：可用性与质量取舍。
- **参考答案**：精排是增强层，失败时仍可用向量结果回答；牺牲相关性换取知识库可用。强质量场景可配置 fail-on-error。
- **结合项目**：缺 Key、HTTP 异常、空结果都走 `handleFailure()`。
- **可能追问**：如何让用户知道处于降级？是否应该重试？

#### 39. 当前 Rerank score 有什么实现问题？

- **考察**：代码细读。
- **参考答案**：排序依据 relevance_score，但返回原 SearchResult，没有把该分数写入；score 仍是 Milvus 的值，解释性不足。
- **结合项目**：建议增加 `recallDistance` 和 `rerankScore` 两个字段，避免混淆。
- **可能追问**：如何在答案中展示来源置信度？不同分数能否归一化比较？

#### 40. 如何评估 Reranker 是否值得？

- **考察**：数据驱动决策。
- **参考答案**：对同一标注查询集比较 Recall@K、MRR/NDCG、答案引用正确率、P95 延迟和调用成本，并关注失败降级比例。
- **结合项目**：当前只有排序单测，没有真实质量基准；不能仅凭模型名声称效果提升。
- **可能追问**：离线指标提升但线上满意度下降怎么办？

---

### I. Agent（41–45）

#### 41. ReactAgent 是什么？

- **考察**：是否理解 Agent 不只是一次模型调用。
- **参考答案**：ReactAgent 让模型在问题、工具定义和工具结果之间循环，动态决定是否行动以及何时生成最终答案。
- **结合项目**：ChatService 每次请求构造 Agent，注入 Java method tools 和 MCP callbacks。
- **可能追问**：与固定工作流相比优缺点是什么？如何限制循环？

#### 42. Agent 如何选择工具？

- **考察**：Tool Calling 路由原理。
- **参考答案**：模型根据系统 Prompt、工具名称、描述、参数 schema 和对话上下文生成工具调用，不是后端硬编码关键词路由。
- **结合项目**：内部文档、Prometheus、时间、日志都有不同 `@Tool` 描述；天气提示却没有对应工具。
- **可能追问**：如何减少误选？工具描述过长有什么影响？

#### 43. Java 方法工具和 MCP 工具有何区别？

- **考察**：本地与远程工具架构。
- **参考答案**：方法工具是应用内 Java 对象，由框架反射调用；MCP 工具由外部服务发布，通过 ToolCallbackProvider 获取，便于跨进程、跨语言集成。
- **结合项目**：Mock CLS 是 QueryLogsTools；真实 CLS 通过 mcp profile 的 callbacks。
- **可能追问**：MCP 不可用如何降级？远程工具如何鉴权和审计？

#### 44. 工具返回错误为什么用 JSON 而不是直接抛异常？

- **考察**：Agent 可恢复性。
- **参考答案**：结构化失败可让模型知道“工具没拿到数据”，选择其他步骤或诚实报告；直接异常可能终止整个 Agent。但错误必须有统一 schema，不能让模型误判成功。
- **结合项目**：Prometheus/InternalDocs 工具将错误转换为 JSON；VectorSearch 内部仍先抛异常。
- **可能追问**：什么时候应该 fail-fast？如何防止模型忽略 success=false？

#### 45. 多轮历史为什么拼到 System Prompt？

- **考察**：上下文管理。
- **参考答案**：当前实现把最近 6 对历史文本加入系统提示，使新 Agent 能理解代词和上下文；实现简单，但角色边界和 Prompt 注入风险不如原生 Message 列表清晰。
- **结合项目**：`buildSystemPrompt()` 以“用户/助手”文本拼接，而 `RagService` 则构造真实 UserMessage/AssistantMessage。
- **可能追问**：用户历史中的恶意指令怎么办？如何做摘要和 token 预算？

### J. 变更风险分析（46–50）

#### 46. 为什么使用 Supervisor/Planner/Executor？

- **考察**：复杂任务编排。
- **参考答案**：变更风险分析需要多步证据收集和根据结果调整；Planner 决定下一步，Executor 执行并反馈，Supervisor 控制角色切换和结束。
- **结合项目**：状态 key 是 planner_plan 与 executor_feedback，最终从 planner_plan 提取报告。
- **可能追问**：一个 ReactAgent 能否完成？多 Agent 增加了什么成本？

#### 47. 为什么 Executor 只执行第一步？

- **考察**：反馈闭环。
- **参考答案**：避免一次性执行过时计划；每一步证据可能改变后续方向，执行一项后交回 Planner 重规划更适合故障诊断。
- **结合项目**：Executor Prompt 明确只读最新 planner_plan 并执行第一步。
- **可能追问**：什么任务适合并行执行多个步骤？如何控制依赖？

#### 48. Prompt 中“三次失败停止”可靠吗？

- **考察**：软约束与硬控制。
- **参考答案**：不完全可靠，模型可能计数错误或不遵守。生产上要在代码状态机维护 per-tool 失败次数、最大步骤、总超时和重复检测。
- **结合项目**：当前主要是 Prompt 文字约束，没有显式程序计数器。
- **可能追问**：如何设计状态 schema？失败计数按工具还是按参数？

#### 49. 如何防止变更风险分析编造结论？

- **考察**：证据可信和幻觉治理。
- **参考答案**：报告结论必须绑定工具证据；工具返回带 source/time/status；无证据时强制“证据不足”；结构化校验引用；离线评测和人工反馈。
- **结合项目**：Prompt 禁止编造并要求失败如实说明，但尚无程序级引用验证。
- **可能追问**：知识文档本身错误怎么办？如何评估根因准确率？

#### 50. `/api/ai_ops` 真的是流式编排吗？

- **考察**：代码真实性。
- **参考答案**：不是内部步骤实时流。先发启动提示，等待 invoke 完成，再把最终报告每 50 字符发送。
- **结合项目**：内部 Agent 步骤主要在框架状态和日志，前端收不到单独 plan/tool events。
- **可能追问**：如何改成真实事件流？事件 schema 如何设计？

### K. 系统设计（51–55）

#### 51. 会话如何改造成生产方案？

- **考察**：分布式状态。
- **参考答案**：Redis 按 user/session 保存消息，设置 TTL、最大长度、版本；鉴权绑定用户；同 session 用队列/锁串行；长历史做摘要；敏感数据加密和删除能力。
- **结合项目**：替换 Controller 内 ConcurrentHashMap/SessionInfo，并保持 API ID 语义。
- **可能追问**：Redis 挂了怎么办？摘要导致信息丢失怎么办？

#### 52. 文档索引如何改成可靠异步任务？

- **考察**：任务、状态和一致性。
- **参考答案**：上传只落文件/对象存储并创建任务；Worker 分片、批量 Embedding、写新版本；记录进度和错误；成功后原子切换 active version；支持幂等重试和死信。
- **结合项目**：解决同步上传超时和 delete-old-first 的部分写风险。
- **可能追问**：如何生成幂等键？如何处理文档删除和回滚？

#### 53. 如何做限流和容量规划？

- **考察**：生产流量治理。
- **参考答案**：按用户/租户限制请求与并发流；全局有界队列；模型、Embedding、Rerank 分别设置并发；记录 token、延迟、错误率；过载时快速拒绝或降级。
- **结合项目**：替换 cached thread pool，限制 SSE/风险分析同时执行数。
- **可能追问**：普通 chat 和风险分析是否同一优先级？如何防止大客户占满资源？

#### 54. 应该监控哪些指标？

- **考察**：可观测性。
- **参考答案**：请求量、首字/P95 延迟、SSE 活跃连接、取消、模型 token/错误、工具成功率、RAG Recall@K、Rerank 降级率、索引队列/失败、Agent 步数和成本。
- **结合项目**：当前主要是日志，没有完整指标/tracing；工具和 Agent 事件应带 request/session/trace ID。
- **可能追问**：如何从一次错误答案追溯到检索候选和工具结果？

#### 55. 如果未来加入自动修复，需要哪些安全措施？

- **考察**：高风险 Agent 安全。
- **参考答案**：动作白名单、最小权限、参数验证、审批、双人确认、幂等、预检查、影响评估、灰度、回滚、审计和 kill switch；模型只提议，确定性代码执行。
- **结合项目**：当前明确不执行高风险变更，这是正确边界。
- **可能追问**：哪些低风险动作可以先自动化？如何防止 Prompt Injection 触发动作？

### L. 故障排查（56–60）

#### 56. 应用启动报 `OPENAI_API_KEY is required` 怎么查？

- **考察**：配置和进程环境。
- **参考答案**：确认 Key 在启动 Maven/Java 的同一终端环境；检查 YAML 属性映射和 profile；不要用 ChatGPT/Codex 登录替代 API Key。
- **结合项目**：OpenAiConfig 的 PostConstruct 主动抛错，OpenAiConfigTest 验证文案。
- **可能追问**：容器/Kubernetes 中如何安全注入？如何轮换 Key？

#### 57. 上传返回 503 怎么定位？

- **考察**：跨组件故障链路。
- **参考答案**：503 表示文件已保存但 indexSingleFile 失败；按日志检查读取、删除旧向量、分片、Embedding、维度、Milvus load/insert；修复后重新上传。
- **结合项目**：FileUploadControllerTest 明确验证文件仍存在且状态不是成功。
- **可能追问**：如果已插入部分 chunk 怎么清理？为何不用 500？

#### 58. 检索结果明显不相关怎么查？

- **考察**：RAG 调试方法。
- **参考答案**：先看文档是否正确入库和 metadata；检查 query/document 模型一致；打印召回 candidates 与 L2；调 chunk、topK、nprobe；启用/评估 Rerank；检查语料和问题表达。
- **结合项目**：区分 Milvus 召回顺序与 Rerank 顺序，注意 score 未保存 rerank 分数。
- **可能追问**：如何判断是检索问题还是生成问题？

#### 59. SSE 页面一直不出字怎么查？

- **考察**：全链路流式排查。
- **参考答案**：确认 HTTP 200 和 `text/event-stream`；用 curl `-N` 绕过前端；检查 Agent 是否产生 `AGENT_MODEL_STREAMING`；看 emitter.send 异常；检查前端 parser、代理 buffering、压缩和超时。
- **结合项目**：风险分析长时间无字可能是其先完成编排再分块的设计，不一定是 SSE 坏了。
- **可能追问**：为什么后端打印 chunk 但浏览器一次性出现？

#### 60. MCP 日志工具不存在怎么查？

- **考察**：条件配置和外部工具发现。
- **参考答案**：确认 `SPRING_PROFILES_ACTIVE=mcp`、endpoint 非空、MCP client enabled、连接可达、Provider 注入和工具列表；确认 CLS Mock 已关闭。
- **结合项目**：没有 Provider 时 ChatService 返回空 callback 数组，不会启动空指针；`logAvailableTools` 只打印 MCP 工具。
- **可能追问**：如何做 readiness？MCP 不可用是否应阻止整个应用启动？

---

## 20. 面试快速复习区

### 20.1 三分钟项目介绍

> 我做的是一个生产变更风险分析与证据决策平台 ChangeGuard AI。发布或配置变更发生前，负责人需要分别查看变更描述、监控、日志和内部规范，再人工判断影响范围与风险。这个项目把这些数据源统一封装为 Agent 工具，让系统能够围绕一项变更主动取证并生成验证清单和回滚建议。
>
> 后端使用 Java 17 和 Spring Boot。普通聊天请求进入 ChatController 后，ChatService 会把最近六轮问答拼入系统提示，注入时间、内部文档、Prometheus 和日志等工具，再创建 ReactAgent。模型根据工具描述决定是否调用工具，拿到结果后生成最终回答。系统同时支持完整 JSON 和 SSE 流式输出；流式接口把 Agent 的增量模型输出包装成 content、error、done 三类事件，前端使用 fetch 和 ReadableStream 解析，因为请求需要用 POST 携带问题和 session ID。
>
> 内部知识库采用 RAG。上传的 TXT/Markdown 会先做路径和后缀安全校验，然后按 Markdown 标题和段落分片，默认最大 800 字符、重叠 100 字符；每个 chunk 使用 OpenAI Embedding 转成 1536 维向量，连同原文和 metadata 写入 Milvus。查询时先做 Milvus L2 向量召回，Rerank 开启后默认先召回 12 条，再通过 Cross Encoder 精排到 3 条；精排失败默认回退到向量结果。
>
> 对变更风险分析，项目使用 Supervisor、Planner 和 Executor。Planner 制定或调整取证步骤，Executor 每次执行第一步并返回监控、日志或知识库证据，Supervisor 控制循环，最后由 Planner 输出 Markdown 风险报告。当前默认 Prometheus 和 CLS 使用 Mock；真实 Prometheus 有 HTTP 查询代码，真实 CLS 依赖 MCP。系统只提出验证和回滚建议，不执行发布、回滚、重启或扩容。
>
> 工程上已经覆盖了上传路径穿越防护、Embedding 维度校验、Rerank 降级和本地单元测试，但仍有生产化空间：内存会话需要迁移 Redis，索引更新需要版本化和原子切换，SSE 需要取消、心跳和限流，多 Agent 需要程序级步骤预算，并且要建立检索、工具和报告质量评测。

说完后接自己的真实贡献：不要照搬“我做了全部”，应选 1–2 个你真正能深挖的模块。

### 20.2 一分钟架构介绍

> 系统分为 Web 接入、Agent 编排、工具与数据源、RAG 和基础设施五层。浏览器通过 HTTP/SSE 调用 Spring MVC Controller；普通聊天由 ChatService 构造 ReactAgent，变更分析由 AiOpsService 构造 Supervisor/Planner/Executor；工具层提供内部文档、Prometheus、Mock CLS 或 MCP 日志；RAG 层负责文档分片、Embedding、Milvus 召回和可选 Rerank；OpenAI 和 Milvus 是关键外部依赖。当前会话在内存，前端另用 localStorage 保存 UI 历史。

### 20.3 三条链路速记

普通聊天：

```text
POST /api/chat
-> ChatController
-> Session history
-> ChatService
-> ReactAgent
-> optional tools
-> answer
-> save history
-> JSON
```

RAG：

```text
upload -> validate/save -> chunk -> embedding -> Milvus
query -> embedding -> Milvus L2 recall -> optional rerank -> Agent
```

AIOps：

```text
/api/ai_ops -> Supervisor -> Planner -> Executor/tools
-> feedback -> Planner/replan -> FINISH -> planner_plan report -> SSE chunks
```

### 20.4 十个必须背熟的设计取舍

1. **Agent 而非固定关键词路由**：更灵活，但不确定性更高，需要约束和观测。
2. **RAG 而非只靠模型参数**：能使用内部、可更新知识，但质量依赖检索和文档。
3. **标题/段落分片**：语义更完整，但算法对超长单段处理不足。
4. **100 字符 overlap**：降低边界丢失，但增加重复和成本。
5. **Milvus 向量召回**：适合规模检索，但 L2 排名不总等于回答相关性。
6. **召回 + Rerank**：平衡速度和准确度，但增加外部依赖与延迟。
7. **Rerank 默认降级**：保障可用性，但答案质量可能无声下降。
8. **SSE 而非 WebSocket**：满足单向文本流且简单，但断线续传和取消需补充。
9. **Planner/Executor 分步闭环**：能根据证据重规划，但模型调用更多。
10. **只诊断不自动操作**：降低生产风险；自动化需审批、幂等、审计和回滚。

### 20.5 十个项目局限

1. 会话保存在单 JVM 内存，无 TTL，多实例不共享；
2. 同 session 并发请求可能语义乱序；
3. cached thread pool 无上限且未显式关闭；
4. SSE 无心跳、事件 ID、断线续传和完整取消；
5. 文档更新先删后逐条写，缺少原子性；
6. Embedding/insert 未利用已有批量能力；
7. Rerank score 未写回结果，解释性不足；
8. AIOps 失败次数和循环预算主要依赖 Prompt；
9. 默认数据含 Mock，真实外部链路缺完整 E2E；
10. 缺鉴权、限流、评测、全链路 tracing，前端 Markdown 需净化。

### 20.6 面试前一天速记版

必须记住的数字和名称：

```text
Java 17
Spring Boot 3.2.0
Spring AI 1.1.0
端口 9900
Collection oncall_knowledge_openai
Embedding 1536 维
chunk 800 / overlap 100
final topK 3 / rerank recall 12
IVF_FLAT + L2
nlist 128 / nprobe 10
聊天 SSE 5 分钟 / AIOps 10 分钟
会话最多 6 对
```

必须主动澄清：

```text
RagService 不是 /api/chat 主链路
真实 CLS 依赖 MCP，不是 QueryLogsTools 直连
AIOps 是最终报告分块，不是内部步骤实时流
天气 Prompt 有描述但没有工具
测试当前实际 18 个，不代表真实 E2E
```

回答任何技术题的统一模板：

```text
先说业务问题
-> 当前代码怎么做
-> 为什么这样选
-> 失败时当前怎么处理
-> 当前不足
-> 生产上如何改
```

---

## 21. 全模块责任索引

| 模块 | 解决问题 | 输入 → 输出 | 上游 → 下游 | 失败/追问重点 |
| --- | --- | --- | --- | --- |
| Main | 启动应用 | args/config → context | JVM → Spring | 扫描与自动装配 |
| OpenAiConfig | Key/HTTP 配置 | properties → 校验/Builder | Spring → RestClient | 启动失败、外部校验时机 |
| MilvusProperties | 类型化配置 | YAML → POJO | Spring → Factory | database 未使用 |
| MilvusClientFactory | 连接和建 Collection | properties → client | MilvusConfig → Milvus | schema 旧、索引异步 |
| MilvusConfig | client Bean 生命周期 | factory → Bean | Spring → services | 关闭资源、启动耦合 |
| WebConfig | 编码和 JSON | converter config → MVC | Spring → HTTP | ObjectMapper 不统一 |
| WebMvcConfig | CORS/静态资源 | Web config → mappings | Spring → browser | `*` Origin 风险 |
| ChatController | Chat/SSE/AIOps API | JSON → JSON/SSE | browser → services | 过重、HTTP 错误语义 |
| SessionInfo | 多轮历史 | Q/A → history | Controller → Prompt | 内存、TTL、并发顺序 |
| ChatService | 构建/执行 Agent | history/question → answer | Controller → model/tools | 工具日志不完整、提示错配 |
| DateTimeTools | 当前时间 | 无 → 时间字符串 | Agent → Java time | 时区来源 |
| InternalDocsTools | RAG 工具桥 | query → JSON results | Agent → VectorSearch | 错误 JSON、无结果 |
| QueryMetricsTools | 活跃告警 | 无 → JSON alerts | Agent → Mock/Prometheus | 去重丢实例、超时 |
| QueryLogsTools | Mock 日志 | topic/query → JSON logs | Agent → mock builders | 仅 Mock Bean |
| AiOpsService | 多 Agent 诊断 | model/tools → state/report | Controller → Agents | Prompt 软约束、输出 key |
| FileUploadController | 上传并索引 | multipart → status | browser → index | 文件/索引状态分裂 |
| DocumentChunkService | 语义分片 | text → chunks | Index → config | 超长单段、坐标近似 |
| VectorEmbeddingService | 文本向量化 | text(s) → vectors | Index/Search → model | 维度、限流、批量未用 |
| VectorIndexService | 向量写入更新 | file → Milvus rows | Upload → Milvus | 非原子、部分写 |
| VectorSearchService | 候选召回 | query/topK → results | Tools/Rag → Milvus | L2 语义、异常包装 |
| RerankService | 候选精排 | query/docs → reordered | Search → HTTP service | 回退、score 未回写 |
| RagService | 直接 RAG 示例 | query/history → callbacks | 无公开上游 → Search/Chat | 当前未接入 |
| MilvusCheckController | 基础健康 | GET → collections | operator → Milvus | 仅证明基础 RPC |
| AIOpsRequest | 变更风险请求 DTO | userRequest → object | ChatController → AiOpsService | 当前只接收自然语言描述 |
| DropCollection | 手工重建辅助 | main → 删除 Collection | 人工 → Milvus | 高破坏、硬编码 localhost |
| app.js | UI 与 API 客户端 | user events → DOM | browser → APIs | 硬编码 URL、SSE parser、XSS |
| Tests | 回归保护 | mock inputs → assertions | Maven → classes | 外部 E2E 未覆盖 |

---

## 22. 最终验收清单

完成复习后，你应该能在不看文档的情况下：

- [ ] 1 分钟说明架构，3 分钟介绍项目；
- [ ] 画出普通聊天、流式聊天、RAG 入库、RAG 查询和变更风险分析五条流程；
- [ ] 解释每个核心类的上下游；
- [ ] 解释 800/100、1536、12/3、nlist/nprobe、L2；
- [ ] 区分 method tools、MCP tools、Mock 和真实数据；
- [ ] 准确指出自然语言变更分析、Git Diff 解析和真实变更执行之间的边界；
- [ ] 回答 SSE、线程池、会话并发和 Redis 改造；
- [ ] 回答索引部分写、Rerank 回退和模型故障；
- [ ] 说明现有 18 个测试覆盖什么、不覆盖什么；
- [ ] 提出按优先级排序的生产化方案；
- [ ] 用真实证据讲清自己的个人贡献，不夸大。

复习的终点不是“记住所有代码”，而是形成稳定的解释框架：**业务问题 → 数据流 → 核心实现 → 设计取舍 → 失败模式 → 生产改进**。
