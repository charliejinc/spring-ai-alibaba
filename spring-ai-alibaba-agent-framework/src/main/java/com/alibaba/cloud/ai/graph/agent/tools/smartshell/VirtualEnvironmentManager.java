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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manages virtual environments for Python and Node.js.
 * Provides automatic detection and activation of virtual environments.
 */
public class VirtualEnvironmentManager {

	private static final Logger log = LoggerFactory.getLogger(VirtualEnvironmentManager.class);

	private final boolean isWindows;

	public VirtualEnvironmentManager() {
		String osName = System.getProperty("os.name").toLowerCase();
		this.isWindows = osName.contains("windows");
	}

	/**
	 * Type of virtual environment.
	 */
	public enum EnvType {
		PYTHON_VENV,
		PYTHON_VIRTUALENV,
		PYTHON_CONDA,
		NODE_NPM,
		NODE_YARN,
		UNKNOWN
	}

	/**
	 * Information about a detected virtual environment.
	 */
	public static class VirtualEnvInfo {
		private final EnvType type;
		private final Path path;
		private final String activationCommand;
		private final String pythonExecutable;
		private final String nodeExecutable;
		private final boolean isActive;
		private final boolean isWindows;

		public VirtualEnvInfo(EnvType type, Path path, String activationCommand, String pythonExecutable, String nodeExecutable, boolean isWindows) {
			this.type = type;
			this.path = path;
			this.activationCommand = activationCommand;
			this.pythonExecutable = pythonExecutable;
			this.nodeExecutable = nodeExecutable;
			this.isActive = false;
			this.isWindows = isWindows;
		}

		public EnvType getType() { return type; }
		public Path getPath() { return path; }
		public String getActivationCommand() { return activationCommand; }
		public String getPythonExecutable() { return pythonExecutable; }
		public String getNodeExecutable() { return nodeExecutable; }
		public boolean isActive() { return isActive; }

		/**
		 * Get the Python executable path in this environment.
		 */
		public String getPython() {
			if (pythonExecutable != null) {
				return pythonExecutable;
			}
			if (isWindows) {
				return path.resolve("Scripts").resolve("python.exe").toString();
			} else {
				return path.resolve("bin").resolve("python").toString();
			}
		}

		/**
		 * Get the npm executable path in this environment.
		 */
		public String getNpm() {
			if (nodeExecutable != null) {
				return nodeExecutable;
			}
			if (isWindows) {
				return path.resolve("node_modules").resolve(".bin").resolve("npm.cmd").toString();
			} else {
				return path.resolve("node_modules").resolve(".bin").resolve("npm").toString();
			}
		}
	}

	/**
	 * Detect virtual environment in a given directory.
	 */
	public List<VirtualEnvInfo> detectVirtualEnvironments(Path baseDir) {
		List<VirtualEnvInfo> envs = new ArrayList<>();

		if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
			return envs;
		}

		try (var stream = Files.list(baseDir)) {
			stream.filter(Files::isDirectory)
				.forEach(dir -> {
					// Check for Python venv
					VirtualEnvInfo venv = detectPythonVenv(dir);
					if (venv != null) {
						envs.add(venv);
					}

					// Check for Node.js node_modules
					VirtualEnvInfo node = detectNodeEnv(dir);
					if (node != null) {
						envs.add(node);
					}

					// Check for conda environment
					VirtualEnvInfo conda = detectCondaEnv(dir);
					if (conda != null) {
						envs.add(conda);
					}
				});
		} catch (Exception e) {
			log.debug("Failed to list directory {}: {}", baseDir, e.getMessage());
		}

