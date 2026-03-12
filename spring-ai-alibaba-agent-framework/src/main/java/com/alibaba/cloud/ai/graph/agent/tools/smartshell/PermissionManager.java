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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Permission manager for shell command authorization.
 *
 * <p>
 * Provides a three-level permission system similar to Claude Code:
 * <ul>
 * <li>ALLOW - Commands that can be executed without confirmation</li>
 * <li>DENY - Commands that are blocked</li>
 * <li>ASK - Commands that require user confirmation (default)</li>
 * </ul>
 *
 * <p>
 * Supports wildcard patterns using glob syntax:
 * <ul>
 * <li>* matches any characters</li>
 * <li>? matches single character</li>
 * <li>** matches any path segments</li>
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * PermissionManager pm = PermissionManager.builder()
 *     .allow("git *")
 *     .allow("mvn *")
 *     .deny("rm -rf /**")
 *     .deny("format *")
 *     .sensitiveCommands("curl | bash", "wget -O- | bash")
 *     .build();
 *
 * PermissionManager.PermissionResult result = pm.checkPermission("git status");
 * // result.isAllowed() == true
 *
 * PermissionManager.PermissionResult result2 = pm.checkPermission("rm -rf /");
 * // result2.isAllowed() == false
 * </pre>
 */
public class PermissionManager {

	private static final Logger log = LoggerFactory.getLogger(PermissionManager.class);

	/**
	 * Permission level enum.
	 */
	public enum Permission {
		/**
		 * Command is allowed without confirmation.
		 */
		ALLOW,

		/**
		 * Command is denied and will be blocked.
		 */
		DENY,

		/**
		 * Command requires user confirmation (default).
		 */
		ASK
	}

	/**
	 * Result of permission check.
	 */
	public static class PermissionResult {

		private final Permission permission;

		private final String message;

		private final String matchedPattern;

		private final boolean isSensitiveCommand;

		private final List<String> sensitiveWarnings;

		public PermissionResult(Permission permission, String message) {
			this(permission, message, null, false, null);
		}

		public PermissionResult(Permission permission, String message, String matchedPattern, boolean isSensitiveCommand,
				List<String> sensitiveWarnings) {
			this.permission = permission;
			this.message = message;
			this.matchedPattern = matchedPattern;
			this.isSensitiveCommand = isSensitiveCommand;
			this.sensitiveWarnings = sensitiveWarnings;
		}

		public Permission getPermission() {
			return permission;
		}

		public String getMessage() {
			return message;
		}

		public String getMatchedPattern() {
			return matchedPattern;
		}

		public boolean isSensitiveCommand() {
			return isSensitiveCommand;
		}

		public List<String> getSensitiveWarnings() {
			return sensitiveWarnings;
		}

		/**
		 * Check if command is allowed (either ALLOW or ASK with confirmation).
		 */
		public boolean isAllowed() {
			return permission != Permission.DENY;
		}

		/**
		 * Check if command requires user confirmation.
		 */
		public boolean requiresConfirmation() {
			return permission == Permission.ASK;
		}

		/**
		 * Check if command is explicitly denied.
		 */
		public boolean isDenied() {
			return permission == Permission.DENY;
		}

	}

	/**
	 * Builder for PermissionManager.
	 */
	public static class Builder {

		private final List<String> allowPatterns = new ArrayList<>();

		private final List<String> denyPatterns = new ArrayList<>();

		private final List<String> sensitivePatterns = new ArrayList<>();

		private Permission defaultPermission = Permission.ASK;

		private boolean enableSensitiveWarning = true;

		public Builder allow(String... patterns) {
			allowPatterns.addAll(Arrays.asList(patterns));
			return this;
		}

		public Builder allow(List<String> patterns) {
			allowPatterns.addAll(patterns);
			return this;
		}

		public Builder deny(String... patterns) {
			denyPatterns.addAll(Arrays.asList(patterns));
			return this;
		}

		public Builder deny(List<String> patterns) {
			denyPatterns.addAll(patterns);
			return this;
		}

