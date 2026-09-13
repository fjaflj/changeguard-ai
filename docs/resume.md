# 简历与面试说明

## 项目名称

ChangeGuard AI 生产变更风险分析与证据决策平台

## 简历描述

- 设计并实现基于 Java 17、Spring Boot、Spring AI 和 Milvus 的变更风险分析平台，接收发布、配置和依赖升级描述，结合监控、日志与内部知识库生成发布决策建议。
- 构建 RAG 证据检索链路，完成 TXT/Markdown 分片、`text-embedding-3-small` 向量化、Milvus 召回和 Cross Encoder 二阶段精排。
- 将 Prometheus、Mock/CLS 日志和内部文档封装为 Agent 工具，由模型根据变更内容选择取证数据源，并在报告中区分已验证证据与无法确认的信息。
- 基于 Supervisor / Planner / Executor 实现“变更理解—取证计划—工具执行—再规划—风险报告”流程，输出风险等级、影响范围、验证清单、观察指标和回滚建议。
- 使用 SSE 返回长耗时分析结果，完善文件上传路径校验、索引失败状态反馈、Mock/MCP 配置隔离和 OpenAI/Milvus 运行配置。

## 技术关键词

Java 17、Spring Boot、Spring AI OpenAI、`gpt-5.6-terra`、`text-embedding-3-small`、Milvus、RAG、Cross Encoder/Reranker、ReactAgent、Supervisor、Planner-Executor、Prometheus、MCP、SSE、Docker Compose、JUnit 5、GitHub Actions。

## 面试重点

- 为什么需要把“向量召回”和 Cross Encoder 精排拆成两阶段；
- 变更描述如何进入 Supervisor / Planner / Executor 的分析上下文；
- 如何区分监控、日志、知识库证据和模型推断；
- 为什么风险分析只能给出建议，不能直接执行发布和回滚；
- OpenAI Embedding 更换后为什么需要新的向量集合和重新索引；
- Mock、真实 Prometheus 和 MCP 日志之间如何切换；
- SSE 长连接的超时、取消、异常和代理缓冲问题；
- 当前系统的会话、鉴权、Git Diff 解析和自动变更执行边界。

## 当前边界

系统分析用户提交的自然语言变更描述，不解析真实 Git Diff，不连接发布系统，也不执行发布、回滚、重启、扩容或配置修改。会话保存在单 JVM 内存中，默认 Prometheus/CLS 使用 Mock，真实 CLS 查询依赖 MCP 配置。AIOps 报告在编排完成后进行分块 SSE 输出，不代表每个内部 Agent 步骤都实时展示。
