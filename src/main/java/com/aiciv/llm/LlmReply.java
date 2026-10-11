package com.aiciv.llm;

import java.util.List;

/**
 * A parsed model reply. thought is text that came alongside tool calls (logged, never chatted).
 * plainText is set only when the reply had no actions at all.
 */
public record LlmReply(List<ToolCall> calls, String thought, String plainText) {}
