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

/**
 * Utility class for converting paths between Windows and WSL.
 */
public class PathConverter {

	/**
	 * Convert a Windows path to WSL path format.
	 * Examples:
	 * - C:\Users\jinch -> /mnt/c/Users/jinch
	 * - D:\projects\foo -> /mnt/d/projects/foo
	 */
	public static String windowsToWsl(String path) {
		if (path == null || path.isEmpty()) {
			return path;
		}

		// Check if it's a Windows path (starts with drive letter like C:\ or D:\)
		if (path.length() >= 2 && path.charAt(1) == ':') {
			char drive = Character.toLowerCase(path.charAt(0));
			String remaining = path.substring(2);

			// Convert backslashes to forward slashes
			remaining = remaining.replace('\\', '/');

			// Handle /c/ or /C/ format
			if (remaining.startsWith("/") || remaining.startsWith("\\")) {
				return String.format("/mnt/%c%s", drive, remaining);
			}
			else {
				return String.format("/mnt/%c/%s", drive, remaining);
			}
		}

		// Already a Unix-style path, return as-is
		return path;
	}

	/**
	 * Check if a path is a Windows-style path (contains drive letter).
	 */
	public static boolean isWindowsPath(String path) {
		if (path == null || path.length() < 2) {
			return false;
		}
		char firstChar = path.charAt(0);
		return Character.isLetter(firstChar) && path.charAt(1) == ':';
	}

	/**
	 * Convert a WSL path to Windows path format.
	 * Examples:
	 * - /mnt/c/Users/jinch -> C:\Users\jinch
	 * - /home/user/projects -> /home/user/projects (no conversion)
	 */
	public static String wslToWindows(String path) {
		if (path == null || path.isEmpty()) {
			return path;
		}

		// Check if it's a /mnt/x/ path
		if (path.startsWith("/mnt/") && path.length() >= 6) {
			char drive = path.charAt(5);
			String remaining = path.substring(6);

			// Convert forward slashes to backslashes
			remaining = remaining.replace('/', '\\');

			return Character.toUpperCase(drive) + ":\\" + remaining;
		}

		// Not a /mnt path, return as-is (it's already a Unix path)
		return path;
	}

}
