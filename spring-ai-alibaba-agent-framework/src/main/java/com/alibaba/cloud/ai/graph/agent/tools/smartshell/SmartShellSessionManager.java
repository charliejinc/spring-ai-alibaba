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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Enhanced shell session manager with automatic error detection and recovery.
 * Similar to Claude's execution model - automatically fixes common issues.
 */
public class SmartShellSessionManager {

	private static final Logger log = LoggerFactory.getLogger(SmartShellSessionManager.class);
	private static final String DONE_MARKER_PREFIX = "__SMART_SHELL_DONE__";
	private static final String SESSION_INSTANCE_CONTEXT_KEY = "_SMART_SHELL_SESSION_";
	private static final String SESSION_PATH_CONTEXT_KEY = "_SMART_SHELL_PATH_";

	private final Path workspaceRoot;
	private final boolean useTemporaryWorkspace;
	private final List<String> startupCommands;
	private final List<String> shutdownCommands;
	private final long commandTimeout;
	private final long startupTimeout;
	private final long terminationTimeout;
	private final int maxOutputLines;
	private final Long maxOutputBytes;
	private final Map<String, String> environment;
	private final boolean autoFixEnabled;
	private final boolean tryAlternativeShells;
	private final List<ShellEnvironment> availableShells;
	private final boolean timeoutRetryEnabled;
	private final int timeoutMaxRetries;
	private final long timeoutRetryDelayMs;

	private final ShellEnvironmentDetector detector;

