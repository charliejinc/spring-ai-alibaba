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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PathUtils path conversion utilities.
 */
class PathUtilsTest {

	@Test
	void testFromGitBashFormat() {
		// Test /c/... format
		assertEquals("C:\\Users\\test\\file.txt", PathUtils.fromGitBashFormat("/c/Users/test/file.txt"));
		assertEquals("C:\\temp", PathUtils.fromGitBashFormat("/c/temp"));

		// Test uppercase drive
		assertEquals("D:\\projects\\code", PathUtils.fromGitBashFormat("/D/projects/code"));

		// Test non-GitBash format should return unchanged
		assertEquals("C:\\Windows\\file.txt", PathUtils.fromGitBashFormat("C:\\Windows\\file.txt"));
		assertEquals("relative/path", PathUtils.fromGitBashFormat("relative/path"));
	}

	@Test
	void testToGitBashFormat() {
		// Test Windows format to Git Bash
		assertEquals("/c/Users/test/file.txt", PathUtils.toGitBashFormat("C:\\Users\\test\\file.txt"));
		assertEquals("/d/projects/code", PathUtils.toGitBashFormat("D:\\projects\\code"));

		// Test forward slash Windows path
		assertEquals("/c/temp/file.txt", PathUtils.toGitBashFormat("C:/temp/file.txt"));

		// Test already Git Bash format should return similar
		assertEquals("/c/temp", PathUtils.toGitBashFormat("/c/temp"));

		// Test relative path
		assertEquals("relative/path", PathUtils.toGitBashFormat("relative\\path"));
	}

	@Test
	void testNormalizePath() {
		// Test forward slash conversion
		assertEquals("C:\\Users\\test\\file.txt", PathUtils.normalizePath("C:/Users/test/file.txt"));

		// Test Git Bash format conversion
		assertEquals("C:\\temp\\file.txt", PathUtils.normalizePath("/c/temp/file.txt"));

		// Test duplicate separators
		assertEquals("C:\\temp\\file.txt", PathUtils.normalizePath("C:\\\\temp\\\\file.txt"));
	}

	@Test
	void testToWslFormat() {
		// Test Windows to WSL
		assertEquals("/mnt/c/Users/test", PathUtils.toWslFormat("C:\\Users\\test"));
		assertEquals("/mnt/d/projects", PathUtils.toWslFormat("D:/projects"));

		// Test already WSL format
		assertEquals("/mnt/c/temp", PathUtils.toWslFormat("/mnt/c/temp"));

		// Test relative path
		assertEquals("relative/path", PathUtils.toWslFormat("relative\\path"));
	}

	@Test
	void testFromWslFormat() {
		// Test WSL to Windows
		assertEquals("C:\\Users\\test", PathUtils.fromWslFormat("/mnt/c/Users/test"));
		assertEquals("D:\\projects", PathUtils.fromWslFormat("/mnt/D/projects"));

		// Test non-WSL format
		assertEquals("relative\\path", PathUtils.fromWslFormat("relative/path"));
	}

	@Test
	void testIsGitBashFormat() {
		assertTrue(PathUtils.isGitBashFormat("/c/Users/test"));
		assertTrue(PathUtils.isGitBashFormat("/C/temp"));
		assertTrue(PathUtils.isGitBashFormat("/d/projects"));

		assertFalse(PathUtils.isGitBashFormat("C:\\Users\\test"));
		assertFalse(PathUtils.isGitBashFormat("relative/path"));
		assertFalse(PathUtils.isGitBashFormat("/mnt/c/temp"));
	}

	@Test
	void testIsWindowsAbsolutePath() {
		assertTrue(PathUtils.isWindowsAbsolutePath("C:\\Users\\test"));
		assertTrue(PathUtils.isWindowsAbsolutePath("D:/projects"));
		assertTrue(PathUtils.isWindowsAbsolutePath("c:\\temp"));

		assertFalse(PathUtils.isWindowsAbsolutePath("/c/Users/test"));
		assertFalse(PathUtils.isWindowsAbsolutePath("relative\\path"));
		assertFalse(PathUtils.isWindowsAbsolutePath("/mnt/c/temp"));
	}

	@Test
	void testToShellFormat() {
		// Test Windows path to Git Bash
		assertEquals("/c/temp/file.txt", PathUtils.toShellFormat("C:\\temp\\file.txt", ShellEnvironment.Type.GIT_BASH));

		// Test Windows path to CMD
		assertEquals("C:\\temp\\file.txt", PathUtils.toShellFormat("C:/temp/file.txt", ShellEnvironment.Type.CMD));

		// Test Windows path to PowerShell
		assertEquals("C:\\temp\\file.txt", PathUtils.toShellFormat("/c/temp/file.txt", ShellEnvironment.Type.POWERSHELL));

		// Test Windows path to WSL
		assertEquals("/mnt/c/temp/file.txt", PathUtils.toShellFormat("C:\\temp\\file.txt", ShellEnvironment.Type.WSL));

		// Test Git Bash path to CMD (should convert to Windows)
		assertEquals("C:\\temp\\file.txt", PathUtils.toShellFormat("/c/temp/file.txt", ShellEnvironment.Type.CMD));
	}

