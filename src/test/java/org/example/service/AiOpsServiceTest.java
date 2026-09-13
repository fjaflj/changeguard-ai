package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiOpsServiceTest {

    @Test
    void taskPromptContainsSubmittedChangeAndDecisionFields() {
        AiOpsService service = new AiOpsService();

        String prompt = service.buildTaskPrompt("升级 payment-service 并调整数据库连接池");

        assertTrue(prompt.contains("升级 payment-service 并调整数据库连接池"));
        assertTrue(prompt.contains("风险等级"));
        assertTrue(prompt.contains("发布前验证清单"));
        assertTrue(prompt.contains("回滚建议"));
    }

    @Test
    void emptyChangeUsesCompatibleDemoPrompt() {
        AiOpsService service = new AiOpsService();

        String prompt = service.buildTaskPrompt(" ");

        assertTrue(prompt.contains("演示变更"));
    }
}