	private SmartShellSessionManager(Builder builder) {
		this.workspaceRoot = builder.workspaceRoot;
		this.useTemporaryWorkspace = builder.workspaceRoot == null;
		this.startupCommands = new ArrayList<>(builder.startupCommands);
		this.shutdownCommands = new ArrayList<>(builder.shutdownCommands);
		this.commandTimeout = builder.commandTimeout;
		this.startupTimeout = builder.startupTimeout;
		this.terminationTimeout = builder.terminationTimeout;
		this.maxOutputLines = builder.maxOutputLines;
		this.maxOutputBytes = builder.maxOutputBytes;
		this.environment = new HashMap<>(builder.environment);
		this.autoFixEnabled = builder.autoFixEnabled;
		this.tryAlternativeShells = builder.tryAlternativeShells;
		this.timeoutRetryEnabled = builder.timeoutRetryEnabled;
		this.timeoutMaxRetries = builder.timeoutMaxRetries;
		this.timeoutRetryDelayMs = builder.timeoutRetryDelayMs;
		this.detector = new ShellEnvironmentDetector();
		this.availableShells = new ArrayList<>(builder.preferredShells);

		// If no preferred shells specified, detect available ones
		if (this.availableShells.isEmpty()) {
			this.availableShells.addAll(detector.detectAvailableShells());
		}

		if (this.availableShells.isEmpty()) {
			throw new RuntimeException("No shell environment available on this system");
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Get the current shell environment type.
	 */
	public ShellEnvironment.Type getCurrentShellType(RunnableConfig config) {
		SmartShellSession session = (SmartShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		if (session != null) {
			ShellEnvironment shell = session.getCurrentShell();
			if (shell != null) {
				return shell.getType();
			}
		}
		// Fallback to primary shell
		if (!availableShells.isEmpty()) {
			return availableShells.get(0).getType();
		}
		return null;
	}

	/**
	 * Initialize shell session with the best available shell.
	 */
	public void initialize(RunnableConfig config) {
		ShellEnvironment primaryShell = availableShells.get(0);
		log.info("Initializing smart shell session with: {}", primaryShell.getType());

		try {
			Path workspace = workspaceRoot;
			if (useTemporaryWorkspace) {
				Path tempDir = Files.createTempDirectory("smart_shell_");
				config.context().put(SESSION_PATH_CONTEXT_KEY, tempDir);
				workspace = tempDir;
			} else {
				Files.createDirectories(workspace);
			}

			SmartShellSession session = new SmartShellSession(workspace, primaryShell, availableShells);
			session.start();
			config.context().put(SESSION_INSTANCE_CONTEXT_KEY, session);

			log.info("Smart shell session started in workspace: {} using {}", workspace, primaryShell.getType());

			// Run startup commands
			for (String command : startupCommands) {
				CommandResult result = session.execute(command, startupTimeout, maxOutputLines, maxOutputBytes, null, null);
				if (result.isTimedOut() || (result.getExitCode() != null && result.getExitCode() != 0)) {
					log.warn("Startup command failed: {}, exit code: {}", command, result.getExitCode());
					// Don't throw - let the session continue
				}
			}
		} catch (Exception e) {
			cleanup(config);
			throw new RuntimeException("Failed to initialize smart shell session", e);
		}
	}

	/**
	 * Clean up shell session.
	 */
	public void cleanup(RunnableConfig config) {
		try {
			SmartShellSession session = (SmartShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
			if (session != null) {
				// Run shutdown commands
				for (String command : shutdownCommands) {
					try {
						session.execute(command, commandTimeout, maxOutputLines, maxOutputBytes, null, null);
					} catch (Exception e) {
						log.warn("Shutdown command failed: {}", command, e);
					}
				}
			}
		} finally {
			doCleanup(config);
		}
	}

	private void doCleanup(RunnableConfig config) {
		SmartShellSession session = (SmartShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		if (session != null) {
			session.stop(terminationTimeout);
			config.context().remove(SESSION_INSTANCE_CONTEXT_KEY);
		}

		Path tempDir = (Path) config.context().get(SESSION_PATH_CONTEXT_KEY);
		if (tempDir != null) {
			try {
				deleteDirectory(tempDir);
			} catch (IOException e) {
				log.warn("Failed to delete temporary directory: {}", tempDir, e);
			}
			config.context().remove(SESSION_PATH_CONTEXT_KEY);
		}
	}

	/**
	 * Execute a command with automatic error detection and optional recovery.
	 */
	public SmartCommandResult executeCommand(String command, RunnableConfig config) {
		return executeCommand(command, config, autoFixEnabled, null, null, commandTimeout);
	}

	/**
	 * Execute a command with optional auto-fix.
	 */
	public SmartCommandResult executeCommand(String command, RunnableConfig config, boolean attemptAutoFix) {
		return executeCommand(command, config, attemptAutoFix, null, null, commandTimeout);
	}

	/**
	 * Execute a command with full control over execution parameters.
	 *
	 * @param command the command to execute
	 * @param config the runnable configuration
	 * @param attemptAutoFix whether to attempt auto-fix on failure
	 * @param cwd temporary working directory (null to use session default)
	 * @param env temporary environment variables (null to use session default)
	 * @param timeoutMs timeout in milliseconds (0 or negative to use default)
	 * @return the smart command result
	 */
	public SmartCommandResult executeCommand(String command, RunnableConfig config, boolean attemptAutoFix,
										   String cwd, Map<String, String> env, long timeoutMs) {
		SmartShellSession session = (SmartShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		if (session == null) {
			throw new IllegalStateException("Shell session not initialized. Call initialize() first.");
		}

		long effectiveTimeout = timeoutMs > 0 ? timeoutMs : commandTimeout;

		log.info("Executing command: {} (cwd={}, timeout={}ms)", command, cwd != null ? cwd : "default", effectiveTimeout);

		// Execute the command with timeout retry logic
		CommandResult result = executeWithTimeoutRetry(session, command, effectiveTimeout, cwd, env);

		// Analyze the result
		ErrorRecoveryStrategy analyzer = new ErrorRecoveryStrategy(session.getCurrentShell());
		ErrorRecoveryStrategy.ErrorAnalysis analysis = analyzer.analyze(command, result.getOutput(), result.getExitCode());

		SmartCommandResult smartResult = new SmartCommandResult(result, analysis);

		// If command timed out but retry succeeded, mark it
		if (result.isSuccess() && smartResult.wasRetried()) {
			smartResult.setRetryCount(smartResult.getRetryCount());
		}

		// If command failed and auto-fix is enabled, try to recover
		if (!result.isSuccess() && attemptAutoFix && analysis.isRecoverable()) {
			ErrorRecoveryStrategy.RecoverySuggestion suggestion = analyzer.suggestRecovery(analysis);

			if (suggestion.isCanAutoFix() && suggestion.getAutoFixCommand() != null) {
				log.info("Attempting auto-fix for command: {}", command);

				// Try the fix
				CommandResult fixResult = session.execute(suggestion.getAutoFixCommand(), commandTimeout, maxOutputLines, maxOutputBytes, null, null);

				if (fixResult.isSuccess()) {
					// Retry the original command
					log.info("Auto-fix successful, retrying original command");
					result = session.execute(command, commandTimeout, maxOutputLines, maxOutputBytes, null, null);

					// Re-analyze
					analysis = analyzer.analyze(command, result.getOutput(), result.getExitCode());
					smartResult = new SmartCommandResult(result, analysis);
					smartResult.setAutoFixed(true);
					smartResult.setFixCommand(suggestion.getAutoFixCommand());
				}
			}
		}

		// If still failed and we have alternative shells, try them
		if (!smartResult.isSuccess() && tryAlternativeShells && session.hasAlternativeShells()) {
			log.info("Trying alternative shells for failed command");
			SmartCommandResult alternativeResult = tryAlternativeShells(command, session, analysis, cwd, env, timeoutMs);
			if (alternativeResult != null && alternativeResult.isSuccess()) {
				return alternativeResult;
			}
		}

		return smartResult;
	}

	/**
	 * Execute command with automatic retry on timeout.
	 * Uses exponential backoff between retries.
	 */
	private CommandResult executeWithTimeoutRetry(SmartShellSession session, String command, long timeoutMs,
												  String cwd, Map<String, String> env) {
		if (!timeoutRetryEnabled || timeoutMaxRetries <= 0) {
			return session.execute(command, timeoutMs, maxOutputLines, maxOutputBytes, cwd, env);
		}

		int retryCount = 0;
		long currentDelay = timeoutRetryDelayMs;

		while (true) {
			CommandResult result = session.execute(command, timeoutMs, maxOutputLines, maxOutputBytes, cwd, env);

			// If succeeded or not a timeout, return result
			if (!result.isTimedOut()) {
				if (retryCount > 0) {
					result.setRetryCount(retryCount);
				}
				return result;
			}

			// Command timed out
			retryCount++;

			if (retryCount > timeoutMaxRetries) {
				log.warn("Command timed out after {} retries", timeoutMaxRetries);
				result.setRetryCount(retryCount);
				return result;
			}

			log.info("Command timed out, waiting {}ms before retry {}/{}", currentDelay, retryCount, timeoutMaxRetries);

			try {
				Thread.sleep(currentDelay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}

			// Exponential backoff
			currentDelay = currentDelay * 2;
		}

		// Return last result if interrupted
		return session.execute(command, timeoutMs, maxOutputLines, maxOutputBytes, cwd, env);
	}

	/**
	 * Try the command in alternative shells.
	 */
	private SmartCommandResult tryAlternativeShells(String command, SmartShellSession session,
												ErrorRecoveryStrategy.ErrorAnalysis analysis,
												String cwd, Map<String, String> env, long timeoutMs) {
		List<String> alternatives = analysis.getAlternativeCommands();
		long effectiveTimeout = timeoutMs > 0 ? timeoutMs : commandTimeout;

		for (String alternative : alternatives) {
			log.info("Trying alternative command: {}", alternative);
			CommandResult result = session.executeInNewShell(alternative, effectiveTimeout, maxOutputLines, maxOutputBytes, cwd, env);

			if (result.isSuccess()) {
				log.info("Alternative command succeeded");
				ErrorRecoveryStrategy analyzer = new ErrorRecoveryStrategy(session.getCurrentShell());
				ErrorRecoveryStrategy.ErrorAnalysis newAnalysis = analyzer.analyze(alternative, result.getOutput(), result.getExitCode());
				SmartCommandResult smartResult = new SmartCommandResult(result, newAnalysis);
				smartResult.setUsedAlternativeCommand(true);
				smartResult.setAlternativeCommand(alternative);
				return smartResult;
			}
		}

		return null;
	}

	/**
	 * Check if a command exists in the current shell.
	 * Uses both shell session and direct system check for robustness.
	 */
	public boolean commandExists(String command, RunnableConfig config) {
		SmartShellSession session = (SmartShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		if (session == null) {
			return false;
		}

		// First try via shell session (may have PATH issues)
		String baseCommand = command.split("\\s+")[0];
		String checkCommand = session.getCurrentShell().getCheckCommand(baseCommand);
		CommandResult result = session.execute(checkCommand, 10000, 10, null, null, null);
		if (result.isSuccess()) {
			return true;
		}

		// Fallback: try direct system check (bypasses shell session PATH issues)
		log.debug("Shell session check failed for '{}', trying direct system check", baseCommand);
		return commandExistsDirect(baseCommand);
	}

	/**
	 * Directly check if a command exists in system PATH.
	 * On Windows, refreshes PATH from registry to detect newly-installed commands.
	 */
	private boolean commandExistsDirect(String command) {
		String os = System.getProperty("os.name").toLowerCase();
		try {
			ProcessBuilder pb;
			if (os.contains("windows")) {
				// Use cmd /c where with refreshed PATH from registry
				// This catches commands installed after JVM startup (e.g., pip after Python install)
				pb = new ProcessBuilder("cmd", "/c", "where", command);
				String freshPath = getWindowsFreshPath();
				if (freshPath != null) {
					pb.environment().put("PATH", freshPath);
				}
			} else {
				pb = new ProcessBuilder("which", command);
			}
			pb.redirectErrorStream(true);
			Process process = pb.start();
			boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
			return finished && process.exitValue() == 0;
		} catch (Exception e) {
			log.debug("Direct command check failed for '{}': {}", command, e.getMessage());
			return false;
		}
	}

	/**
	 * Get fresh PATH from Windows registry, combining system and user PATH.
	 * This captures PATH changes made after JVM startup (e.g., new software installs).
	 */
	private static String getWindowsFreshPath() {
		try {
			// Query system PATH from registry
			String systemPath = queryRegistryPath(
				"HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", "Path");
			// Query user PATH from registry
			String userPath = queryRegistryPath("HKCU\\Environment", "Path");

			StringBuilder freshPath = new StringBuilder();
			if (systemPath != null && !systemPath.isEmpty()) {
				freshPath.append(systemPath);
			}
			if (userPath != null && !userPath.isEmpty()) {
				if (freshPath.length() > 0) {
					freshPath.append(";");
				}
				freshPath.append(userPath);
			}

			if (freshPath.length() > 0) {
				log.debug("Refreshed PATH from registry ({} chars)", freshPath.length());
				return freshPath.toString();
			}
		} catch (Exception e) {
			log.debug("Failed to refresh PATH from registry: {}", e.getMessage());
		}
		return null;
	}

	/**
	 * Query a value from the Windows registry using reg query.
	 */
	private static String queryRegistryPath(String key, String valueName) {
		try {
			ProcessBuilder pb = new ProcessBuilder("reg", "query", key, "/v", valueName);
			pb.redirectErrorStream(true);
			Process process = pb.start();
			boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
			if (finished && process.exitValue() == 0) {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(process.getInputStream()))) {
					String line;
					while ((line = reader.readLine()) != null) {
						// Format: "    Path    REG_EXPAND_SZ    C:\Windows\system32;..."
						line = line.trim();
						if (line.contains("REG_SZ") || line.contains("REG_EXPAND_SZ")) {
							int typeEnd = line.indexOf("REG_SZ");
							if (typeEnd == -1) {
								typeEnd = line.indexOf("REG_EXPAND_SZ");
								if (typeEnd != -1) {
									typeEnd += "REG_EXPAND_SZ".length();
								}
							} else {
								typeEnd += "REG_SZ".length();
							}
							if (typeEnd != -1 && typeEnd < line.length()) {
								String value = line.substring(typeEnd).trim();
								// Expand %USERPROFILE% and similar variables
								value = expandEnvironmentVariables(value);
								return value;
							}
						}
					}
				}
			}
		} catch (Exception e) {
			log.debug("Registry query failed for {}: {}", key, e.getMessage());
		}
		return null;
	}

	/**
	 * Expand common Windows environment variables in a string.
	 */
	private static String expandEnvironmentVariables(String value) {
		if (value == null || !value.contains("%")) {
			return value;
		}
		// Expand known environment variables
		Map<String, String> currentEnv = System.getenv();
		for (Map.Entry<String, String> entry : currentEnv.entrySet()) {
			String varPattern = "%" + entry.getKey() + "%";
			if (value.contains(varPattern)) {
				value = value.replace(varPattern, entry.getValue());
			}
		}
		return value;
	}

	/**
	 * Restart the shell session.
	 */
	public void restartSession(RunnableConfig config) {
		SmartShellSession session = (SmartShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		if (session == null) {
			throw new IllegalStateException("Shell session not initialized.");
		}

		log.info("Restarting smart shell session");
		session.restart();

		// Re-run startup commands
		for (String command : startupCommands) {
			session.execute(command, startupTimeout, maxOutputLines, maxOutputBytes, null, null);
		}
	}

	/**
	 * Switch to a different shell environment.
	 */
	public boolean switchShell(ShellEnvironment.Type shellType, RunnableConfig config) {
		SmartShellSession session = (SmartShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		if (session == null) {
			return false;
		}

		return session.switchToShell(shellType);
	}

	public List<ShellEnvironment> getAvailableShells() {
		return List.copyOf(availableShells);
	}

	public int getMaxOutputLines() {
		return maxOutputLines;
	}

	public Long getMaxOutputBytes() {
		return maxOutputBytes;
	}

	private void deleteDirectory(Path directory) throws IOException {
		if (Files.exists(directory)) {
			try (var stream = Files.walk(directory)) {
				stream.sorted(Comparator.reverseOrder())
					.forEach(path -> {
						try {
							Files.delete(path);
						} catch (IOException e) {
							log.warn("Failed to delete: {}", path, e);
						}
					});
			}
		}
	}

	/**
	 * Smart shell session that can switch between shells.
	 */
	private class SmartShellSession {
		private final Path workspace;
		private final List<ShellEnvironment> allShells;
		private ShellEnvironment currentShell;
		private ShellSession activeSession;

		SmartShellSession(Path workspace, ShellEnvironment primaryShell, List<ShellEnvironment> allShells) {
			this.workspace = workspace;
			this.currentShell = primaryShell;
			this.allShells = allShells;
		}

		void start() throws IOException {
			this.activeSession = new ShellSession(workspace, currentShell);
			this.activeSession.start();
		}

		void restart() {
			stop(terminationTimeout);
			try {
				start();
			} catch (IOException e) {
				throw new RuntimeException("Failed to restart shell session", e);
			}
		}

		void stop(long timeoutMs) {
			if (activeSession != null) {
				activeSession.stop(timeoutMs);
			}
		}

		CommandResult execute(String command, long timeoutMs, int maxOutputLines, Long maxOutputBytes,
							  String cwd, Map<String, String> env) {
			if (activeSession == null || !activeSession.isAlive()) {
				// Session is not running, try to restart it
				log.warn("Shell session is not running, attempting to restart...");
				try {
					restart();
				} catch (RuntimeException e) {
					throw new IllegalStateException("Shell session is not running and failed to restart: " + e.getMessage());
				}
			}
			return activeSession.execute(command, timeoutMs, maxOutputLines, maxOutputBytes, cwd, env);
		}

		CommandResult executeInNewShell(String command, long timeoutMs, int maxOutputLines, Long maxOutputBytes,
										String cwd, Map<String, String> env) {
			// Create a temporary session for this command
			try {
				ShellSession tempSession = new ShellSession(workspace, currentShell);
				tempSession.start();
				CommandResult result = tempSession.execute(command, timeoutMs, maxOutputLines, maxOutputBytes, cwd, env);
				tempSession.stop(terminationTimeout);
				return result;
			} catch (IOException e) {
				return new CommandResult("Failed to create temporary shell: " + e.getMessage(), 1, false, false, false, 0, 0);
			}
		}

		boolean switchToShell(ShellEnvironment.Type shellType) {
			ShellEnvironment newShell = allShells.stream()
				.filter(s -> s.getType() == shellType)
				.findFirst()
				.orElse(null);

			if (newShell == null) {
				return false;
			}

			stop(terminationTimeout);
			this.currentShell = newShell;
			try {
				start();
				return true;
			} catch (IOException e) {
				log.error("Failed to switch to shell: {}", shellType, e);
				return false;
			}
		}

		/**
		 * Get the current shell environment.
		 */
		public ShellEnvironment getCurrentShell() {
			return currentShell;
		}

		boolean hasAlternativeShells() {
			return allShells.size() > 1;
		}
	}

	/**
	 * Individual shell session.
	 */
	private static class ShellSession {
		private final Path workspace;
		private final ShellEnvironment shellEnv;
		private Process process;
		private BufferedWriter stdin;
		private BlockingQueue<OutputLine> outputQueue;
		private volatile boolean terminated;

		ShellSession(Path workspace, ShellEnvironment shellEnv) {
			this.workspace = workspace;
			this.shellEnv = shellEnv;
			this.outputQueue = new LinkedBlockingQueue<>();
		}

		void start() throws IOException {
			ProcessBuilder pb = new ProcessBuilder(shellEnv.getCommand());
			pb.directory(workspace.toFile());
			// Only add environment variables from shell config, don't replace inherited PATH
			Map<String, String> additionalEnv = shellEnv.getEnvironment();
			if (additionalEnv != null && !additionalEnv.isEmpty()) {
				pb.environment().putAll(additionalEnv);
			}
			// On Windows, refresh PATH from registry to pick up newly-installed commands
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("windows")) {
				String freshPath = getWindowsFreshPath();
				if (freshPath != null) {
					pb.environment().put("PATH", freshPath);
				}
			}
			pb.redirectErrorStream(false);

			process = pb.start();
			stdin = new BufferedWriter(EncodingUtils.createOutputStreamWriter(process.getOutputStream()));

			// Start stdout reader
			new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(EncodingUtils.createInputStreamReader(process.getInputStream()))) {
					String line;
					while ((line = reader.readLine()) != null) {
						outputQueue.offer(new OutputLine("stdout", line));
					}
				} catch (IOException e) {
					log.debug("Stdout reader terminated", e);
				} finally {
					outputQueue.offer(new OutputLine("stdout", null));
				}
			}, "smart-shell-stdout-reader").start();

			// Start stderr reader
			new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(EncodingUtils.createInputStreamReader(process.getErrorStream()))) {
					String line;
					while ((line = reader.readLine()) != null) {
						outputQueue.offer(new OutputLine("stderr", line));
					}
				} catch (IOException e) {
					log.debug("Stderr reader terminated", e);
				} finally {
					outputQueue.offer(new OutputLine("stderr", null));
				}
			}, "smart-shell-stderr-reader").start();
		}

		boolean isAlive() {
			return process != null && process.isAlive();
		}

		CommandResult execute(String command, long timeoutMs, int maxOutputLines, Long maxOutputBytes,
							  String cwd, Map<String, String> env) {
			if (!isAlive()) {
				throw new IllegalStateException("Shell session is not running");
			}

			// Convert paths in command to appropriate format for the target shell
			String processedCommand = PathUtils.convertPathsInCommand(command, shellEnv.getType());

			// If cwd or env is specified, or if using WSL, we need to create a one-off process
			// WSL doesn't work well with interactive shell, use wsl <command> directly
			if ((cwd != null && !cwd.isEmpty()) || (env != null && !env.isEmpty())
					|| shellEnv.getType() == ShellEnvironment.Type.WSL) {
				return executeWithTempSettings(processedCommand, timeoutMs, maxOutputLines, maxOutputBytes, cwd, env);
			}

			String marker = DONE_MARKER_PREFIX + UUID.randomUUID().toString().replace("-", "");
			long deadline = System.currentTimeMillis() + timeoutMs;

			try {
				outputQueue.clear();

				// Send command (use processedCommand with converted paths)
				stdin.write(processedCommand);
				if (!processedCommand.endsWith("\n")) {
					stdin.write("\n");
				}

				// Send marker with exit code
				stdin.write(shellEnv.formatMarkerCommand(marker));
				stdin.write("\n");
				stdin.flush();

				return collectOutput(marker, deadline, maxOutputLines, maxOutputBytes);

			} catch (IOException e) {
				throw new RuntimeException("Failed to execute command", e);
			}
		}

		/**
		 * Execute command with temporary working directory and/or environment variables.
		 * Creates a one-off process for this command only.
		 */
		CommandResult executeWithTempSettings(String command, long timeoutMs, int maxOutputLines, Long maxOutputBytes,
											  String cwd, Map<String, String> env) {
			try {
				ProcessBuilder pb;
				String osName = System.getProperty("os.name").toLowerCase();
				boolean isWindows = osName.contains("windows");

				// Convert paths in command to appropriate format for the target shell
				String processedCommand = PathUtils.convertPathsInCommand(command, shellEnv.getType());

				// For WSL, use wsl -e bash -c 'command' format to properly execute commands
				if (shellEnv.getType() == ShellEnvironment.Type.WSL) {
					pb = new ProcessBuilder("wsl", "-e", "bash", "-c", processedCommand);
				} else if (isWindows && shellEnv.getType() == ShellEnvironment.Type.GIT_BASH) {
					// On Windows with Git Bash, use Git Bash to execute commands
					// The command is passed directly to bash -c
					pb = new ProcessBuilder(shellEnv.getExecutablePath(), "-c", processedCommand);

					// Set working directory
					if (cwd != null && !cwd.isEmpty()) {
						// Convert cwd to Windows format for ProcessBuilder
						pb.directory(new java.io.File(PathUtils.normalizePath(cwd)));
					} else {
						pb.directory(workspace.toFile());
					}

					// Merge environment variables - include Windows PATH for accessing Windows programs
					Map<String, String> mergedEnv = new HashMap<>(shellEnv.getEnvironment());
					if (env != null) {
						mergedEnv.putAll(env);
					}
					pb.environment().putAll(mergedEnv);
				} else {
					pb = new ProcessBuilder(shellEnv.getCommand());

					// Set working directory
					if (cwd != null && !cwd.isEmpty()) {
						pb.directory(new java.io.File(cwd));
					} else {
						pb.directory(workspace.toFile());
					}

					// Merge environment variables
					Map<String, String> mergedEnv = new HashMap<>(shellEnv.getEnvironment());
					if (env != null) {
						mergedEnv.putAll(env);
					}
					pb.environment().putAll(mergedEnv);
				}

				// On Windows, refresh PATH from registry to pick up newly-installed commands
				if (isWindows) {
					String freshPath = getWindowsFreshPath();
					if (freshPath != null) {
						pb.environment().put("PATH", freshPath);
					}
				}

				pb.redirectErrorStream(true);

				// Execute the command
				Process tempProcess = pb.start();

				// Write command to stdin only for interactive shells
				// For shells using -c parameter (WSL, Git Bash with -c), skip stdin writing
				boolean useCommandLine = shellEnv.getType() == ShellEnvironment.Type.WSL ||
						(isWindows && shellEnv.getType() == ShellEnvironment.Type.GIT_BASH);
				if (!useCommandLine) {
					try (BufferedWriter writer = new BufferedWriter(EncodingUtils.createOutputStreamWriter(tempProcess.getOutputStream()))) {
						writer.write(processedCommand);
						if (!processedCommand.endsWith("\n")) {
							writer.write("\n");
						}
						writer.write("exit");
						writer.write("\n");
						writer.flush();
					}
				}

				// Read output
				StringBuilder output = new StringBuilder();
				boolean finished = tempProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

				try (BufferedReader reader = new BufferedReader(EncodingUtils.createInputStreamReader(tempProcess.getInputStream()))) {
					String line;
					int lineCount = 0;
					long byteCount = 0;
					boolean truncatedByLines = false;
					boolean truncatedByBytes = false;

					while ((line = reader.readLine()) != null) {
						lineCount++;
						byteCount += line.getBytes().length + 1;

						if (lineCount <= maxOutputLines) {
							if (maxOutputBytes == null || byteCount <= maxOutputBytes) {
								output.append(line).append("\n");
							} else {
								truncatedByBytes = true;
							}
						} else {
							truncatedByLines = true;
						}
					}

					Integer exitCode = finished ? tempProcess.exitValue() : null;

					return new CommandResult(
						output.toString(),
						exitCode,
						!finished,
						truncatedByLines,
						truncatedByBytes,
						lineCount,
						byteCount
					);
				}

			} catch (Exception e) {
				return new CommandResult(
					"Failed to execute command with temp settings: " + e.getMessage(),
					1, false, false, false, 0, 0
				);
			}
		}

		private CommandResult collectOutput(String marker, long deadline, int maxOutputLines, Long maxOutputBytes) {
			List<String> lines = new ArrayList<>();
			int totalLines = 0;
			long totalBytes = 0;
			boolean truncatedByLines = false;
			boolean truncatedByBytes = false;
			Integer exitCode = null;
			boolean timedOut = false;

			while (true) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) {
					timedOut = true;
					break;
				}

				try {
					OutputLine outputLine = outputQueue.poll(remaining, TimeUnit.MILLISECONDS);
					if (outputLine == null) {
						timedOut = true;
						break;
					}

					if (outputLine.content == null) {
						continue;
					}

					String line = outputLine.content;

					// Check for marker
					if ("stdout".equals(outputLine.source) && line.startsWith(marker)) {
						String[] parts = line.split(" ", 2);
						if (parts.length > 1) {
							try {
								exitCode = Integer.parseInt(parts[1].trim());
							} catch (NumberFormatException e) {
								// Ignore
							}
						}
						break;
					}

					totalLines++;

					String formattedLine = line;
					if ("stderr".equals(outputLine.source)) {
						formattedLine = "[stderr] " + line;
					}

					totalBytes += formattedLine.getBytes().length + 1;

					if (totalLines <= maxOutputLines) {
						if (maxOutputBytes == null || totalBytes <= maxOutputBytes) {
							lines.add(formattedLine);
						} else {
							truncatedByBytes = true;
						}
					} else {
						truncatedByLines = true;
					}

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}

			String output = String.join("\n", lines);
			return new CommandResult(output, exitCode, timedOut, truncatedByLines, truncatedByBytes, totalLines, totalBytes);
		}

		void stop(long timeoutMs) {
			if (process == null || !process.isAlive()) {
				return;
			}

			terminated = true;
			try {
				stdin.write("exit\n");
				stdin.flush();
			} catch (IOException e) {
				log.debug("Failed to send exit command", e);
			}

			try {
				if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
					process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				process.destroyForcibly();
			}

			try {
				stdin.close();
			} catch (IOException e) {
				log.debug("Failed to close stdin", e);
			}
		}
	}

