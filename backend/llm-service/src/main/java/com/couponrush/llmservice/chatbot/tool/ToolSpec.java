package com.couponrush.llmservice.chatbot.tool;

import java.util.Map;

/** 도구 서버가 알려 준 도구 하나 (MCP tools/list). inputSchema 는 JSON Schema 그대로 — 모델에게 그대로 넘긴다 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
}
