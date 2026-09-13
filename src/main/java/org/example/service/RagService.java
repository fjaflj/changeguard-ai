package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** RAG 服务：检索 Milvus 知识库并通过 Spring AI ChatModel 流式生成答案。 */
@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private ChatModel chatModel;

    @Value("${rag.top-k:3}")
    private int topK;

    /** 流式处理用户问题（不带历史消息）。 */
    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    /** 流式处理用户问题（带历史消息）。 */
    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            logger.info("收到 RAG 流式查询: {}", question);
            List<VectorSearchService.SearchResult> searchResults =
                    vectorSearchService.searchSimilarDocuments(question, topK);
            callback.onSearchResults(searchResults);

            if (searchResults.isEmpty()) {
                callback.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            String prompt = buildPrompt(question, buildContext(searchResults));
            generateAnswerStream(prompt, history, callback);
        } catch (Exception e) {
            logger.error("RAG 流式查询失败", e);
            callback.onError(e);
        }
    }

    private String buildContext(List<VectorSearchService.SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult result = searchResults.get(i);
            context.append("【参考资料 ").append(i + 1).append("】\n")
                    .append(result.getContent()).append("\n\n");
        }
        return context.toString();
    }

    private String buildPrompt(String question, String context) {
        return "请根据以下参考资料回答用户问题。若资料中没有依据，请明确说明，不要编造。\n\n"
                + "参考资料：\n" + context + "\n用户问题：" + question;
    }

    private void generateAnswerStream(String prompt, List<Map<String, String>> history,
                                      StreamCallback callback) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("你是 ChangeGuard AI 的知识库助手，回答必须以检索到的资料为依据。"));
        for (Map<String, String> historyMsg : history) {
            String role = historyMsg.get("role");
            String content = historyMsg.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        messages.add(new UserMessage(prompt));

        logger.info("开始调用 OpenAI ChatModel 流式接口，消息数量: {}", messages.size());
        Flux<ChatResponse> result = chatModel.stream(new Prompt(messages));
        StringBuilder finalContent = new StringBuilder();

        result.toStream().forEach(response -> {
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return;
            }
            String content = response.getResult().getOutput().getText();
            if (content != null && !content.isEmpty()) {
                finalContent.append(content);
                callback.onContentChunk(content);
            }
        });

        callback.onComplete(finalContent.toString(), "");
        logger.info("OpenAI ChatModel 流式响应完成，内容长度: {}", finalContent.length());
    }

    public interface StreamCallback {
        void onSearchResults(List<VectorSearchService.SearchResult> results);
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        void onError(Exception e);
    }
}