	private record OutputLine(String source, String content) {}

	/**
	 * Result of command execution with error analysis.
	 */
	public static class SmartCommandResult {
		private final CommandResult baseResult;
		private final ErrorRecoveryStrategy.ErrorAnalysis analysis;
		private boolean autoFixed;
		private String fixCommand;
		private boolean usedAlternativeCommand;
		private String alternativeCommand;

		public SmartCommandResult(CommandResult baseResult, ErrorRecoveryStrategy.ErrorAnalysis analysis) {
			this.baseResult = baseResult;
			this.analysis = analysis;
		}

		public CommandResult getBaseResult() { return baseResult; }
		public ErrorRecoveryStrategy.ErrorAnalysis getAnalysis() { return analysis; }

		public boolean isAutoFixed() { return autoFixed; }
		public void setAutoFixed(boolean autoFixed) { this.autoFixed = autoFixed; }

		public String getFixCommand() { return fixCommand; }
		public void setFixCommand(String fixCommand) { this.fixCommand = fixCommand; }

		public boolean isUsedAlternativeCommand() { return usedAlternativeCommand; }
		public void setUsedAlternativeCommand(boolean usedAlternativeCommand) { this.usedAlternativeCommand = usedAlternativeCommand; }

		public String getAlternativeCommand() { return alternativeCommand; }
		public void setAlternativeCommand(String alternativeCommand) { this.alternativeCommand = alternativeCommand; }

