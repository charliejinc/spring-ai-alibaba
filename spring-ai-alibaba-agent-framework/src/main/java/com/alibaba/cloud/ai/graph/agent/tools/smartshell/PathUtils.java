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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for path conversions between different shell environments.
 * Provides unified path handling so callers don't need to worry about path formats.
 */
public final class PathUtils {

	private PathUtils() {
		// Utility class
	}

	// Pattern to match Git Bash style paths: /c/... or /C/...
	private static final Pattern GIT_BASH_DRIVE_PATTERN = Pattern.compile("^/([a-zA-Z])/(.*)");

	// Pattern to match Windows absolute paths: C:\... or C:/...
	private static final Pattern WINDOWS_DRIVE_PATTERN = Pattern.compile("^([a-zA-Z]):[/\\\\](.*)");

	// Pattern to match UNC paths: \\server\share...
	private static final Pattern UNC_PATH_PATTERN = Pattern.compile("^\\\\\\\\([^\\\\]+)\\\\(.*)");

	/**
	 * Normalize a path to standard format (handles both Windows and Unix style).
	 * Converts forward slashes to back slashes on Windows, removes redundant separators.
	 *
	 * @param path the input path
	 * @return normalized path
	 */
	public static String normalizePath(String path) {
		if (path == null || path.isEmpty()) {
			return path;
		}

		// First convert Git Bash format to Windows format if needed
		path = fromGitBashFormat(path);

		// Normalize separators
		String normalized = path.replace('/', '\\');

		// Remove duplicate separators (but keep \\ at start for UNC paths)
		normalized = normalized.replaceAll("(?<!^)\\\\+", "\\\\");

		return normalized;
	}

	/**
	 * Convert a Git Bash style path (/c/Users/...) to Windows format (C:\Users\...).
	 *
	 * @param gitBashPath the Git Bash style path
	 * @return Windows format path, or original if not in Git Bash format
	 */
	public static String fromGitBashFormat(String gitBashPath) {
		if (gitBashPath == null || gitBashPath.isEmpty()) {
			return gitBashPath;
		}

		Matcher matcher = GIT_BASH_DRIVE_PATTERN.matcher(gitBashPath);
		if (matcher.matches()) {
			String drive = matcher.group(1).toUpperCase();
			String rest = matcher.group(2).replace('/', '\\');
			return drive + ":\\" + rest;
		}

		return gitBashPath;
	}

	/**
	 * Convert a Windows path to Git Bash format.
	 *
	 * @param windowsPath the Windows style path (C:\Users\...)
	 * @return Git Bash format path (/c/Users/...), or original if not convertible
	 */
	public static String toGitBashFormat(String windowsPath) {
		if (windowsPath == null || windowsPath.isEmpty()) {
			return windowsPath;
		}

		Matcher matcher = WINDOWS_DRIVE_PATTERN.matcher(windowsPath);
		if (matcher.matches()) {
			String drive = matcher.group(1).toLowerCase();
			String rest = matcher.group(2).replace('\\', '/');
			return "/" + drive + "/" + rest;
		}

		// Already in Unix format or relative path
		return windowsPath.replace('\\', '/');
	}

	/**
	 * Convert path to format suitable for the target shell environment.
	 * This is the main method for unified path handling.
	 *
	 * @param path the input path (any format)
	 * @param targetShell the target shell type
	 * @return path formatted for the target shell
	 */
	public static String toShellFormat(String path, ShellEnvironment.Type targetShell) {
		if (path == null || path.isEmpty()) {
			return path;
		}

		switch (targetShell) {
			case GIT_BASH, BASH, ZSH, SH -> {
				// For Unix-like shells, use forward slashes
				// Convert Windows paths to Git Bash format if needed
				return toGitBashFormat(path);
			}
			case POWERSHELL -> {
				// PowerShell accepts both formats, but prefers Windows format
				return normalizePath(path);
			}
			case CMD -> {
				// CMD requires Windows format
				return normalizePath(path);
			}
			case WSL -> {
				// WSL uses forward slashes, and converts Windows drives to /mnt/
				return toWslFormat(path);
			}
			default -> {
				return path.replace('/', '\\');
			}
		}
	}

