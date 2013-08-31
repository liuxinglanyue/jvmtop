package com.jvmtop.openjdk.tools;

import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

public abstract class JConsolePlugin {
	private volatile JConsoleContext context = null;
	private List<PropertyChangeListener> listeners = null;

	public final synchronized void setContext(JConsoleContext context) {
		this.context = context;
		if (this.listeners != null) {
			for (PropertyChangeListener l : this.listeners) {
				context.addPropertyChangeListener(l);
			}

			this.listeners = null;
		}
	}

	public final JConsoleContext getContext() {
		return this.context;
	}

	public abstract Map<String, JPanel> getTabs();

	public abstract SwingWorker<?, ?> newSwingWorker();

	public void dispose() {
	}

	public final void addContextPropertyChangeListener(
			PropertyChangeListener listener) {
		if (listener == null) {
			throw new NullPointerException("listener is null");
		}

		if (this.context == null) {
			synchronized (this) {
				if (this.context == null) {
					if (this.listeners == null) {
						this.listeners = new ArrayList();
					}
					this.listeners.add(listener);
					return;
				}
			}
		}
		this.context.addPropertyChangeListener(listener);
	}

	public final void removeContextPropertyChangeListener(
			PropertyChangeListener listener) {
		if (listener == null) {
			throw new NullPointerException("listener is null");
		}

		if (this.context == null) {
			synchronized (this) {
				if (this.context == null) {
					if (this.listeners != null) {
						this.listeners.remove(listener);
					}
					return;
				}
			}
		}
		this.context.removePropertyChangeListener(listener);
	}
}