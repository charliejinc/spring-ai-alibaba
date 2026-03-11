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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;

/**
 * A unified smart shell tool that combines the features of SmartShellTool and
 * EnhancedSmartShellTool.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Auto-detects available shells (PowerShell, cmd, WSL, Git Bash, bash, zsh)</li>
 * <li>Analyzes command failures and suggests fixes</li>
 * <li>Auto-installs missing dependencies (optional)</li>
 * <li>Switches to alternative shells when commands fail</li>
 * <li>Provides detailed error analysis and recovery suggestions</li>
 * <li>Supports SSH execution with password authentication via sshpass</li>
 * <li>Supports special URI formats (ssh://, docker://, wsl://)</li>
 * <li>Command availability checking with auto-installation</li>
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * SmartShellTool shellTool = SmartShellTool.builder("/tmp/workspace")
 *     .withAutoFix(true)
 *     .withTryAlternativeShells(true)
 *     .withAutoInstall(true)
 *     .build();
 * </pre>
 */
public class SmartShellTool {

	private static final Logger log = LoggerFactory.getLogger(SmartShellTool.class);

	public static final String DEFAULT_TOOL_DESCRIPTION = """
		Execute shell commands with intelligent error handling and automatic recovery.

		This tool automatically:
		1. Detects the best available shell (PowerShell, cmd, WSL, Git Bash, bash, zsh)
		2. Analyzes command failures and provides detailed error information
		3. Attempts automatic fixes for common issues (missing commands, Python modules, npm packages)
		4. Suggests installation commands for missing dependencies
		5. Falls back to alternative shells when commands fail
		6. Supports SSH execution via sshpass (automatic password authentication)
		7. Supports database connections (MySQL, PostgreSQL, MongoDB, Redis)

		Special URI formats supported:
		- ssh://user@host/command     - Execute on remote host via SSH
		- docker://container/command  - Execute in Docker container
		- wsl://command               - Execute in WSL
		- db://type://user:pass@host:port/db?query - Execute database query

		The working directory is managed automatically. Use standard shell commands (ls/dir, pwd/cd)
		to navigate and inspect the filesystem.

		For long-running commands, output may be truncated. Commands exceeding the timeout
		will be terminated and the session restarted.
		""";

	private final SmartShellSessionManager sessionManager;
	private final SmartEnvironmentManager environmentManager;
	private final BackgroundTaskManager backgroundTaskManager;
	private final boolean autoFix;
	private final boolean verboseErrors;
	private final boolean autoInstall;

	private SmartShellTool(Builder builder) {
		this.sessionManager = builder.sessionManager;
		this.autoFix = builder.autoFix;
		this.verboseErrors = builder.verboseErrors;
		this.autoInstall = builder.autoInstall;
		// Create environment manager using a wrapper that delegates to sessionManager
		this.environmentManager = new SmartEnvironmentManager(new SessionManagerExecutorWrapper(sessionManager),
				autoInstall);
		// Create background task manager
		this.backgroundTaskManager = builder.backgroundTaskManager != null
				? builder.backgroundTaskManager
				: new BackgroundTaskManager();
	}