	@Test
	void testConvertPathsInCommand() {
		// Test command with Windows path
		String cmd1 = "python C:\\temp\\test.py";
		String result1 = PathUtils.convertPathsInCommand(cmd1, ShellEnvironment.Type.GIT_BASH);
		assertTrue(result1.contains("/c/temp/test.py"));

		// Test command with Git Bash path to CMD
		String cmd2 = "python /c/temp/test.py";
		String result2 = PathUtils.convertPathsInCommand(cmd2, ShellEnvironment.Type.CMD);
		assertTrue(result2.contains("C:\\temp\\test.py") || result2.contains("C:/temp/test.py"));

		// Test command with no paths should remain unchanged
		String cmd3 = "echo hello world";
		assertEquals(cmd3, PathUtils.convertPathsInCommand(cmd3, ShellEnvironment.Type.GIT_BASH));
	}

	@Test
	void testFormatWorkingDirectory() {
		// Windows to Git Bash
		assertEquals("/c/temp", PathUtils.formatWorkingDirectory("C:\\temp", ShellEnvironment.Type.GIT_BASH));

		// Windows to CMD
		assertEquals("C:\\temp", PathUtils.formatWorkingDirectory("C:\\temp", ShellEnvironment.Type.CMD));

		// Windows to WSL
		assertEquals("/mnt/c/temp", PathUtils.formatWorkingDirectory("C:\\temp", ShellEnvironment.Type.WSL));

		// Git Bash to Windows shells
		assertEquals("C:\\temp", PathUtils.formatWorkingDirectory("/c/temp", ShellEnvironment.Type.CMD));
	}

	@Test
	void testNullAndEmptyHandling() {
		assertNull(PathUtils.fromGitBashFormat(null));
		assertEquals("", PathUtils.fromGitBashFormat(""));

		assertNull(PathUtils.toGitBashFormat(null));
		assertEquals("", PathUtils.toGitBashFormat(""));

		assertNull(PathUtils.normalizePath(null));
		assertEquals("", PathUtils.normalizePath(""));

		assertNull(PathUtils.toShellFormat(null, ShellEnvironment.Type.GIT_BASH));
		assertEquals("", PathUtils.toShellFormat("", ShellEnvironment.Type.GIT_BASH));
	}

	@Test
	void testPathsWithSpaces() {
		// Test path conversion with spaces (quoted paths)
		String cmd1 = "python \"C:\\Program Files\\Python\\script.py\"";
		String result1 = PathUtils.convertPathsInCommand(cmd1, ShellEnvironment.Type.GIT_BASH);
		assertTrue(result1.contains("/c/Program Files/Python/script.py"));

		// Test unquoted path with spaces (should still work if quoted properly)
		String cmd2 = "cat '/c/Users/My User/file.txt'";
		String result2 = PathUtils.convertPathsInCommand(cmd2, ShellEnvironment.Type.GIT_BASH);
		assertTrue(result2.contains("/c/Users/My User/file.txt"));
	}

	@Test
	void testUncPaths() {
		// Test UNC path detection
		assertTrue(PathUtils.isUncPath("\\\\server\\share\\folder"));
		assertTrue(PathUtils.isUncPath("\\\\192.168.1.1\\share"));

		assertFalse(PathUtils.isUncPath("C:\\Users\\test"));
		assertFalse(PathUtils.isUncPath("/c/Users/test"));
		assertFalse(PathUtils.isUncPath("relative\\path"));

		// Test UNC to Git Bash conversion
		assertEquals("//server/share/folder", PathUtils.uncToGitBashFormat("\\\\server\\share\\folder"));
		assertEquals("//192.168.1.1/share", PathUtils.uncToGitBashFormat("\\\\192.168.1.1\\share"));

		// Test UNC path in command conversion
		String cmd = "copy \\\\server\\share\\file.txt C:\\temp\\";
		String result = PathUtils.convertPathsInCommand(cmd, ShellEnvironment.Type.GIT_BASH);
		assertTrue(result.contains("//server/share/file.txt"));
	}

	@Test
	void testPipesAndRedirects() {
		// Test path conversion in pipes and redirects
		String cmd = "cat C:\\temp\\input.txt | grep error > C:\\temp\\output.txt";
		String result = PathUtils.convertPathsInCommand(cmd, ShellEnvironment.Type.GIT_BASH);
		assertTrue(result.contains("/c/temp/input.txt"));
		assertTrue(result.contains("/c/temp/output.txt"));
	}

	@Test
	void testScriptArguments() {
		// Test path conversion with script arguments
		String cmd = "python script.py --config C:\\path\\config.json --output D:\\results";
		String result = PathUtils.convertPathsInCommand(cmd, ShellEnvironment.Type.GIT_BASH);
		assertTrue(result.contains("/c/path/config.json"));
		assertTrue(result.contains("/d/results"));
	}

}
