package com.jvmtop;

import com.jvmtop.openjdk.tools.LocalVirtualMachine;
import com.jvmtop.openjdk.tools.ProxyClient;
import com.sun.tools.attach.AttachNotSupportedException;
import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class VMInfo {
	private ProxyClient proxyClient = null;
	private OperatingSystemMXBean osBean;
	private RuntimeMXBean runtimeMXBean;
	private Collection<GarbageCollectorMXBean> gcMXBeans;
	private long lastGcTime;
	private long lastUpTime = -1L;

	private long lastCPUTime = -1L;

	private long gcCount = 0L;

	private double cpuLoad = 0.0D;

	private double gcLoad = 0.0D;
	private MemoryMXBean memoryMXBean;
	private MemoryUsage heapMemoryUsage;
	private MemoryUsage nonHeapMemoryUsage;
	private ThreadMXBean threadMXBean;
	private VMInfoState state_ = VMInfoState.INIT;

	private String rawId_ = null;
	private LocalVirtualMachine localVm_;
	public static final Comparator<VMInfo> USED_HEAP_COMPARATOR = new UsedHeapComparator();

	public static final Comparator<VMInfo> CPU_LOAD_COMPARATOR = new CPULoadComparator();
	private long deltaUptime_;
	private long deltaCpuTime_;
	private long deltaGcTime_;
	private long totalLoadedClassCount_;
	private ClassLoadingMXBean classLoadingMXBean_;

	public VMInfo(ProxyClient proxyClient, LocalVirtualMachine localVm,
			String rawId) throws Exception {
		this.localVm_ = localVm;
		this.rawId_ = rawId;
		this.proxyClient = proxyClient;

		this.state_ = VMInfoState.ATTACHED;
		update();
	}

	public static VMInfo processNewVM(LocalVirtualMachine localvm, int vmid) {
		try {
			if ((localvm == null) || (!(localvm.isAttachable()))) {
				return createDeadVM(vmid, localvm);
			}
			return attachToVM(localvm, vmid);
		} catch (Exception e) {
		}

		return createDeadVM(vmid, localvm);
	}

	private static VMInfo attachToVM(LocalVirtualMachine localvm, int vmid)
			throws AttachNotSupportedException, IOException,
			NoSuchMethodException, IllegalAccessException,
			InvocationTargetException, Exception {
		ProxyClient proxyClient = ProxyClient.getProxyClient(localvm);
		proxyClient.connect();
		return new VMInfo(proxyClient, localvm, vmid);
	}

	private VMInfo() {
	}

	public static VMInfo createDeadVM(int vmid, LocalVirtualMachine localVm) {
		VMInfo vmInfo = new VMInfo();
		vmInfo.state_ = VMInfoState.ERROR_DURING_ATTACH;
		vmInfo.localVm_ = localVm;
		return vmInfo;
	}

	public VMInfoState getState() {
		return this.state_;
	}

	public void update() throws Exception {
		if ((this.state_ == VMInfoState.ERROR_DURING_ATTACH)
				|| (this.state_ == VMInfoState.DETACHED)) {
			return;
		}

		if (this.proxyClient.isDead()) {
			this.state_ = VMInfoState.DETACHED;
			return;
		}

		try {
			this.proxyClient.flush();

			this.osBean = this.proxyClient.getSunOperatingSystemMXBean();
			this.runtimeMXBean = this.proxyClient.getRuntimeMXBean();
			this.gcMXBeans = this.proxyClient.getGarbageCollectorMXBeans();
			this.classLoadingMXBean_ = this.proxyClient.getClassLoadingMXBean();
			this.memoryMXBean = this.proxyClient.getMemoryMXBean();
			this.heapMemoryUsage = this.memoryMXBean.getHeapMemoryUsage();
			this.nonHeapMemoryUsage = this.memoryMXBean.getNonHeapMemoryUsage();
			this.threadMXBean = this.proxyClient.getThreadMXBean();

			updateInternal();
		} catch (Exception e) {
			e.printStackTrace(System.err);
			this.state_ = VMInfoState.ATTACHED_UPDATE_ERROR;
		}
	}

	private void updateInternal() throws Exception {
		long uptime = this.runtimeMXBean.getUptime();

		long cpuTime = this.proxyClient.getProcessCpuTime();

		long gcTime = sumGCTimes();
		this.gcCount = sumGCCount();
		if ((this.lastUpTime > 0L) && (this.lastCPUTime > 0L) && (gcTime > 0L)) {
			this.deltaUptime_ = (uptime - this.lastUpTime);
			this.deltaCpuTime_ = ((cpuTime - this.lastCPUTime) / 1000000L);
			this.deltaGcTime_ = (gcTime - this.lastGcTime);

			this.gcLoad = calcLoad(this.deltaCpuTime_, this.deltaGcTime_);
			this.cpuLoad = calcLoad(this.deltaUptime_, this.deltaCpuTime_);
		}

		this.lastUpTime = uptime;
		this.lastCPUTime = cpuTime;
		this.lastGcTime = gcTime;

		this.totalLoadedClassCount_ = this.classLoadingMXBean_
				.getTotalLoadedClassCount();
	}

	private double calcLoad(double deltaUptime, double deltaTime) {
		if (deltaTime <= 0.0D) {
			return 0.0D;
		}
		return Math.min(99.0D,
				deltaTime / deltaUptime * this.osBean.getAvailableProcessors());
	}

	private long sumGCTimes() {
		long sum = 0L;
		for (GarbageCollectorMXBean mxBean : this.gcMXBeans) {
			sum += mxBean.getCollectionTime();
		}
		return sum;
	}

	private long sumGCCount() {
		long sum = 0L;
		for (GarbageCollectorMXBean mxBean : this.gcMXBeans) {
			sum += mxBean.getCollectionCount();
		}
		return sum;
	}

	public long getHeapUsed() {
		return this.heapMemoryUsage.getUsed();
	}

	public long getHeapMax() {
		return this.heapMemoryUsage.getMax();
	}

	public long getNonHeapUsed() {
		return this.nonHeapMemoryUsage.getUsed();
	}

	public long getNonHeapMax() {
		return this.nonHeapMemoryUsage.getMax();
	}

	public long getTotalLoadedClassCount() {
		return this.totalLoadedClassCount_;
	}

	public boolean hasDeadlockThreads() {
		return ((this.threadMXBean.findDeadlockedThreads() != null) || (this.threadMXBean
				.findMonitorDeadlockedThreads() != null));
	}

	public long getThreadCount() {
		return this.threadMXBean.getThreadCount();
	}

	public double getCpuLoad() {
		return this.cpuLoad;
	}

	public double getGcLoad() {
		return this.gcLoad;
	}

	public ProxyClient getProxyClient() {
		return this.proxyClient;
	}

	public String getDisplayName() {
		return this.localVm_.displayName();
	}

	public Integer getId() {
		return Integer.valueOf(this.localVm_.vmid());
	}

	public String getRawId() {
		return this.rawId_;
	}

	public long getGcCount() {
		return this.gcCount;
	}

	public String getVMVersion() {
		return extractShortVer(this.runtimeMXBean);
	}

	public String getOSUser() {
		return ((String) this.runtimeMXBean.getSystemProperties().get(
				"user.name"));
	}

	public long getGcTime() {
		return this.lastGcTime;
	}

	public RuntimeMXBean getRuntimeMXBean() {
		return this.runtimeMXBean;
	}

	public Collection<GarbageCollectorMXBean> getGcMXBeans() {
		return this.gcMXBeans;
	}

	public MemoryMXBean getMemoryMXBean() {
		return this.memoryMXBean;
	}

	public ThreadMXBean getThreadMXBean() {
		return this.threadMXBean;
	}

	public OperatingSystemMXBean getOSBean() {
		return this.osBean;
	}

	public long getDeltaUptime() {
		return this.deltaUptime_;
	}

	public long getDeltaCpuTime() {
		return this.deltaCpuTime_;
	}

	public long getDeltaGcTime() {
		return this.deltaGcTime_;
	}

	public static String extractShortVer(RuntimeMXBean runtimeMXBean) {
		String vmVer = (String) runtimeMXBean.getSystemProperties().get(
				"java.runtime.version");

		String vmVendor = (String) runtimeMXBean.getSystemProperties().get(
				"java.vendor");

		Pattern pattern = Pattern.compile("[0-9]\\.([0-9])\\.0_([0-9]+)-.*");
		Matcher matcher = pattern.matcher(vmVer);
		if (matcher.matches()) {
			return vmVendor.charAt(0) + matcher.group(1) + "U"
					+ matcher.group(2);
		}

		pattern = Pattern.compile(".*-(.*)_.*");
		matcher = pattern.matcher(vmVer);
		if (matcher.matches()) {
			return vmVendor.charAt(0) + matcher.group(1).substring(2, 6);
		}
		return vmVer;
	}

	private static final class CPULoadComparator implements Comparator<VMInfo> {
		public int compare(VMInfo o1, VMInfo o2) {
			return Double.valueOf(o2.getCpuLoad()).compareTo(
					Double.valueOf(o1.getCpuLoad()));
		}
	}

	private static final class UsedHeapComparator implements Comparator<VMInfo> {
		public int compare(VMInfo o1, VMInfo o2) {
			return Long.valueOf(o1.getHeapUsed()).compareTo(
					Long.valueOf(o2.getHeapUsed()));
		}
	}
}