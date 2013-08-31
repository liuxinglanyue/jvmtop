package com.jvmtop;

import com.jvmtop.openjdk.tools.LocalVirtualMachine;
import com.jvmtop.openjdk.tools.ProxyClient;
import java.io.PrintStream;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class VMDetailView extends AbstractConsoleView {
	private VMInfo vmInfo_;
	private boolean sortByTotalCPU_ = false;

	private Map<Long, Long> previousThreadCPUMillis = new HashMap();

	public VMDetailView(int vmid) throws Exception {
		LocalVirtualMachine localVirtualMachine = LocalVirtualMachine
				.getLocalVirtualMachine(vmid);
		this.vmInfo_ = VMInfo.processNewVM(localVirtualMachine, vmid);
	}

	public boolean isSortByTotalCPU() {
		return this.sortByTotalCPU_;
	}

	public void setSortByTotalCPU(boolean sortByTotalCPU) {
		this.sortByTotalCPU_ = sortByTotalCPU;
	}

	public void printView() throws Exception {
		this.vmInfo_.update();

		Map properties = this.vmInfo_.getRuntimeMXBean().getSystemProperties();

		String command = (String) properties.get("sun.java.command");
		if (command != null) {
			String[] commandArray = command.split(" ");

			List commandList = Arrays.asList(commandArray);
			commandList = commandList.subList(1, commandList.size());

			System.out.printf(" PID %d: %s %n",
					new Object[] { this.vmInfo_.getId(), commandArray[0] });

			String argJoin = join(commandList, " ");
			if (argJoin.length() > 67) {
				System.out.printf(" ARGS: %s[...]%n",
						new Object[] { leftStr(argJoin, 67) });
			} else {
				System.out.printf(" ARGS: %s%n", new Object[] { argJoin });
			}
		} else {
			System.out.printf(" PID %d: %n",
					new Object[] { this.vmInfo_.getId() });
			System.out.printf(" ARGS: [UNKNOWN] %n", new Object[0]);
		}

		String join = join(this.vmInfo_.getRuntimeMXBean().getInputArguments(),
				" ");
		if (join.length() > 65) {
			System.out.printf(" VMARGS: %s[...]%n",
					new Object[] { leftStr(join, 65) });
		} else {
			System.out.printf(" VMARGS: %s%n", new Object[] { join });
		}

		System.out.printf(
				" VM: %s %s %s%n",
				new Object[] { properties.get("java.vendor"),
						properties.get("java.vm.name"),
						properties.get("java.version") });
		System.out
				.printf(" UP: %-7s #THR: %-4d #THRPEAK: %-4d #THRCREATED: %-4d USER: %-12s%n",
						new Object[] {
								toHHMM(this.vmInfo_.getRuntimeMXBean()
										.getUptime()),
								Long.valueOf(this.vmInfo_.getThreadCount()),
								Integer.valueOf(this.vmInfo_.getThreadMXBean()
										.getPeakThreadCount()),
								Long.valueOf(this.vmInfo_.getThreadMXBean()
										.getTotalStartedThreadCount()),
								this.vmInfo_.getOSUser() });

		System.out
				.printf(" GC-Time: %-7s  #GC-Runs: %-8d  #TotalLoadedClasses: %-8d%n",
						new Object[] {
								toHHMM(this.vmInfo_.getGcTime()),
								Long.valueOf(this.vmInfo_.getGcCount()),
								Long.valueOf(this.vmInfo_
										.getTotalLoadedClassCount()) });

		System.out
				.printf(" CPU: %5.2f%% GC: %5.2f%% HEAP:%4dm /%4dm NONHEAP:%4dm /%4dm%n",
						new Object[] {
								Double.valueOf(this.vmInfo_.getCpuLoad() * 100.0D),
								Double.valueOf(this.vmInfo_.getGcLoad() * 100.0D),
								Long.valueOf(toMB(this.vmInfo_.getHeapUsed())),
								Long.valueOf(toMB(this.vmInfo_.getHeapMax())),
								Long.valueOf(toMB(this.vmInfo_.getNonHeapUsed())),
								Long.valueOf(toMB(this.vmInfo_.getNonHeapMax())) });

		Map newThreadCPUMillis = new HashMap();

		Map cpuTimeMap = new TreeMap();
		long[] arrayOfLong;
		int j = (arrayOfLong = this.vmInfo_.getThreadMXBean().getAllThreadIds()).length;
		for (int i = 0; i < j; ++i) {
			Long tid = Long.valueOf(arrayOfLong[i]);

			long threadCpuTime = this.vmInfo_.getThreadMXBean()
					.getThreadCpuTime(tid.longValue());
			long deltaThreadCpuTime = 0L;
			if (this.previousThreadCPUMillis.containsKey(tid)) {
				deltaThreadCpuTime = threadCpuTime
						- ((Long) this.previousThreadCPUMillis.get(tid))
								.longValue();

				cpuTimeMap.put(tid, Long.valueOf(deltaThreadCpuTime));
			}
			newThreadCPUMillis.put(tid, Long.valueOf(threadCpuTime));
		}

		cpuTimeMap = sortByValue(cpuTimeMap, true);
		System.out.println();

		System.out
				.printf("  TID   NAME                                    STATE    CPU  TOTALCPU BLOCKEDBY%n",
						new Object[0]);

		int displayedThreads = 0;
		for (Long tid : cpuTimeMap.keySet()) {
			ThreadInfo info = this.vmInfo_.getThreadMXBean().getThreadInfo(
					tid.longValue());
			++displayedThreads;
			if (displayedThreads > 10) {
				break;
			}

			if (info == null)
				continue;
			System.out.printf(
					" %6d %-30s  %13s %5.2f%%    %5.2f%% %5s %n",
					new Object[] {
							tid,
							leftStr(info.getThreadName(), 30),
							info.getThreadState(),
							Double.valueOf(getThreadCPUUtilization(
									((Long) cpuTimeMap.get(tid)).longValue(),
									this.vmInfo_.getDeltaUptime())),
							Double.valueOf(getThreadCPUUtilization(
									this.vmInfo_.getThreadMXBean()
											.getThreadCpuTime(tid.longValue()),
									this.vmInfo_.getProxyClient()
											.getProcessCpuTime(), 1.0D)),
							getBlockedThread(info) });
		}

		if (newThreadCPUMillis.size() >= 10) {
			System.out
					.println(" Note: Only top 10 threads (according cpu load) are shown!");
		}
		this.previousThreadCPUMillis = newThreadCPUMillis;
	}

	private String getBlockedThread(ThreadInfo info) {
		if (info.getLockOwnerId() >= 0L) {
			return info.getLockOwnerId();
		}

		return "";
	}

	private double getThreadCPUUtilization(long deltaThreadCpuTime,
			long totalTime) {
		return getThreadCPUUtilization(deltaThreadCpuTime, totalTime,
				1000000.0D);
	}

	private double getThreadCPUUtilization(long deltaThreadCpuTime,
			long totalTime, double factor) {
		if (totalTime == 0L) {
			return 0.0D;
		}
		return (deltaThreadCpuTime / factor / totalTime * 100.0D);
	}
}