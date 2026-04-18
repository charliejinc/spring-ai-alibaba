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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import com.alibaba.cloud.ai.studio.runtime.domain.chat.ToolCallType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.BiFunction;

/**
 * Tool for executing Python code using the system Python interpreter.
 *
 * This tool allows the agent to execute Python code snippets and get results.
 * It uses the system Python interpreter, so pip-installed packages are available.
 */
public class PythonTool implements BiFunction<PythonTool.PythonRequest, ToolContext, String> {

	public static final String DESCRIPTION = """
			Executes Python code using the system Python interpreter. Supports all pip-installed packages.

			Supported Libraries (built-in + pip installed):
			- Standard library: json, re, math, datetime, collections, itertools, functools, operator, random, statistics, typing
			- Third-party: Any pip-installed packages (e.g., pandas, numpy, pypdf, requests, etc.)

			Usage:
			- The code parameter must be valid Python code
			- The tool will execute the code using system Python interpreter
			- If the code produces a result, it will be returned as a string
			- Errors will be caught and returned as error messages
			- IMPORTANT: Supports pip-installed packages unlike GraalVM Python

			Examples:
			- Basic math: code = "2 + 2 * 3" returns "8"
			- Pip package: code = "import pandas as pd; df = pd.DataFrame({'a': [1,2,3]}); print(df)"
			- File operations: code = "with open('test.txt', 'r') as f: print(f.read())"
			- JSON: code = 'import json; print(json.dumps({"a": 1}))'
			- Regex: code = 'import re; print(re.findall(r"\\d+", "abc123def456"))'
			""";

	private static final Logger log = LoggerFactory.getLogger(PythonTool.class);

	// Cache the Python executable path
	private static String pythonExecutable;

	public PythonTool() {
		// No initialization needed
	}

	/**
	 * Get the Python executable path. Checks common locations on Windows.
	 */
	private static String getPythonExecutable() {
		if (pythonExecutable != null) {
			return pythonExecutable;
		}

		// Try python3 first, then python
		String[] candidates = {"python3", "python", "py"};

		for (String candidate : candidates) {
			pythonExecutable = findPythonExecutable(candidate);
			if (pythonExecutable != null) {
				log.info("Found Python executable: {}", pythonExecutable);
				return pythonExecutable;
			}
		}

		// Default to "python" if not found - will fail at runtime
		log.warn("Could not find Python executable, using default 'python'");
		pythonExecutable = "python";
		return pythonExecutable;
	}

	/**
	 * Find Python executable by trying to run it with -c "print('test')"
	 */
	private static String findPythonExecutable(String executable) {
		try {
			ProcessBuilder pb = new ProcessBuilder(executable, "-c", "print('test')");
			pb.redirectErrorStream(true);
			Process process = pb.start();

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String output = reader.readLine();
				int exitCode = process.waitFor();

				if (exitCode == 0 && "test".equals(output)) {
					return executable;
				}
			}
			process.destroy();
		}
		catch (Exception e) {
			log.debug("Python executable '{}' not found: {}", executable, e.getMessage());
		}
		return null;
	}

	/**
	 * Create an AgentToolCallback for the Python tool.
	 */
	public static AgentToolCallback createPythonToolCallbackAgent(String description) {
		ToolCallback delegate = FunctionToolCallback.builder("python_tool", new PythonTool())
				.description(description)
				.inputType(PythonRequest.class)
				.build();
		return AgentToolCallbackAdapter.createCallback(delegate, "python_tool");
	}

    public static ToolCallback createPythonToolCallback(String description) {
        ToolCallback delegate = FunctionToolCallback.builder("python_tool", new PythonTool())
                .description(description)
                .inputType(PythonRequest.class)
                .build();
        return delegate;
    }

	@Override
	public String apply(PythonRequest request, ToolContext toolContext) {
		if (request.code == null || request.code.trim().isEmpty()) {
			return "Error: Python code cannot be empty";
		}

		try {
			String python = getPythonExecutable();
			log.debug("Executing Python code with {}: {}", python, request.code);

			// Execute Python code using system interpreter
			String result = executePython(python, request.code);
			return result;
		}
		catch (Exception e) {
			log.error("Error executing Python code", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Execute Python code using the system Python interpreter.
	 */
	private String executePython(String python, String code) throws Exception {
		// Wrap code to capture stdout
		String wrappedCode = """
				import sys
				import io
				# Capture stdout
				old_stdout = sys.stdout
				sys.stdout = io.StringIO()

				# Execute user code
				%s

				# Get captured output
				output = sys.stdout.getvalue()
				sys.stdout = old_stdout

				# Print the output
				print(output, end='')
				""".formatted(code);

		ProcessBuilder pb = new ProcessBuilder(python, "-c", wrappedCode);
		pb.redirectErrorStream(true);

		Process process = pb.start();

		StringBuilder output = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append("\n");
			}
		}

		int exitCode = process.waitFor();
		process.destroy();

		if (exitCode != 0) {
			return "Error (exit code " + exitCode + "): " + output.toString();
		}

		return output.toString().trim();
	}

	/**
	 * Request structure for the Python tool.
	 */
	public static class PythonRequest {

		@JsonProperty(required = true)
		@JsonPropertyDescription("The Python code to execute")
		public String code;

		public PythonRequest() {
		}

		public PythonRequest(String code) {
			this.code = code;
		}
	}
}
