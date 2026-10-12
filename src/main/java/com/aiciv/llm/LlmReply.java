package com.aiciv.llm;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * A parsed model reply. thought is text that came alongside tool calls (logged, never chatted).
 * plainText is set only when the reply had no actions at all. finishReason and usage are what the
 * provider reported, or null when absent.
 */
public record LlmReply(List<ToolCall> calls, String thought, String plainText, String finishReason, JsonObject usage) {}