	/**
	 * Convert Windows path to WSL format (/mnt/c/...).
	 *
	 * @param windowsPath the Windows path
	 * @return WSL format path
	 */
	public static String toWslFormat(String windowsPath) {
		if (windowsPath == null || windowsPath.isEmpty()) {
			return windowsPath;
		}

		Matcher matcher = WINDOWS_DRIVE_PATTERN.matcher(windowsPath);
		if (matcher.matches()) {
			String drive = matcher.group(1).toLowerCase();
			String rest = matcher.group(2).replace('\\', '/');
			return "/mnt/" + drive + "/" + rest;
		}

		// Check if already in /mnt/ format
		if (windowsPath.startsWith("/mnt/")) {
			return windowsPath;
		}

		// Relative path or already Unix format
		return windowsPath.replace('\\', '/');
	}

	/**
	 * Convert WSL format path back to Windows format.
	 *
	 * @param wslPath the WSL path (/mnt/c/...)
	 * @return Windows format path
	 */
	public static String fromWslFormat(String wslPath) {
		if (wslPath == null || wslPath.isEmpty()) {
			return wslPath;
		}

		// Pattern: /mnt/c/... or /mnt/C/...
		Pattern wslPattern = Pattern.compile("^/mnt/([a-zA-Z])/(.*)");
		Matcher matcher = wslPattern.matcher(wslPath);
		if (matcher.matches()) {
			String drive = matcher.group(1).toUpperCase();
			String rest = matcher.group(2).replace('/', '\\');
			return drive + ":\\" + rest;
		}

		return wslPath.replace('/', '\\');
	}

