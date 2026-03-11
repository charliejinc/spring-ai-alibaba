/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.graph.agent.hook.smartshell;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.ToolInjection;
import com.alibaba.cloud.ai.graph.agent.tools.smartshell.SmartShellSessionManager;
import com.alibaba.cloud.ai.graph.agent.tools.smartshell.SmartShellTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hook for managing SmartShellTool lifecycle.
 * This hook initializes the smart shell session before the agent starts,
 * auto-detects available shells, and provides intelligent error recovery.
 *
 * <p>Features:
 * <ul>
 *   <li>Auto-detects best available shell (PowerShell, WSL, Git Bash, etc.)</li>
 *   <li>Initializes shell session before agent execution</li>
 *   <li>Provides automatic error recovery for common issues</li>
 *   <li>Cleans up sessions after agent finishes</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * SmartShellToolAgentHook hook = SmartShellToolAgentHook.builder()
 *     .workspaceRoot(System.getProperty("user.dir"))
 *     .autoFixEnabled(true)
 *     .tryAlternativeShells(true)
 *     .build();
 *
 * ReactAgent agent = ReactAgent.builder()
 *     .model(chatModel)
 *     .hooks(List.of(hook))
 *     .build();
 * </pre>
 */
@HookPositions({HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT})
public class SmartShellToolAgentHook extends AgentHook implements ToolInjection {

	private static final Logger log = LoggerFactory.getLogger(SmartShellToolAgentHook.class);

	private SmartShellTool smartShellTool;
	private SmartShellSessionManager sessionManager;
	private String shellToolName;

	private final String workspaceRoot;
	private final boolean autoFixEnabled;
	private final boolean tryAlternativeShells;
	private final boolean verboseErrors;
	private final long commandTimeout;
	private final int maxOutputLines;

	private SmartShellToolAgentHook(Builder builder) {
		this.workspaceRoot = builder.workspaceRoot;
		this.autoFixEnabled = builder.autoFixEnabled;
		this.tryAlternativeShells = builder.tryAlternativeShells;
		this.verboseErrors = builder.verboseErrors;
		this.commandTimeout = builder.commandTimeout;
		this.maxOutputLines = builder.maxOutputLines;
		this.shellToolName = builder.shellToolName;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
		SmartShellSessionManager sessionManager = getSessionManager();
		if (sessionManager == null) {
			log.warn("SmartShellToolAgentHook: No SmartShellTool injected, skipping initialization");
			return CompletableFuture.completedFuture(new HashMap<>());
		}

		log.info("SmartShellToolAgentHook: Initializing smart shell session before agent execution");
		log.info("Available shells: {}",
			sessionManager.getAvailableShells().stream()
				.map(s -> s.getType().name())
				.toList());

		try {
			sessionManager.initialize(config);
			log.info("Smart shell session initialized successfully");
		} catch (Exception e) {
			log.error("Failed to initialize smart shell session", e);
			throw new RuntimeException("Failed to initialize smart shell session", e);
		}

		return CompletableFuture.completedFuture(new HashMap<>());
	}

	@Override
	public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
		SmartShellSessionManager sessionManager = getSessionManager();
		if (sessionManager == null) {
			log.warn("SmartShellToolAgentHook: No SmartShellTool injected, skipping cleanup");
			return CompletableFuture.completedFuture(new HashMap<>());
		}

		log.info("SmartShellToolAgentHook: Cleaning up smart shell session after agent execution");

		try {
			sessionManager.cleanup(config);
			log.info("Smart shell session cleaned up successfully");
		} catch (Exception e) {
			log.error("Failed to cleanup smart shell session", e);
			// Don't throw exception in cleanup to avoid masking original errors
		}

