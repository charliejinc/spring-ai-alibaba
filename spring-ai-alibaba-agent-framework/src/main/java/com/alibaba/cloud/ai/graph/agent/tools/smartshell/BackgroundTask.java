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

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a background shell task.
 * Similar to Claude Code's background task model.
 */
public class BackgroundTask {

	public enum Status {
		PENDING,      // Task submitted but not started
		RUNNING,      // Task is executing
		COMPLETED,    // Task finished successfully
		FAILED,       // Task failed with error
		CANCELLED,    // Task was cancelled
		TIMEOUT       // Task timed out
	}

	private final String taskId;
	private final String command;
	private final String description;
	private final String cwd;
	private final Instant startTime;
	private volatile Status status;
	private final AtomicReference<String> output = new AtomicReference<>("");
	private final AtomicReference<Integer> exitCode = new AtomicReference<>();
	private final AtomicReference<String> error = new AtomicReference<>();
	private volatile Instant endTime;
	private volatile long timeoutMs;
	private final CompletableFuture<SmartShellSessionManager.CommandResult> future;

	public BackgroundTask(String taskId, String command, String description,
						  String cwd, long timeoutMs,
						  CompletableFuture<SmartShellSessionManager.CommandResult> future) {
		this.taskId = taskId;
		this.command = command;
		this.description = description;
		this.cwd = cwd;
		this.startTime = Instant.now();
		this.status = Status.PENDING;
		this.timeoutMs = timeoutMs;
		this.future = future;
	}

	public String getTaskId() {
		return taskId;
	}

	public String getCommand() {
		return command;
	}

	public String getDescription() {
		return description;
	}

	public String getCwd() {
		return cwd;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public Status getStatus() {
		return status;
	}

	void setStatus(Status status) {
		this.status = status;
		if (status == Status.COMPLETED || status == Status.FAILED ||
			status == Status.CANCELLED || status == Status.TIMEOUT) {
			this.endTime = Instant.now();
		}
	}

	public String getOutput() {
		return output.get();
	}

	void appendOutput(String newOutput) {
		output.updateAndGet(current -> current + newOutput);
	}

	void setOutput(String output) {
		this.output.set(output);
	}

	public Integer getExitCode() {
		return exitCode.get();
	}

	void setExitCode(Integer exitCode) {
		this.exitCode.set(exitCode);
	}

	public String getError() {
		return error.get();
	}

	void setError(String error) {
		this.error.set(error);
	}

	public Instant getEndTime() {
		return endTime;
	}

	public long getTimeoutMs() {
		return timeoutMs;
	}

	void setTimeoutMs(long timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	public CompletableFuture<SmartShellSessionManager.CommandResult> getFuture() {
		return future;
	}

	/**
	 * Check if the task is still running.
	 */
	public boolean isRunning() {
		return status == Status.RUNNING || status == Status.PENDING;
	}

	/**
	 * Check if the task has completed (successfully or not).
	 */
	public boolean isCompleted() {
		return status == Status.COMPLETED || status == Status.FAILED ||
			   status == Status.CANCELLED || status == Status.TIMEOUT;
	}

	/**
	 * Get the duration in milliseconds if task has ended, or current duration if running.
	 */
	public long getDurationMs() {
		Instant end = endTime != null ? endTime : Instant.now();
		return end.toEpochMilli() - startTime.toEpochMilli();
	}

	/**
	 * Cancel the task.
	 */
	public boolean cancel() {
		if (isCompleted()) {
			return false;
		}
		setStatus(Status.CANCELLED);
		future.cancel(true);
		return true;
	}

	/**
	 * Convert to a result object when task completes.
	 */
	public SmartShellSessionManager.CommandResult toResult() {
		return new SmartShellSessionManager.CommandResult(
			getOutput(),
			getExitCode(),
			status == Status.TIMEOUT,
			false,
			false,
			0,
			getOutput() != null ? getOutput().length() : 0
		);
	}

	@Override
	public String toString() {
		return String.format("BackgroundTask{id='%s', status=%s, command='%s'}",
			taskId, status, command);
	}
}
