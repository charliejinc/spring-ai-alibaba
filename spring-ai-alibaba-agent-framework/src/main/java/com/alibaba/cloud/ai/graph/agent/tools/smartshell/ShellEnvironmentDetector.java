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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Detects available shell environments on the system.
 * Automatically finds and prioritizes shells (PowerShell, cmd, WSL, Git Bash, bash, zsh, sh).
 */
public class ShellEnvironmentDetector {

	private static final Logger log = LoggerFactory.getLogger(ShellEnvironmentDetector.class);

	// Priority constants - higher number = higher priority
	private static final int PRIORITY_POWERSHELL = 100;
	private static final int PRIORITY_WSL = 90;
	private static final int PRIORITY_GIT_BASH = 80;
	private static final int PRIORITY_BASH = 70;
	private static final int PRIORITY_ZSH = 60;
	private static final int PRIORITY_CMD = 50;
	private static final int PRIORITY_SH = 10;

	private final boolean isWindows;
	private final boolean isMac;

	public ShellEnvironmentDetector() {
		String osName = System.getProperty("os.name").toLowerCase();
		this.isWindows = osName.contains("windows");
		this.isMac = osName.contains("mac");
	}

	/**
	 * Detect all available shell environments and return them sorted by priority.
	 */
	public List<ShellEnvironment> detectAvailableShells() {
		List<ShellEnvironment> shells = new ArrayList<>();

		if (isWindows) {
			detectWindowsShells(shells);
		} else {
			detectUnixShells(shells);
		}

		// Sort by priority (descending)
		shells.sort(Comparator.comparingInt(ShellEnvironment::getPriority).reversed());

		log.info("Detected {} available shell environments: {}", shells.size(),
			shells.stream().map(ShellEnvironment::getType).toList());

		return shells;
	}

	/**
	 * Get the best available shell environment.
	 */
	public ShellEnvironment getBestShell() {
		List<ShellEnvironment> shells = detectAvailableShells();
		if (shells.isEmpty()) {
			throw new RuntimeException("No available shell environment found on this system");
		}
		return shells.get(0);
	}

	/**
	 * Find a shell by type, or return null if not available.
	 */
	public ShellEnvironment findShell(ShellEnvironment.Type type) {
		return detectAvailableShells().stream()
			.filter(s -> s.getType() == type)
			.findFirst()
			.orElse(null);
	}

	private void detectWindowsShells(List<ShellEnvironment> shells) {
		// 1. PowerShell (preferred on Windows)
		ShellEnvironment ps = detectPowerShell();
		if (ps != null) shells.add(ps);

		// 2. WSL
		ShellEnvironment wsl = detectWsl();
		if (wsl != null) shells.add(wsl);

		// 3. Git Bash
		ShellEnvironment gitBash = detectGitBash();
		if (gitBash != null) shells.add(gitBash);

		// 4. cmd.exe (fallback)
		ShellEnvironment cmd = detectCmd();
		if (cmd != null) shells.add(cmd);
	}

	private void detectUnixShells(List<ShellEnvironment> shells) {
		// 1. zsh (preferred on macOS)
		if (isMac) {
			ShellEnvironment zsh = detectZsh();
			if (zsh != null) shells.add(zsh);
		}

		// 2. bash
		ShellEnvironment bash = detectBash();
		if (bash != null) shells.add(bash);

		// 3. zsh on Linux (if bash not found or lower priority)
		if (!isMac) {
			ShellEnvironment zsh = detectZsh();
			if (zsh != null) shells.add(zsh);
		}

		// 4. sh (fallback)
		ShellEnvironment sh = detectSh();
		if (sh != null) shells.add(sh);
	}

	private ShellEnvironment detectPowerShell() {
		// Try PowerShell Core (pwsh) first, then Windows PowerShell
		String[] possiblePaths = {
			"pwsh",                          // PowerShell Core (cross-platform)
			"powershell",                    // Windows PowerShell
			"C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
			"C:\\Program Files\\PowerShell\\7\\pwsh.exe"
		};

		for (String path : possiblePaths) {
			if (commandExists(path)) {
				log.debug("Found PowerShell at: {}", path);
				return ShellEnvironment.builder()
					.type(ShellEnvironment.Type.POWERSHELL)
					.executablePath(path)
					.command(List.of(path))
					.environment(System.getenv())
					.available(true)
					.priority(PRIORITY_POWERSHELL)
					.build();
			}
		}
		return null;
	}

	private ShellEnvironment detectCmd() {
		String cmdPath = "C:\\Windows\\System32\\cmd.exe";
		if (Files.exists(Path.of(cmdPath))) {
			log.debug("Found cmd.exe at: {}", cmdPath);
			return ShellEnvironment.builder()
				.type(ShellEnvironment.Type.CMD)
				.executablePath(cmdPath)
				.command(List.of(cmdPath))
				.environment(System.getenv())
				.available(true)
				.priority(PRIORITY_CMD)
				.build();
		}
		return null;
	}

