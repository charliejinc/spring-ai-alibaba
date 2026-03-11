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

import java.util.*;

/**
 * Manages command environment - detects missing commands and auto-installs them.
 * This is the core "intelligence" that allows SmartShellTool to self-heal.
 *
 * <p>Supports cross-platform: Windows (PowerShell/cmd), macOS (zsh/bash), Linux (bash/zsh)</p>
 */
public class SmartEnvironmentManager {

	private static final Logger log = LoggerFactory.getLogger(SmartEnvironmentManager.class);

	private final ShellExecutor executor;
	private final boolean autoInstall;
	private final Map<String, InstallationStrategy> installationStrategies;
	private final Platform platform;

	public SmartEnvironmentManager(ShellExecutor executor, boolean autoInstall) {
		this.executor = executor;
		this.autoInstall = autoInstall;
		this.platform = detectPlatform();
		this.installationStrategies = new HashMap<>();
		initializeStrategies();
		log.info("SmartEnvironmentManager initialized for platform: {}", platform);
	}

	/**
	 * Detect current platform.
	 */
	private Platform detectPlatform() {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.contains("windows")) {
			return Platform.WINDOWS;
		} else if (osName.contains("mac")) {
			return Platform.MAC;
		} else {
			return Platform.LINUX;
		}
	}

	public enum Platform {
		WINDOWS, MAC, LINUX
	}

	/**
	 * Check if a command exists, and if not, try to install it.
	 */
	public CommandStatus ensureCommandAvailable(String command, RunnableConfig config) {
		String baseCommand = command.split("\\s+")[0];

		// First check if command exists
		if (executor.commandExists(baseCommand, config)) {
			return CommandStatus.available(baseCommand);
		}

		// Command not found - get installation suggestion
		String suggestion = executor.getInstallSuggestion(baseCommand);
		log.warn("Command '{}' not found. Suggestion: {}", baseCommand, suggestion);

		if (!autoInstall) {
			return CommandStatus.missing(baseCommand, suggestion);
		}

		// Try to auto-install
		return tryInstall(baseCommand, config);
	}

	/**
	 * Try to install a command automatically.
	 */
	private CommandStatus tryInstall(String command, RunnableConfig config) {
		InstallationStrategy strategy = installationStrategies.get(command.toLowerCase());

		if (strategy == null) {
			// Try generic strategy detection
			strategy = detectStrategy(command);
		}

		if (strategy == null) {
			log.warn("No installation strategy found for: {}", command);
			return CommandStatus.missing(command, executor.getInstallSuggestion(command));
		}

		log.info("Attempting to install '{}' using strategy: {} on {}", command, strategy.getName(), platform);

		// Get platform-specific installation commands
		List<String> installCommands = strategy.getInstallCommandsForPlatform(platform);

		for (String installCmd : installCommands) {
			log.info("Running install command: {}", installCmd);
			ShellExecutor.ExecutionResult result = executor.execute(installCmd, config, 120000);

			if (result.isSuccess()) {
				// Verify installation
				if (executor.commandExists(command, config)) {
					log.info("Successfully installed '{}'", command);
					return CommandStatus.installed(command);
				}
			} else {
				log.warn("Install command failed: {}", result.getOutput());
			}
		}

		return CommandStatus.installFailed(command, executor.getInstallSuggestion(command));
	}

	/**
	 * Detect installation strategy based on command name patterns.
	 */
	private InstallationStrategy detectStrategy(String command) {
		String cmd = command.toLowerCase();

		// Python ecosystem
		if (cmd.matches("pip\\d?|python\\d?|pytest|black|flake8|mypy")) {
			return InstallationStrategy.pythonPackage(command);
		}

		// Node.js ecosystem
		if (cmd.matches("npm|node|yarn|pnpm|npx")) {
			return InstallationStrategy.nodeJs();
		}

		// Java ecosystem
		if (cmd.matches("mvn|gradle|java|javac")) {
			return InstallationStrategy.java();
		}

		// Version control
		if (cmd.equals("git")) {
			return InstallationStrategy.git();
		}

		// Docker
		if (cmd.equals("docker") || cmd.equals("docker-compose")) {
			return InstallationStrategy.docker();
		}

		// Kubernetes
		if (cmd.equals("kubectl") || cmd.equals("helm")) {
			return InstallationStrategy.kubernetes();
		}

		// Build tools
		if (cmd.equals("make") || cmd.equals("gcc") || cmd.equals("g++")) {
			return InstallationStrategy.buildTools();
		}

		// Network tools
		if (cmd.matches("curl|wget|ping|netstat|ss|nmap")) {
			return InstallationStrategy.networkTools();
		}

		return null;
	}

	private void initializeStrategies() {
		// Register known strategies
		installationStrategies.put("python", InstallationStrategy.python());
		installationStrategies.put("python3", InstallationStrategy.python());
		installationStrategies.put("pip", InstallationStrategy.python());
		installationStrategies.put("pip3", InstallationStrategy.python());
		installationStrategies.put("node", InstallationStrategy.nodeJs());
		installationStrategies.put("npm", InstallationStrategy.nodeJs());
		installationStrategies.put("git", InstallationStrategy.git());
		installationStrategies.put("mvn", InstallationStrategy.java());
		installationStrategies.put("maven", InstallationStrategy.java());
		installationStrategies.put("gradle", InstallationStrategy.java());
		installationStrategies.put("docker", InstallationStrategy.docker());
		installationStrategies.put("kubectl", InstallationStrategy.kubernetes());
		installationStrategies.put("make", InstallationStrategy.buildTools());
		installationStrategies.put("gcc", InstallationStrategy.buildTools());
		installationStrategies.put("curl", InstallationStrategy.networkTools());
		installationStrategies.put("wget", InstallationStrategy.networkTools());
	}

	/**
	 * Get all supported installation strategies.
	 */
	public Map<String, InstallationStrategy> getStrategies() {
		return Collections.unmodifiableMap(installationStrategies);
	}

	/**
	 * Status of a command availability check.
	 */
	public static class CommandStatus {
		private final String command;
		private final boolean available;
		private final boolean installed;
		private final String message;
		private final String installSuggestion;

		private CommandStatus(String command, boolean available, boolean installed,
							 String message, String installSuggestion) {
			this.command = command;
			this.available = available;
			this.installed = installed;
			this.message = message;
			this.installSuggestion = installSuggestion;
		}

		public static CommandStatus available(String command) {
			return new CommandStatus(command, true, false, "Command is available", null);
		}

		public static CommandStatus installed(String command) {
			return new CommandStatus(command, true, true, "Command was auto-installed", null);
		}

		public static CommandStatus missing(String command, String suggestion) {
			return new CommandStatus(command, false, false,
				"Command not found (auto-install disabled)", suggestion);
		}

		public static CommandStatus installFailed(String command, String suggestion) {
			return new CommandStatus(command, false, false,
				"Auto-install failed", suggestion);
		}

		public String getCommand() { return command; }
		public boolean isAvailable() { return available; }
		public boolean wasInstalled() { return installed; }
		public String getMessage() { return message; }
		public String getInstallSuggestion() { return installSuggestion; }
	}

	/**
	 * Installation strategy for a command/tool.
	 */
	public static class InstallationStrategy {
		private final String name;
		private final List<String> installCommands;
		private final String description;

		public InstallationStrategy(String name, List<String> installCommands, String description) {
			this.name = name;
			this.installCommands = installCommands;
			this.description = description;
		}

		public String getName() { return name; }
		public List<String> getInstallCommands() { return installCommands; }
		public String getDescription() { return description; }

		/**
		 * Get installation commands sorted by platform preference.
		 * Platform order: current platform first, then others.
		 */
		public List<String> getInstallCommandsForPlatform(Platform currentPlatform) {
			if (installCommands.isEmpty()) {
				return Collections.emptyList();
			}

			// If only one command, return as-is
			if (installCommands.size() == 1) {
				return installCommands;
			}

			// Try to find platform-specific command first
			String platformPrefix = switch (currentPlatform) {
				case WINDOWS -> "powershell";
				case MAC -> "brew";
				case LINUX -> "sudo apt-get";
			};

			// Find commands that start with platform-specific prefix
			List<String> prioritized = new ArrayList<>();
			List<String> others = new ArrayList<>();

			for (String cmd : installCommands) {
				if (cmd.trim().startsWith(platformPrefix) || cmd.contains(platformPrefix)) {
					prioritized.add(cmd);
				} else {
					others.add(cmd);
				}
			}

			// Add prioritized commands first, then others
			List<String> result = new ArrayList<>(prioritized);
			result.addAll(others);

			// If no platform-specific command found, just return original
			return result.isEmpty() ? installCommands : result;
		}

		// Predefined strategies

		public static InstallationStrategy python() {
			return new InstallationStrategy("python",
				Arrays.asList(
					// Mac
					"brew install python3",
					// Linux
					"sudo apt-get update && sudo apt-get install -y python3 python3-pip",
					// Windows via WSL
					"wsl sudo apt-get update && wsl sudo apt-get install -y python3 python3-pip",
					// Windows via PowerShell
					"powershell -Command \"winget install -e --id Python.Python.3.11 --accept-package-agreements --accept-source-agreements\"",
					// Windows via Chocolatey
					"choco install python -y"
				),
				"Install Python and pip");
		}

		public static InstallationStrategy pythonPackage(String packageName) {
			return new InstallationStrategy("python-package",
				Arrays.asList(
					"wsl pip3 install " + packageName,
					"pip install " + packageName,
					"python -m pip install " + packageName
				),
				"Install Python package: " + packageName);
		}

		public static InstallationStrategy nodeJs() {
			return new InstallationStrategy("nodejs",
				Arrays.asList(
					// Mac
					"brew install node",
					// Linux
					"sudo apt-get update && sudo apt-get install -y nodejs npm",
					// Windows via WSL (fallback)
					"wsl sudo apt-get update && wsl sudo apt-get install -y nodejs npm",
					// Windows via PowerShell
					"powershell -Command \"winget install -e --id OpenJS.NodeJS --accept-package-agreements --accept-source-agreements\"",
					// Windows via Chocolatey
					"choco install nodejs -y"
				),
				"Install Node.js and npm");
		}

		public static InstallationStrategy java() {
			return new InstallationStrategy("java",
				Arrays.asList(
					// Mac
					"brew install openjdk@17 && brew install maven",
					// Linux
					"sudo apt-get update && sudo apt-get install -y default-jdk maven",
					// Windows via WSL
					"wsl sudo apt-get update && wsl sudo apt-get install -y default-jdk maven",
					// Windows via PowerShell
					"powershell -Command \"winget install -e --id EclipseAdoptium.Temurin.17.JDK --accept-package-agreements\"",
					// Windows via Chocolatey
					"choco install openjdk maven -y"
				),
				"Install Java JDK and Maven");
		}

		public static InstallationStrategy git() {
			return new InstallationStrategy("git",
				Arrays.asList(
					// Mac
					"brew install git",
					// Linux
					"sudo apt-get update && sudo apt-get install -y git",
					// Windows via WSL
					"wsl sudo apt-get update && wsl sudo apt-get install -y git",
					// Windows via PowerShell
					"powershell -Command \"winget install -e --id Git.Git --accept-package-agreements\"",
					// Windows via Chocolatey
					"choco install git -y"
				),
				"Install Git");
		}

		public static InstallationStrategy docker() {
			return new InstallationStrategy("docker",
				Arrays.asList(
					"wsl sudo apt-get update && wsl sudo apt-get install -y docker.io",
					"powershell -Command \"winget install -e --id Docker.DockerDesktop --accept-package-agreements\""
				),
				"Install Docker");
		}

		public static InstallationStrategy kubernetes() {
			return new InstallationStrategy("kubernetes",
				Arrays.asList(
					"wsl sudo apt-get update && wsl sudo apt-get install -y apt-transport-https ca-certificates curl && wsl curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.28/deb/Release.key | wsl sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg && wsl sudo chmod 644 /etc/apt/keyrings/kubernetes-apt-keyring.gpg && echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.28/deb/ /' | wsl sudo tee /etc/apt/sources.list.d/kubernetes.list && wsl sudo chmod 644 /etc/apt/sources.list.d/kubernetes.list && wsl sudo apt-get update && wsl sudo apt-get install -y kubectl",
				"powershell -Command \"winget install -e --id Kubernetes.kubectl --accept-package-agreements\"",
				"choco install kubernetes-cli -y"
			),
				"Install Kubernetes CLI (kubectl)");
		}

		public static InstallationStrategy buildTools() {
			return new InstallationStrategy("build-tools",
				Arrays.asList(
					"wsl sudo apt-get update && wsl sudo apt-get install -y build-essential",
					"choco install make mingw -y"
				),
				"Install build tools (gcc, make, etc.)");
		}

		public static InstallationStrategy networkTools() {
			return new InstallationStrategy("network-tools",
				Arrays.asList(
					"wsl sudo apt-get update && wsl sudo apt-get install -y curl wget net-tools iputils-ping",
					"choco install curl -y"
				),
				"Install network tools (curl, wget, ping, etc.)");
		}

		public static InstallationStrategy sshpass() {
			return new InstallationStrategy("sshpass",
				Arrays.asList(
					"wsl sudo apt-get update && wsl sudo apt-get install -y sshpass"
				),
				"Install sshpass for SSH password authentication");
		}
	}
}