		return envs;
	}

	/**
	 * Detect Python venv or virtualenv.
	 */
	private VirtualEnvInfo detectPythonVenv(Path dir) {
		Path activateScript;
		Path pythonExec;

		if (isWindows) {
			activateScript = dir.resolve("Scripts").resolve("activate.bat");
			pythonExec = dir.resolve("Scripts").resolve("python.exe");
		} else {
			activateScript = dir.resolve("bin").resolve("activate");
			pythonExec = dir.resolve("bin").resolve("python");
		}

		if (Files.exists(activateScript) && Files.exists(pythonExec)) {
			String activationCmd = isWindows
				? "call " + activateScript.toString().replace("\\", "\\\\")
				: "source " + activateScript;

			return new VirtualEnvInfo(
				EnvType.PYTHON_VENV,
				dir,
				activationCmd,
				pythonExec.toString(),
				null,
				isWindows
			);
		}

		return null;
	}

	/**
	 * Detect Node.js environment (by checking for package.json and node_modules).
	 */
	private VirtualEnvInfo detectNodeEnv(Path dir) {
		Path packageJson = dir.resolve("package.json");
		Path nodeModules = dir.resolve("node_modules");

		if (Files.exists(packageJson) && Files.isDirectory(nodeModules)) {
			// Check for local npm
			Path localNpm = isWindows
				? nodeModules.resolve(".bin").resolve("npm.cmd")
				: nodeModules.resolve(".bin").resolve("npm");

			String nodeExec = localNpm.toString();

			return new VirtualEnvInfo(
				EnvType.NODE_NPM,
				dir,
				"cd " + dir.toString(),
				null,
				nodeExec,
				isWindows
			);
		}

		return null;
	}

	/**
	 * Detect Conda environment.
	 */
	private VirtualEnvInfo detectCondaEnv(Path dir) {
		Path condaMeta = dir.resolve("conda-meta");

		if (Files.isDirectory(condaMeta)) {
			String pythonExec = isWindows
				? dir.resolve("python.exe").toString()
				: dir.resolve("bin").resolve("python").toString();

			return new VirtualEnvInfo(
				EnvType.PYTHON_CONDA,
				dir,
				"conda activate " + dir.getFileName(),
				pythonExec,
				null,
				isWindows
			);
		}

		return null;
	}

	/**
	 * Find the best virtual environment for a project.
	 */
	public Optional<VirtualEnvInfo> findProjectEnvironment(Path projectDir) {
		// First check if there's a venv in the project directory
		List<VirtualEnvInfo> envs = detectVirtualEnvironments(projectDir);
		if (!envs.isEmpty()) {
			// Prefer Python venv over Node
			Optional<VirtualEnvInfo> pythonEnv = envs.stream()
				.filter(e -> e.getType() == EnvType.PYTHON_VENV || e.getType() == EnvType.PYTHON_CONDA)
				.findFirst();
			if (pythonEnv.isPresent()) {
				return pythonEnv;
			}
			return envs.stream().findFirst();
		}

		// Check parent directory
		if (projectDir.getParent() != null) {
			List<VirtualEnvInfo> parentEnvs = detectVirtualEnvironments(projectDir.getParent());
			for (VirtualEnvInfo env : parentEnvs) {
				// Check if the env name matches the project name
				String envName = env.getPath().getFileName().toString().toLowerCase();
				String projectName = projectDir.getFileName().toString().toLowerCase();
				if (envName.contains(projectName) || projectName.contains(envName)) {
					return Optional.of(env);
				}
			}
		}

		return Optional.empty();
	}

	/**
	 * Get commands to create a new Python virtual environment.
	 */
	public String[] getCreateVenvCommands(String envName, Path location) {
		String venvPath = location.resolve(envName).toString();
		return new String[] {
			"python -m venv " + venvPath,
			isWindows
				? venvPath + "\\Scripts\\pip install --upgrade pip"
				: venvPath + "/bin/pip install --upgrade pip"
		};
	}

	/**
	 * Get commands to create a new Node.js project.
	 */
	public String[] getCreateNodeProjectCommands(Path projectDir) {
		return new String[] {
			"cd " + projectDir,
			"npm init -y",
			"npm install"
		};
	}

	/**
	 * Get the activation script path for a virtual environment.
	 */
	public Path getActivationScript(Path venvDir) {
		if (isWindows) {
			return venvDir.resolve("Scripts").resolve("activate.bat");
		} else {
			return venvDir.resolve("bin").resolve("activate");
		}
	}

	/**
	 * Check if a path is inside a virtual environment.
	 */
	public boolean isInVirtualEnvironment(Path pythonPath) {
		if (pythonPath == null) {
			return false;
		}

		try {
			ProcessBuilder pb;
			if (isWindows) {
				pb = new ProcessBuilder(pythonPath.toString(), "-c", "import sys; print(sys.prefix)");
			} else {
				pb = new ProcessBuilder(pythonPath.toString(), "-c", "import sys; print(sys.prefix)");
			}
			pb.redirectErrorStream(true);
			Process process = pb.start();
			boolean finished = process.waitFor(5, TimeUnit.SECONDS);

			if (finished && process.exitValue() == 0) {
				String prefix = new String(process.getInputStream().readAllBytes()).trim();
				// Check if prefix is different from base prefix (indicating venv)
				pb = new ProcessBuilder(pythonPath.toString(), "-c", "import sys; print(sys.base_prefix)");
				process = pb.start();
				finished = process.waitFor(5, TimeUnit.SECONDS);
				if (finished && process.exitValue() == 0) {
					String basePrefix = new String(process.getInputStream().readAllBytes()).trim();
					return !prefix.equals(basePrefix);
				}
			}
		} catch (Exception e) {
			log.debug("Failed to check virtual environment: {}", e.getMessage());
		}

		return false;
	}
}
