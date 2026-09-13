package org.example.dto;

import lombok.Data;

/** ChangeGuard AI 变更风险分析请求。 */
@Data
public class AIOpsRequest {

    /** 发布、配置或依赖变更的自然语言描述。 */
    private String userRequest;
}
