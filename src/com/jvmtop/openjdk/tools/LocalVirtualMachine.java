package com.jvmtop.openjdk.tools;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.sun.tools.javac.resources.compiler;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import sun.jvmstat.monitor.HostIdentifier;
import sun.jvmstat.monitor.MonitorException;
import sun.jvmstat.monitor.MonitoredHost;
import sun.jvmstat.monitor.MonitoredVm;
import sun.jvmstat.monitor.MonitoredVmUtil;
import sun.jvmstat.monitor.VmIdentifier;

public class LocalVirtualMachine {
	private String address;
	private String commandLine;
	private String displayName;
	private int vmid;
	private boolean isAttachSupported;
	private static boolean J9Mode = false;
	private static final String LOCAL_CONNECTOR_ADDRESS_PROP = "com.sun.management.jmxremote.localConnectorAddress";

	static {
		
		J9Mode = true;
		System.setProperty("com.ibm.tools.attach.timeout", "5");
	}

	public static boolean isJ9Mode() {
		return J9Mode;
	}

	public LocalVirtualMachine(int vmid, String commandLine, boolean canAttach,
			String connectorAddress) {
		this.vmid = vmid;
		this.commandLine = commandLine;
		this.address = connectorAddress;
		this.isAttachSupported = canAttach;
		this.displayName = getDisplayName(commandLine);
	}

	private static String getDisplayName(String commandLine) {
		String[] res = commandLine.split(" ", 2);
		if (res[0].endsWith(".jar")) {
			File jarfile = new File(res[0]);
			String displayName = jarfile.getName();
			if (res.length == 2) {
				displayName = displayName + " " + res[1];
			}
			return displayName;
		}
		return commandLine;
	}

	public int vmid() {
		return this.vmid;
	}

	public boolean isManageable() {
		return (this.address != null);
	}

	public boolean isAttachable() {
		return this.isAttachSupported;
	}

	public void startManagementAgent() throws IOException {
		if (this.address != null) {
			return;
		}

		if (!(isAttachable())) {
			throw new IOException("This virtual machine \"" + this.vmid
					+ "\" does not support dynamic attach.");
		}

		loadManagementAgent();

		if (this.address != null) {
			return;
		}
		throw new IOException("Fails to find connector address");
	}

	public String connectorAddress() {
		return this.address;
	}

	public String displayName() {
		return this.displayName;
	}

	public String toString() {
		return this.commandLine;
	}

	public static Map<Integer, LocalVirtualMachine> getAllVirtualMachines() {
		Map map = new HashMap();
		getMonitoredVMs(map, Collections.EMPTY_MAP);
		getAttachableVMs(map, Collections.EMPTY_MAP);
		return map;
	}

	public static Map<Integer, LocalVirtualMachine> getNewVirtualMachines(
			Map<Integer, LocalVirtualMachine> existingVmMap) {
		Map map = new HashMap(existingVmMap);
		getMonitoredVMs(map, existingVmMap);
		getAttachableVMs(map, existingVmMap);
		return map;
	}

	private static void getMonitoredVMs(Map<Integer, LocalVirtualMachine> map,
			Map<Integer, LocalVirtualMachine> existingMap) {
		if (J9Mode) {
			return;
		}
		MonitoredHost host;
		Set vms;
		try {
			host = MonitoredHost.getMonitoredHost(new HostIdentifier());
			vms = host.activeVms();
		} catch (URISyntaxException sx) {
			throw new InternalError(sx.getMessage());
		} catch (MonitorException mx) {
			throw new InternalError(mx.getMessage());
		}
		for (Iterator localIterator = vms.iterator(); localIterator.hasNext();) {
			Object vmid = localIterator.next();

			if (existingMap.containsKey(vmid)) {
				continue;
			}

			if (!(vmid instanceof Integer))
				continue;
			int pid = ((Integer) vmid).intValue();
			String name = vmid.toString();
			boolean attachable = false;
			String address = null;
			try {
				MonitoredVm mvm = host.getMonitoredVm(new VmIdentifier(name));

				name = MonitoredVmUtil.commandLine(mvm);
				attachable = MonitoredVmUtil.isAttachable(mvm);
				address = ConnectorAddressLink.importFrom(pid);
				mvm.detach();
			} catch (Exception localException) {
			}

			map.put((Integer) vmid, new LocalVirtualMachine(pid, name,
					attachable, address));
		}
	}