	private ShellEnvironment detectWsl() {
		// Check if wsl command exists
		if (!commandExists("wsl")) {
			return null;
		}

		// Verify WSL is actually working
		try {
			ProcessBuilder pb = new ProcessBuilder("wsl", "echo", "test");
			pb.redirectErrorStream(true);
			Process process = pb.start();
			boolean finished = process.waitFor(5, TimeUnit.SECONDS);

			if (finished && process.exitValue() == 0) {
				log.debug("WSL is available and working");
				return ShellEnvironment.builder()
					.type(ShellEnvironment.Type.WSL)
					.executablePath("wsl")
					.command(List.of("wsl", "bash"))
					.environment(System.getenv())
					.available(true)
					.priority(PRIORITY_WSL)
					.build();
			}
		} catch (Exception e) {
			log.debug("WSL detection failed: {}", e.getMessage());
		}
		return null;
	}

	private ShellEnvironment detectGitBash() {
		// Common Git Bash installation paths
		String[] possiblePaths = {
			"C:\\Program Files\\Git\\bin\\bash.exe",
			"C:\\Program Files (x86)\\Git\\bin\\bash.exe",
			"%USERPROFILE%\\AppData\\Local\\Programs\\Git\\bin\\bash.exe"
		};

		for (String path : possiblePaths) {
			String expandedPath = path.replace("%USERPROFILE%", System.getProperty("user.home"));
			if (Files.exists(Path.of(expandedPath))) {
				log.debug("Found Git Bash at: {}", expandedPath);
				return ShellEnvironment.builder()
					.type(ShellEnvironment.Type.GIT_BASH)
					.executablePath(expandedPath)
					.command(List.of(expandedPath, "--login", "-i"))
					.environment(System.getenv())
					.available(true)
					.priority(PRIORITY_GIT_BASH)
					.build();
			}
		}
		return null;
	}

	private ShellEnvironment detectBash() {
		String[] possiblePaths = {
			"/bin/bash",
			"/usr/bin/bash",
			"/usr/local/bin/bash",
			"bash"
		};

		for (String path : possiblePaths) {
			if (path.equals("bash") ? commandExists("bash") : Files.exists(Path.of(path))) {
				log.debug("Found bash at: {}", path);
				return ShellEnvironment.builder()
					.type(ShellEnvironment.Type.BASH)
					.executablePath(path)
					.command(List.of(path))
					.environment(System.getenv())
					.available(true)
					.priority(PRIORITY_BASH)
					.build();
			}
		}
		return null;
	}

	private ShellEnvironment detectZsh() {
		String[] possiblePaths = {
			"/bin/zsh",
			"/usr/bin/zsh",
			"/usr/local/bin/zsh",
			"zsh"
		};

		for (String path : possiblePaths) {
			if (path.equals("zsh") ? commandExists("zsh") : Files.exists(Path.of(path))) {
				log.debug("Found zsh at: {}", path);
				return ShellEnvironment.builder()
					.type(ShellEnvironment.Type.ZSH)
					.executablePath(path)
					.command(List.of(path))
					.environment(System.getenv())
					.available(true)
					.priority(PRIORITY_ZSH)
					.build();
			}
		}
		return null;
	}

	private ShellEnvironment detectSh() {
		String[] possiblePaths = {
			"/bin/sh",
			"/usr/bin/sh"
		};

		for (String path : possiblePaths) {
			if (Files.exists(Path.of(path))) {
				log.debug("Found sh at: {}", path);
				return ShellEnvironment.builder()
					.type(ShellEnvironment.Type.SH)
					.executablePath(path)
					.command(List.of(path))
					.environment(System.getenv())
					.available(true)
					.priority(PRIORITY_SH)
					.build();
			}
		}
		return null;
	}

	/**
	 * Check if a command exists in PATH.
	 */
	private boolean commandExists(String command) {
		try {
			ProcessBuilder pb;
			if (isWindows) {
				pb = new ProcessBuilder("where", command);
			} else {
				pb = new ProcessBuilder("which", command);
			}
			pb.redirectErrorStream(true);
			Process process = pb.start();
			boolean finished = process.waitFor(3, TimeUnit.SECONDS);
			return finished && process.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Get a fallback shell environment (basic cmd or sh).
	 */
	public ShellEnvironment getFallbackShell() {
		if (isWindows) {
			return ShellEnvironment.builder()
				.type(ShellEnvironment.Type.CMD)
				.executablePath("cmd.exe")
				.command(List.of("cmd.exe", "/c"))
				.environment(System.getenv())
				.available(true)
				.priority(0)
				.build();
		} else {
			return ShellEnvironment.builder()
				.type(ShellEnvironment.Type.SH)
				.executablePath("/bin/sh")
				.command(List.of("/bin/sh"))
				.environment(System.getenv())
				.available(true)
				.priority(0)
				.build();
		}
	}
}