		public Builder sensitiveCommands(String... patterns) {
			sensitivePatterns.addAll(Arrays.asList(patterns));
			return this;
		}

		public Builder sensitiveCommands(List<String> patterns) {
			sensitivePatterns.addAll(patterns);
			return this;
		}

		public Builder defaultPermission(Permission permission) {
			this.defaultPermission = permission;
			return this;
		}

		public Builder enableSensitiveWarning(boolean enable) {
			this.enableSensitiveWarning = enable;
			return this;
		}

		public PermissionManager build() {
			return new PermissionManager(this);
		}

	}

	/**
	 * Default dangerous command patterns that should require extra caution.
	 */
	private static final List<String> DEFAULT_DENY_PATTERNS = Arrays.asList("rm -rf /*", "rm -rf /", "format:",
			"del /s /q /f *", "dd if=* of=/dev/", "mkfs", "fdisk -l", "> /dev/sd", "> /dev/nvme");

	/**
	 * Default sensitive command patterns that require warnings.
	 */
	private static final List<String> DEFAULT_SENSITIVE_PATTERNS = Arrays.asList("curl.*\\|.*bash", "wget.*\\|.*bash",
			"curl.*-O.*http", "wget.*-O-", "sudo.*", "su -", "chmod 777", "chown -R", "kill -9", "pkill -9",
			"shutdown", "reboot", "init 0", "init 6", "systemctl.*stop", "systemctl.*disable", "docker run -d",
			"docker rm -f", "docker system prune", "iptables -F", "iptables -X");

	private final List<Pattern> allowCompiled;

	private final List<Pattern> denyCompiled;

	private final List<Pattern> sensitiveCompiled;

	private final Permission defaultPermission;

	private final boolean enableSensitiveWarning;

	private PermissionManager(Builder builder) {
		this.allowCompiled = compilePatterns(builder.allowPatterns);
		this.denyCompiled = compilePatterns(builder.denyPatterns);
		this.sensitiveCompiled = compileSensitivePatterns(builder.sensitivePatterns);
		this.defaultPermission = builder.defaultPermission;
		this.enableSensitiveWarning = builder.enableSensitiveWarning;

		log.info("PermissionManager initialized: allow={}, deny={}, sensitive={}, default={}", allowCompiled.size(),
				denyCompiled.size(), sensitiveCompiled.size(), defaultPermission);
	}

	private List<Pattern> compilePatterns(List<String> patterns) {
		List<Pattern> compiled = new ArrayList<>();
		for (String pattern : patterns) {
			try {
				// Convert glob to regex
				String regex = globToRegex(pattern.trim());
				compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
			}
			catch (PatternSyntaxException e) {
				log.warn("Invalid pattern '{}': {}", pattern, e.getMessage());
			}
		}
		return compiled;
	}

	private List<Pattern> compileSensitivePatterns(List<String> patterns) {
		List<Pattern> compiled = new ArrayList<>();
		// Add default sensitive patterns
		for (String pattern : DEFAULT_SENSITIVE_PATTERNS) {
			try {
				String regex = globToRegex(pattern.trim());
				compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
			}
			catch (PatternSyntaxException e) {
				log.warn("Invalid default sensitive pattern '{}': {}", pattern, e.getMessage());
			}
		}
		// Add custom sensitive patterns
		for (String pattern : patterns) {
			try {
				String regex = globToRegex(pattern.trim());
				compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
			}
			catch (PatternSyntaxException e) {
				log.warn("Invalid sensitive pattern '{}': {}", pattern, e.getMessage());
			}
		}
		return compiled;
	}

