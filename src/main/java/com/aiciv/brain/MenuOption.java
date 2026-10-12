package com.aiciv.brain;

import com.aiciv.llm.ToolCall;
import java.util.List;

/**
 * One choice in a menu-mode decision. {@code id} is short and matches [a-z0-9_]{1,40}; {@code calls} is the
 * 0 to 3 action chain it stands for (empty for "wait").
 */
public record MenuOption(String id, String description, List<ToolCall> calls) {}