	private static void getAttachableVMs(Map<Integer, LocalVirtualMachine> map,
			Map<Integer, LocalVirtualMachine> existingVmMap) {
		List vms = VirtualMachine.list();
		for (VirtualMachineDescriptor vmd : vms) {
			try {
				Integer vmid = Integer.valueOf(vmd.id());
				if ((!(map.containsKey(vmid)))
						&& (!(existingVmMap.containsKey(vmid)))) {
					boolean attachable = false;
					String address = null;
					try {
						VirtualMachine vm = VirtualMachine.attach(vmd);
						attachable = true;
						Properties agentProps = vm.getAgentProperties();
						address = (String) agentProps
								.get("com.sun.management.jmxremote.localConnectorAddress");
						vm.detach();
					} catch (AttachNotSupportedException x) {
						x.printStackTrace(System.err);
					} catch (NullPointerException e) {
						e.printStackTrace(System.err);
					} catch (IOException localIOException) {
					}

					map.put(vmid,
							new LocalVirtualMachine(vmid.intValue(), vmd
									.displayName(), attachable, address));
				}
			} catch (NumberFormatException localNumberFormatException) {
			}
		}
	}

	public static LocalVirtualMachine getLocalVirtualMachine(int vmid)
			throws Exception {
		Map map = getAllVirtualMachines();
		LocalVirtualMachine lvm = (LocalVirtualMachine) map.get(Integer
				.valueOf(vmid));
		if (lvm == null) {
			boolean attachable = false;
			String address = null;
			String name = String.valueOf(vmid);

			VirtualMachine vm = VirtualMachine.attach(name);
			attachable = true;
			Properties agentProps = vm.getAgentProperties();
			address = (String) agentProps
					.get("com.sun.management.jmxremote.localConnectorAddress");
			vm.detach();
			lvm = new LocalVirtualMachine(vmid, name, attachable, address);
		}

		return lvm;
	}

	public static LocalVirtualMachine getDelegateMachine(VirtualMachine vm)
			throws IOException {
		boolean attachable = false;
		String address = null;
		String name = String.valueOf(vm.id());

		attachable = true;
		Properties agentProps = vm.getAgentProperties();
		address = (String) agentProps
				.get("com.sun.management.jmxremote.localConnectorAddress");
		vm.detach();
		return new LocalVirtualMachine(Integer.parseInt(vm.id()), name,
				attachable, address);
	}

	private void loadManagementAgent() throws IOException {
		VirtualMachine vm = null;
		String name = String.valueOf(this.vmid);
		try {
			vm = VirtualMachine.attach(name);
		} catch (AttachNotSupportedException x) {
			IOException ioe = new IOException(x.getMessage());
			ioe.initCause(x);
			throw ioe;
		}

		String home = vm.getSystemProperties().getProperty("java.home");

		String agent = home + File.separator + "jre" + File.separator + "lib"
				+ File.separator + "management-agent.jar";
		File f = new File(agent);
		if (!(f.exists())) {
			agent = home + File.separator + "lib" + File.separator
					+ "management-agent.jar";
			f = new File(agent);
			if (!(f.exists())) {
				throw new IOException("Management agent not found");
			}
		}

		agent = f.getCanonicalPath();
		IOException ioe;
		try {
			vm.loadAgent(agent, "com.sun.management.jmxremote");
		} catch (AgentLoadException x) {
			ioe = new IOException(x.getMessage());
			ioe.initCause(x);
			throw ioe;
		} catch (AgentInitializationException x) {
			ioe = new IOException(x.getMessage());
			ioe.initCause(x);
			throw ioe;
		}

		if (J9Mode) {
			Properties localProperties = vm.getSystemProperties();
			this.address = ((String) localProperties
					.get("com.sun.management.jmxremote.localConnectorAddress"));
		} else {
			Properties agentProps = vm.getAgentProperties();
			this.address = ((String) agentProps
					.get("com.sun.management.jmxremote.localConnectorAddress"));
		}

		vm.detach();
	}
}