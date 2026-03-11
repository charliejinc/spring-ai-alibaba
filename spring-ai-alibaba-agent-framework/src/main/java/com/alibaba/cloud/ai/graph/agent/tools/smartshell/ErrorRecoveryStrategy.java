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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes command execution errors and provides recovery strategies.
 * Similar to how Claude automatically fixes issues when commands fail.
 */
public class ErrorRecoveryStrategy {

	private static final Logger log = LoggerFactory.getLogger(ErrorRecoveryStrategy.class);

	// Error patterns for different types of failures
	private static final Pattern COMMAND_NOT_FOUND_PATTERN = Pattern.compile(
		"(?i)(?:is not recognized|cannot find|command not found|unknown command|not found|'[^']+' is not|'[^']+' could not be found)");

	private static final Pattern PERMISSION_DENIED_PATTERN = Pattern.compile(
		"(?i)(permission denied|access is denied|cannot access|operation not permitted)");

	private static final Pattern PYTHON_MODULE_NOT_FOUND_PATTERN = Pattern.compile(
		"(?i)(moduleNotFoundError|no module named|cannot import|import error)");

	private static final Pattern NPM_MODULE_NOT_FOUND_PATTERN = Pattern.compile(
		"(?i)(cannot find module|module not found|Error: Cannot find module)");

	private static final Pattern COMPILATION_ERROR_PATTERN = Pattern.compile(
		"(?i)(syntax error|compilation error|build failed|failed to compile)");

	private static final Pattern NETWORK_ERROR_PATTERN = Pattern.compile(
		"(?i)(connection refused|network error|timeout|could not resolve|dns error|unable to connect)");

	private static final Pattern JAVA_CLASS_NOT_FOUND_PATTERN = Pattern.compile(
		"(?i)(classnotfoundexception|noclassdeffounderror|could not find or load main class)");

	private static final Pattern MAVEN_GRADLE_ERROR_PATTERN = Pattern.compile(
		"(?i)(could not resolve|could not find artifact|failed to execute goal|build failure)");

	private final ShellEnvironment shellEnvironment;

	public ErrorRecoveryStrategy(ShellEnvironment shellEnvironment) {
		this.shellEnvironment = shellEnvironment;
	}