		public int getRetryCount() { return baseResult.getRetryCount(); }
		public void setRetryCount(int count) { baseResult.setRetryCount(count); }
		public boolean wasRetried() { return baseResult.wasRetried(); }

		public boolean isSuccess() {
			return baseResult.isSuccess();
		}

		public String getOutput() {
			return baseResult.getOutput();
		}

		public Integer getExitCode() {
			return baseResult.getExitCode();
		}

		public boolean isTimedOut() {
			return baseResult.isTimedOut();
		}

		public String getFormattedOutput() {
			StringBuilder sb = new StringBuilder();
			sb.append(baseResult.getOutput());

			if (autoFixed) {
				sb.append("\n\n[Auto-fixed by installing missing dependency]");
			}
			if (usedAlternativeCommand) {
				sb.append("\n\n[Used alternative command: ").append(alternativeCommand).append("]");
			}

			return sb.toString();
		}
	}

	/**
	 * Basic command result.
	 */
	public static class CommandResult {
		private final String output;
		private final Integer exitCode;
		private final boolean timedOut;
		private final boolean truncatedByLines;
		private final boolean truncatedByBytes;
		private final int totalLines;
		private final long totalBytes;
		private int retryCount = 0;

		public CommandResult(String output, Integer exitCode, boolean timedOut,
							 boolean truncatedByLines, boolean truncatedByBytes,
							 int totalLines, long totalBytes) {
			this.output = output;
			this.exitCode = exitCode;
			this.timedOut = timedOut;
			this.truncatedByLines = truncatedByLines;
			this.truncatedByBytes = truncatedByBytes;
			this.totalLines = totalLines;
			this.totalBytes = totalBytes;
		}

