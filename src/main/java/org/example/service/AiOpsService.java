package org.example.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** ChangeGuard AI 变更风险分析服务。 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    /** 保留无请求体调用的兼容入口，使用默认演示变更。 */
    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks, null);
    }

    /**
     * 执行变更风险分析流程。
     *
     * @param chatModel 大模型实例
     * @param toolCallbacks 工具回调数组
     * @param userRequest 用户提交的变更描述，可为空
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel,
                                                        ToolCallback[] toolCallbacks,
                                                        String userRequest) throws GraphRunnerException {
        logger.info("开始执行 ChangeGuard AI 变更风险分析流程");

        // 构建 Planner 和 Executor Agent
        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

        // 构建 Supervisor Agent
        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("change_risk_supervisor")
                .description("负责调度 Planner 与 Executor 的变更风险分析控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        String taskPrompt = buildTaskPrompt(userRequest);

        logger.info("调用 ChangeGuard Supervisor 开始编排...");
        return supervisorAgent.invoke(taskPrompt);
    }

    /** 构造稳定、可测试的变更风险分析任务。 */
    String buildTaskPrompt(String userRequest) {
        String changeDescription = userRequest == null || userRequest.isBlank()
                ? "演示变更：请根据当前活动告警和知识库，分析一项待发布服务变更的潜在风险。"
                : userRequest.trim();
        return "你是 ChangeGuard AI 的变更风险分析负责人。请分析下面这项发布、配置或依赖变更：\n"
                + "【变更描述】\n" + changeDescription + "\n\n"
                + "请结合 Prometheus 告警、日志和内部知识库证据，执行规划→取证→再规划闭环，"
                + "最终输出变更摘要、影响服务与依赖、证据、风险等级（低/中/高）、发布前验证清单、观察指标、回滚建议和无法确认的信息。"
                + "所有结论必须基于工具返回内容，禁止编造；不执行任何发布、回滚或配置修改。";
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        // 提取 Planner 最终输出（包含完整的变更风险报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            logger.info("成功提取到 Planner 最终报告，长度: {}", reportText.length());
            return Optional.of(reportText);
        } else {
            logger.warn("未能提取到 Planner 最终报告");
            return Optional.empty();
        }
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(ChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解变更风险、规划取证与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(ChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个取证步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback")
                .build();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 ChangeGuard Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析变更描述、Prometheus 告警、日志、内部文档等信息，制定可执行的下一步取证步骤。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容；如果连续 3 次调用同一工具仍失败或返回空结果，需停止该方向并在最终报告的结论部分说明"无法完成"的原因。
                
                ## 最终变更风险报告输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：
                
                ```
                # 变更风险分析报告
                
                ---
                
                ## 📝 变更摘要

                ## 🎯 影响范围
                
                | 服务/依赖 | 影响判断 | 证据来源 |
                |---------|----------|----------|
                | [服务或依赖] | [影响说明] | [监控/日志/文档] |
                
                ---
                
                ## 🔍 风险项分析1 - [风险项]
                
                ### 风险详情
                - **风险等级**: [低/中/高]
                - **受影响服务**: [服务名]
                - **证据**: [监控、日志或文档证据]
                
                ### 症状描述
                [根据变更内容和观测数据描述风险]
                
                ### 观测证据
                [引用查询到的关键监控或日志]
                
                ### 风险结论
                [基于证据得出的风险判断]
                
                ---
                
                ## ✅ 发布前验证清单
                
                ### 建议验证步骤
                1. [步骤1]
                2. [步骤2]
                
                ### 观察指标
                [发布后需要关注的指标、日志和告警]
                
                ## ↩️ 回滚建议
                [仅提出回滚条件和建议，不执行回滚]
                
                ---
                
                ## 🔍 风险项分析2 - [风险项]
                [如果有第2个风险项，重复上述格式]
                
                ---
                
                ## 📊 决策结论
                
                ### 整体评估
                [总结变更是否建议发布，以及需要满足的前置条件]
                
                ### 关键发现
                - [发现1]
                - [发现2]
                
                ### 无法确认的信息
                1. [待确认信息1]
                2. [待确认信息2]
                
                ### 风险评估
                [说明证据不足、数据源不可用或需要人工确认的内容]
                ```
                
                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 变更风险分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的变更、服务或依赖，方便 Planner 填充风险分析章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。


                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "近1小时未见 error 日志，仅有 info",
                  "evidence": "...",
                  "nextHint": "建议转向高占用进程"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 ChangeGuard Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出完整的《变更风险分析报告》，包含变更摘要、影响范围、证据、风险等级、验证清单、观察指标和回滚建议。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
                6. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。

                """;
    }
}