	/**
	 * Analyze error output and determine the error type.
	 */
	public ErrorAnalysis analyze(String command, String output, Integer exitCode) {
		ErrorAnalysis analysis = new ErrorAnalysis();
		analysis.setOriginalCommand(command);
		analysis.setExitCode(exitCode);
		analysis.setOutput(output);

		if (exitCode != null && exitCode == 0) {
			analysis.setType(ErrorType.SUCCESS);
			return analysis;
		}

		// Check for specific error patterns
		if (COMMAND_NOT_FOUND_PATTERN.matcher(output).find()) {
			analysis.setType(ErrorType.COMMAND_NOT_FOUND);
			analysis.setMissingCommand(extractCommandFromNotFound(output));
			analysis.setSuggestedFix(generateInstallSuggestion(analysis.getMissingCommand()));
		} else if (PYTHON_MODULE_NOT_FOUND_PATTERN.matcher(output).find()) {
			analysis.setType(ErrorType.PYTHON_MODULE_MISSING);
			analysis.setMissingModule(extractPythonModule(output));
			analysis.setSuggestedFix(generatePythonModuleInstallSuggestion(analysis.getMissingModule()));
		} else if (NPM_MODULE_NOT_FOUND_PATTERN.matcher(output).find()) {
			analysis.setType(ErrorType.NPM_MODULE_MISSING);
			analysis.setMissingModule(extractNpmModule(output));
			analysis.setSuggestedFix(generateNpmInstallSuggestion(analysis.getMissingModule()));
		} else if (PERMISSION_DENIED_PATTERN.matcher(output).find()) {
			analysis.setType(ErrorType.PERMISSION_DENIED);
			analysis.setSuggestedFix("Try running with elevated privileges:\n" +
				"  Windows: Run as Administrator\n" +
				"  Linux/Mac: Use 'sudo ' prefix");
		} else if (JAVA_CLASS_NOT_FOUND_PATTERN.matcher(output).find()) {
			analysis.setType(ErrorType.JAVA_CLASS_NOT_FOUND);
			analysis.setSuggestedFix("Missing Java class. Try:\n" +
				"1. Check your classpath\n" +
				"2. Ensure all dependencies are built: mvn clean install\n" +
				"3. Check for missing jar files");
		} else if (MAVEN_GRADLE_ERROR_PATTERN.matcher(output).find()) {
			analysis.setType(ErrorType.BUILD_TOOL_ERROR);
			analysis.setSuggestedFix("Build tool dependency issue. Try:\n" +
				"1. Check your internet connection\n" +
				"2. Clear cache: mvn clean / gradle clean\n" +
				"3. Update dependencies: mvn dependency:resolve");
		} else if (NETWORK_ERROR_PATTERN.matcher(output).find()) {
			analysis.setType(ErrorType.NETWORK_ERROR);
			// Check if it's an npm/npx/yarn/pnpm command and suggest mirror configuration
			String lowerCommand = command.toLowerCase().trim();
			boolean isNpmCommand = lowerCommand.startsWith("npm ") || lowerCommand.startsWith("npx ")
					|| lowerCommand.startsWith("yarn ") || lowerCommand.startsWith("pnpm ");

			if (isNpmCommand) {
				analysis.setSuggestedFix(
						"Network connectivity issue with npm/npx/yarn/pnpm. For China users, try configuring a mirror:\n" +
								"1. Set npm mirror: npm config set registry https://registry.npmmirror.com\n" +
								"2. Set npx mirror: npx config set registry https://registry.npmmirror.com\n" +
								"3. Set yarn mirror: yarn config set registry https://registry.npmmirror.com\n" +
								"4. Set pnpm mirror: pnpm config set registry https://registry.npmmirror.com\n\n" +
								"Or reset to default:\n" +
								"  npm config set registry https://registry.npmjs.org");
			} else {
				analysis.setSuggestedFix("Network connectivity issue. Try:\n" +
						"1. Check your internet connection\n" +
						"2. Check proxy settings\n" +
						"3. Retry the command");
			}
		} else if (COMPILATION_ERROR_PATTERN.matcher(output).find()) {
			analysis.setType(ErrorType.COMPILATION_ERROR);
			analysis.setSuggestedFix("Compilation failed. Check the error output for specific issues.");
		} else {
			analysis.setType(ErrorType.UNKNOWN);
			analysis.setSuggestedFix("Unknown error. Please review the output and try again.");
		}

		// Generate alternative approaches
		analysis.setAlternativeCommands(generateAlternatives(command, analysis.getType()));

		return analysis;
	}

	/**
	 * Generate a recovery suggestion based on error analysis.
	 */
	public RecoverySuggestion suggestRecovery(ErrorAnalysis analysis) {
		RecoverySuggestion suggestion = new RecoverySuggestion();
		suggestion.setAnalysis(analysis);

		switch (analysis.getType()) {
			case COMMAND_NOT_FOUND -> {
				suggestion.setCanAutoFix(true);
				suggestion.setAutoFixCommand(generateInstallCommand(analysis.getMissingCommand()));
				suggestion.setMessage(String.format(
					"Command '%s' not found. %s",
					analysis.getMissingCommand(),
					analysis.getSuggestedFix()
				));
			}
			case PYTHON_MODULE_MISSING -> {
				suggestion.setCanAutoFix(true);
				suggestion.setAutoFixCommand(generatePipInstallCommand(analysis.getMissingModule()));
				suggestion.setMessage(String.format(
					"Python module '%s' is missing. Install it with: %s",
					analysis.getMissingModule(),
					suggestion.getAutoFixCommand()
				));
			}
			case NPM_MODULE_MISSING -> {
				suggestion.setCanAutoFix(true);
				suggestion.setAutoFixCommand(generateNpmInstallCommand(analysis.getMissingModule()));
				suggestion.setMessage(String.format(
					"Node module '%s' is missing. Install it with: %s",
					analysis.getMissingModule(),
					suggestion.getAutoFixCommand()
				));
			}
			case PERMISSION_DENIED -> {
				suggestion.setCanAutoFix(true);
				suggestion.setAutoFixCommand("sudo " + analysis.getOriginalCommand());
				suggestion.setMessage(analysis.getSuggestedFix());
			}
			default -> {
				suggestion.setCanAutoFix(false);
				suggestion.setMessage(analysis.getSuggestedFix());
			}
		}

		return suggestion;
	}

