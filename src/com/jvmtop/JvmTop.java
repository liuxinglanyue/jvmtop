package com.jvmtop;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Iterator;
import java.util.Locale;

public class JvmTop {
	public static void main(String[] args) throws Exception {
		if (args.length > 0) {
			if ("--sysinfo".equals(args[0])) {
				outputSystemProps();
			} else {
				int pid = Integer.parseInt(args[0]);
				new JvmTop().run(new VMDetailView(pid));
			}

		} else {
			new JvmTop().run(new VMOverviewView());
		}
	}

	private static void outputSystemProps() {
		for (Iterator localIterator = System.getProperties().keySet()
				.iterator(); localIterator.hasNext();) {
			Object key = localIterator.next();

			System.out.println(key
					+ "="
					+ System.getProperty(new StringBuilder().append(key)
							.toString()));
		}
	}

	protected void run(ConsoleView view) throws Exception {
		Locale.setDefault(Locale.US);
		System.setOut(new PrintStream(new BufferedOutputStream(
				new FileOutputStream(FileDescriptor.out)), false));
		while (true) {
			if (System.getProperty("os.name").contains("Windows")) {
				System.out.printf(
						"%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n",
						new Object[0]);
			} else {
				System.out.print('\f');
			}
			printTopBar();
			view.printView();
			System.out.flush();
			Thread.sleep(1000L);
		}
	}

	private void printTopBar() {
		OperatingSystemMXBean localOSBean = ManagementFactory
				.getOperatingSystemMXBean();

		if ((!(supportSystemLoadAverage(localOSBean)))
				|| (localOSBean.getSystemLoadAverage() == -1.0D)) {
			System.out
					.printf(" JvmTop 0.5.0 alpha (expect bugs) %6s, %2d cpus, %15.15s%n",
							new Object[] {
									localOSBean.getArch(),
									Integer.valueOf(localOSBean
											.getAvailableProcessors()),
									localOSBean.getName() + " "
											+ localOSBean.getVersion() });
		} else {
			System.out
					.printf(" JvmTop 0.5.0 alpha (expect bugs) %6s, %2d cpus, %15.15s, load avg %3.2f%n",
							new Object[] {
									localOSBean.getArch(),
									Integer.valueOf(localOSBean
											.getAvailableProcessors()),
									localOSBean.getName() + " "
											+ localOSBean.getVersion(),
									Double.valueOf(localOSBean
											.getSystemLoadAverage()) });
		}
		System.out.println(" http://code.google.com/p/jvmtop");
		System.out.println();
	}

	private boolean supportSystemLoadAverage(OperatingSystemMXBean localOSBean) {
		try {
			return (localOSBean.getClass().getMethod("getSystemLoadAverage",
					new Class[0]) == null);
		} catch (Throwable e) {
		}
		return false;
	}
}