	/**
	 * Check if a path is in Git Bash format.
	 *
	 * @param path the path to check
	 * @return true if in Git Bash format (/c/...)
	 */
	public static boolean isGitBashFormat(String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}
		return GIT_BASH_DRIVE_PATTERN.matcher(path).matches();
	}

	/**
	 * Check if a path is a Windows absolute path.
	 *
	 * @param path the path to check
	 * @return true if it's a Windows absolute path
	 */
	public static boolean isWindowsAbsolutePath(String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}
		return WINDOWS_DRIVE_PATTERN.matcher(path).matches();
	}

	/**
	 * Check if a path is a UNC (network) path.
	 *
	 * @param path the path to check
	 * @return true if it's a UNC path like \\server\share
	 */
	public static boolean isUncPath(String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}
		return UNC_PATH_PATTERN.matcher(path).matches();
	}

	/**
	 * Convert UNC path to Git Bash format.
	 * Git Bash mounts UNC paths as //server/share
	 *
	 * @param uncPath the UNC path (\\server\share\...)
	 * @return Git Bash format path (//server/share/...)
	 */
	public static String uncToGitBashFormat(String uncPath) {
		if (uncPath == null || uncPath.isEmpty()) {
			return uncPath;
		}

		Matcher matcher = UNC_PATH_PATTERN.matcher(uncPath);
		if (matcher.matches()) {
			String server = matcher.group(1);
			String rest = matcher.group(2).replace('\\', '/');
			return "//" + server + "/" + rest;
		}

		return uncPath.replace('\\', '/');
	}

	/**
	 * Convert all paths in a command string to the appropriate format.
	 * Detects file paths in the command and converts them.
	 * Handles quoted paths with spaces.
	 *
	 * @param command the command string
	 * @param targetShell the target shell type
	 * @return command with paths converted
	 */
	public static String convertPathsInCommand(String command, ShellEnvironment.Type targetShell) {
		if (command == null || command.isEmpty()) {
			return command;
		}

		// Simple heuristic: look for path-like patterns
		// This handles common cases like: python /c/temp/test.py or python C:\temp\test.py

		StringBuilder result = new StringBuilder();
		StringBuilder currentToken = new StringBuilder();
		char quoteChar = 0;

		for (int i = 0; i < command.length(); i++) {
			char c = command.charAt(i);

			// Handle quoted strings (paths with spaces)
			if ((c == '"' || c == '\'') && quoteChar == 0) {
				// Start of quote
				if (currentToken.length() > 0) {
					result.append(convertTokenIfPath(currentToken.toString(), targetShell));
					currentToken.setLength(0);
				}
				quoteChar = c;
				result.append(c);
			}
			else if (c == quoteChar && quoteChar != 0) {
				// End of quote - process content and preserve quote
				if (currentToken.length() > 0) {
					result.append(convertTokenIfPath(currentToken.toString(), targetShell));
					currentToken.setLength(0);
				}
				quoteChar = 0;
				result.append(c);
			}
			else if (c == ' ' && quoteChar == 0) {
				// Space outside quote - process token
				if (currentToken.length() > 0) {
					result.append(convertTokenIfPath(currentToken.toString(), targetShell));
					currentToken.setLength(0);
				}
				result.append(c);
			}
			else {
				currentToken.append(c);
			}
		}

		// Process last token
		if (currentToken.length() > 0) {
			result.append(convertTokenIfPath(currentToken.toString(), targetShell));
		}

		return result.toString();
	}

	/**
	 * Convert a token to path format if it looks like a path.
	 */
	private static String convertTokenIfPath(String token, ShellEnvironment.Type targetShell) {
		// Check if it's a UNC network path (e.g., \\server\share\...)
		if (isUncPath(token)) {
			if (targetShell == ShellEnvironment.Type.GIT_BASH ||
				    targetShell == ShellEnvironment.Type.BASH ||
				    targetShell == ShellEnvironment.Type.ZSH ||
				    targetShell == ShellEnvironment.Type.SH) {
				return uncToGitBashFormat(token);
			}
			// For Windows shells, keep as is but normalize
			return token.replace('/', '\\');
		}

		// Check if it's clearly a Windows absolute path (e.g., C:\Users\...)
		if (isWindowsAbsolutePath(token)) {
			return toShellFormat(token, targetShell);
		}

		// Check if it's already in Git Bash format (e.g., /c/Users/...)
		if (isGitBashFormat(token)) {
			return toShellFormat(token, targetShell);
		}

		// Check if it looks like a file path with separators
		if (token.contains("/") || token.contains("\\")) {
			// Check if it has a file extension or is a directory
			// Be more lenient: if it has path separators, it's likely a path
			if (token.contains(".") || token.endsWith("/") || token.endsWith("\\") || token.contains(":")) {
				return toShellFormat(token, targetShell);
			}
			// Also convert if it looks like a relative path with multiple components
			if ((token.startsWith("./") || token.startsWith(".\\") || token.startsWith("../") || token.startsWith("..\\"))
					|| (token.split("[/\\]").length > 1)) {
				return toShellFormat(token, targetShell);
			}
		}
		return token;
	}

	/**
	 * Get the current working directory in format suitable for the shell.
	 *
	 * @param cwd current working directory path
	 * @param targetShell target shell type
	 * @return formatted path
	 */
	public static String formatWorkingDirectory(String cwd, ShellEnvironment.Type targetShell) {
		if (cwd == null || cwd.isEmpty()) {
			return cwd;
		}

		// For WSL, we need to convert Windows paths to /mnt/ format
		if (targetShell == ShellEnvironment.Type.WSL) {
			return toWslFormat(cwd);
		}

		// For Git Bash and other Unix shells
		if (targetShell == ShellEnvironment.Type.GIT_BASH ||
			targetShell == ShellEnvironment.Type.BASH ||
			targetShell == ShellEnvironment.Type.ZSH ||
			targetShell == ShellEnvironment.Type.SH) {
			return toGitBashFormat(cwd);
		}

		// For Windows shells (CMD, PowerShell)
		return normalizePath(cwd);
	}

}