	/**
	 * Try to extract the missing command from error output.
	 */
	private String extractCommandFromNotFound(String output) {
		// Try to find patterns like "'xxx' is not recognized" or "xxx: command not found"
		Pattern[] patterns = {
			Pattern.compile("'([^']+)' is not recognized"),
			Pattern.compile("'([^']+)' could not be found"),
			Pattern.compile("([^\s:]+): command not found"),
			Pattern.compile("cannot find ['\"]([^'\"]+)['\"]"),
		};

		for (Pattern pattern : patterns) {
			Matcher matcher = pattern.matcher(output);
			if (matcher.find()) {
				return matcher.group(1).trim();
			}
		}

		// Fallback: extract first word from command
		return "";
	}

	/**
	 * Extract Python module name from error output.
	 */
	private String extractPythonModule(String output) {
		Pattern[] patterns = {
			Pattern.compile("No module named ['\"]([^'\"]+)['\"]"),
			Pattern.compile("ModuleNotFoundError: No module named ['\"]([^'\"]+)['\"]"),
			Pattern.compile("cannot import name ['\"]([^'\"]+)['\"]"),
		};

		for (Pattern pattern : patterns) {
			Matcher matcher = pattern.matcher(output);
			if (matcher.find()) {
				return matcher.group(1);
			}
		}
		return "";
	}

	/**
	 * Extract npm module name from error output.
	 */
	private String extractNpmModule(String output) {
		Pattern pattern = Pattern.compile("Cannot find module ['\"]([^'\"]+)['\"]");
		Matcher matcher = pattern.matcher(output);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return "";
	}

	/**
	 * Generate installation suggestion for a missing command.
	 */
	private String generateInstallSuggestion(String command) {
		return shellEnvironment.getInstallSuggestion(command);
	}

	/**
	 * Generate installation command for a missing command.
	 */
	private String generateInstallCommand(String command) {
		// This would be environment-specific
		return "# Install " + command + ":\n" + shellEnvironment.getInstallSuggestion(command);
	}

	/**
	 * Generate Python module installation suggestion.
	 */
	private String generatePythonModuleInstallSuggestion(String module) {
		return switch (shellEnvironment.getType()) {
			case POWERSHELL, CMD -> "pip install " + module + "\n or\npython -m pip install " + module;
			case WSL -> "wsl pip3 install " + module;
			default -> "pip3 install " + module;
		};
	}

	private String generatePipInstallCommand(String module) {
		return switch (shellEnvironment.getType()) {
			case POWERSHELL, CMD -> "pip install " + module;
			case WSL -> "wsl pip3 install " + module;
			default -> "pip3 install " + module;
		};
	}

	/**
	 * Generate npm installation suggestion.
	 */
	private String generateNpmInstallSuggestion(String module) {
		if (module.startsWith("@") || module.contains("/")) {
			return "npm install " + module;
		}
		return "npm install " + module;
	}

	private String generateNpmInstallCommand(String module) {
		return "npm install " + module;
	}

