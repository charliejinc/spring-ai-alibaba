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
package com.alibaba.cloud.ai.graph.agent.skills.examples;

import com.alibaba.cloud.ai.graph.agent.tools.smartshell.SmartShellTool;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Example Skill demonstrating SmartShellTool capabilities.
 *
 * <p>This skill shows how to:
 * <ul>
 *   <li>Execute commands with automatic dependency installation</li>
 *   <li>Connect to remote servers via SSH</li>
 *   <li>Ensure tools are available before execution</li>
 * </ul>
 */
public class SmartShellSkillExample {

	private final SmartShellTool shellTool;

	public SmartShellSkillExample() {
		// Create the smart shell tool with auto-install enabled
		this.shellTool = SmartShellTool.builder(System.getProperty("java.io.tmpdir"))
			.withAutoInstall(true)
			.withCommandTimeout(120000)
			.build();
	}

	/**
	 * Example: Deploy a Python application.
	 * Automatically installs Python and dependencies if needed.
	 */
	public void deployPythonApp(ToolContext context) {
		// Step 1: Ensure Python is available (auto-install if needed)
		var pythonStatus = shellTool.ensure("python", true, context);
		if (!pythonStatus.isAvailable()) {
			throw new RuntimeException("Python not available: " + pythonStatus.getMessage());
		}

		// Step 2: Install pip dependencies
		String[] dependencies = {"flask", "requests", "gunicorn"};
		for (String dep : dependencies) {
			shellTool.executeShellCommand("pip install " + dep, false, true, false, context);
		}

		// Step 3: Run the application
		var result = shellTool.executeShellCommand("python app.py", false, true, false, context);
		if (!result.isSuccess()) {
			throw new RuntimeException("Failed to start app: " + result.getOutput());
		}
	}

	/**
	 * Example: Execute command on remote server via SSH.
	 * Automatically installs sshpass if needed.
	 */
	public void remoteServerMaintenance(ToolContext context) {
		// Execute commands on remote server
		String[] maintenanceCommands = {
			"df -h",
			"free -m",
			"systemctl status nginx",
			"tail -100 /var/log/nginx/error.log"
		};

		for (String cmd : maintenanceCommands) {
			var result = shellTool.executeSshCommand(
				"10.1.120.166",  // host
				22,                // port
				"root",            // username
				"root-2026!",      // password
				cmd,               // command
				context
			);

			System.out.println("Command: " + cmd);
			System.out.println("Output: " + result.getOutput());
			System.out.println("---");
		}
	}

	/**
	 * Example: Setup development environment.
	 * Automatically installs all required tools.
	 */
	public void setupDevEnvironment(ToolContext context) {
		String[] requiredTools = {"git", "python", "node", "npm", "docker"};

		for (String tool : requiredTools) {
			System.out.println("Checking/Installing: " + tool);

			var status = shellTool.ensure(tool, true, context);

			if (status.isAvailable()) {
				System.out.println("  ✓ " + tool + " is available" +
					(status.isInstalled() ? " (auto-installed)" : ""));
			} else {
				System.out.println("  ✗ " + tool + " failed: " + status.getMessage());
				System.out.println("  Manual install: " + status.getInstallSuggestion());
			}
		}
	}

	/**
	 * Example: Run a script that may require missing dependencies.
	 * The tool automatically handles missing commands.
	 */
	public void runAutomationScript(ToolContext context, String scriptContent) {
		// Write script to temp file
		shellTool.executeShellCommand(
			"cat > /tmp/automation.sh << 'EOF'\n" + scriptContent + "\nEOF",
			false, true, false, context
		);

		// Make executable and run
		shellTool.executeShellCommand("chmod +x /tmp/automation.sh", false, true, false, context);

		var result = shellTool.executeShellCommand("/tmp/automation.sh", false, true, false, context);

		System.out.println("Script output: " + result.getOutput());
	}

	/**
	 * Example: Execute MySQL query.
	 * Can use either URI format or individual parameters.
	 */
	public void queryMySQL(ToolContext context) {
		// Method 1: Using URI format
		var result1 = shellTool.executeDatabaseCommand(
			"db://mysql://root:secret@localhost:3306/myapp?SELECT * FROM users WHERE active=1",
			null, null, null, null, null, null, null, null, context
		);

		if (result1.isSuccess()) {
			System.out.println("Query result:\n" + result1.getOutput());
		}

		// Method 2: Using individual parameters
		var result2 = shellTool.executeDatabaseCommand(
			null,          // uri
			"mysql",       // type
			"localhost",   // host
			3306,          // port
			"root",        // username
			"secret",      // password
			"myapp",       // database
			"SELECT COUNT(*) FROM orders",
			30000L,        // timeout
			context
		);

		System.out.println("Order count: " + result2.getOutput());
	}

	/**
	 * Example: Execute PostgreSQL query.
	 */
	public void queryPostgreSQL(ToolContext context) {
		var result = shellTool.executeDatabaseCommand(
			null,
			"postgresql",
			"10.1.1.100",
			5432,
			"admin",
			"secure_password",
			"production",
			"SELECT * FROM sales_summary ORDER BY date DESC LIMIT 10",
			60000L,
			context
		);

		if (result.isSuccess()) {
			System.out.println("Sales data:\n" + result.getOutput());
		}
		else {
			System.err.println("Query failed: " + result.getErrorMessage());
		}
	}

	/**
	 * Example: Execute MongoDB command.
	 */
	public void queryMongoDB(ToolContext context) {
		var result = shellTool.executeDatabaseCommand(
			"db://mongodb://mongouser:pass@mongodb.example.com:27017/analytics?db.events.find({event_type: 'purchase'}).limit(5)",
			null, null, null, null, null, null, null, null, context
		);

		System.out.println("MongoDB result:\n" + result.getOutput());
	}

	/**
	 * Example: Execute Redis commands.
	 */
	public void queryRedis(ToolContext context) {
		// Get all keys matching pattern
		var keysResult = shellTool.executeDatabaseCommand(
			null,
			"redis",
			"redis.example.com",
			6379,
			null,  // Redis doesn't use username
			"redis_secret",
			"0",   // Database number 0
			"KEYS user:*",
			null,
			context
		);

		System.out.println("Redis keys: " + keysResult.getOutput());

		// Get a specific hash
		var hashResult = shellTool.executeDatabaseCommand(
			null,
			"redis",
			"redis.example.com",
			6379,
			null,
			"redis_secret",
			"0",
			"HGETALL user:12345",
			null,
			context
		);

		System.out.println("User data: " + hashResult.getOutput());
	}

	/**
	 * Example: Test database connectivity before executing queries.
	 */
	public void testDatabaseConnections(ToolContext context) {
		String[] dbConfigs = {
			"mysql://localhost:3306/myapp",
			"postgresql://10.1.1.100:5432/production",
			"redis://cache.example.com:6379/0"
		};

		for (String dbConfig : dbConfigs) {
			String[] parts = dbConfig.split("://");
			String type = parts[0];
			String[] hostParts = parts[1].split(":");
			String host = hostParts[0];
			String portAndDb = hostParts[1];
			int port = Integer.parseInt(portAndDb.split("/")[0]);
			String database = portAndDb.contains("/") ? portAndDb.split("/")[1] : "";

			System.out.println("Testing " + type + " connection...");

			var result = shellTool.testDatabaseConnection(
				type, host, port, "admin", "password", database, context
			);

			System.out.println("  " + (result.isSuccess() ? "✓ Connected" : "✗ Failed: " + result.getErrorMessage()));
		}
	}
}
