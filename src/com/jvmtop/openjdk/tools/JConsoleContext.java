package com.jvmtop.openjdk.tools;

import java.beans.PropertyChangeListener;
import javax.management.MBeanServerConnection;

public abstract interface JConsoleContext {
	public static final String CONNECTION_STATE_PROPERTY = "connectionState";

	public abstract MBeanServerConnection getMBeanServerConnection();

	public abstract ConnectionState getConnectionState();

	public abstract void addPropertyChangeListener(
			PropertyChangeListener paramPropertyChangeListener);

	public abstract void removePropertyChangeListener(
			PropertyChangeListener paramPropertyChangeListener);

	public static enum ConnectionState {
		CONNECTED, DISCONNECTED, CONNECTING;
	}
}