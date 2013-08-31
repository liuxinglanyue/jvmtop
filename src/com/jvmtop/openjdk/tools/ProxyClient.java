package com.jvmtop.openjdk.tools;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RemoteObject;
import java.rmi.server.RemoteObjectInvocationHandler;
import java.rmi.server.RemoteRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnector;
import javax.management.remote.rmi.RMIServer;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import sun.rmi.server.*;
import sun.rmi.transport.*;

public class ProxyClient implements JConsoleContext {
	private JConsoleContext.ConnectionState connectionState = JConsoleContext.ConnectionState.DISCONNECTED;
	private static Map<String, ProxyClient> cache;
	private volatile boolean isDead = true;
	private String hostName = null;
	private int port = 0;
	private String userName = null;
	private String password = null;
	private boolean hasPlatformMXBeans = false;
	private boolean hasHotSpotDiagnosticMXBean = false;
	private boolean hasCompilationMXBean = false;
	private boolean supportsLockUsage = false;
	private LocalVirtualMachine lvm;
	private String advancedUrl = null;

	private JMXServiceURL jmxUrl = null;
	private MBeanServerConnection mbsc = null;
	private SnapshotMBeanServerConnection server = null;
	private JMXConnector jmxc = null;
	private RMIServer stub = null;
	private static final SslRMIClientSocketFactory sslRMIClientSocketFactory;
	private String registryHostName = null;
	private int registryPort = 0;
	private boolean vmConnector = false;
	private boolean sslRegistry = false;
	private boolean sslStub = false;
	private final String connectionName;
	private final String displayName;
	private ClassLoadingMXBean classLoadingMBean = null;
	private CompilationMXBean compilationMBean = null;
	private MemoryMXBean memoryMBean = null;
	private OperatingSystemMXBean operatingSystemMBean = null;
	private RuntimeMXBean runtimeMBean = null;
	private ThreadMXBean threadMBean = null;

	private OperatingSystemMXBean sunOperatingSystemMXBean = null;

	private List<GarbageCollectorMXBean> garbageCollectorMBeans = null;
	private static final String HOTSPOT_DIAGNOSTIC_MXBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";
	private static final String rmiServerImplStubClassName = "javax.management.remote.rmi.RMIServerImpl_Stub";
	private static final Class<? extends Remote> rmiServerImplStubClass;

	static {
		cache = Collections.synchronizedMap(new HashMap());

		sslRMIClientSocketFactory = new SslRMIClientSocketFactory();

		Class serverStubClass = null;
		try {
			serverStubClass = Class.forName(
					"javax.management.remote.rmi.RMIServerImpl_Stub")
					.asSubclass(Remote.class);
		} catch (ClassNotFoundException e) {
			throw ((InternalError) new InternalError(e.getMessage())
					.initCause(e));
		}
		rmiServerImplStubClass = serverStubClass;
	}

	private ProxyClient(String hostName, int port, String userName,
			String password) throws IOException {
		this.connectionName = getConnectionName(hostName, port, userName);
		this.displayName = this.connectionName;
		if ((hostName.equals("localhost")) && (port == 0)) {
			this.hostName = hostName;
			this.port = port;
		} else {
			String urlPath = "/jndi/rmi://" + hostName + ":" + port + "/jmxrmi";
			JMXServiceURL url = new JMXServiceURL("rmi", "", 0, urlPath);
			setParameters(url, userName, password);
			this.vmConnector = true;
			this.registryHostName = hostName;
			this.registryPort = port;
			checkSslConfig();
		}
	}

	private ProxyClient(String url, String userName, String password)
			throws IOException {
		this.advancedUrl = url;
		this.connectionName = getConnectionName(url, userName);
		this.displayName = this.connectionName;
		setParameters(new JMXServiceURL(url), userName, password);
	}

	private ProxyClient(LocalVirtualMachine lvm) throws IOException {
		this.lvm = lvm;
		this.connectionName = getConnectionName(lvm);
		this.displayName = "pid: " + lvm.vmid() + " " + lvm.displayName();
	}

	private void setParameters(JMXServiceURL url, String userName,
			String password) {
		this.jmxUrl = url;
		this.hostName = this.jmxUrl.getHost();
		this.port = this.jmxUrl.getPort();
		this.userName = userName;
		this.password = password;
	}

