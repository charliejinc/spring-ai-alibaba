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
package com.alibaba.cloud.ai.graph.agent.tools.smartshell;

import com.alibaba.cloud.ai.graph.RunnableConfig;

/**
 * Abstract interface for shell command execution backends.
 * Implementations can include local shell, SSH, Docker, etc.
 */
public interface ShellExecutor {

	/**
	 * Execute a command and return the result.
	 *
	 * @param command the command to execute
	 * @param config the runnable configuration
	 * @param timeoutMs timeout in milliseconds
	 * @return the execution result
	 */
	ExecutionResult execute(String command, RunnableConfig config, long timeoutMs);

	/**
	 * Check if this executor is available (e.g., connection established).
	 */
	boolean isAvailable();

	/**
	 * Initialize the executor (e.g., establish connection).
	 */
	void initialize(RunnableConfig config);

	/**
	 * Cleanup resources.
	 */
	void cleanup(RunnableConfig config);

	/**
	 * Get the executor name.
	 */
	String getName();

	/**
	 * Check if a command exists in this executor's environment.
	 */
	boolean commandExists(String command, RunnableConfig config);

	/**
	 * Get installation suggestion for a missing command.
	 */
	String getInstallSuggestion(String command);

	/**
	 * Result of command execution.
	 */
	class ExecutionResult {
		private final String output;
		private final Integer exitCode;
		private final boolean timedOut;
		private final boolean success;

		public ExecutionResult(String output, Integer exitCode, boolean timedOut, boolean success) {
			this.output = output;
			this.exitCode = exitCode;
			this.timedOut = timedOut;
			this.success = success;
		}

		public String getOutput() { return output; }
		public Integer getExitCode() { return exitCode; }
		public boolean isTimedOut() { return timedOut; }
		public boolean isSuccess() { return success; }

		public static ExecutionResult success(String output) {
			return new ExecutionResult(output, 0, false, true);
		}

		public static ExecutionResult failure(String output, Integer exitCode) {
			return new ExecutionResult(output, exitCode, false, false);
		}

		public static ExecutionResult timeout(String output) {
			return new ExecutionResult(output, null, true, false);
		}
	}
}