		return CompletableFuture.completedFuture(new HashMap<>());
	}

	@Override
	public String getName() {
		return "SmartShellToolAgentHook";
	}

	@Override
	public void injectTool(ToolCallback toolCallback) {
		if (smartShellTool != null) {
			// Tool already injected
			return;
		}

		log.info("SmartShellToolAgentHook: Processing tool callback for smart shell tool extraction");

		try {
			// Extract SmartShellTool
			SmartShellTool extractedTool = extractSmartShellTool(toolCallback);
			if (extractedTool != null) {
				this.smartShellTool = extractedTool;
				this.sessionManager = extractedTool.getSessionManager();
				log.info("Successfully extracted and injected SmartShellTool from tool: {}",
					toolCallback.getToolDefinition().name());
				return;
			}

			log.warn("Failed to extract SmartShellTool from tool: {}",
				toolCallback.getToolDefinition().name());
		} catch (Exception e) {
			log.error("Error extracting SmartShellTool from tool callback", e);
		}
	}

	/**
	 * Extract SmartShellTool instance from ToolCallback using reflection.
	 */
	private SmartShellTool extractSmartShellTool(ToolCallback toolCallback) {
		try {
			Class<?> clazz = toolCallback.getClass();

			// Look for 'toolObject' field in MethodToolCallback
			while (clazz != null) {
				try {
					Field toolObjectField = clazz.getDeclaredField("toolObject");
					toolObjectField.setAccessible(true);
					Object toolObject = toolObjectField.get(toolCallback);

					if (toolObject instanceof SmartShellTool) {
						return (SmartShellTool) toolObject;
					}
					break;
				} catch (NoSuchFieldException e) {
					// Try parent class
					clazz = clazz.getSuperclass();
				}
			}
		} catch (Exception e) {
			log.debug("Could not extract SmartShellTool from ToolCallback via reflection", e);
		}

		return null;
	}

	@Override
	public List<ToolCallback> getTools() {
		if (smartShellTool == null) {
			log.info("No SmartShellTool instance injected, creating default instance");
			this.smartShellTool = SmartShellTool.builder(workspaceRoot != null ? workspaceRoot : System.getProperty("user.dir"))
				.withAutoFix(autoFixEnabled)
				.withTryAlternativeShells(tryAlternativeShells)
				.withVerboseErrors(verboseErrors)
				.withCommandTimeout(commandTimeout)
				.withMaxOutputLines(maxOutputLines)
				.build();
			this.sessionManager = smartShellTool.getSessionManager();
		}
		return Arrays.asList(ToolCallbacks.from(smartShellTool));
	}

	@Override
	public String getRequiredToolName() {
		return shellToolName;
	}

	@Override
	public Class<? extends ToolCallback> getRequiredToolType() {
		// We don't filter by ToolCallback type because SmartShellTool is wrapped
		// We rely on tool name matching instead
		return null;
	}

	/**
	 * Get the injected SmartShellTool instance.
	 */
	protected SmartShellTool getSmartShellTool() {
		return smartShellTool;
	}

	/**
	 * Get the SmartShellSessionManager from SmartShellTool.
	 */
	private SmartShellSessionManager getSessionManager() {
		if (sessionManager != null) {
			return sessionManager;
		}
		if (smartShellTool != null) {
			return smartShellTool.getSessionManager();
		}
		return null;
	}

	/**
	 * Builder class for constructing SmartShellToolAgentHook instances.
	 */
	public static class Builder {
		private String workspaceRoot = System.getProperty("user.dir");
		private boolean autoFixEnabled = true;
		private boolean tryAlternativeShells = true;
		private boolean verboseErrors = true;
		private long commandTimeout = 60000;
		private int maxOutputLines = 1000;
		private String shellToolName = "smart_shell";

		/**
		 * Set the workspace root directory.
		 */
		public Builder workspaceRoot(String workspaceRoot) {
			this.workspaceRoot = workspaceRoot;
			return this;
		}

		/**
		 * Enable or disable automatic error fixing.
		 * When enabled, the tool will attempt to automatically install missing
		 * dependencies and fix common issues.
		 * Default: true
		 */
		public Builder autoFixEnabled(boolean autoFixEnabled) {
			this.autoFixEnabled = autoFixEnabled;
			return this;
		}

		/**
		 * Enable or disable trying alternative shells when commands fail.
		 * When enabled, the tool will try alternative commands or shells
		 * (e.g., try 'python3' if 'python' fails, or use WSL).
		 * Default: true
		 */
		public Builder tryAlternativeShells(boolean tryAlternativeShells) {
			this.tryAlternativeShells = tryAlternativeShells;
			return this;
		}

		/**
		 * Enable or disable verbose error reporting.
		 * When enabled, error responses include detailed recovery information.
		 * Default: true
		 */
		public Builder verboseErrors(boolean verboseErrors) {
			this.verboseErrors = verboseErrors;
			return this;
		}

		/**
		 * Set the command timeout in milliseconds.
		 * Default: 60000 (60 seconds)
		 */
		public Builder commandTimeout(long commandTimeout) {
			this.commandTimeout = commandTimeout;
			return this;
		}

		/**
		 * Set the maximum number of output lines.
		 * Default: 1000
		 */
		public Builder maxOutputLines(int maxOutputLines) {
			this.maxOutputLines = maxOutputLines;
			return this;
		}

		/**
		 * Set the shell tool name for matching.
		 */
		public Builder shellToolName(String shellToolName) {
			this.shellToolName = shellToolName;
			return this;
		}

		/**
		 * Build the SmartShellToolAgentHook instance.
		 */
		public SmartShellToolAgentHook build() {
			return new SmartShellToolAgentHook(this);
		}
	}
}