		public String getOutput() { return output; }
		public Integer getExitCode() { return exitCode; }
		public boolean isTimedOut() { return timedOut; }
		public boolean isTruncatedByLines() { return truncatedByLines; }
		public boolean isTruncatedByBytes() { return truncatedByBytes; }
		public int getTotalLines() { return totalLines; }
		public long getTotalBytes() { return totalBytes; }
		public int getRetryCount() { return retryCount; }
		public void setRetryCount(int count) { this.retryCount = count; }
		public boolean wasRetried() { return retryCount > 0; }

		public boolean isSuccess() {
			return !timedOut && (exitCode == null || exitCode == 0);
		}
	}

	/**
	 * Builder for SmartShellSessionManager.
	 */
	public static class Builder {
		private Path workspaceRoot;
		private List<String> startupCommands = new ArrayList<>();
		private List<String> shutdownCommands = new ArrayList<>();
		private long commandTimeout = 60000;
		private long startupTimeout = 10000;
		private long terminationTimeout = 5000;
		private int maxOutputLines = 1000;
		private Long maxOutputBytes = null;
		private Map<String, String> environment = new HashMap<>();
		private boolean autoFixEnabled = true;
		private boolean tryAlternativeShells = true;
		private List<ShellEnvironment> preferredShells = new ArrayList<>();

