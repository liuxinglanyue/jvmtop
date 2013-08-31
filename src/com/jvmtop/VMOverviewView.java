package com.jvmtop;

import com.jvmtop.openjdk.tools.LocalVirtualMachine;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class VMOverviewView extends AbstractConsoleView {
	private List<VMInfo> vmInfoList = new ArrayList();

	private Map<Integer, LocalVirtualMachine> vmMap = new HashMap();

	public void printView() throws Exception {
		printHeader();

		scanForNewVMs();

		updateVMs(this.vmInfoList);

		Collections.sort(this.vmInfoList, VMInfo.CPU_LOAD_COMPARATOR);

		for (VMInfo vmInfo : this.vmInfoList) {
			if ((vmInfo.getState() == VMInfoState.ATTACHED)
					|| (vmInfo.getState() == VMInfoState.ATTACHED_UPDATE_ERROR)) {
				printVM(vmInfo);
			} else {
				if (vmInfo.getState() != VMInfoState.ERROR_DURING_ATTACH)
					continue;
				System.out.printf(
						"%5d %-15.15s [ERROR: Could not attach to VM] %n",
						new Object[] { vmInfo.getId(),
								getEntryPointClass(vmInfo.getDisplayName()) });
			}
		}
	}

	private String getEntryPointClass(String name) {
		if (name.indexOf(32) > 0) {
			name = name.substring(0, name.indexOf(32));
		}
		return rightStr(name, 15);
	}

	private void printVM(VMInfo vmInfo) throws Exception {
		String deadlockState = "";
		if (vmInfo.hasDeadlockThreads()) {
			deadlockState = "!D";
		}

		System.out
				.printf("%5d %-15.15s %4dm %4dm %4dm %4dm %5.2f%% %5.2f%% %-5.5s %8.8s %4d %2.2s%n",
						new Object[] { vmInfo.getId(),
								getEntryPointClass(vmInfo.getDisplayName()),
								Long.valueOf(toMB(vmInfo.getHeapUsed())),
								Long.valueOf(toMB(vmInfo.getHeapMax())),
								Long.valueOf(toMB(vmInfo.getNonHeapUsed())),
								Long.valueOf(toMB(vmInfo.getNonHeapMax())),
								Double.valueOf(vmInfo.getCpuLoad() * 100.0D),
								Double.valueOf(vmInfo.getGcLoad() * 100.0D),
								vmInfo.getVMVersion(), vmInfo.getOSUser(),
								Long.valueOf(vmInfo.getThreadCount()),
								deadlockState });
	}

	private void updateVMs(List<VMInfo> vmList) throws Exception {
		for (VMInfo vmInfo : vmList) {
			vmInfo.update();
		}
	}

	private void scanForNewVMs() {
		Map machines = LocalVirtualMachine.getNewVirtualMachines(this.vmMap);
		Set set = machines.entrySet();

		for (Map.Entry entry : set) {
			LocalVirtualMachine localvm = (LocalVirtualMachine) entry
					.getValue();
			int vmid = localvm.vmid();

			if (this.vmMap.containsKey(Integer.valueOf(vmid)))
				continue;
			VMInfo vmInfo = VMInfo.processNewVM(localvm, vmid);
			this.vmInfoList.add(vmInfo);
		}

		this.vmMap = machines;
	}

	private void printHeader() {
		System.out.printf(
				"%5s %-15.15s %5s %5s %5s %5s %6s %6s %5s %8s %4s %2s%n",
				new Object[] { "PID", "MAIN-CLASS", "HPCUR", "HPMAX", "NHCUR",
						"NHMAX", "CPU", "GC", "VM", "USERNAME", "#T", "DL" });
	}
}