	private static void checkStub(Remote stub, Class<? extends Remote> stubClass) {
		if (stub.getClass() != stubClass) {
			if (!(Proxy.isProxyClass(stub.getClass()))) {
				throw new SecurityException("Expecting a "
						+ stubClass.getName() + " stub!");
			}
			InvocationHandler handler = Proxy.getInvocationHandler(stub);
			if (handler.getClass() != RemoteObjectInvocationHandler.class) {
				throw new SecurityException(
						"Expecting a dynamic proxy instance with a "
								+ RemoteObjectInvocationHandler.class.getName()
								+ " invocation handler!");
			}
			stub = (Remote) handler;
		}


	}

	private void checkSslConfig() throws IOException {
		Registry registry;
		try {
			registry = LocateRegistry.getRegistry(this.registryHostName,
					this.registryPort, sslRMIClientSocketFactory);
			try {
				this.stub = ((RMIServer) registry.lookup("jmxrmi"));
			} catch (NotBoundException nbe) {
				throw ((IOException) new IOException(nbe.getMessage())
						.initCause(nbe));
			}
			this.sslRegistry = true;
		} catch (IOException e) {
			registry = LocateRegistry.getRegistry(this.registryHostName,
					this.registryPort);
			try {
				this.stub = ((RMIServer) registry.lookup("jmxrmi"));
			} catch (NotBoundException nbe) {
				throw ((IOException) new IOException(nbe.getMessage())
						.initCause(nbe));
			}
			this.sslRegistry = false;
		}

		try {
			checkStub(this.stub, rmiServerImplStubClass);
			this.sslStub = true;
		} catch (SecurityException e) {
			this.sslStub = false;
		}
	}

	public boolean isSslRmiRegistry() {
		if (!(isVmConnector())) {
			throw new UnsupportedOperationException(
					"ProxyClient.isSslRmiRegistry() is only supported if this ProxyClient is a JMX connector for a JMX VM agent");
		}

		return this.sslRegistry;
	}

	public boolean isSslRmiStub() {
		if (!(isVmConnector())) {
			throw new UnsupportedOperationException(
					"ProxyClient.isSslRmiStub() is only supported if this ProxyClient is a JMX connector for a JMX VM agent");
		}

		return this.sslStub;
	}

	public boolean isVmConnector() {
		return this.vmConnector;
	}

	private void setConnectionState(JConsoleContext.ConnectionState state) {
		JConsoleContext.ConnectionState oldState = this.connectionState;
		this.connectionState = state;
	}

	public JConsoleContext.ConnectionState getConnectionState() {
		return this.connectionState;
	}

	public void flush() {
		if (this.server != null)
			this.server.flush();
	}

	public void connect() {
		setConnectionState(JConsoleContext.ConnectionState.CONNECTING);
		try {
			tryConnect();
			setConnectionState(JConsoleContext.ConnectionState.CONNECTED);
		} catch (Exception e) {
			e.printStackTrace(System.err);
			setConnectionState(JConsoleContext.ConnectionState.DISCONNECTED);
		}
	}

	private void tryConnect() throws IOException {
		if ((this.jmxUrl == null) && ("localhost".equals(this.hostName))
				&& (this.port == 0)) {
			this.jmxc = null;
			this.mbsc = ManagementFactory.getPlatformMBeanServer();
			this.server = Snapshot.newSnapshot(this.mbsc);
		} else {
			if (this.lvm != null) {
				if (!(this.lvm.isManageable())) {
					this.lvm.startManagementAgent();
					if (!(this.lvm.isManageable())) {
						throw new IOException(this.lvm + "not manageable");
					}
				}
				if (this.jmxUrl == null) {
					this.jmxUrl = new JMXServiceURL(this.lvm.connectorAddress());
				}
			}

			if ((this.userName == null) && (this.password == null)) {
				if (isVmConnector()) {
					if (this.stub == null) {
						checkSslConfig();
					}
					this.jmxc = new RMIConnector(this.stub, null);
					this.jmxc.connect();
				} else {
					this.jmxc = JMXConnectorFactory.connect(this.jmxUrl);
				}
			} else {
				Map env = new HashMap();
				env.put("jmx.remote.credentials", new String[] { this.userName,
						this.password });
				if (isVmConnector()) {
					if (this.stub == null) {
						checkSslConfig();
					}
					this.jmxc = new RMIConnector(this.stub, null);
					this.jmxc.connect(env);
				} else {
					this.jmxc = JMXConnectorFactory.connect(this.jmxUrl, env);
				}
			}
			this.mbsc = this.jmxc.getMBeanServerConnection();
			this.server = Snapshot.newSnapshot(this.mbsc);
		}
		this.isDead = false;
		InternalError ie;
		try {
			ObjectName on = new ObjectName("java.lang:type=Threading");
			this.hasPlatformMXBeans = this.server.isRegistered(on);
			this.hasHotSpotDiagnosticMXBean = this.server
					.isRegistered(new ObjectName(
							"com.sun.management:type=HotSpotDiagnostic"));

			if (this.hasPlatformMXBeans) {
				MBeanOperationInfo[] mopis = this.server.getMBeanInfo(on)
						.getOperations();

				for (MBeanOperationInfo op : mopis) {
					if (op.getName().equals("findDeadlockedThreads")) {
						this.supportsLockUsage = true;
						break;
					}
				}

				on = new ObjectName("java.lang:type=Compilation");
				this.hasCompilationMXBean = this.server.isRegistered(on);
			}
		} catch (MalformedObjectNameException e) {
			throw new InternalError(e.getMessage());
		} catch (IntrospectionException e) {
			ie = new InternalError(e.getMessage());
			ie.initCause(e);
			throw ie;
		} catch (InstanceNotFoundException e) {
			ie = new InternalError(e.getMessage());
			ie.initCause(e);
			throw ie;
		} catch (ReflectionException e) {
			ie = new InternalError(e.getMessage());
			ie.initCause(e);
			throw ie;
		}

		if (!(this.hasPlatformMXBeans)) {
			return;
		}
		getRuntimeMXBean();
	}