		// Timeout retry configuration
		private boolean timeoutRetryEnabled = true;
		private int timeoutMaxRetries = 2;
		private long timeoutRetryDelayMs = 5000; // 5 seconds initial delay

		public Builder workspaceRoot(String path) {
			this.workspaceRoot = Path.of(path);
			return this;
		}

		public Builder workspaceRoot(Path path) {
			this.workspaceRoot = path;
			return this;
		}

		public Builder addStartupCommand(String command) {
			this.startupCommands.add(command);
			return this;
		}

		public Builder addShutdownCommand(String command) {
			this.shutdownCommands.add(command);
			return this;
		}

		public Builder setStartupCommands(List<String> commands) {
			this.startupCommands.addAll(commands);
			return this;
		}

		public Builder setShutdownCommands(List<String> commands) {
			this.shutdownCommands.addAll(commands);
			return this;
		}

		public Builder commandTimeout(long millis) {
			this.commandTimeout = millis;
			return this;
		}

		public Builder startupTimeout(long millis) {
			this.startupTimeout = millis;
			return this;
		}

		public Builder terminationTimeout(long millis) {
			this.terminationTimeout = millis;
			return this;
		}

		public Builder maxOutputLines(int lines) {
			this.maxOutputLines = lines;
			return this;
		}

		public Builder maxOutputBytes(long bytes) {
			this.maxOutputBytes = bytes;
			return this;
		}

		public Builder environment(Map<String, String> env) {
			this.environment.putAll(env);
			return this;
		}

		public Builder autoFixEnabled(boolean enabled) {
			this.autoFixEnabled = enabled;
			return this;
		}

		public Builder tryAlternativeShells(boolean tryAlternatives) {
			this.tryAlternativeShells = tryAlternatives;
			return this;
		}

		public Builder preferredShell(ShellEnvironment shell) {
			this.preferredShells.add(shell);
			return this;
		}

		public Builder preferredShells(List<ShellEnvironment> shells) {
			this.preferredShells.addAll(shells);
			return this;
		}

		public Builder timeoutRetryEnabled(boolean enabled) {
			this.timeoutRetryEnabled = enabled;
			return this;
		}

		public Builder timeoutMaxRetries(int maxRetries) {
			this.timeoutMaxRetries = maxRetries;
			return this;
		}

		public Builder timeoutRetryDelayMs(long delayMs) {
			this.timeoutRetryDelayMs = delayMs;
			return this;
		}

		public SmartShellSessionManager build() {
			return new SmartShellSessionManager(this);
		}
	}
}
