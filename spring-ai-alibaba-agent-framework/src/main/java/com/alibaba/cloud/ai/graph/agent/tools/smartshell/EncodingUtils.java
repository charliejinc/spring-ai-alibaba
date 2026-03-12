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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Utility class for handling character encoding in shell command execution.
 * Provides robust encoding detection similar to CoPaw's approach.
 */
public class EncodingUtils {

	/**
	 * Get the system's preferred encoding.
	 * Falls back to UTF-8 if the preferred encoding is not available.
	 * Similar to Python's locale.getpreferredencoding().
	 *
	 * @return the preferred charset, defaults to UTF-8
	 */
	public static Charset getPreferredEncoding() {
		try {
			String encoding = System.getProperty("file.encoding", "UTF-8");
			Charset charset = Charset.forName(encoding);
			// Verify the charset is actually available
			if (Charset.isSupported(encoding)) {
				return charset;
			}
		} catch (Exception e) {
			// Fall through to default
		}
		return StandardCharsets.UTF_8;
	}

	/**
	 * Get the console encoding specifically for Windows cmd/PowerShell.
	 * On Windows, console output often uses the OEM codepage (e.g., GBK for Chinese Windows).
	 *
	 * @return the console encoding, defaults to getPreferredEncoding()
	 */
	public static Charset getConsoleEncoding() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

		if (os.contains("windows")) {
			try {
				// Try to get Windows console codepage
				ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "chcp");
				pb.redirectErrorStream(true);
				Process process = pb.start();
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

				// Parse codepage from output like "Active code page: 936"
				if (output.contains("code page:")) {
					String codepage = output.split("code page:")[1].trim();
					return Charset.forName("CP" + codepage);
				}
			} catch (Exception e) {
				// Fall back to default
			}
		}

		return getPreferredEncoding();
	}

	/**
	 * Create an InputStreamReader with robust encoding handling.
	 * Uses the system's preferred encoding with fallback to UTF-8.
	 *
	 * @param inputStream the input stream to read from
	 * @return a new InputStreamReader with appropriate encoding
	 */
	public static InputStreamReader createInputStreamReader(InputStream inputStream) {
		return new InputStreamReader(inputStream, getConsoleEncoding());
	}

	/**
	 * Create an OutputStreamWriter with robust encoding handling.
	 * Uses the system's preferred encoding with fallback to UTF-8.
	 *
	 * @param outputStream the output stream to write to
	 * @return a new OutputStreamWriter with appropriate encoding
	 */
	public static OutputStreamWriter createOutputStreamWriter(OutputStream outputStream) {
		return new OutputStreamWriter(outputStream, getConsoleEncoding());
	}
}
