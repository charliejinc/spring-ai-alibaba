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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Detects available runtime environments (Python, Node.js, etc.) on the system.
 * Similar to CoPaw's runtime detection approach.
 */
public class RuntimeEnvironmentDetector {

	private static final Logger log = LoggerFactory.getLogger(RuntimeEnvironmentDetector.class);

	private final boolean isWindows;

	public RuntimeEnvironmentDetector() {
		String osName = System.getProperty("os.name").toLowerCase();
		this.isWindows = osName.contains("windows");
	}

	/**
	 * Detected runtime information.
	 */
	public static class RuntimeInfo {
		private final String name;
		private final String executable;
		private final String version;
		private final boolean available;
		private final String installSuggestion;

		public RuntimeInfo(String name, String executable, String version, boolean available, String installSuggestion) {
			this.name = name;
			this.executable = executable;
			this.version = version;
			this.available = available;
			this.installSuggestion = installSuggestion;
		}

		public String getName() { return name; }
		public String getExecutable() { return executable; }
		public String getVersion() { return version; }
		public boolean isAvailable() { return available; }
		public String getInstallSuggestion() { return installSuggestion; }
	}

	/**
	 * Detect Python runtime (python, python3, py).
	 */
	public List<RuntimeInfo> detectPython() {
		List<RuntimeInfo> runtimes = new ArrayList<>();
		String[] executables = isWindows
			? new String[]{"python", "python3", "py", "python3.11", "python3.10", "python3.9"}
			: new String[]{"python3", "python", "python3.11", "python3.10", "python3.9"};

		for (String exe : executables) {
			RuntimeInfo info = detectRuntime("Python", exe, "--version");
			if (info != null && info.isAvailable()) {
				runtimes.add(info);
				break; // Return first available
			}
		}

		if (runtimes.isEmpty()) {
			runtimes.add(new RuntimeInfo("Python", null, null, false,
				isWindows ? "Install Python from https://python.org" : "Install Python: brew install python3 (macOS) or apt-get install python3 (Linux)"));
		}

		return runtimes;
	}

	/**
	 * Detect Node.js runtime.
	 */
	public List<RuntimeInfo> detectNode() {
		List<RuntimeInfo> runtimes = new ArrayList<>();
		String[] executables = isWindows
			? new String[]{"node", "nodejs"}
			: new String[]{"node", "nodejs"};

		for (String exe : executables) {
			RuntimeInfo info = detectRuntime("Node.js", exe, "--version");
			if (info != null && info.isAvailable()) {
				runtimes.add(info);
				break;
			}
		}

		if (runtimes.isEmpty()) {
			runtimes.add(new RuntimeInfo("Node.js", null, null, false,
				isWindows ? "Install Node.js from https://nodejs.org" : "Install Node.js: brew install node (macOS) or apt-get install nodejs (Linux)"));
		}

		return runtimes;
	}

	/**
	 * Detect npm package manager.
	 */
	public List<RuntimeInfo> detectNpm() {
		List<RuntimeInfo> runtimes = new ArrayList<>();
		RuntimeInfo info = detectRuntime("npm", "npm", "--version");
		if (info != null && info.isAvailable()) {
			runtimes.add(info);
		} else {
			runtimes.add(new RuntimeInfo("npm", null, null, false,
				"Install npm: comes with Node.js from https://nodejs.org"));
		}
		return runtimes;
	}

	/**
	 * Detect pip package manager.
	 */
	public List<RuntimeInfo> detectPip() {
		List<RuntimeInfo> runtimes = new ArrayList<>();
		String[] executables = isWindows
			? new String[]{"pip", "pip3"}
			: new String[]{"pip3", "pip"};

		for (String exe : executables) {
			RuntimeInfo info = detectRuntime("pip", exe, "--version");
			if (info != null && info.isAvailable()) {
				runtimes.add(info);
				break;
			}
		}

		if (runtimes.isEmpty()) {
			runtimes.add(new RuntimeInfo("pip", null, null, false,
				"Install pip: comes with Python from https://python.org"));
		}
		return runtimes;
	}

	/**
	 * Detect a specific runtime.
	 */
	private RuntimeInfo detectRuntime(String name, String executable, String versionFlag) {
		try {
			ProcessBuilder pb = new ProcessBuilder(executable, versionFlag);
			pb.redirectErrorStream(true);
			Process process = pb.start();
			boolean finished = process.waitFor(5, TimeUnit.SECONDS);

			if (finished && process.exitValue() == 0) {
				String output = new String(process.getInputStream().readAllBytes()).trim();
				// Extract version number
				String version = extractVersion(output);
				log.debug("Found {} at {}: {}", name, executable, version);
				return new RuntimeInfo(name, executable, version, true, null);
			}
		} catch (Exception e) {
			log.debug("Failed to detect {} at {}: {}", name, executable, e.getMessage());
		}
		return null;
	}

	/**
	 * Extract version string from output.
	 */
	private String extractVersion(String output) {
		// Common version patterns: "Python 3.11.0", "v18.17.0", "9.6.0"
		String[] parts = output.split("[\\s,]+");
		for (String part : parts) {
			if (part.matches("^\\d+\\.\\d+.*$") || part.matches("^v\\d+.*$")) {
				return part.startsWith("v") ? part.substring(1) : part;
			}
		}
		return output;
	}

	/**
	 * Get all detected runtime information.
	 */
	public Map<String, List<RuntimeInfo>> detectAll() {
		return Map.of(
			"python", detectPython(),
			"node", detectNode(),
			"npm", detectNpm(),
			"pip", detectPip()
		);
	}

	/**
	 * Get the best available Python executable.
	 */
	public Optional<String> getBestPython() {
		return detectPython().stream()
			.filter(RuntimeInfo::isAvailable)
			.map(RuntimeInfo::getExecutable)
			.findFirst();
	}

	/**
	 * Get the best available Node.js executable.
	 */
	public Optional<String> getBestNode() {
		return detectNode().stream()
			.filter(RuntimeInfo::isAvailable)
			.map(RuntimeInfo::getExecutable)
			.findFirst();
	}
}
