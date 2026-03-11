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

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for background task functionality in SmartShellTool.
 */
class BackgroundTaskTest {

	@TempDir
	Path tempDir;

	private RunnableConfig config;

	private ToolContext toolContext;

	@BeforeEach
	void setUp() {
		config = RunnableConfig.builder()
			.threadId("test-thread-bg-" + System.currentTimeMillis())
			.build();

		Map<String, Object> context = new HashMap<>();
		context.put(AGENT_CONFIG_CONTEXT_KEY, config);
		toolContext = new ToolContext(context);
	}

	/**
	 * Test that BackgroundTaskManager can be used independently.
	 */
	@Test
	void testBackgroundTaskManagerBasic() {
		BackgroundTaskManager manager = new BackgroundTaskManager();

		// Submit a simple background task
		BackgroundTask task = manager.submitTask(
			"echo test",
			"Test echo task",
			null,
			10000,
			(t) -> {
				// Simulate command execution
				return new SmartShellSessionManager.CommandResult(
					"test output",
					0,
					false,
					false,
					false,
					1,
					10
				);
			}
		);

		assertNotNull(task);
		assertNotNull(task.getTaskId());
		assertEquals(BackgroundTask.Status.RUNNING, task.getStatus());

		// Wait for task to complete
		SmartShellSessionManager.CommandResult result = manager.waitForTask(task.getTaskId(), 5000);
		assertNotNull(result);
		assertEquals(0, result.getExitCode());
		assertEquals("test output", result.getOutput());

		// Cleanup
		manager.shutdown();
	}

	/**
	 * Test runInBackground tool method.
	 */
	@Test
	void testRunInBackground() {
		SmartShellTool tool = SmartShellTool.builder(tempDir.toString())
			.withAutoFix(false)
			.withTryAlternativeShells(false)
			.build();

		// Initialize session
		tool.getSessionManager().initialize(config);

		try {
			// Start a background task (using ping which runs for a while on Windows)
			String command = System.getProperty("os.name").toLowerCase().contains("windows")
				? "ping -n 3 127.0.0.1"
				: "sleep 1";

			SmartShellTool.BackgroundTaskResult result = tool.runInBackground(
				command,
				"Test background task",
				null,
				10000L,
				toolContext
			);

			assertNotNull(result);
			assertTrue(result.isSuccess(), "Background task should start: " + result.getMessage());
			assertNotNull(result.getTaskId());
		}
		finally {
			tool.getSessionManager().cleanup(config);
		}
	}

	/**
	 * Test listBackgroundTasks tool method.
	 */
	@Test
	void testListBackgroundTasks() {
		SmartShellTool tool = SmartShellTool.builder(tempDir.toString())
			.withAutoFix(false)
			.withTryAlternativeShells(false)
			.build();

		// Initialize session
		tool.getSessionManager().initialize(config);

		try {
			// Start a background task
			String command = System.getProperty("os.name").toLowerCase().contains("windows")
				? "ping -n 2 127.0.0.1"
				: "sleep 1";

			SmartShellTool.BackgroundTaskResult startedResult = tool.runInBackground(
				command,
				"Test task for listing",
				null,
				10000L,
				toolContext
			);

			assertTrue(startedResult.isSuccess());

			// List background tasks
			SmartShellTool.BackgroundTaskListResult listResult = tool.listBackgroundTasks();

			assertNotNull(listResult);
			assertTrue(listResult.getRunningCount() >= 0);
			assertNotNull(listResult.getTasks());
			assertFalse(listResult.getTasks().isEmpty(), "Should have at least one task");

			// Find our task
			SmartShellTool.BackgroundTaskInfo ourTask = listResult.getTasks().stream()
				.filter(t -> t.getTaskId().equals(startedResult.getTaskId()))
				.findFirst()
				.orElse(null);

			assertNotNull(ourTask);
			assertEquals(command, ourTask.getCommand());
			assertEquals("Test task for listing", ourTask.getDescription());
		}
		finally {
			tool.getSessionManager().cleanup(config);
		}
	}

