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
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced working directory management with history and bookmarks.
 * Provides flexible directory switching similar to modern shell experience.
 */
public class WorkingDirectoryManager {

	private static final Logger log = LoggerFactory.getLogger(WorkingDirectoryManager.class);

	private final Path defaultRoot;
	private final ConcurrentHashMap<String, Path> bookmarks = new ConcurrentHashMap<>();
	private final Deque<Path> directoryHistory = new LinkedList<>();
	private final int maxHistorySize = 50;

	public WorkingDirectoryManager(Path defaultRoot) {
		this.defaultRoot = defaultRoot;
		// Initialize with default root
		this.directoryHistory.addFirst(defaultRoot);
	}

	/**
	 * Get the current working directory.
	 */
	public Path getCurrent() {
		return directoryHistory.peekFirst();
	}

	/**
	 * Change to a new directory.
	 */
	public Path changeDirectory(Path newDir) {
		// Resolve relative paths
		Path resolved = resolvePath(newDir);

		// Validate directory exists
		if (!Files.exists(resolved)) {
			log.warn("Directory does not exist: {}", resolved);
			return null;
		}

		if (!Files.isDirectory(resolved)) {
			log.warn("Path is not a directory: {}", resolved);
			return null;
		}

		// Add to history
		addToHistory(resolved);

		log.info("Changed directory to: {}", resolved);
		return resolved;
	}

	/**
	 * Resolve a path (relative or absolute).
	 */
	private Path resolvePath(Path path) {
		if (path.isAbsolute()) {
			return path.normalize();
		}

		Path current = getCurrent();
		if (current == null) {
			current = defaultRoot;
		}

		return current.resolve(path).normalize();
	}

	/**
	 * Add a directory to history.
	 */
	private void addToHistory(Path dir) {
		// Remove if already exists (to move to front)
		directoryHistory.remove(dir);

		// Add to front
		directoryHistory.addFirst(dir);

		// Trim history if too large
		while (directoryHistory.size() > maxHistorySize) {
			directoryHistory.removeLast();
		}
	}

	/**
	 * Go back to previous directory.
	 */
	public Path goBack() {
		if (directoryHistory.size() > 1) {
			Path current = directoryHistory.pollFirst();
			// Move current to second position
			directoryHistory.addFirst(current);
			// Now get the new first (previous)
			Path prev = directoryHistory.pollFirst();
			// Add current back
			directoryHistory.addFirst(current);
			return prev;
		}
		return getCurrent();
	}

	/**
	 * Go to next directory in history.
	 */
	public Path goForward() {
		if (directoryHistory.size() > 1) {
			Path current = directoryHistory.pollFirst();
			Path next = directoryHistory.pollFirst();
			// Add current back at front
			directoryHistory.addFirst(current);
			if (next != null) {
				directoryHistory.addFirst(next);
				return next;
			}
		}
		return getCurrent();
	}

	/**
	 * Go to home directory.
	 */
	public Path goHome() {
		Path home = Path.of(System.getProperty("user.home"));
		return changeDirectory(home);
	}

	/**
	 * Go to default root directory.
	 */
	public Path goToRoot() {
		return changeDirectory(defaultRoot);
	}

	/**
	 * Go to parent directory.
	 */
	public Path goUp() {
		Path current = getCurrent();
		if (current != null) {
			Path parent = current.getParent();
			if (parent != null) {
				return changeDirectory(parent);
			}
		}
		return current;
	}

	/**
	 * Get directory history.
	 */
	public List<Path> getHistory() {
		return new ArrayList<>(directoryHistory);
	}

	/**
	 * Get recent directories (excluding current).
	 */
	public List<Path> getRecent(int count) {
		List<Path> recent = new ArrayList<>();
		int i = 0;
		for (Path dir : directoryHistory) {
			if (i > 0 && i <= count) {
				recent.add(dir);
			}
			i++;
		}
		return recent;
	}

	/**
	 * Save current directory as a bookmark.
	 */
	public void saveBookmark(String name) {
		Path current = getCurrent();
		if (current != null) {
			bookmarks.put(name, current);
			log.info("Saved bookmark '{}' -> {}", name, current);
		}
	}

