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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Detects and configures npm/pip mirrors for China region.
 * Similar to CoPaw's mirror configuration approach.
 */
public class MirrorConfigurator {

	private static final Logger log = LoggerFactory.getLogger(MirrorConfigurator.class);

	// Known China mirrors
	public static final Map<String, String> NPM_MIRRORS = Map.of(
		"taobao", "https://registry.npmmirror.com",
		"tencent", "https://mirrors.cloud.tencent.com/npm/",
		"default", "https://registry.npmjs.org"
	);

	public static final Map<String, String> PIP_MIRRORS = Map.of(
		"tsinghua", "https://pypi.tuna.tsinghua.edu.cn",
		"aliyun", "https://mirrors.aliyun.com/pypi/simple/",
		"tencent", "https://mirrors.cloud.tencent.com/pypi/simple",
		"douban", "https://pypi.doubanio.com/simple/",
		"default", "https://pypi.org/simple"
	);

	private final boolean isWindows;
	private final String countryCode;

	public MirrorConfigurator() {
		String osName = System.getProperty("os.name").toLowerCase();
		this.isWindows = osName.contains("windows");
		// Try to detect country from locale
		this.countryCode = detectCountry();
	}

	/**
	 * Detect if the system is likely in China based on various factors.
	 */
	private String detectCountry() {
		// Check system locale
		String lang = System.getProperty("user.language", "").toLowerCase();
		String country = System.getProperty("user.country", "").toUpperCase();

		if (country.equals("CN")) {
			return "CN";
		}

		// Check timezone
		String timezone = System.getProperty("user.timezone", "");
		if (timezone.startsWith("Asia/Shanghai") || timezone.startsWith("Asia/HongKong")
				|| timezone.startsWith("Asia/Beijing") || timezone.startsWith("Asia/Chongqing")) {
			return "CN";
		}

		// Check language
		if (lang.equals("zh")) {
			return "CN";
		}

		return "UNKNOWN";
	}

	/**
	 * Check if the system appears to be in China.
	 */
	public boolean isInChina() {
		return "CN".equals(countryCode);
	}

	/**
	 * Get the current npm registry.
	 */
	public String getCurrentNpmRegistry() {
		try {
			ProcessBuilder pb = new ProcessBuilder("npm", "config", "get", "registry");
			pb.redirectErrorStream(true);
			Process process = pb.start();
			boolean finished = process.waitFor(5, TimeUnit.SECONDS);

			if (finished && process.exitValue() == 0) {
				String output = new String(process.getInputStream().readAllBytes()).trim();
				if (!output.isEmpty()) {
					return output;
				}
			}
		} catch (Exception e) {
			log.debug("Failed to get npm registry: {}", e.getMessage());
		}
		return NPM_MIRRORS.get("default");
	}

	/**
	 * Get the current pip index URL.
	 */
	public String getCurrentPipMirror() {
		// Check pip.conf first
		String pipConfig = getPipConfig();
		if (pipConfig != null) {
			return pipConfig;
		}

		// Check environment variable
		String envPip = System.getenv("PIP_INDEX_URL");
		if (envPip != null && !envPip.isEmpty()) {
			return envPip;
		}

		return PIP_MIRRORS.get("default");
	}

	/**
	 * Get pip configuration from pip.conf.
	 */
	private String getPipConfig() {
		try {
			String configFile;
			if (isWindows) {
				configFile = System.getenv("APPDATA") + "\\pip\\pip.ini";
			} else {
				configFile = System.getProperty("user.home") + "/.pip/pip.conf";
			}

			java.nio.file.Path path = java.nio.file.Path.of(configFile);
			if (java.nio.file.Files.exists(path)) {
				String content = java.nio.file.Files.readString(path);
				// Look for index-url in the content
				String[] lines = content.split("\n");
				for (String line : lines) {
					line = line.trim();
					if (line.startsWith("index-url")) {
						String[] parts = line.split("=", 2);
						if (parts.length == 2) {
							return parts[1].trim();
						}
					}
				}
			}
		} catch (Exception e) {
			log.debug("Failed to read pip config: {}", e.getMessage());
		}
		return null;
	}

	/**
	 * Get recommended npm registry based on current location.
	 */
	public String getRecommendedNpmRegistry() {
		if (isInChina()) {
			// Use npmmirror as default for China (most reliable)
			return NPM_MIRRORS.get("taobao");
		}
		return NPM_MIRRORS.get("default");
	}

	/**
	 * Get recommended pip mirror based on current location.
	 */
	public String getRecommendedPipMirror() {
		if (isInChina()) {
			// Use Tsinghua as default for China (most reliable)
			return PIP_MIRRORS.get("tsinghua");
		}
		return PIP_MIRRORS.get("default");
	}

	/**
	 * Check if npm mirror should be changed.
	 */
	public boolean needsNpmMirrorChange() {
		String current = getCurrentNpmRegistry();
		String recommended = getRecommendedNpmRegistry();
		return !current.equals(recommended);
	}

	/**
	 * Check if pip mirror should be changed.
	 */
	public boolean needsPipMirrorChange() {
		String current = getCurrentPipMirror();
		String recommended = getRecommendedPipMirror();
		return !current.equals(recommended);
	}

	/**
	 * Get npm config commands to set mirror.
	 */
	public String getNpmMirrorConfigCommands() {
		String registry = getRecommendedNpmRegistry();
		return "npm config set registry " + registry;
	}

	/**
	 * Get pip install commands with mirror.
	 */
	public String getPipInstallCommand(String packageName) {
		String mirror = getRecommendedPipMirror();
		return "pip install --index-url " + mirror + " " + packageName;
	}

	/**
	 * Get pip config commands to set mirror.
	 */
	public String[] getPipMirrorConfigCommands() {
		String mirror = getRecommendedPipMirror();
		if (isWindows) {
			return new String[] {
				"pip config set global.index-url " + mirror,
				"pip config set global.trusted-host " + extractHost(mirror)
			};
		} else {
			return new String[] {
				"pip config set global.index-url " + mirror,
				"pip config set global.trusted-host " + extractHost(mirror)
			};
		}
	}

	/**
	 * Extract host from URL.
	 */
	private String extractHost(String url) {
		try {
			if (url.startsWith("https://")) {
				url = url.substring(8);
			}
			if (url.startsWith("http://")) {
				url = url.substring(7);
			}
			int slashIdx = url.indexOf('/');
			if (slashIdx > 0) {
				return url.substring(0, slashIdx);
			}
			return url;
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * Get mirror status summary.
	 */
	public Map<String, Object> getMirrorStatus() {
		Map<String, Object> status = new HashMap<>();
		status.put("country", countryCode);
		status.put("isInChina", isInChina());
		status.put("npmCurrent", getCurrentNpmRegistry());
		status.put("npmRecommended", getRecommendedNpmRegistry());
		status.put("npmNeedsChange", needsNpmMirrorChange());
		status.put("pipCurrent", getCurrentPipMirror());
		status.put("pipRecommended", getRecommendedPipMirror());
		status.put("pipNeedsChange", needsPipMirrorChange());
		return status;
	}
}