	/**
	 * Convert glob pattern to regex.
	 */
	private String globToRegex(String glob) {
		StringBuilder regex = new StringBuilder("^");
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			switch (c) {
			case '*':
				if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
					regex.append(".*");
					i++;
				}
				else {
					regex.append("[^\\s]*");
				}
				break;
			case '?':
				regex.append(".");
				break;
			case '.':
			case '+':
			case '^':
			case '$':
			case '|':
			case '(':
			case ')':
			case '[':
			case ']':
			case '{':
			case '}':
			case '\\':
				regex.append("\\").append(c);
				break;
			case ' ':
				regex.append("\\s+");
				break;
			default:
				regex.append(c);
			}
		}
		regex.append("$");
		return regex.toString();
	}

	/**
	 * Check if a command is allowed.
	 *
	 * @param command The command to check
	 * @return PermissionResult containing the permission level and any warnings
	 */
	public PermissionResult checkPermission(String command) {
		if (command == null || command.trim().isEmpty()) {
			return new PermissionResult(Permission.DENY, "Empty command is not allowed");
		}

		String trimmedCommand = command.trim();

		// First check deny patterns (highest priority)
		for (int i = 0; i < denyCompiled.size(); i++) {
			if (denyCompiled.get(i).matcher(trimmedCommand).find()) {
				log.warn("Command denied: {} (matched deny pattern)", trimmedCommand);
				return new PermissionResult(Permission.DENY,
						"Command is denied by security policy. This type of operation is not allowed.", getDenyPatterns()
								.get(i), false, null);
			}
		}

		// Check allow patterns
		for (int i = 0; i < allowCompiled.size(); i++) {
			if (allowCompiled.get(i).matcher(trimmedCommand).find()) {
				log.debug("Command allowed: {} (matched allow pattern)", trimmedCommand);
				return checkSensitiveCommand(trimmedCommand, getAllowPatterns().get(i));
			}
		}

		// Default to ask
		return checkSensitiveCommand(trimmedCommand, null);
	}

	/**
	 * Check if command is sensitive and generate warnings.
	 */
	private PermissionResult checkSensitiveCommand(String command, String matchedAllowPattern) {
		List<String> warnings = new ArrayList<>();

		for (Pattern pattern : sensitiveCompiled) {
			if (pattern.matcher(command).find()) {
				warnings.add("This command may be potentially dangerous. Please verify the command before proceeding.");
				warnings.add("Warning: This command could modify system state or execute arbitrary code.");
				break;
			}
		}

		if (!warnings.isEmpty() && enableSensitiveWarning) {
			log.warn("Sensitive command detected: {}", command);
			if (matchedAllowPattern != null) {
				return new PermissionResult(Permission.ALLOW, "Command allowed but has warnings", matchedAllowPattern,
						true, warnings);
			}
			return new PermissionResult(Permission.ASK, "Confirmation required - sensitive command detected", null,
					true, warnings);
		}

		if (matchedAllowPattern != null) {
			return new PermissionResult(Permission.ALLOW, "Command allowed", matchedAllowPattern, false, null);
		}

		return new PermissionResult(defaultPermission, "Confirmation required for this command", null, false, null);
	}

	/**
	 * Get the list of allow patterns.
	 */
	public List<String> getAllowPatterns() {
		return new ArrayList<>(allowCompiled.stream().map(Pattern::pattern).toList());
	}

	/**
	 * Get the list of deny patterns.
	 */
	public List<String> getDenyPatterns() {
		return new ArrayList<>(denyCompiled.stream().map(Pattern::pattern).toList());
	}

	/**
	 * Get the list of sensitive patterns.
	 */
	public List<String> getSensitivePatterns() {
		return new ArrayList<>(sensitiveCompiled.stream().map(Pattern::pattern).toList());
	}

	/**
	 * Get the default permission level.
	 */
	public Permission getDefaultPermission() {
		return defaultPermission;
	}

	/**
	 * Create a new builder.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Create a builder with default secure configuration.
	 */
	public static Builder builderSecure() {
		return new Builder().defaultPermission(Permission.ASK).enableSensitiveWarning(true).deny(DEFAULT_DENY_PATTERNS);
	}

	/**
	 * Create a builder with permissive configuration (for trusted environments).
	 */
	public static Builder builderPermissive() {
		return new Builder().defaultPermission(Permission.ALLOW).enableSensitiveWarning(false);
	}

}