	/**
	 * Execute a shell command with intelligent error handling.
	 */
	// @formatter:off
	@Tool(name = "smart_shell", description = DEFAULT_TOOL_DESCRIPTION)
	public SmartShellResult executeShellCommand(
		@ToolParam(description = "The shell command to execute. Supports standard shell syntax and special URIs (ssh://, docker://, wsl://).") String command,
		@ToolParam(description = "Restart the shell session before executing (default: false).", required = false) Boolean restart,
		@ToolParam(description = "Enable auto-fix for this command (overrides default).", required = false) Boolean enableAutoFix,
		@ToolParam(description = "Try alternative shells if command fails (default: true).", required = false) Boolean tryAlternatives,
		ToolContext toolContext) { // @formatter:on

		try {
			RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);

			// Handle restart request
			if (Boolean.TRUE.equals(restart)) {
				log.info("Restarting smart shell session as requested");
				sessionManager.restartSession(config);
				if (command == null || command.trim().isEmpty()) {
					return SmartShellResult.success("Shell session restarted successfully.");
				}
			}

			if (command == null || command.trim().isEmpty()) {
				return SmartShellResult.error("Error: Command cannot be empty.");
			}

			// Parse command for special URI formats
			CommandParseResult parsed = parseCommand(command);

			// Handle special URI formats
			if (parsed.target == ExecutionTarget.SSH) {
				return executeSshCommand(parsed.host, parsed.port, parsed.user, parsed.password, parsed.remoteCommand,
						toolContext);
			}
			if (parsed.target == ExecutionTarget.DATABASE) {
				// Convert DatabaseResult to SmartShellResult
				DatabaseResult dbResult = executeDatabaseCommand(command, null, null, null, null, null, null, null, null,
						toolContext);
				return dbResult.isSuccess() ? SmartShellResult.success(dbResult.getOutput())
						: SmartShellResult.error(dbResult.getErrorMessage() != null ? dbResult.getErrorMessage()
								: dbResult.getOutput());
			}

			// Determine auto-fix setting
			boolean shouldAutoFix = enableAutoFix != null ? enableAutoFix : this.autoFix;
			boolean shouldTryAlternatives = tryAlternatives != null ? tryAlternatives : true;

			log.info("Executing smart shell command (autoFix={}): {}", shouldAutoFix, command);

			// Ensure dependencies are available
			if (autoInstall && parsed.requiredCommands != null && !parsed.requiredCommands.isEmpty()) {
				for (String cmd : parsed.requiredCommands) {
					SmartEnvironmentManager.CommandStatus status = environmentManager.ensureCommandAvailable(cmd, config);
					if (!status.isAvailable()) {
						return SmartShellResult.error(
								String.format("Command '%s' not available. %s", cmd, status.getMessage()),
								status.getInstallSuggestion());
					}
					if (status.wasInstalled()) {
						log.info("Auto-installed: {}", cmd);
					}
				}
			}

			// Execute with error analysis and recovery
			SmartShellSessionManager.SmartCommandResult result = sessionManager.executeCommand(command, config,
					shouldAutoFix);

			// Format the result
			return formatResult(result, command);

		}
		catch (Exception e) {
			log.error("Smart shell command execution failed", e);
			return SmartShellResult.error("Error executing command: " + e.getMessage());
		}
	}

	/**
	 * Execute a command on a remote host via SSH. Automatically sets up sshpass if
	 * needed for password authentication.
	 */
	@Tool(name = "smart_shell_ssh", description = """
		Execute a command on a remote host via SSH.

		Automatically handles SSH password authentication using sshpass.
		If sshpass is not available, it will be auto-installed (if autoInstall is enabled).

		Example:
		- host: 10.1.120.166
		- port: 22
		- username: root
		- password: secret
		- command: uname -a
		""")
	public SmartShellResult executeSshCommand(
		@ToolParam(description = "Remote host address") String host,
		@ToolParam(description = "SSH port (default: 22)", required = false) Integer port,
		@ToolParam(description = "Username") String username,
		@ToolParam(description = "Password (optional if using key auth)", required = false) String password,
		@ToolParam(description = "Command to execute on remote host") String command,
		ToolContext toolContext) {

		RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);
		int effectivePort = port != null ? port : 22;

		log.info("Executing SSH command on {}@{}:{}", username, host, effectivePort);

		try {
			// Ensure sshpass is available
			SmartEnvironmentManager.CommandStatus sshpassStatus = environmentManager.ensureCommandAvailable("sshpass",
					config);

			if (!sshpassStatus.isAvailable()) {
				return SmartShellResult.error("sshpass not available and auto-install failed",
						sshpassStatus.getInstallSuggestion());
			}

			// Build SSH command
			String sshCommand;
			if (password != null && !password.isEmpty()) {
				sshCommand = String.format(
						"wsl sshpass -p '%s' ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -p %d %s@%s '%s'",
						password, effectivePort, username, host, command.replace("'", "'\"'\"'"));
			}
			else {
				sshCommand = String.format(
						"wsl ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -p %d %s@%s '%s'", effectivePort, username,
						host, command.replace("'", "'\"'\"'"));
			}

			SmartShellSessionManager.SmartCommandResult result = sessionManager.executeCommand(sshCommand, config,
					autoFix);

			return formatResult(result, "ssh://" + username + "@" + host + "/" + command);

		}
		catch (Exception e) {
			log.error("SSH execution failed", e);
			return SmartShellResult.error("SSH execution failed: " + e.getMessage());
		}
	}

	/**
	 * Check what shell environments are available on the system.
	 */
	@Tool(name = "detect_shell_environments", description = """
		Detect and list all available shell environments on the system.

		Returns information about detected shells including:
		- Shell type (PowerShell, cmd, WSL, Git Bash, bash, zsh, sh)
		- Executable path
		- Priority (which shell is preferred)

		Use this to understand what shells are available before executing commands.
		""")
	public ShellEnvironmentList detectShellEnvironments() {
		List<ShellEnvironment> shells = sessionManager.getAvailableShells();

		List<ShellEnvironmentInfo> infoList = new ArrayList<>();
		for (ShellEnvironment shell : shells) {
			infoList.add(new ShellEnvironmentInfo(shell.getType().name(), shell.getExecutablePath(), shell.getPriority(),
					shell.isAvailable()));
		}

		return new ShellEnvironmentList(infoList);
	}

	/**
	 * Check if a specific command is available in the current shell.
	 */
	@Tool(name = "check_command_available", description = """
		Check if a specific command is available in the current shell environment.

		Returns true if the command exists and can be executed, false otherwise.
		Use this to verify prerequisites before running complex operations.
		""")
	public CommandAvailableResult checkCommandAvailable(
		@ToolParam(description = "The command to check (e.g., 'git', 'python', 'mvn')") String command,
		ToolContext toolContext) {

		try {
			RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);
			boolean available = sessionManager.commandExists(command, config);

			if (available) {
				return new CommandAvailableResult(true, "Command '" + command + "' is available.");
			}
			else {
				// Get installation suggestion
				ShellEnvironment shell = sessionManager.getAvailableShells().get(0);
				String suggestion = shell.getInstallSuggestion(command);
				return new CommandAvailableResult(false,
						"Command '" + command + "' is not available.\n\n" + suggestion);
			}
		}
		catch (Exception e) {
			return new CommandAvailableResult(false, "Error checking command availability: " + e.getMessage());
		}
	}

	/**
	 * Ensure a command is available, installing it if necessary.
	 */
	@Tool(name = "smart_shell_ensure", description = """
		Ensure a command is available, installing it if necessary.

		Returns the status of the command availability check.
		If autoInstall is true (or enabled by default), attempts to install missing commands.
		""")
	public EnsureResult ensure(
		@ToolParam(description = "Command to check (e.g., 'python', 'git')") String command,
		@ToolParam(description = "Auto-install if missing", required = false) Boolean install,
		ToolContext toolContext) {

		RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);
		boolean shouldInstall = install != null ? install : this.autoInstall;

		SmartEnvironmentManager envManager = new SmartEnvironmentManager(new SessionManagerExecutorWrapper(sessionManager),
				shouldInstall);
		SmartEnvironmentManager.CommandStatus status = envManager.ensureCommandAvailable(command, config);

		return new EnsureResult(status.getCommand(), status.isAvailable(), status.wasInstalled(), status.getMessage(),
				status.getInstallSuggestion());
	}

	/**
	 * Install a specific tool or dependency.
	 */
	@Tool(name = "smart_shell_install", description = """
		Install a specific tool or dependency.

		Automatically detects the best installation method for the current system.
		Supports: Python, Node.js, Java, Git, Docker, kubectl, and more.
		""")
	public InstallResult install(
		@ToolParam(description = "Tool to install (e.g., 'python', 'git', 'docker')") String tool,
		ToolContext toolContext) {

		RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);

		SmartEnvironmentManager.CommandStatus status = environmentManager.ensureCommandAvailable(tool, config);

		return new InstallResult(tool, status.isAvailable(), status.wasInstalled(), status.getMessage(),
				status.getInstallSuggestion());
	}

	/**
	 * Execute a database query or command.
	 *
	 * <p>
	 * Supports MySQL, PostgreSQL, MongoDB, and Redis. Automatically installs the
	 * required database client if missing (when autoInstall is enabled).
	 *
	 * <p>
	 * Connection can be specified either via URI or individual parameters.
	 *
	 * <p>
	 * URI format: db://type://user:password@host:port/database?query
	 *
	 * <p>
	 * Examples:
	 *
	 * <pre>
	 * // MySQL query via URI
	 * db://mysql://root:secret@localhost:3306/mydb?SELECT * FROM users
	 *
	 * // PostgreSQL query via URI
	 * db://postgresql://admin:pass@10.1.1.1:5432/prod?SELECT count(*) FROM orders
	 *
	 * // MongoDB command via URI
	 * db://mongodb://user:pass@localhost:27017/mydb?db.collection.find({})
	 *
	 * // Redis command via URI
	 * db://redis://:password@localhost:6379/0?KEYS *
	 * </pre>
	 */
	@Tool(name = "database_execute", description = """
		Execute a database query or command.

		Supports MySQL, PostgreSQL, MongoDB, and Redis.
		Automatically installs required database clients if missing.

		Connection methods (choose one):
		1. URI format: db://type://user:password@host:port/database?query
		2. Individual parameters: type, host, port, username, password, database, query

		Database types: mysql, postgresql, mongodb, redis

		Examples:
		- URI: db://mysql://root:secret@localhost:3306/mydb?SELECT * FROM users
		- Params: type=mysql, host=localhost, port=3306, database=testdb, query="SHOW TABLES"

		For MongoDB, use JavaScript syntax in the query parameter.
		For Redis, use standard Redis commands like "GET key" or "HGETALL hash".
		""")
	public DatabaseResult executeDatabaseCommand(
		@ToolParam(description = "Database URI (optional if using individual params). Format: db://type://user:pass@host:port/db?query", required = false) String uri,
		@ToolParam(description = "Database type: mysql, postgresql, mongodb, redis (required if not using URI)", required = false) String type,
		@ToolParam(description = "Database host (required if not using URI)", required = false) String host,
		@ToolParam(description = "Database port (optional, uses default for database type)", required = false) Integer port,
		@ToolParam(description = "Username (required if not using URI)", required = false) String username,
		@ToolParam(description = "Password (optional)", required = false) String password,
		@ToolParam(description = "Database name (required if not using URI)", required = false) String database,
		@ToolParam(description = "SQL query or command to execute (required if not using URI)", required = false) String query,
		@ToolParam(description = "Timeout in milliseconds (default: 30000)", required = false) Long timeout,
		ToolContext toolContext) {

		RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);
		long effectiveTimeout = timeout != null ? timeout : 30000;

		try {
			// Parse connection parameters
			DbConnectionParams params;
			if (uri != null && !uri.isEmpty()) {
				params = parseDatabaseUri(uri);
			}
			else {
				params = new DbConnectionParams(type, host, port, username, password, database, query);
			}

			// Validate required parameters
			String validationError = validateDbParams(params);
			if (validationError != null) {
				return DatabaseResult.error(validationError);
			}

			// Ensure database client is available
			String clientCommand = getDbClientCommand(params.type);
			SmartEnvironmentManager.CommandStatus clientStatus = environmentManager
				.ensureCommandAvailable(clientCommand, config);
			if (!clientStatus.isAvailable()) {
				return DatabaseResult.error("Database client '" + clientCommand + "' not available. "
						+ clientStatus.getMessage() + "\nInstall suggestion: " + clientStatus.getInstallSuggestion());
			}

			// Build the database command
			String dbCommand = buildDatabaseCommand(params);

			log.info("Executing database command on {}:{}/{} using {}", params.host, params.port, params.database,
					params.type);

			// Execute the command
			SmartShellSessionManager.SmartCommandResult result = sessionManager.executeCommand(dbCommand, config,
					autoFix);

			return new DatabaseResult(result.isSuccess(), result.getExitCode(), result.getOutput(), params.type,
					params.host, params.database, result.isTimedOut());

		}
		catch (Exception e) {
			log.error("Database command execution failed", e);
			return DatabaseResult.error("Database execution failed: " + e.getMessage());
		}
	}

	/**
	 * Test database connectivity without executing a query.
	 */
	@Tool(name = "database_test_connection", description = """
		Test database connectivity without executing a query.

		Supports MySQL, PostgreSQL, MongoDB, and Redis.
		Returns success if connection can be established.

		Use this to verify database credentials before running queries.
		""")
	public DatabaseResult testDatabaseConnection(
		@ToolParam(description = "Database type: mysql, postgresql, mongodb, redis") String type,
		@ToolParam(description = "Database host") String host,
		@ToolParam(description = "Database port (optional, uses default)", required = false) Integer port,
		@ToolParam(description = "Username") String username,
		@ToolParam(description = "Password (optional)", required = false) String password,
		@ToolParam(description = "Database name") String database,
		ToolContext toolContext) {

		RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);

		try {
			DbConnectionParams params = new DbConnectionParams(type, host, port, username, password, database, null);

			// Validate
			String validationError = validateDbParams(params);
			if (validationError != null) {
				return DatabaseResult.error(validationError);
			}

			// Ensure client is available
			String clientCommand = getDbClientCommand(type);
			if (!sessionManager.commandExists(clientCommand, config)) {
				return DatabaseResult.error("Database client '" + clientCommand + "' not found. "
						+ "Please install it first using the smart_shell_install tool.");
			}

			// Build test command
			String testCommand = buildTestConnectionCommand(params);

			SmartShellSessionManager.SmartCommandResult result = sessionManager.executeCommand(testCommand, config,
					autoFix);

			if (result.isSuccess()) {
				return DatabaseResult.success("Successfully connected to " + type + " at " + host + ":"
						+ (port != null ? port : getDefaultPort(type)) + "/" + database, type, host, database);
			}
			else {
				return DatabaseResult.error("Connection failed: " + result.getOutput());
			}

		}
		catch (Exception e) {
			return DatabaseResult.error("Connection test failed: " + e.getMessage());
		}
	}

	/**
	 * Execute a command in the background (non-blocking).
	 * Similar to Claude Code's run_in_background.
	 */
	@Tool(name = "run_in_background", description = """
		Execute a shell command in the background (non-blocking).

		The command starts running immediately but returns a task ID.
		Use list_background_tasks to check status and kill_background_task to stop it.

		Example use cases:
		- Start a long-running server (like npm dev, python server)
		- Run a build process while continuing other work
		- Start multiple independent commands in parallel

		Returns a task ID that can be used to:
		- Check status with list_background_tasks
		- Get output with get_background_task_output
		- Stop with kill_background_task

		Note: Background tasks have a maximum timeout of 10 minutes.
		""")
	public BackgroundTaskResult runInBackground(
			@ToolParam(description = "The shell command to execute in the background") String command,
			@ToolParam(description = "Optional description of what this task does", required = false) String description,
			@ToolParam(description = "Working directory for the command (optional)", required = false) String cwd,
			@ToolParam(description = "Timeout in milliseconds (max: 600000, default: 60000)", required = false) Long timeout,
			ToolContext toolContext) {

		RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);

		if (command == null || command.trim().isEmpty()) {
			return BackgroundTaskResult.error("Error: Command cannot be empty.");
		}

		long effectiveTimeout = timeout != null ? Math.min(timeout, 600000) : 60000;
		String effectiveCwd = cwd;
		String effectiveDescription = description != null ? description : "Background task: " + command;

		log.info("Starting background task: {} (timeout: {}ms)", command, effectiveTimeout);

		try {
			BackgroundTask task = backgroundTaskManager.submitTask(
					command,
					effectiveDescription,
					effectiveCwd,
					effectiveTimeout,
					(t) -> {
						// Execute the command using session manager with temporary cwd
						return sessionManager.executeCommand(
								command,
								config,
								autoFix,
								effectiveCwd,
								null,
								effectiveTimeout
						).getBaseResult();
					}
			);

			return BackgroundTaskResult.started(
					task.getTaskId(),
					"Background task started. Task ID: " + task.getTaskId() + "\n" +
					"Command: " + command + "\n" +
					"Description: " + effectiveDescription + "\n" +
					"Use list_background_tasks to check status or kill_background_task to stop."
			);
		}
		catch (Exception e) {
			log.error("Failed to start background task", e);
			return BackgroundTaskResult.error("Failed to start background task: " + e.getMessage());
		}
	}

	/**
	 * List all running background tasks.
	 * Similar to Claude Code's list_tasks.
	 */
	@Tool(name = "list_background_tasks", description = """
		List all running background tasks.

		Returns information about each background task including:
		- Task ID (used to get output or kill the task)
		- Command being executed
		- Status (RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT)
		- Start time
		- Duration (if completed)

		Use this to monitor long-running operations.
		""")
	public BackgroundTaskListResult listBackgroundTasks() {
		List<BackgroundTask> runningTasks = backgroundTaskManager.getRunningTasks();
		List<BackgroundTask> allTasks = backgroundTaskManager.getAllTasks();

		List<BackgroundTaskInfo> taskInfos = new ArrayList<>();
		for (BackgroundTask task : allTasks) {
			BackgroundTaskInfo info = new BackgroundTaskInfo(
					task.getTaskId(),
					task.getCommand(),
					task.getDescription(),
					task.getStatus().name(),
					task.getStartTime().toString(),
					task.getEndTime() != null ? task.getEndTime().toString() : null,
					task.getDurationMs(),
					task.getExitCode(),
					task.isRunning()
			);
			taskInfos.add(info);
		}

		return new BackgroundTaskListResult(
				runningTasks.size(),
				taskInfos,
				runningTasks.isEmpty()
						? "No running background tasks."
						: "Found " + runningTasks.size() + " running task(s)."
		);
	}

	/**
	 * Get the output of a background task (non-blocking).
	 * Returns current output even if task is still running.
	 */
	@Tool(name = "get_background_task_output", description = """
		Get the output of a background task.

		Can be called while task is running (non-blocking) or after completion.
		Returns the captured output up to this point.

		Use list_background_tasks first to get the task ID.
		""")
	public BackgroundTaskOutputResult getBackgroundTaskOutput(
			@ToolParam(description = "The task ID returned by run_in_background") String taskId) {

		if (taskId == null || taskId.trim().isEmpty()) {
			return BackgroundTaskOutputResult.error("Task ID is required.");
		}

		BackgroundTask task = backgroundTaskManager.getTask(taskId);
		if (task == null) {
			return BackgroundTaskOutputResult.error("Task not found: " + taskId);
		}

		BackgroundTask.Status status = task.getStatus();
		String output = task.getOutput();
		if (output == null || output.isEmpty()) {
			output = "(No output yet)";
		}

		return new BackgroundTaskOutputResult(
				taskId,
				status.name(),
				output,
				task.getExitCode(),
				task.isRunning(),
				task.getDurationMs()
		);
	}

	/**
	 * Wait for a background task to complete and get its output.
	 * Blocks until the task finishes or timeout occurs.
	 */
	@Tool(name = "wait_background_task", description = """
		Wait for a background task to complete and get its output.

		This is a blocking call that waits for the task to finish.
		Use a reasonable timeout to avoid hanging.

		Use list_background_tasks first to get the task ID.
		""")
	public BackgroundTaskOutputResult waitBackgroundTask(
			@ToolParam(description = "The task ID returned by run_in_background") String taskId,
			@ToolParam(description = "Wait timeout in milliseconds (default: 60000)", required = false) Long timeout) {

		if (taskId == null || taskId.trim().isEmpty()) {
			return BackgroundTaskOutputResult.error("Task ID is required.");
		}

		long waitTimeout = timeout != null ? timeout : 60000;

		SmartShellSessionManager.CommandResult result = backgroundTaskManager.waitForTask(taskId, waitTimeout);

		if (result == null) {
			// Check if task exists
			BackgroundTask task = backgroundTaskManager.getTask(taskId);
			if (task == null) {
				return BackgroundTaskOutputResult.error("Task not found: " + taskId);
			}
			return new BackgroundTaskOutputResult(
					taskId,
					task.getStatus().name(),
					"Task still running after " + waitTimeout + "ms timeout.",
					null,
					true,
					task.getDurationMs()
			);
		}

		String output = result.getOutput();
		if (output == null || output.isEmpty()) {
			output = "(No output)";
		}

		return new BackgroundTaskOutputResult(
				taskId,
				result.isSuccess() ? "COMPLETED" : "FAILED",
				output,
				result.getExitCode(),
				false,
				result.isTimedOut() ? -1 : (output.length() > 0 ? output.length() : 0)
		);
	}

	/**
	 * Kill a running background task.
	 * Similar to Claude Code's kill.
	 */
	@Tool(name = "kill_background_task", description = """
		Kill a running background task.

		Stops a background task that is currently executing.
		Use list_background_tasks to find the task ID.

		Returns success if the task was stopped, or if it had already completed.
		""")
	public BackgroundTaskResult killBackgroundTask(
			@ToolParam(description = "The task ID returned by run_in_background") String taskId) {

		if (taskId == null || taskId.trim().isEmpty()) {
			return BackgroundTaskResult.error("Task ID is required.");
		}

		BackgroundTask task = backgroundTaskManager.getTask(taskId);
		if (task == null) {
			return BackgroundTaskResult.error("Task not found: " + taskId);
		}

		if (task.isCompleted()) {
			return BackgroundTaskResult.success(
					"Task " + taskId + " has already completed with status: " + task.getStatus()
			);
		}

		boolean stopped = backgroundTaskManager.stopTask(taskId);

		if (stopped) {
			return BackgroundTaskResult.success(
					"Task " + taskId + " has been stopped."
			);
		}
		else {
			return BackgroundTaskResult.error(
					"Failed to stop task " + taskId + ". Task may have already completed."
			);
		}
	}

	private CommandParseResult parseCommand(String command) {
		// Parse special URI formats
		if (command.startsWith("ssh://")) {
			return parseSshUri(command);
		}
		if (command.startsWith("docker://")) {
			return parseDockerUri(command);
		}
		if (command.startsWith("wsl://")) {
			return parseWslUri(command);
		}
		if (command.startsWith("db://")) {
			// Database URI - let the executeDatabaseCommand handle it
			return new CommandParseResult(command, ExecutionTarget.DATABASE, Set.of(), null, null, null, null, null);
		}

		// Regular command - extract required commands
		Set<String> required = extractRequiredCommands(command);
		return new CommandParseResult(command, ExecutionTarget.LOCAL, required, null, null, null, null, null);
	}

	private CommandParseResult parseSshUri(String uri) {
		// ssh://user:password@host:port/command or ssh://user@host/command
		String withoutPrefix = uri.substring(6);

		// Extract user credentials and host
		String credentialsAndHost;
		String remoteCommand;
		int slashIndex = withoutPrefix.indexOf('/');
		if (slashIndex == -1) {
			credentialsAndHost = withoutPrefix;
			remoteCommand = "";
		}
		else {
			credentialsAndHost = withoutPrefix.substring(0, slashIndex);
			remoteCommand = withoutPrefix.substring(slashIndex + 1);
		}

		// Parse user:password@host:port
		String user = null;
		String password = null;
		String host;
		int port = 22;

		// Check for credentials
		int atIndex = credentialsAndHost.lastIndexOf('@');
		if (atIndex != -1) {
			String credentials = credentialsAndHost.substring(0, atIndex);
			host = credentialsAndHost.substring(atIndex + 1);

			// Check for password in credentials
			int colonIndex = credentials.indexOf(':');
			if (colonIndex != -1) {
				user = credentials.substring(0, colonIndex);
				password = credentials.substring(colonIndex + 1);
			}
			else {
				user = credentials;
			}
		}
		else {
			host = credentialsAndHost;
		}

		// Check for port in host
		int portIndex = host.indexOf(':');
		if (portIndex != -1) {
			try {
				port = Integer.parseInt(host.substring(portIndex + 1));
			}
			catch (NumberFormatException e) {
				// Use default port
			}
			host = host.substring(0, portIndex);
		}

		return new CommandParseResult(uri, ExecutionTarget.SSH, Set.of("ssh", "sshpass"), host, port, user, password,
				remoteCommand);
	}

	private CommandParseResult parseDockerUri(String uri) {
		String command = uri.substring(9); // Remove docker://
		return new CommandParseResult(command, ExecutionTarget.DOCKER, Set.of("docker"), null, null, null, null, null);
	}

	private CommandParseResult parseWslUri(String uri) {
		String command = uri.substring(6); // Remove wsl://
		return new CommandParseResult(command, ExecutionTarget.WSL, Set.of("wsl"), null, null, null, null, null);
	}

	// Database helper methods

	private DbConnectionParams parseDatabaseUri(String uri) {
		// Format: db://type://user:password@host:port/database?query
		// or: db://type://user:password@host:port/database (query separate)
		if (!uri.startsWith("db://")) {
			throw new IllegalArgumentException("Invalid database URI format. Must start with db://");
		}

		String withoutPrefix = uri.substring(5); // Remove db://

		// Extract type
		int typeEnd = withoutPrefix.indexOf("://");
		if (typeEnd == -1) {
			throw new IllegalArgumentException("Invalid database URI format. Missing :// after type");
		}
		String type = withoutPrefix.substring(0, typeEnd).toLowerCase();

		String afterType = withoutPrefix.substring(typeEnd + 3);

		// Extract query (after ?)
		String query = null;
		int queryStart = afterType.indexOf('?');
		if (queryStart != -1) {
			query = afterType.substring(queryStart + 1);
			afterType = afterType.substring(0, queryStart);
		}

		// Extract user credentials
		String user = null;
		String pass = null;
		String hostAndDb = afterType;

		int atIndex = afterType.lastIndexOf('@');
		if (atIndex != -1) {
			String credentials = afterType.substring(0, atIndex);
			hostAndDb = afterType.substring(atIndex + 1);

			int colonIndex = credentials.indexOf(':');
			if (colonIndex != -1) {
				user = credentials.substring(0, colonIndex);
				pass = credentials.substring(colonIndex + 1);
			}
			else {
				user = credentials;
			}
		}

		// Extract host and port
		String host;
		int port = getDefaultPort(type);

		int portStart = hostAndDb.indexOf(':');
		int dbStart = hostAndDb.indexOf('/');

		if (portStart != -1 && (dbStart == -1 || portStart < dbStart)) {
			host = hostAndDb.substring(0, portStart);
			String portStr = dbStart != -1 ? hostAndDb.substring(portStart + 1, dbStart)
					: hostAndDb.substring(portStart + 1);
			try {
				port = Integer.parseInt(portStr);
			}
			catch (NumberFormatException e) {
				// Use default port
			}
		}
		else {
			host = dbStart != -1 ? hostAndDb.substring(0, dbStart) : hostAndDb;
		}

		// Extract database
		String database = null;
		if (dbStart != -1) {
			database = hostAndDb.substring(dbStart + 1);
		}

		return new DbConnectionParams(type, host, port, user, pass, database, query);
	}

	private String validateDbParams(DbConnectionParams params) {
		if (params.type == null || params.type.isEmpty()) {
			return "Database type is required";
		}
		if (!isValidDbType(params.type)) {
			return "Unsupported database type: " + params.type + ". Supported: mysql, postgresql, mongodb, redis";
		}
		if (params.host == null || params.host.isEmpty()) {
			return "Database host is required";
		}
		if (params.database == null || params.database.isEmpty()) {
			return "Database name is required";
		}
		return null;
	}

	private boolean isValidDbType(String type) {
		return type.equals("mysql") || type.equals("mariadb") || type.equals("postgresql") || type.equals("postgres")
				|| type.equals("mongodb") || type.equals("mongo") || type.equals("redis");
	}

	private String getDbClientCommand(String type) {
		return switch (type.toLowerCase()) {
			case "mysql", "mariadb" -> "mysql";
			case "postgresql", "postgres" -> "psql";
			case "mongodb", "mongo" -> "mongosh";
			case "redis" -> "redis-cli";
			default -> type;
		};
	}

	private int getDefaultPort(String type) {
		return switch (type.toLowerCase()) {
			case "mysql", "mariadb" -> 3306;
			case "postgresql", "postgres" -> 5432;
			case "mongodb", "mongo" -> 27017;
			case "redis" -> 6379;
			default -> 0;
		};
	}

	private String buildDatabaseCommand(DbConnectionParams params) {
		return switch (params.type.toLowerCase()) {
			case "mysql", "mariadb" -> buildMySqlCommand(params);
			case "postgresql", "postgres" -> buildPostgresCommand(params);
			case "mongodb", "mongo" -> buildMongoCommand(params);
			case "redis" -> buildRedisCommand(params);
			default -> throw new IllegalArgumentException("Unsupported database type: " + params.type);
		};
	}

	private String buildMySqlCommand(DbConnectionParams params) {
		StringBuilder cmd = new StringBuilder("mysql");
		if (params.host != null) {
			cmd.append(" -h ").append(params.host);
		}
		if (params.port != null && params.port != 3306) {
			cmd.append(" -P ").append(params.port);
		}
		if (params.username != null) {
			cmd.append(" -u ").append(params.username);
		}
		if (params.password != null && !params.password.isEmpty()) {
			cmd.append(" -p'").append(params.password.replace("'", "'\\''")).append("'");
		}
		if (params.database != null) {
			cmd.append(" -D ").append(params.database);
		}
		cmd.append(" -e '").append(params.query.replace("'", "'\\''")).append("'");
		return cmd.toString();
	}

	private String buildPostgresCommand(DbConnectionParams params) {
		StringBuilder cmd = new StringBuilder("PGPASSWORD='");
		if (params.password != null) {
			cmd.append(params.password.replace("'", "'\\''"));
		}
		cmd.append("' psql");

		if (params.host != null) {
			cmd.append(" -h ").append(params.host);
		}
		if (params.port != null && params.port != 5432) {
			cmd.append(" -p ").append(params.port);
		}
		if (params.username != null) {
			cmd.append(" -U ").append(params.username);
		}
		if (params.database != null) {
			cmd.append(" -d ").append(params.database);
		}
		cmd.append(" -c '").append(params.query.replace("'", "'\\''")).append("'");
		return cmd.toString();
	}

	private String buildMongoCommand(DbConnectionParams params) {
		StringBuilder cmd = new StringBuilder("mongosh");

		// Build connection string
		cmd.append(" \"mongodb://");
		if (params.username != null) {
			cmd.append(params.username);
			if (params.password != null && !params.password.isEmpty()) {
				cmd.append(":").append(params.password);
			}
			cmd.append("@");
		}
		cmd.append(params.host);
		if (params.port != null && params.port != 27017) {
			cmd.append(":").append(params.port);
		}
		cmd.append("/").append(params.database);
		cmd.append("\"");

		// Add the eval command
		if (params.query != null && !params.query.isEmpty()) {
			cmd.append(" --eval '\"").append(params.query.replace("'", "'\\''")).append("\"'");
		}

		return cmd.toString();
	}

	private String buildRedisCommand(DbConnectionParams params) {
		StringBuilder cmd = new StringBuilder("redis-cli");

		if (params.host != null && !params.host.equals("localhost")) {
			cmd.append(" -h ").append(params.host);
		}
		if (params.port != null && params.port != 6379) {
			cmd.append(" -p ").append(params.port);
		}
		if (params.password != null && !params.password.isEmpty()) {
			cmd.append(" -a '").append(params.password.replace("'", "'\\''")).append("'");
		}
		// Database number (for Redis)
		try {
			int dbNum = Integer.parseInt(params.database);
			cmd.append(" -n ").append(dbNum);
		}
		catch (NumberFormatException e) {
			// Use default database 0
			cmd.append(" -n 0");
		}

		if (params.query != null && !params.query.isEmpty()) {
			// Split query by spaces for Redis commands
			String[] parts = params.query.split("\\s+");
			for (String part : parts) {
				cmd.append(" '").append(part.replace("'", "'\\''")).append("'");
			}
		}

		return cmd.toString();
	}

	private String buildTestConnectionCommand(DbConnectionParams params) {
		return switch (params.type.toLowerCase()) {
			case "mysql", "mariadb" -> buildMySqlCommand(
					new DbConnectionParams(params.type, params.host, params.port, params.username, params.password,
							params.database, "SELECT 1"));
			case "postgresql", "postgres" -> buildPostgresCommand(
					new DbConnectionParams(params.type, params.host, params.port, params.username, params.password,
							params.database, "SELECT 1"));
			case "mongodb", "mongo" -> buildMongoCommand(
					new DbConnectionParams(params.type, params.host, params.port, params.username, params.password,
							params.database, "db.adminCommand('ping')"));
			case "redis" -> buildRedisCommand(
					new DbConnectionParams(params.type, params.host, params.port, params.username, params.password, "0",
							"PING"));
			default -> throw new IllegalArgumentException("Unsupported database type: " + params.type);
		};
	}

	private Set<String> extractRequiredCommands(String command) {
		Set<String> commands = new HashSet<>();
		String[] parts = command.split("\\s+");
		if (parts.length > 0) {
			commands.add(parts[0]);
		}
		return commands;
	}

	private SmartShellResult formatResult(SmartShellSessionManager.SmartCommandResult result, String originalCommand) {
		SmartShellResult response = new SmartShellResult();
		response.setCommand(originalCommand);
		response.setExitCode(result.getExitCode());
		response.setSuccess(result.isSuccess());
		response.setOutput(result.getOutput());
		response.setTimedOut(result.isTimedOut());

		ErrorRecoveryStrategy.ErrorAnalysis analysis = result.getAnalysis();
		response.setErrorType(analysis.getType().name());

		// Add truncation info
		SmartShellSessionManager.CommandResult baseResult = result.getBaseResult();
		List<String> warnings = new ArrayList<>();

		if (baseResult.isTruncatedByLines()) {
			warnings.add(String.format("Output truncated at %d lines (total: %d)", sessionManager.getMaxOutputLines(),
					baseResult.getTotalLines()));
		}
		if (baseResult.isTruncatedByBytes() && sessionManager.getMaxOutputBytes() != null) {
			warnings.add(String.format("Output truncated at %d bytes (total: %d)", sessionManager.getMaxOutputBytes(),
					baseResult.getTotalBytes()));
		}
		if (result.isTimedOut()) {
			warnings.add("Command timed out and was terminated");
		}

		response.setWarnings(warnings.isEmpty() ? null : warnings);

		// Add recovery information if there was an error
		if (!result.isSuccess() && verboseErrors) {
			RecoveryInfo recoveryInfo = new RecoveryInfo();
			recoveryInfo.setSuggestedFix(analysis.getSuggestedFix());
			recoveryInfo.setAlternativeCommands(analysis.getAlternativeCommands());

			if (result.isAutoFixed()) {
				recoveryInfo.setAutoFixed(true);
				recoveryInfo.setFixCommand(result.getFixCommand());
			}
			if (result.isUsedAlternativeCommand()) {
				recoveryInfo.setUsedAlternativeCommand(true);
				recoveryInfo.setAlternativeCommandUsed(result.getAlternativeCommand());
			}

			response.setRecoveryInfo(recoveryInfo);
		}

		return response;
	}

	public SmartShellSessionManager getSessionManager() {
		return sessionManager;
	}

	public SmartEnvironmentManager getEnvironmentManager() {
		return environmentManager;
	}

	public static Builder builder(String workspaceRoot) {
		return new Builder(workspaceRoot);
	}

	// Result classes for JSON serialization

	public static class SmartShellResult {

		private String command;

		private boolean success;

		private Integer exitCode;

		private String output;

		private boolean timedOut;

		private String errorType;

		private List<String> warnings;

		private RecoveryInfo recoveryInfo;

		public static SmartShellResult success(String output) {
			SmartShellResult r = new SmartShellResult();
			r.success = true;
			r.output = output;
			r.exitCode = 0;
			return r;
		}

		public static SmartShellResult error(String message) {
			return error(message, null);
		}

		public static SmartShellResult error(String message, String suggestion) {
			SmartShellResult r = new SmartShellResult();
			r.success = false;
			r.output = message;
			r.exitCode = 1;
			// Store suggestion in recovery info if provided
			if (suggestion != null) {
				RecoveryInfo info = new RecoveryInfo();
				info.setSuggestedFix(suggestion);
				r.recoveryInfo = info;
			}
			return r;
		}

		// Getters and setters
		public String getCommand() {
			return command;
		}

		public void setCommand(String command) {
			this.command = command;
		}

		public boolean isSuccess() {
			return success;
		}

		public void setSuccess(boolean success) {
			this.success = success;
		}

		public Integer getExitCode() {
			return exitCode;
		}

		public void setExitCode(Integer exitCode) {
			this.exitCode = exitCode;
		}

		public String getOutput() {
			return output;
		}

		public void setOutput(String output) {
			this.output = output;
		}

		public boolean isTimedOut() {
			return timedOut;
		}

		public void setTimedOut(boolean timedOut) {
			this.timedOut = timedOut;
		}

		public String getErrorType() {
			return errorType;
		}

		public void setErrorType(String errorType) {
			this.errorType = errorType;
		}

		public List<String> getWarnings() {
			return warnings;
		}

		public void setWarnings(List<String> warnings) {
			this.warnings = warnings;
		}

		public RecoveryInfo getRecoveryInfo() {
			return recoveryInfo;
		}

		public void setRecoveryInfo(RecoveryInfo recoveryInfo) {
			this.recoveryInfo = recoveryInfo;
		}

	}

	public static class RecoveryInfo {

		private boolean autoFixed;

		private String fixCommand;

		private String suggestedFix;

		private boolean usedAlternativeCommand;

		private String alternativeCommandUsed;

		private List<String> alternativeCommands;

		public boolean isAutoFixed() {
			return autoFixed;
		}

		public void setAutoFixed(boolean autoFixed) {
			this.autoFixed = autoFixed;
		}

		public String getFixCommand() {
			return fixCommand;
		}

		public void setFixCommand(String fixCommand) {
			this.fixCommand = fixCommand;
		}

		public String getSuggestedFix() {
			return suggestedFix;
		}

		public void setSuggestedFix(String suggestedFix) {
			this.suggestedFix = suggestedFix;
		}

		public boolean isUsedAlternativeCommand() {
			return usedAlternativeCommand;
		}

		public void setUsedAlternativeCommand(boolean usedAlternativeCommand) {
			this.usedAlternativeCommand = usedAlternativeCommand;
		}

		public String getAlternativeCommandUsed() {
			return alternativeCommandUsed;
		}

		public void setAlternativeCommandUsed(String alternativeCommandUsed) {
			this.alternativeCommandUsed = alternativeCommandUsed;
		}

		public List<String> getAlternativeCommands() {
			return alternativeCommands;
		}

		public void setAlternativeCommands(List<String> alternativeCommands) {
			this.alternativeCommands = alternativeCommands;
		}

	}

	public static class ShellEnvironmentList {

		private final List<ShellEnvironmentInfo> shells;

		private final int count;

		public ShellEnvironmentList(List<ShellEnvironmentInfo> shells) {
			this.shells = shells;
			this.count = shells.size();
		}

		public List<ShellEnvironmentInfo> getShells() {
			return shells;
		}

		public int getCount() {
			return count;
		}

	}

	public static class ShellEnvironmentInfo {

		private final String type;

		private final String path;

		private final int priority;

		private final boolean available;

		public ShellEnvironmentInfo(String type, String path, int priority, boolean available) {
			this.type = type;
			this.path = path;
			this.priority = priority;
			this.available = available;
		}

		public String getType() {
			return type;
		}

		public String getPath() {
			return path;
		}

		public int getPriority() {
			return priority;
		}

		public boolean isAvailable() {
			return available;
		}

	}

	public static class CommandAvailableResult {

		private final boolean available;

		private final String message;

		public CommandAvailableResult(boolean available, String message) {
			this.available = available;
			this.message = message;
		}

		public boolean isAvailable() {
			return available;
		}

		public String getMessage() {
			return message;
		}

	}

	public static class EnsureResult {

		private final String command;

		private final boolean available;

		private final boolean installed;

		private final String message;

		private final String installSuggestion;

		public EnsureResult(String command, boolean available, boolean installed, String message,
				String installSuggestion) {
			this.command = command;
			this.available = available;
			this.installed = installed;
			this.message = message;
			this.installSuggestion = installSuggestion;
		}

		public String getCommand() {
			return command;
		}

		public boolean isAvailable() {
			return available;
		}

		public boolean isInstalled() {
			return installed;
		}

		public String getMessage() {
			return message;
		}

		public String getInstallSuggestion() {
			return installSuggestion;
		}

	}

	public static class InstallResult {

		private final String tool;

		private final boolean success;

		private final boolean wasInstalled;

		private final String message;

		private final String manualInstallInstructions;

		public InstallResult(String tool, boolean success, boolean wasInstalled, String message,
				String manualInstallInstructions) {
			this.tool = tool;
			this.success = success;
			this.wasInstalled = wasInstalled;
			this.message = message;
			this.manualInstallInstructions = manualInstallInstructions;
		}

		public String getTool() {
			return tool;
		}

		public boolean isSuccess() {
			return success;
		}

		public boolean isWasInstalled() {
			return wasInstalled;
		}

		public String getMessage() {
			return message;
		}

		public String getManualInstallInstructions() {
			return manualInstallInstructions;
		}

	}

	// Database result class

	public static class DatabaseResult {

		private final boolean success;

		private final Integer exitCode;

		private final String output;

		private final String type;

		private final String host;

		private final String database;

		private final boolean timedOut;

		private final String errorMessage;

		public DatabaseResult(boolean success, Integer exitCode, String output, String type, String host, String database,
				boolean timedOut) {
			this.success = success;
			this.exitCode = exitCode;
			this.output = output;
			this.type = type;
			this.host = host;
			this.database = database;
			this.timedOut = timedOut;
			this.errorMessage = null;
		}

		private DatabaseResult(boolean success, String output, String errorMessage, String type, String host,
				String database) {
			this.success = success;
			this.output = output;
			this.errorMessage = errorMessage;
			this.type = type;
			this.host = host;
			this.database = database;
			this.exitCode = success ? 0 : 1;
			this.timedOut = false;
		}

		public static DatabaseResult error(String errorMessage) {
			return new DatabaseResult(false, null, errorMessage, null, null, null);
		}

		public static DatabaseResult success(String output, String type, String host, String database) {
			return new DatabaseResult(true, output, null, type, host, database);
		}

		public boolean isSuccess() {
			return success;
		}

		public Integer getExitCode() {
			return exitCode;
		}

		public String getOutput() {
			return output;
		}

		public String getType() {
			return type;
		}

		public String getHost() {
			return host;
		}

		public String getDatabase() {
			return database;
		}

		public boolean isTimedOut() {
			return timedOut;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

	}

	// Background task result classes

	public static class BackgroundTaskResult {

		private final boolean success;
		private final String message;
		private final String taskId;

		private BackgroundTaskResult(boolean success, String message, String taskId) {
			this.success = success;
			this.message = message;
			this.taskId = taskId;
		}

		public static BackgroundTaskResult started(String taskId, String message) {
			return new BackgroundTaskResult(true, message, taskId);
		}

		public static BackgroundTaskResult success(String message) {
			return new BackgroundTaskResult(true, message, null);
		}

		public static BackgroundTaskResult error(String message) {
			return new BackgroundTaskResult(false, message, null);
		}

		public boolean isSuccess() {
			return success;
		}

		public String getMessage() {
			return message;
		}

		public String getTaskId() {
			return taskId;
		}
	}

	public static class BackgroundTaskListResult {

		private final int runningCount;
		private final List<BackgroundTaskInfo> tasks;
		private final String message;

		public BackgroundTaskListResult(int runningCount, List<BackgroundTaskInfo> tasks, String message) {
			this.runningCount = runningCount;
			this.tasks = tasks;
			this.message = message;
		}

		public int getRunningCount() {
			return runningCount;
		}

		public List<BackgroundTaskInfo> getTasks() {
			return tasks;
		}

		public String getMessage() {
			return message;
		}
	}

	public static class BackgroundTaskInfo {

		private final String taskId;
		private final String command;
		private final String description;
		private final String status;
		private final String startTime;
		private final String endTime;
		private final long durationMs;
		private final Integer exitCode;
		private final boolean running;

		public BackgroundTaskInfo(String taskId, String command, String description, String status,
								  String startTime, String endTime, long durationMs, Integer exitCode, boolean running) {
			this.taskId = taskId;
			this.command = command;
			this.description = description;
			this.status = status;
			this.startTime = startTime;
			this.endTime = endTime;
			this.durationMs = durationMs;
			this.exitCode = exitCode;
			this.running = running;
		}

		public String getTaskId() {
			return taskId;
		}

		public String getCommand() {
			return command;
		}

		public String getDescription() {
			return description;
		}

		public String getStatus() {
			return status;
		}

		public String getStartTime() {
			return startTime;
		}

		public String getEndTime() {
			return endTime;
		}

		public long getDurationMs() {
			return durationMs;
		}

		public Integer getExitCode() {
			return exitCode;
		}

		public boolean isRunning() {
			return running;
		}
	}

	public static class BackgroundTaskOutputResult {

		private final String taskId;
		private final String status;
		private final String output;
		private final Integer exitCode;
		private final boolean running;
		private final long durationMs;
		private final String error;

		private BackgroundTaskOutputResult(String taskId, String status, String output,
										   Integer exitCode, boolean running, long durationMs) {
			this(taskId, status, output, exitCode, running, durationMs, null);
		}

		private BackgroundTaskOutputResult(String taskId, String status, String output,
										   Integer exitCode, boolean running, long durationMs, String error) {
			this.taskId = taskId;
			this.status = status;
			this.output = output;
			this.exitCode = exitCode;
			this.running = running;
			this.durationMs = durationMs;
			this.error = error;
		}

		public static BackgroundTaskOutputResult error(String message) {
			return new BackgroundTaskOutputResult(null, null, null, null, false, 0, message);
		}

		public String getTaskId() {
			return taskId;
		}

		public String getStatus() {
			return status;
		}

		public String getOutput() {
			return output;
		}

		public Integer getExitCode() {
			return exitCode;
		}

		public boolean isRunning() {
			return running;
		}

		public long getDurationMs() {
			return durationMs;
		}

		public String getError() {
			return error;
		}

		public boolean isSuccess() {
			return error == null && !running && exitCode != null && exitCode == 0;
		}
	}

	// Internal records and enums

	private record CommandParseResult(String command, ExecutionTarget target, Set<String> requiredCommands, String host,
			Integer port, String user, String password, String remoteCommand) {
	}

	private record DbConnectionParams(String type, String host, Integer port, String username, String password,
			String database, String query) {
	}

	private enum ExecutionTarget {

		LOCAL, SSH, DOCKER, WSL, DATABASE

	}

	/**
	 * Wrapper to adapt SmartShellSessionManager to ShellExecutor interface for
	 * SmartEnvironmentManager.
	 */
	private static class SessionManagerExecutorWrapper implements ShellExecutor {

		private final SmartShellSessionManager sessionManager;

		SessionManagerExecutorWrapper(SmartShellSessionManager sessionManager) {
			this.sessionManager = sessionManager;
		}

		@Override
		public ExecutionResult execute(String command, RunnableConfig config, long timeoutMs) {
			SmartShellSessionManager.SmartCommandResult result = sessionManager.executeCommand(command, config, true);
			return new ExecutionResult(result.getOutput(), result.getExitCode(), result.isTimedOut(), result.isSuccess());
		}

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public void initialize(RunnableConfig config) {
			// Session is initialized lazily
		}

		@Override
		public void cleanup(RunnableConfig config) {
			sessionManager.cleanup(config);
		}

		@Override
		public String getName() {
			return "session-manager";
		}

		@Override
		public boolean commandExists(String command, RunnableConfig config) {
			return sessionManager.commandExists(command, config);
		}

		@Override
		public String getInstallSuggestion(String command) {
			ShellEnvironmentDetector detector = new ShellEnvironmentDetector();
			List<ShellEnvironment> shells = detector.detectAvailableShells();
			if (shells.isEmpty())
				return "No shell environment available";
			return shells.get(0).getInstallSuggestion(command);
		}

	}

	/**
	 * Builder for SmartShellTool.
	 */
	public static class Builder {

		private final String workspaceRoot;

		private SmartShellSessionManager sessionManager;

		private BackgroundTaskManager backgroundTaskManager;

		private List<String> startupCommands = new ArrayList<>();

		private List<String> shutdownCommands = new ArrayList<>();

		private long commandTimeout = 60000;

		private int maxOutputLines = 1000;

		private Long maxOutputBytes = null;

		private Map<String, String> environment = Map.of();

		private boolean autoFix = true;

		private boolean verboseErrors = true;

		private boolean tryAlternativeShells = true;

		private boolean autoInstall = true;

		private List<ShellEnvironment> preferredShells = new ArrayList<>();

		public Builder(String workspaceRoot) {
			this.workspaceRoot = workspaceRoot;
		}

		public Builder withStartupCommands(List<String> commands) {
			this.startupCommands = commands;
			return this;
		}

		public Builder withShutdownCommands(List<String> commands) {
			this.shutdownCommands = commands;
			return this;
		}

		public Builder withCommandTimeout(long timeout) {
			this.commandTimeout = timeout;
			return this;
		}

		public Builder withMaxOutputLines(int lines) {
			this.maxOutputLines = lines;
			return this;
		}

		public Builder withMaxOutputBytes(long bytes) {
			this.maxOutputBytes = bytes;
			return this;
		}

		public Builder withEnvironment(Map<String, String> env) {
			this.environment = env;
			return this;
		}

		public Builder withAutoFix(boolean autoFix) {
			this.autoFix = autoFix;
			return this;
		}

		public Builder withVerboseErrors(boolean verbose) {
			this.verboseErrors = verbose;
			return this;
		}

		public Builder withTryAlternativeShells(boolean tryAlternatives) {
			this.tryAlternativeShells = tryAlternatives;
			return this;
		}

		public Builder withAutoInstall(boolean autoInstall) {
			this.autoInstall = autoInstall;
			return this;
		}

		public Builder withPreferredShells(List<ShellEnvironment> shells) {
			this.preferredShells = shells;
			return this;
		}

		public Builder withSessionManager(SmartShellSessionManager sessionManager) {
			this.sessionManager = sessionManager;
			return this;
		}

		public Builder withBackgroundTaskManager(BackgroundTaskManager backgroundTaskManager) {
			this.backgroundTaskManager = backgroundTaskManager;
			return this;
		}

		public SmartShellTool build() {
			if (sessionManager == null) {
				SmartShellSessionManager.Builder smBuilder = SmartShellSessionManager.builder()
					.workspaceRoot(workspaceRoot)
					.commandTimeout(commandTimeout)
					.maxOutputLines(maxOutputLines)
					.autoFixEnabled(autoFix)
					.tryAlternativeShells(tryAlternativeShells);

				if (startupCommands != null) {
					smBuilder.setStartupCommands(startupCommands);
				}
				if (shutdownCommands != null) {
					smBuilder.setShutdownCommands(shutdownCommands);
				}
				if (maxOutputBytes != null) {
					smBuilder.maxOutputBytes(maxOutputBytes);
				}
				if (!environment.isEmpty()) {
					smBuilder.environment(environment);
				}
				if (!preferredShells.isEmpty()) {
					smBuilder.preferredShells(preferredShells);
				}

				this.sessionManager = smBuilder.build();
			}

			return new SmartShellTool(this);
		}

	}

}