	public static ProxyClient getProxyClient(LocalVirtualMachine lvm)
			throws IOException {
		String key = getCacheKey(lvm);
		ProxyClient proxyClient = (ProxyClient) cache.get(key);
		if (proxyClient == null) {
			proxyClient = new ProxyClient(lvm);
			cache.put(key, proxyClient);
		}
		return proxyClient;
	}

	public static String getConnectionName(LocalVirtualMachine lvm) {
		return Integer.toString(lvm.vmid());
	}

	private static String getCacheKey(LocalVirtualMachine lvm) {
		return Integer.toString(lvm.vmid());
	}

	public static ProxyClient getProxyClient(String url, String userName,
			String password) throws IOException {
		String key = getCacheKey(url, userName, password);
		ProxyClient proxyClient = (ProxyClient) cache.get(key);
		if (proxyClient == null) {
			proxyClient = new ProxyClient(url, userName, password);
			cache.put(key, proxyClient);
		}
		return proxyClient;
	}

	public static String getConnectionName(String url, String userName) {
		if ((userName != null) && (userName.length() > 0)) {
			return userName + "@" + url;
		}
		return url;
	}

	private static String getCacheKey(String url, String userName,
			String password) {
		return ((url == null) ? "" : url) + ":"
				+ ((userName == null) ? "" : userName) + ":"
				+ ((password == null) ? "" : password);
	}

	public static ProxyClient getProxyClient(String hostName, int port,
			String userName, String password) throws IOException {
		String key = getCacheKey(hostName, port, userName, password);
		ProxyClient proxyClient = (ProxyClient) cache.get(key);
		if (proxyClient == null) {
			proxyClient = new ProxyClient(hostName, port, userName, password);
			cache.put(key, proxyClient);
		}
		return proxyClient;
	}

	public static String getConnectionName(String hostName, int port,
			String userName) {
		String name = hostName + ":" + port;
		if ((userName != null) && (userName.length() > 0)) {
			return userName + "@" + name;
		}
		return name;
	}

	private static String getCacheKey(String hostName, int port,
			String userName, String password) {
		return ((hostName == null) ? "" : hostName) + ":" + port + ":"
				+ ((userName == null) ? "" : userName) + ":"
				+ ((password == null) ? "" : password);
	}