	/**
	 * Generate alternative commands to try.
	 */
	private List<String> generateAlternatives(String originalCommand, ErrorType errorType) {
		List<String> alternatives = new ArrayList<>();

		if (errorType == ErrorType.COMMAND_NOT_FOUND) {
			String cmd = originalCommand.trim().split("\\s+")[0];

			// Suggest alternative commands based on the missing command
			switch (cmd.toLowerCase()) {
				case "python" -> {
					alternatives.add("python3 " + originalCommand.substring(cmd.length()).trim());
					if (shellEnvironment.getType() == ShellEnvironment.Type.WSL) {
						alternatives.add("wsl python3 " + originalCommand.substring(cmd.length()).trim());
					}
				}
				case "python3" -> alternatives.add("python " + originalCommand.substring(cmd.length()).trim());
				case "pip" -> alternatives.add("pip3 " + originalCommand.substring(cmd.length()).trim());
				case "pip3" -> alternatives.add("pip " + originalCommand.substring(cmd.length()).trim());
				case "node" -> {
					if (shellEnvironment.getType() == ShellEnvironment.Type.WSL) {
						alternatives.add("wsl node " + originalCommand.substring(cmd.length()).trim());
					}
				}
				case "npm" -> {
					if (shellEnvironment.getType() == ShellEnvironment.Type.WSL) {
						alternatives.add("wsl npm " + originalCommand.substring(cmd.length()).trim());
					}
				}
				case "code" -> alternatives.add("code.cmd " + originalCommand.substring(cmd.length()).trim());
				default -> {
					// For other commands, suggest using a different shell
					if (shellEnvironment.getType() != ShellEnvironment.Type.WSL) {
						alternatives.add("wsl " + originalCommand);
					}
				}
			}
		}

		return alternatives;
	}

	/**
	 * Types of errors that can occur during command execution.
	 */
	public enum ErrorType {
		SUCCESS,
		COMMAND_NOT_FOUND,
		PYTHON_MODULE_MISSING,
		NPM_MODULE_MISSING,
		PERMISSION_DENIED,
		COMPILATION_ERROR,
		NETWORK_ERROR,
		JAVA_CLASS_NOT_FOUND,
		BUILD_TOOL_ERROR,
		UNKNOWN
	}

	/**
	 * Analysis result containing error details.
	 */
	public static class ErrorAnalysis {
		private ErrorType type;
		private String originalCommand;
		private String output;
		private Integer exitCode;
		private String missingCommand;
		private String missingModule;
		private String suggestedFix;
		private List<String> alternativeCommands;

		public ErrorType getType() { return type; }
		public void setType(ErrorType type) { this.type = type; }

		public String getOriginalCommand() { return originalCommand; }
		public void setOriginalCommand(String originalCommand) { this.originalCommand = originalCommand; }

		public String getOutput() { return output; }
		public void setOutput(String output) { this.output = output; }

		public Integer getExitCode() { return exitCode; }
		public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

		public String getMissingCommand() { return missingCommand; }
		public void setMissingCommand(String missingCommand) { this.missingCommand = missingCommand; }

		public String getMissingModule() { return missingModule; }
		public void setMissingModule(String missingModule) { this.missingModule = missingModule; }

		public String getSuggestedFix() { return suggestedFix; }
		public void setSuggestedFix(String suggestedFix) { this.suggestedFix = suggestedFix; }

		public List<String> getAlternativeCommands() { return alternativeCommands; }
		public void setAlternativeCommands(List<String> alternativeCommands) { this.alternativeCommands = alternativeCommands; }

		public boolean isRecoverable() {
			return type == ErrorType.COMMAND_NOT_FOUND ||
				   type == ErrorType.PYTHON_MODULE_MISSING ||
				   type == ErrorType.NPM_MODULE_MISSING ||
				   type == ErrorType.PERMISSION_DENIED;
		}
	}

	/**
	 * Suggestion for recovering from an error.
	 */
	public static class RecoverySuggestion {
		private ErrorAnalysis analysis;
		private boolean canAutoFix;
		private String autoFixCommand;
		private String message;

		public ErrorAnalysis getAnalysis() { return analysis; }
		public void setAnalysis(ErrorAnalysis analysis) { this.analysis = analysis; }

		public boolean isCanAutoFix() { return canAutoFix; }
		public void setCanAutoFix(boolean canAutoFix) { this.canAutoFix = canAutoFix; }

		public String getAutoFixCommand() { return autoFixCommand; }
		public void setAutoFixCommand(String autoFixCommand) { this.autoFixCommand = autoFixCommand; }

		public String getMessage() { return message; }
		public void setMessage(String message) { this.message = message; }
	}
}
