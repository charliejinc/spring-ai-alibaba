/*
* Copyright 2024-2026 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.alibaba.cloud.ai.studio.core.agent.tool;

import com.alibaba.cloud.ai.studio.runtime.domain.chat.ToolCallType;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Adapter that wraps a plain {@link ToolCallback} and implements {@link AgentToolCallback}.
 * This allows plain ToolCallback implementations (like FunctionToolCallback) to be used
 * where AgentToolCallback is expected.
 *
 * @since 1.0.0.4
 */
public class AgentToolCallbackAdapter implements AgentToolCallback {

	private final ToolCallback delegate;

	private final String id;

	private final ToolCallType toolCallType;

	/**
	 * Creates a new adapter wrapping the given delegate.
	 * @param delegate the ToolCallback to wrap
	 * @param id unique identifier for this tool callback
	 * @param toolCallType the tool call type
	 */
	public AgentToolCallbackAdapter(ToolCallback delegate, String id, ToolCallType toolCallType) {
		this.delegate = delegate;
		this.id = id;
		this.toolCallType = toolCallType;
	}

	/**
	 * Creates an adapter with default tool call type {@link ToolCallType#TOOL_CALL}.
	 * @param delegate the ToolCallback to wrap
	 * @param id unique identifier for this tool callback
	 */
	public AgentToolCallbackAdapter(ToolCallback delegate, String id) {
		this(delegate, id, ToolCallType.TOOL_CALL);
	}


    public  static AgentToolCallback createCallback(ToolCallback delegate, String id) {
        return new AgentToolCallbackAdapter(delegate, id);
    }
	@Override
	public String getId() {
		return id;
	}

	@Override
	public ToolCallType getToolCallType() {
		return toolCallType;
	}

	@Override
	public @NotNull ToolDefinition getToolDefinition() {
		return delegate.getToolDefinition();
	}

	@Override
	public @NotNull String call(@NotNull String functionInput) {
		return delegate.call(functionInput);
	}

	@Override
	public @NotNull String call(@NotNull String functionInput,  ToolContext toolContext) {
		// Spring AI 1.1.2 MethodToolCallback requires ToolContext to have non-empty context map.
		// If an empty ToolContext is provided, wrap it with a non-empty one to avoid validation failure.
		if (toolContext == null || org.springframework.util.CollectionUtils.isEmpty(toolContext.getContext())) {
			toolContext = new ToolContext(java.util.Map.of("toolCallHistory", java.util.List.of()));
		}
		return delegate.call(functionInput, toolContext);
	}

	@Override
	public @NotNull ToolMetadata getToolMetadata() {
		return delegate.getToolMetadata();
	}

}