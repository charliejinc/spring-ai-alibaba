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

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for formatting command output.
 * Similar to CoPaw's output formatting approach.
 */
public class OutputFormatter {

	/**
	 * Format a successful command output.
	 * Similar to CoPaw's success case handling.
	 */
	public static String formatSuccess(String output, boolean hasWarnings) {
		if (output == null || output.isEmpty()) {
			String result = "Command executed successfully (no output).";
			if (hasWarnings) {
				result += "\n\n[Warnings present - see above]";
			}
			return result;
		}

		if (hasWarnings) {
			return output + "\n\n[Warnings present - see above]";
		}
		return output;
	}

	/**
	 * Format a failed command output with detailed error information.
	 * Similar to CoPaw's error case handling.
	 */
	public static String formatError(int exitCode, String stdout, String stderr, boolean timedOut, long timeoutMs,
			boolean truncated, int maxLines) {
		List<String> parts = new ArrayList<>();

		// Main error message
		if (timedOut) {
			parts.add(String.format("Command execution exceeded the timeout of %d seconds.", timeoutMs / 1000));
			parts.add("\nConsider increasing the timeout value if this command requires more time to complete.");
		} else {
			parts.add(String.format("Command failed with exit code %d.", exitCode));
		}

		// stdout
		if (stdout != null && !stdout.isEmpty()) {
			parts.add("\n[stdout]\n" + stdout);
		}

		// stderr
		if (stderr != null && !stderr.isEmpty()) {
			parts.add("\n[stderr]\n" + stderr);
		}

		// Truncation notice
		if (truncated) {
			parts.add(String.format("\n[Output truncated - showing first %d lines]", maxLines));
		}

		return String.join("", parts);
	}

	/**
	 * Format a timeout error with helpful suggestion.
	 * Similar to CoPaw's TimeoutError handling.
	 */
	public static String formatTimeout(long timeoutMs, String partialOutput) {
		StringBuilder sb = new StringBuilder();

		if (partialOutput != null && !partialOutput.isEmpty()) {
			sb.append(partialOutput).append("\n\n");
		}

		sb.append("Command execution exceeded the timeout of ")
			.append(timeoutMs / 1000)
			.append(" seconds.\n")
			.append("Consider increasing the timeout value if this command requires more time to complete.");

		return sb.toString();
	}

	/**
	 * Format auto-fix information.
	 */
	public static String formatAutoFixed(String originalOutput, String fixCommand) {
		StringBuilder sb = new StringBuilder();
		if (originalOutput != null && !originalOutput.isEmpty()) {
			sb.append(originalOutput).append("\n\n");
		}
		sb.append("[Auto-fixed by running: ").append(fixCommand).append("]");
		return sb.toString();
	}

	/**
	 * Format alternative command information.
	 */
	public static String formatAlternativeCommand(String originalOutput, String alternativeCommand) {
		StringBuilder sb = new StringBuilder();
		if (originalOutput != null && !originalOutput.isEmpty()) {
			sb.append(originalOutput).append("\n\n");
		}
		sb.append("[Used alternative command: ").append(alternativeCommand).append("]");
		return sb.toString();
	}
}