	public String connectionName() {
		return this.connectionName;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String toString() {
		return this.displayName;
	}

	public MBeanServerConnection getMBeanServerConnection() {
		return this.mbsc;
	}

	public SnapshotMBeanServerConnection getSnapshotMBeanServerConnection() {
		return this.server;
	}

	public String getUrl() {
		return this.advancedUrl;
	}

	public String getHostName() {
		return this.hostName;
	}

	public int getPort() {
		return this.port;
	}

	public int getVmid() {
		return ((this.lvm != null) ? this.lvm.vmid() : 0);
	}

	public String getUserName() {
		return this.userName;
	}

	public String getPassword() {
		return this.password;
	}

	public void disconnect() {
		this.stub = null;

		if (this.jmxc != null) {
			try {
				this.jmxc.close();
			} catch (IOException localIOException) {
			}
		}
		this.classLoadingMBean = null;
		this.compilationMBean = null;
		this.memoryMBean = null;
		this.operatingSystemMBean = null;
		this.runtimeMBean = null;
		this.threadMBean = null;
		this.sunOperatingSystemMXBean = null;
		this.garbageCollectorMBeans = null;

		if (!(this.isDead)) {
			this.isDead = true;
			setConnectionState(JConsoleContext.ConnectionState.DISCONNECTED);
		}
	}

	public String[] getDomains() throws IOException {
		return this.server.getDomains();
	}

	public Map<ObjectName, MBeanInfo> getMBeans(String domain)
			throws IOException {
		ObjectName name = null;
		if (domain != null) {
			try {
				name = new ObjectName(domain + ":*");
			} catch (MalformedObjectNameException e) {
				
					throw new AssertionError();
			}
		}
		Set mbeans = this.server.queryNames(name, null);
		Map result = new HashMap(mbeans.size());
		Iterator iterator = mbeans.iterator();
		while (iterator.hasNext()) {
			Object object = iterator.next();
			if (object instanceof ObjectName) {
				ObjectName o = (ObjectName) object;
				try {
					MBeanInfo info = this.server.getMBeanInfo(o);
					result.put(o, info);
				} catch (IntrospectionException localIntrospectionException) {
				} catch (InstanceNotFoundException localInstanceNotFoundException) {
				} catch (ReflectionException localReflectionException) {
				}
			}
		}
		return result;
	}

	public AttributeList getAttributes(ObjectName name, String[] attributes)
			throws IOException {
		AttributeList list = null;
		try {
			list = this.server.getAttributes(name, attributes);
		} catch (InstanceNotFoundException localInstanceNotFoundException) {
		} catch (ReflectionException localReflectionException) {
		}
		return list;
	}

	public void setAttribute(ObjectName name, Attribute attribute)
			throws InvalidAttributeValueException, MBeanException, IOException {
		try {
			this.server.setAttribute(name, attribute);
		} catch (InstanceNotFoundException localInstanceNotFoundException) {
		} catch (AttributeNotFoundException e) {
			
				return;
		} catch (ReflectionException localReflectionException) {
		}
	}

	public Object invoke(ObjectName name, String operationName,
			Object[] params, String[] signature) throws IOException,
			MBeanException {
		Object result = null;
		try {
			result = this.server.invoke(name, operationName, params, signature);
		} catch (InstanceNotFoundException localInstanceNotFoundException) {
		} catch (ReflectionException localReflectionException) {
		}
		return result;
	}

	public synchronized ClassLoadingMXBean getClassLoadingMXBean()
			throws IOException {
		if ((this.hasPlatformMXBeans) && (this.classLoadingMBean == null)) {
			this.classLoadingMBean = ((ClassLoadingMXBean) ManagementFactory
					.newPlatformMXBeanProxy(this.server,
							"java.lang:type=ClassLoading",
							ClassLoadingMXBean.class));
		}
		return this.classLoadingMBean;
	}

	public synchronized CompilationMXBean getCompilationMXBean()
			throws IOException {
		if ((this.hasCompilationMXBean) && (this.compilationMBean == null)) {
			this.compilationMBean = ((CompilationMXBean) ManagementFactory
					.newPlatformMXBeanProxy(this.server,
							"java.lang:type=Compilation",
							CompilationMXBean.class));
		}
		return this.compilationMBean;
	}

	public synchronized Collection<GarbageCollectorMXBean> getGarbageCollectorMXBeans()
			throws IOException {
		if (this.garbageCollectorMBeans == null) {
			ObjectName gcName = null;
			try {
				gcName = new ObjectName("java.lang:type=GarbageCollector,*");
			} catch (MalformedObjectNameException e) {
			}
			Set mbeans = this.server.queryNames(gcName, null);
			if (mbeans != null) {
				this.garbageCollectorMBeans = new ArrayList();
				Iterator iterator = mbeans.iterator();
				while (iterator.hasNext()) {
					ObjectName on = (ObjectName) iterator.next();
					String name = "java.lang:type=GarbageCollector,name="
							+ on.getKeyProperty("name");

					GarbageCollectorMXBean mBean = (GarbageCollectorMXBean) ManagementFactory
							.newPlatformMXBeanProxy(this.server, name,
									GarbageCollectorMXBean.class);
					this.garbageCollectorMBeans.add(mBean);
				}
			}
		}
		return this.garbageCollectorMBeans;
	}

	public synchronized MemoryMXBean getMemoryMXBean() throws IOException {
		if ((this.hasPlatformMXBeans) && (this.memoryMBean == null)) {
			this.memoryMBean = ((MemoryMXBean) ManagementFactory
					.newPlatformMXBeanProxy(this.server,
							"java.lang:type=Memory", MemoryMXBean.class));
		}
		return this.memoryMBean;
	}

	public synchronized RuntimeMXBean getRuntimeMXBean() throws IOException {
		if ((this.hasPlatformMXBeans) && (this.runtimeMBean == null)) {
			this.runtimeMBean = ((RuntimeMXBean) ManagementFactory
					.newPlatformMXBeanProxy(this.server,
							"java.lang:type=Runtime", RuntimeMXBean.class));
		}
		return this.runtimeMBean;
	}

	public synchronized ThreadMXBean getThreadMXBean() throws IOException {
		if ((this.hasPlatformMXBeans) && (this.threadMBean == null)) {
			this.threadMBean = ((ThreadMXBean) ManagementFactory
					.newPlatformMXBeanProxy(this.server,
							"java.lang:type=Threading", ThreadMXBean.class));
		}
		return this.threadMBean;
	}

	public synchronized OperatingSystemMXBean getOperatingSystemMXBean()
			throws IOException {
		if ((this.hasPlatformMXBeans) && (this.operatingSystemMBean == null)) {
			this.operatingSystemMBean = ((OperatingSystemMXBean) ManagementFactory
					.newPlatformMXBeanProxy(this.server,
							"java.lang:type=OperatingSystem",
							OperatingSystemMXBean.class));
		}
		return this.operatingSystemMBean;
	}

	public synchronized OperatingSystemMXBean getSunOperatingSystemMXBean()
			throws IOException {
		try {
			ObjectName on = new ObjectName("java.lang:type=OperatingSystem");
			if ((this.sunOperatingSystemMXBean == null)
					&& (this.server.isInstanceOf(on,
							"java.lang.management.OperatingSystemMXBean"))) {
				this.sunOperatingSystemMXBean = ((OperatingSystemMXBean) ManagementFactory
						.newPlatformMXBeanProxy(this.server,
								"java.lang:type=OperatingSystem",
								OperatingSystemMXBean.class));
			}
		} catch (InstanceNotFoundException e) {
			return null;
		} catch (MalformedObjectNameException e) {
			return null;
		}
		return this.sunOperatingSystemMXBean;
	}

	public <T> T getMXBean(ObjectName objName, Class<T> interfaceClass)
			throws IOException {
		return ManagementFactory.newPlatformMXBeanProxy(this.server,
				objName.toString(), interfaceClass);
	}

	public long[] findDeadlockedThreads() throws IOException {
		ThreadMXBean tm = getThreadMXBean();
		if ((this.supportsLockUsage) && (tm.isSynchronizerUsageSupported())) {
			return tm.findDeadlockedThreads();
		}
		return tm.findMonitorDeadlockedThreads();
	}

	public synchronized void markAsDead() {
		disconnect();
	}

	public boolean isDead() {
		return this.isDead;
	}

	boolean isConnected() {
		return (!(isDead()));
	}

	boolean hasPlatformMXBeans() {
		return this.hasPlatformMXBeans;
	}

	boolean hasHotSpotDiagnosticMXBean() {
		return this.hasHotSpotDiagnosticMXBean;
	}

	boolean isLockUsageSupported() {
		return this.supportsLockUsage;
	}

	public boolean isRegistered(ObjectName name) throws IOException {
		return this.server.isRegistered(name);
	}

	public void addPropertyChangeListener(PropertyChangeListener listener) {
	}

	public void addWeakPropertyChangeListener(PropertyChangeListener listener) {
		if (listener instanceof WeakPCL)
			return;
		listener = new WeakPCL(listener);
	}

	public void removePropertyChangeListener(PropertyChangeListener listener) {
	}

	public long getProcessCpuTime() throws Exception {
		try {
			String osMXBeanClassName = "com.sun.management.OperatingSystemMXBean";
			if (LocalVirtualMachine.isJ9Mode()) {
				osMXBeanClassName = "com.ibm.lang.management.OperatingSystemMXBean";
			}

			if (Proxy.isProxyClass(getOperatingSystemMXBean().getClass())) {
				return ((Long) Proxy.getInvocationHandler(
						getOperatingSystemMXBean()).invoke(
						getOperatingSystemMXBean(),
						Class.forName(osMXBeanClassName).getMethod(
								"getProcessCpuTime", new Class[0]), null))
						.longValue();
			}

			throw new UnsupportedOperationException(
					"Unsupported JDK, please report bug");
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public static class Snapshot {
		public static ProxyClient.SnapshotMBeanServerConnection newSnapshot(
				MBeanServerConnection mbsc) {
			InvocationHandler ih = new ProxyClient.SnapshotInvocationHandler(
					mbsc);
			return ((ProxyClient.SnapshotMBeanServerConnection) Proxy
					.newProxyInstance(
							Snapshot.class.getClassLoader(),
							new Class[] { ProxyClient.SnapshotMBeanServerConnection.class },
							ih));
		}
	}

	static class SnapshotInvocationHandler implements InvocationHandler {
		private final MBeanServerConnection conn;
		private Map<ObjectName, NameValueMap> cachedValues = newMap();
		private Map<ObjectName, Set<String>> cachedNames = newMap();

		SnapshotInvocationHandler(MBeanServerConnection conn) {
			this.conn = conn;
		}

		synchronized void flush() {
			this.cachedValues = newMap();
		}

		public Object invoke(Object proxy, Method method, Object[] args)
				throws Throwable {
			String methodName = method.getName();
			if (methodName.equals("getAttribute"))
				return getAttribute((ObjectName) args[0], (String) args[1]);
			if (methodName.equals("getAttributes"))
				return getAttributes((ObjectName) args[0], (String[]) args[1]);
			if (methodName.equals("flush")) {
				flush();
				return null;
			}
			try {
				return method.invoke(this.conn, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		private Object getAttribute(ObjectName objName, String attrName)
				throws MBeanException, InstanceNotFoundException,
				AttributeNotFoundException, ReflectionException, IOException {
			NameValueMap values = getCachedAttributes(objName,
					Collections.singleton(attrName));
			Object value = values.get(attrName);
			if ((value != null) || (values.containsKey(attrName))) {
				return value;
			}

			return this.conn.getAttribute(objName, attrName);
		}

		private AttributeList getAttributes(ObjectName objName,
				String[] attrNames) throws InstanceNotFoundException,
				ReflectionException, IOException {
			NameValueMap values = getCachedAttributes(objName, new TreeSet(
					Arrays.asList(attrNames)));
			AttributeList list = new AttributeList();
			for (String attrName : attrNames) {
				Object value = values.get(attrName);
				if ((value != null) || (values.containsKey(attrName))) {
					list.add(new Attribute(attrName, value));
				}
			}
			return list;
		}

		private synchronized NameValueMap getCachedAttributes(
				ObjectName objName, Set<String> attrNames)
				throws InstanceNotFoundException, ReflectionException,
				IOException {
			NameValueMap values = (NameValueMap) this.cachedValues.get(objName);
			if ((values != null) && (values.keySet().containsAll(attrNames))) {
				return values;
			}
			attrNames = new TreeSet(attrNames);
			Set oldNames = (Set) this.cachedNames.get(objName);
			if (oldNames != null) {
				attrNames.addAll(oldNames);
			}
			values = new NameValueMap();
			AttributeList attrs = this.conn.getAttributes(objName,
					(String[]) attrNames.toArray(new String[attrNames.size()]));
			for (Attribute attr : attrs.asList()) {
				values.put(attr.getName(), attr.getValue());
			}
			this.cachedValues.put(objName, values);
			this.cachedNames.put(objName, attrNames);
			return values;
		}

		private static <K, V> Map<K, V> newMap() {
			return new HashMap();
		}

		private static final class NameValueMap extends HashMap<String, Object> {
		}
	}

	public static abstract interface SnapshotMBeanServerConnection extends
			MBeanServerConnection {
		public abstract void flush();
	}

	private class WeakPCL extends WeakReference<PropertyChangeListener>
			implements PropertyChangeListener {
		WeakPCL(PropertyChangeListener paramPropertyChangeListener) {
			super(paramPropertyChangeListener);
		}

		public void propertyChange(PropertyChangeEvent pce) {
			PropertyChangeListener pcl = (PropertyChangeListener) get();

			if (pcl == null) {
				dispose();
			} else
				pcl.propertyChange(pce);
		}

		private void dispose() {
			ProxyClient.this.removePropertyChangeListener(this);
		}
	}
}