	/**
	 * Go to a bookmarked directory.
	 */
	public Path goToBookmark(String name) {
		Path bookmarked = bookmarks.get(name);
		if (bookmarked != null) {
			return changeDirectory(bookmarked);
		}
		log.warn("Bookmark '{}' not found", name);
		return null;
	}

	/**
	 * Remove a bookmark.
	 */
	public boolean removeBookmark(String name) {
		return bookmarks.remove(name) != null;
	}

	/**
	 * Get all bookmarks.
	 */
	public List<String> getBookmarks() {
		return new ArrayList<>(bookmarks.keySet());
	}

	/**
	 * Find common project directories.
	 */
	public List<Path> findProjectDirectories(Path baseDir) {
		List<Path> projects = new ArrayList<>();

		if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
			return projects;
		}

		try (var stream = Files.list(baseDir)) {
			stream.filter(Files::isDirectory)
				.forEach(dir -> {
					// Check for common project markers
					boolean isProject = false;

					// Python projects
					if (Files.exists(dir.resolve("requirements.txt"))
							|| Files.exists(dir.resolve("setup.py"))
							|| Files.exists(dir.resolve("pyproject.toml"))
							|| Files.exists(dir.resolve("venv"))
							|| Files.exists(dir.resolve(".venv"))) {
						isProject = true;
					}

					// Node.js projects
					if (Files.exists(dir.resolve("package.json"))
							|| Files.exists(dir.resolve("node_modules"))) {
						isProject = true;
					}

					// Java projects
					if (Files.exists(dir.resolve("pom.xml"))
							|| Files.exists(dir.resolve("build.gradle"))
							|| Files.exists(dir.resolve(".mvn"))) {
						isProject = true;
					}

					// Go projects
					if (Files.exists(dir.resolve("go.mod"))) {
						isProject = true;
					}

					// Rust projects
					if (Files.exists(dir.resolve("Cargo.toml"))) {
						isProject = true;
					}

					if (isProject) {
						projects.add(dir);
					}
				});
		} catch (Exception e) {
			log.debug("Failed to list directory {}: {}", baseDir, e.getMessage());
		}

		return projects;
	}

	/**
	 * Get relative path from default root.
	 */
	public String getRelativePath(Path path) {
		try {
			Path relative = defaultRoot.relativize(path);
			return relative.toString();
		} catch (IllegalArgumentException e) {
			// Not relative to default root
			return path.toString();
		}
	}

	/**
	 * Validate and expand path (handle ~, environment variables, etc.).
	 */
	public Path expandPath(String pathStr) {
		if (pathStr == null || pathStr.isEmpty()) {
			return getCurrent();
		}

		// Handle ~
		if (pathStr.startsWith("~")) {
			String rest = pathStr.substring(1);
			if (rest.isEmpty() || rest.startsWith("/") || rest.startsWith("\\")) {
				Path home = Path.of(System.getProperty("user.home"));
				return rest.isEmpty() ? home : home.resolve(rest.substring(1));
			}
		}

		// Handle environment variables
		if (pathStr.contains("$") || pathStr.contains("%")) {
			pathStr = expandEnvVars(pathStr);
		}

		return Path.of(pathStr);
	}

	/**
	 * Expand environment variables in path.
	 */
	private String expandEnvVars(String path) {
		// Simple expansion for ${VAR} and $VAR formats
		for (String key : System.getenv().keySet()) {
			String value = System.getenv().get(key);
			path = path.replace("${" + key + "}", value);
			path = path.replace("$" + key, value);
			path = path.replace("%" + key + "%", value);
		}
		return path;
	}

	/**
	 * Directory info for display.
	 */
	public static class DirInfo {
		public final Path path;
		public final String name;
		public final boolean exists;
		public final boolean isDirectory;
		public final long size;

		public DirInfo(Path path) {
			this.path = path;
			this.name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
			this.exists = Files.exists(path);
			this.isDirectory = Files.isDirectory(path);
			this.size = exists && !isDirectory ? 0 : -1;
		}
	}

	/**
	 * Get info about a path.
	 */
	public DirInfo getDirInfo(String pathStr) {
		Path path = expandPath(pathStr);
		return new DirInfo(path);
	}
}
