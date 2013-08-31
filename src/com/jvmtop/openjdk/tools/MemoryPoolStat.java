package com.jvmtop.openjdk.tools;

import java.lang.management.MemoryUsage;

public class MemoryPoolStat {
	private String poolName;
	private long usageThreshold;
	private MemoryUsage usage;
	private long lastGcId;
	private long lastGcStartTime;
	private long lastGcEndTime;
	private long collectThreshold;
	private MemoryUsage beforeGcUsage;
	private MemoryUsage afterGcUsage;

	MemoryPoolStat(String name, long usageThreshold, MemoryUsage usage,
			long lastGcId, long lastGcStartTime, long lastGcEndTime,
			long collectThreshold, MemoryUsage beforeGcUsage,
			MemoryUsage afterGcUsage) {
		this.poolName = name;
		this.usageThreshold = usageThreshold;
		this.usage = usage;
		this.lastGcId = lastGcId;
		this.lastGcStartTime = lastGcStartTime;
		this.lastGcEndTime = lastGcEndTime;
		this.collectThreshold = collectThreshold;
		this.beforeGcUsage = beforeGcUsage;
		this.afterGcUsage = afterGcUsage;
	}

	public String getPoolName() {
		return this.poolName;
	}

	public MemoryUsage getUsage() {
		return this.usage;
	}

	public long getUsageThreshold() {
		return this.usageThreshold;
	}

	public long getCollectionUsageThreshold() {
		return this.collectThreshold;
	}

	public long getLastGcId() {
		return this.lastGcId;
	}

	public long getLastGcStartTime() {
		return this.lastGcStartTime;
	}

	public long getLastGcEndTime() {
		return this.lastGcEndTime;
	}

	public MemoryUsage getBeforeGcUsage() {
		return this.beforeGcUsage;
	}

	public MemoryUsage getAfterGcUsage() {
		return this.beforeGcUsage;
	}
}