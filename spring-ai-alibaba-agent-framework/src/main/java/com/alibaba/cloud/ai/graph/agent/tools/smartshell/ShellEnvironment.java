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

import java.util.List;
import java.util.Map;

/**
 * Represents an available shell environment on the system.
 */
public class ShellEnvironment {

	public enum Type {
		POWERSHELL,    // Windows PowerShell
		CMD,           // Windows Command Prompt
		WSL,           // Windows Subsystem for Linux
		GIT_BASH,      // Git Bash (Windows)
		BASH,          // Linux/macOS Bash
		ZSH,           // macOS/Linux Zsh
		SH             // Basic Unix shell
	}

	private final Type type;
	private final String executablePath;
	private final List<String> command;
	private final Map<String, String> environment;
	private final boolean available;
	private final int priority;  // Higher priority = preferred

	private ShellEnvironment(Builder builder) {
		this.type = builder.type;
		this.executablePath = builder.executablePath;
		this.command = List.copyOf(builder.command);
		this.environment = Map.copyOf(builder.environment);
		this.available = builder.available;
		this.priority = builder.priority;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Type getType() {
		return type;
	}

	public String getExecutablePath() {
		return executablePath;
	}

	public List<String> getCommand() {
		return command;
	}

	public Map<String, String> getEnvironment() {
		return environment;
	}

	public boolean isAvailable() {
		return available;
	}

	public int getPriority() {
		return priority;
	}

	/**
	 * Get the command to check if a command exists in this shell.
	 * Uses try-catch to get non-zero exit code when command not found.
	 */
	public String getCheckCommand(String command) {
		return switch (type) {
			// PowerShell: use try-catch to get proper exit code
			case POWERSHELL -> String.format("try { Get-Command %s -ErrorAction Stop | Out-Null } catch { exit 1 }", command);
			case CMD -> String.format("where %s", command);
			case WSL -> String.format("wsl which %s", command);
			case GIT_BASH, BASH, ZSH, SH -> String.format("which %s", command);
		};
	}

	/**
	 * Get the exit code check command for this shell.
	 */
	public String getExitCodeCommand() {
		return switch (type) {
			case POWERSHELL -> "$LASTEXITCODE";
			case CMD -> "%ERRORLEVEL%";
			case WSL -> "echo $?";
			case GIT_BASH, BASH, ZSH, SH -> "echo $?";
		};
	}

	/**
	 * Format a command to echo a marker with exit code.
	 */
	public String formatMarkerCommand(String marker) {
		return switch (type) {
			case POWERSHELL -> String.format("Write-Output \"%s $LASTEXITCODE\"", marker);
			case CMD -> String.format("echo %s %%ERRORLEVEL%%", marker);
			case WSL -> String.format("wsl bash -c 'printf \"%%s %%s\\n\" \"%s\" \"$?\"'", marker);
			case GIT_BASH, BASH, ZSH, SH -> String.format("printf '%s %%s\\n' $?", marker);
		};
	}

	/**
	 * Get installation suggestions for common commands.
	 */
	public String getInstallSuggestion(String command) {
		return switch (type) {
			case POWERSHELL, CMD -> getWindowsInstallSuggestion(command);
			case WSL -> getWslInstallSuggestion(command);
			case GIT_BASH -> getGitBashInstallSuggestion(command);
			case BASH, ZSH, SH -> getUnixInstallSuggestion(command);
		};
	}

	private String getWindowsInstallSuggestion(String command) {
		return switch (command.toLowerCase()) {
			case "git" -> "Git not found. Install options:\n" +
				"1. winget install Git.Git\n" +
				"2. Download from https://git-scm.com/download/win\n" +
				"3. choco install git";
			case "node", "npm" -> "Node.js not found. Install options:\n" +
				"1. winget install OpenJS.NodeJS\n" +
				"2. Download from https://nodejs.org/\n" +
				"3. choco install nodejs";
			case "python", "python3", "pip" -> "Python not found. Install options:\n" +
				"1. winget install Python.Python.3.11\n" +
				"2. Download from https://www.python.org/downloads/\n" +
				"3. Microsoft Store: search 'Python'";
			case "mvn", "maven" -> "Maven not found. Install options:\n" +
				"1. winget install Apache.Maven\n" +
				"2. choco install maven\n" +
				"3. Download from https://maven.apache.org/download.cgi";
			case "gradle" -> "Gradle not found. Install options:\n" +
				"1. winget install Gradle.Gradle\n" +
				"2. choco install gradle";
			case "docker" -> "Docker not found. Install Docker Desktop from https://www.docker.com/products/docker-desktop/";
			case "kubectl" -> "kubectl not found. Install options:\n" +
				"1. winget install Kubernetes.kubectl\n" +
				"2. choco install kubernetes-cli";
			case "code", "vscode" -> "VS Code not found. Install: winget install Microsoft.VisualStudioCode";
			case "javac", "java" -> "JDK not found. Install options:\n" +
				"1. winget install EclipseAdoptium.Temurin.17.JDK\n" +
				"2. choco install openjdk";
			default -> String.format("Command '%s' not found in Windows. Try:\n" +
				"1. winget install <package>\n" +
				"2. choco install <package>\n" +
				"3. Or use WSL: wsl <command>", command);
		};
	}

	private String getWslInstallSuggestion(String command) {
		return switch (command.toLowerCase()) {
			case "git" -> "Git not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y git";
			case "node", "npm" -> "Node.js not found in WSL. Run:\n" +
				"wsl bash -c 'curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash - && sudo apt-get install -y nodejs'";
			case "python3", "python", "pip3", "pip" -> "Python not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y python3 python3-pip";
			case "mvn", "maven" -> "Maven not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y maven";
			case "gradle" -> "Gradle not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y gradle";
			case "docker" -> "Docker not found in WSL. Install Docker Desktop with WSL2 backend enabled";
			case "make" -> "Make not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y build-essential";
			case "gcc", "g++" -> "GCC not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y build-essential";
			case "curl" -> "curl not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y curl";
			case "wget" -> "wget not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y wget";
			default -> String.format("Command '%s' not found in WSL. Run: wsl sudo apt-get update && wsl sudo apt-get install -y %s", command, command);
		};
	}

	private String getGitBashInstallSuggestion(String command) {
		return getWindowsInstallSuggestion(command) + "\n\nNote: Git Bash uses Windows PATH, so installing on Windows should make it available here.";
	}

	private String getUnixInstallSuggestion(String command) {
		// Detect package manager
		String pkgManager = detectPackageManager();
		return switch (command.toLowerCase()) {
			case "git" -> String.format("Git not found. Run: %s install git", pkgManager);
			case "node", "npm" -> String.format("Node.js not found. Install:\n" +
				"macOS: brew install node\n" +
				"Ubuntu/Debian: %s install nodejs npm\n" +
				"Or use nvm: https://github.com/nvm-sh/nvm", pkgManager);
			case "python3", "python", "pip3", "pip" -> String.format("Python not found. Run: %s install python3 python3-pip", pkgManager);
			case "mvn", "maven" -> String.format("Maven not found. Run: %s install maven", pkgManager);
			case "gradle" -> String.format("Gradle not found. Run: %s install gradle", pkgManager);
			case "docker" -> "Docker not found. Install: https://docs.docker.com/engine/install/";
			case "make" -> String.format("Make not found. Run: %s install build-essential", pkgManager);
			case "gcc", "g++" -> String.format("GCC not found. Run: %s install build-essential", pkgManager);
			default -> String.format("Command '%s' not found. Run: %s install %s", command, pkgManager, command);
		};
	}

	private String detectPackageManager() {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.contains("mac")) {
			return "brew";
		}
		// Default to apt for Linux
		return "sudo apt-get";
	}

	@Override
	public String toString() {
		return String.format("ShellEnvironment{type=%s, path='%s', available=%s, priority=%d}",
			type, executablePath, available, priority);
	}

	public static class Builder {
		private Type type;
		private String executablePath;
		private List<String> command;
		private Map<String, String> environment = Map.of();
		private boolean available;
		private int priority;

		public Builder type(Type type) {
			this.type = type;
			return this;
		}

		public Builder executablePath(String executablePath) {
			this.executablePath = executablePath;
			return this;
		}

		public Builder command(List<String> command) {
			this.command = command;
			return this;
		}

		public Builder environment(Map<String, String> environment) {
			this.environment = environment;
			return this;
		}

		public Builder available(boolean available) {
			this.available = available;
			return this;
		}

		public Builder priority(int priority) {
			this.priority = priority;
			return this;
		}

		public ShellEnvironment build() {
			return new ShellEnvironment(this);
		}
	}
}
