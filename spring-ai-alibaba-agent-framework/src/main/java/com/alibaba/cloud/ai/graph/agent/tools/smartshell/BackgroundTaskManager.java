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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Manages background shell tasks.
 * Similar to Claude Code's background task management.
 */
public class BackgroundTaskManager {

	private static final Logger log = LoggerFactory.getLogger(BackgroundTaskManager.class);

	private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "smart-shell-bg-" + r.hashCode());
		t.setDaemon(true);
		return t;
	});
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
		Thread t = new Thread(r, "smart-shell-scheduler");
		t.setDaemon(true);
		return t;
	});

	private static final long DEFAULT_MAX_TIMEOUT_MS = 600000; // 10 minutes
	private static final long TASK_CLEANUP_DELAY_MS = 300000;  // 5 minutes after completion

	/**
	 * Submit a new background task.
	 *
	 * @param command the command to execute
	 * @param description optional description of what the command does
	 * @param cwd working directory
	 * @param timeoutMs timeout in milliseconds
	 * @param taskFunction function that executes the task and returns result
	 * @return the created BackgroundTask
	 */
	public BackgroundTask submitTask(String command, String description, String cwd,
									 long timeoutMs,
									 Function<BackgroundTask, SmartShellSessionManager.CommandResult> taskFunction) {
		String taskId = generateTaskId();

		// Cap timeout at maximum
		long effectiveTimeout = Math.min(timeoutMs, DEFAULT_MAX_TIMEOUT_MS);

		CompletableFuture<SmartShellSessionManager.CommandResult> future = new CompletableFuture<>();

		BackgroundTask task = new BackgroundTask(taskId, command, description, cwd, effectiveTimeout, future);
		tasks.put(taskId, task);

		// Submit the task
		executor.submit(() -> {
			task.setStatus(BackgroundTask.Status.RUNNING);
			log.info("Starting background task {}: {}", taskId, command);

			try {
				// Set up timeout handler
				ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
				timeoutScheduler.schedule(() -> {
					if (task.isRunning()) {
						log.warn("Background task {} timed out after {}ms", taskId, effectiveTimeout);
						task.setStatus(BackgroundTask.Status.TIMEOUT);
						task.setError("Task timed out after " + effectiveTimeout + "ms");
						future.complete(task.toResult());
					}
					timeoutScheduler.shutdown();
				}, effectiveTimeout, TimeUnit.MILLISECONDS);

				// Execute the task
				SmartShellSessionManager.CommandResult result = taskFunction.apply(task);

				// Update task with result
				task.setOutput(result.getOutput());
				task.setExitCode(result.getExitCode());

				if (!task.isCompleted()) { // Only update if not already marked (e.g., timeout)
					if (result.isSuccess()) {
						task.setStatus(BackgroundTask.Status.COMPLETED);
					} else {
						task.setStatus(BackgroundTask.Status.FAILED);
						if (result.getOutput() != null && !result.isSuccess()) {
							task.setError(result.getOutput());
						}
					}
				}

				future.complete(result);
				log.info("Background task {} completed with status: {}", taskId, task.getStatus());

			} catch (Exception e) {
				log.error("Background task {} failed with exception", taskId, e);
				task.setStatus(BackgroundTask.Status.FAILED);
				task.setError(e.getMessage());
				future.completeExceptionally(e);
			}

			// Schedule cleanup
			scheduler.schedule(() -> {
				tasks.remove(taskId);
				log.debug("Cleaned up background task {}", taskId);
			}, TASK_CLEANUP_DELAY_MS, TimeUnit.MILLISECONDS);
		});

		return task;
	}

	/**
	 * Get a task by ID.
	 */
	public BackgroundTask getTask(String taskId) {
		return tasks.get(taskId);
	}

	/**
	 * Get all tasks.
	 */
	public List<BackgroundTask> getAllTasks() {
		return List.copyOf(tasks.values());
	}

	/**
	 * Get all running tasks.
	 */
	public List<BackgroundTask> getRunningTasks() {
		return tasks.values().stream()
			.filter(BackgroundTask::isRunning)
			.toList();
	}

	/**
	 * Stop a task by ID.
	 */
	public boolean stopTask(String taskId) {
		BackgroundTask task = tasks.get(taskId);
		if (task == null) {
			return false;
		}
		return task.cancel();
	}

	/**
	 * Wait for a task to complete.
	 */
	public SmartShellSessionManager.CommandResult waitForTask(String taskId, long timeoutMs) {
		BackgroundTask task = tasks.get(taskId);
		if (task == null) {
			return null;
		}

		try {
			if (timeoutMs > 0) {
				return task.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
			} else {
				return task.getFuture().get();
			}
		} catch (Exception e) {
			log.error("Error waiting for task {}", taskId, e);
			return null;
		}
	}

	/**
	 * Get task output (non-blocking).
	 */
	public String getTaskOutput(String taskId) {
		BackgroundTask task = tasks.get(taskId);
		return task != null ? task.getOutput() : null;
	}

	/**
	 * Get task status.
	 */
	public BackgroundTask.Status getTaskStatus(String taskId) {
		BackgroundTask task = tasks.get(taskId);
		return task != null ? task.getStatus() : null;
	}

	/**
	 * Cleanup completed tasks.
	 */
	public void cleanupCompletedTasks() {
		tasks.entrySet().removeIf(entry -> entry.getValue().isCompleted());
	}

	/**
	 * Shutdown the manager.
	 */
	public void shutdown() {
		log.info("Shutting down BackgroundTaskManager");

		// Cancel all running tasks
		tasks.values().stream()
			.filter(BackgroundTask::isRunning)
			.forEach(BackgroundTask::cancel);

		executor.shutdown();
		scheduler.shutdown();

		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
			if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				scheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
			scheduler.shutdownNow();
		}
	}

	private String generateTaskId() {
		return "task_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
	}
}