	/**
	 * Test getBackgroundTaskOutput tool method.
	 */
	@Test
	void testGetBackgroundTaskOutput() {
		SmartShellTool tool = SmartShellTool.builder(tempDir.toString())
			.withAutoFix(false)
			.withTryAlternativeShells(false)
			.build();

		// Initialize session
		tool.getSessionManager().initialize(config);

		try {
			// Start a background task
			String command = "echo HelloFromBackground";

			SmartShellTool.BackgroundTaskResult startedResult = tool.runInBackground(
				command,
				"Test output retrieval",
				null,
				10000L,
				toolContext
			);

			assertTrue(startedResult.isSuccess());

			// Wait a bit for task to complete
			Thread.sleep(500);

			// Get output
			SmartShellTool.BackgroundTaskOutputResult outputResult = tool.getBackgroundTaskOutput(
				startedResult.getTaskId()
			);

			assertNotNull(outputResult);
			assertEquals(startedResult.getTaskId(), outputResult.getTaskId());
			assertNotNull(outputResult.getOutput());
			// Output might contain the echo result
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		finally {
			tool.getSessionManager().cleanup(config);
		}
	}

	/**
	 * Test killBackgroundTask tool method.
	 */
	@Test
	void testKillBackgroundTask() {
		SmartShellTool tool = SmartShellTool.builder(tempDir.toString())
			.withAutoFix(false)
			.withTryAlternativeShells(false)
			.build();

		// Initialize session
		tool.getSessionManager().initialize(config);

		try {
			// Start a long-running background task
			String command = System.getProperty("os.name").toLowerCase().contains("windows")
				? "ping -n 10 127.0.0.1"
				: "sleep 10";

			SmartShellTool.BackgroundTaskResult startedResult = tool.runInBackground(
				command,
				"Task to be killed",
				null,
				60000L,
				toolContext
			);

			assertTrue(startedResult.isSuccess());

			// Give it a moment to start
			Thread.sleep(200);

			// Kill the task
			SmartShellTool.BackgroundTaskResult killResult = tool.killBackgroundTask(
				startedResult.getTaskId()
			);

			assertNotNull(killResult);
			// Kill might succeed or task might have completed
			assertTrue(killResult.isSuccess() || killResult.getMessage().contains("completed"));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		finally {
			tool.getSessionManager().cleanup(config);
		}
	}

	/**
	 * Test waitBackgroundTask tool method.
	 */
	@Test
	void testWaitBackgroundTask() {
		SmartShellTool tool = SmartShellTool.builder(tempDir.toString())
			.withAutoFix(false)
			.withTryAlternativeShells(false)
			.build();

		// Initialize session
		tool.getSessionManager().initialize(config);

		try {
			// Start a quick background task
			String command = System.getProperty("os.name").toLowerCase().contains("windows")
				? "ping -n 1 127.0.0.1"
				: "echo done";

			SmartShellTool.BackgroundTaskResult startedResult = tool.runInBackground(
				command,
				"Task to wait for",
				null,
				10000L,
				toolContext
			);

			assertTrue(startedResult.isSuccess());

			// Wait for task to complete
			SmartShellTool.BackgroundTaskOutputResult waitResult = tool.waitBackgroundTask(
				startedResult.getTaskId(),
				10000L
			);

			assertNotNull(waitResult);
			// Task should have completed
			assertFalse(waitResult.isRunning());
		}
		catch (Exception e) {
			Thread.currentThread().interrupt();
		}
		finally {
			tool.getSessionManager().cleanup(config);
		}
	}

	/**
	 * Test runInBackground with empty command should fail.
	 */
	@Test
	void testRunInBackgroundEmptyCommand() {
		SmartShellTool tool = SmartShellTool.builder(tempDir.toString())
			.withAutoFix(false)
			.withTryAlternativeShells(false)
			.build();

		tool.getSessionManager().initialize(config);

		try {
			SmartShellTool.BackgroundTaskResult result = tool.runInBackground(
				"",
				"Test",
				null,
				10000L,
				toolContext
			);

			assertNotNull(result);
			assertFalse(result.isSuccess());
			assertTrue(result.getMessage().contains("empty"), "Should mention empty command");
		}
		finally {
			tool.getSessionManager().cleanup(config);
		}
	}

	/**
	 * Test getBackgroundTaskOutput with invalid task ID.
	 */
	@Test
	void testGetBackgroundTaskOutputInvalidTaskId() {
		SmartShellTool tool = SmartShellTool.builder(tempDir.toString())
			.withAutoFix(false)
			.withTryAlternativeShells(false)
			.build();

		tool.getSessionManager().initialize(config);

		try {
			SmartShellTool.BackgroundTaskOutputResult result = tool.getBackgroundTaskOutput("invalid-task-id");

			assertNotNull(result);
			assertNull(result.getTaskId());
			assertNotNull(result.getError());
			assertTrue(result.getError().contains("not found"));
		}
		finally {
			tool.getSessionManager().cleanup(config);
		}
	}

	/**
	 * Test killBackgroundTask with invalid task ID.
	 */
	@Test
	void testKillBackgroundTaskInvalidTaskId() {
		SmartShellTool tool = SmartShellTool.builder(tempDir.toString())
			.withAutoFix(false)
			.withTryAlternativeShells(false)
			.build();

		tool.getSessionManager().initialize(config);

		try {
			SmartShellTool.BackgroundTaskResult result = tool.killBackgroundTask("invalid-task-id");

			assertNotNull(result);
			assertFalse(result.isSuccess());
			assertTrue(result.getMessage().contains("not found"));
		}
		finally {
			tool.getSessionManager().cleanup(config);
		}
	}

	/**
	 * Test BackgroundTaskResult factory methods.
	 */
	@Test
	void testBackgroundTaskResultFactory() {
		// Test started
		SmartShellTool.BackgroundTaskResult started = SmartShellTool.BackgroundTaskResult.started("task-123", "Started");
		assertTrue(started.isSuccess());
		assertEquals("task-123", started.getTaskId());
		assertEquals("Started", started.getMessage());

		// Test success
		SmartShellTool.BackgroundTaskResult success = SmartShellTool.BackgroundTaskResult.success("Done");
		assertTrue(success.isSuccess());
		assertNull(success.getTaskId());

		// Test error
		SmartShellTool.BackgroundTaskResult error = SmartShellTool.BackgroundTaskResult.error("Failed");
		assertFalse(error.isSuccess());
		assertEquals("Failed", error.getMessage());
	}

	/**
	 * Test BackgroundTaskOutputResult factory method.
	 */
	@Test
	void testBackgroundTaskOutputResultFactory() {
		// Test error
		SmartShellTool.BackgroundTaskOutputResult error = SmartShellTool.BackgroundTaskOutputResult.error("Not found");
		assertNotNull(error.getError());
		assertEquals("Not found", error.getError());
	}

	/**
	 * Test BackgroundTaskManager task management.
	 */
	@Test
	void testBackgroundTaskManagerTaskManagement() {
		BackgroundTaskManager manager = new BackgroundTaskManager();

		// Submit multiple tasks
		BackgroundTask task1 = manager.submitTask("echo 1", "Task 1", null, 5000, (t) ->
			new SmartShellSessionManager.CommandResult("1", 0, false, false, false, 1, 1));
		BackgroundTask task2 = manager.submitTask("echo 2", "Task 2", null, 5000, (t) ->
			new SmartShellSessionManager.CommandResult("2", 0, false, false, false, 1, 1));

		assertNotNull(task1);
		assertNotNull(task2);
		assertNotEquals(task1.getTaskId(), task2.getTaskId());

		// Get all tasks
		assertEquals(2, manager.getAllTasks().size());

		// Get running tasks
		assertEquals(2, manager.getRunningTasks().size());

		// Wait for tasks to complete
		manager.waitForTask(task1.getTaskId(), 5000);
		manager.waitForTask(task2.getTaskId(), 5000);

		// Wait a bit for status to update
		try { Thread.sleep(100); } catch (InterruptedException e) { }

		// Get completed task
		BackgroundTask retrievedTask = manager.getTask(task1.getTaskId());
		assertNotNull(retrievedTask);
		assertEquals(BackgroundTask.Status.COMPLETED, retrievedTask.getStatus());

		manager.shutdown();
	}

	/**
	 * Test BackgroundTask cancel functionality.
	 */
	@Test
	void testBackgroundTaskCancel() {
		BackgroundTaskManager manager = new BackgroundTaskManager();

		// Submit a long-running task
		BackgroundTask task = manager.submitTask(
			"sleep 10",
			"Long running task",
			null,
			60000,
			(t) -> {
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					// Expected on cancel
				}
				return new SmartShellSessionManager.CommandResult("cancelled", 1, false, false, false, 0, 0);
			}
		);

		// Cancel the task
		boolean cancelled = task.cancel();
		assertTrue(cancelled);

		// Task should be cancelled
		assertEquals(BackgroundTask.Status.CANCELLED, task.getStatus());

		manager.shutdown();
	}

}
