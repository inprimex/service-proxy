/* Copyright 2009, 2012 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.core.transport.http;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.predic8.membrane.annot.MCAttribute;
import com.predic8.membrane.annot.MCElement;
import com.predic8.membrane.core.model.IPortChangeListener;
import com.predic8.membrane.core.transport.SSLContext;
import com.predic8.membrane.core.transport.Transport;

@MCElement(name="transport", group="transport")
public class HttpTransport extends Transport {

	private static Log log = LogFactory.getLog(HttpTransport.class.getName());

	public static final String SOURCE_HOSTNAME = "com.predic8.membrane.transport.http.source.Hostname";
	public static final String HEADER_HOST = "com.predic8.membrane.transport.http.header.Host";
	public static final String SOURCE_IP = "com.predic8.membrane.transport.http.source.Ip";

	private int socketTimeout = 30000;
	private boolean tcpNoDelay = true;
	private boolean autoContinue100Expected = true;

	public Hashtable<Port, HttpEndpointListener> portListenerMapping = new Hashtable<Port, HttpEndpointListener>();
	
	private static class Port {
		public String ip;
		public int port;

		public Port(String ip, int port) {
			this.ip = ip;
			this.port = port;
		}
		
		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Port))
				return false;
			Port other = (Port)obj;
			if (other.port != port)
				return false;
			if (ip == null)
				return other.ip == null;
			return ip.equals(other.ip);
		}
		
		@Override
		public int hashCode() {
			return 5 * port + (ip != null ? 3 * ip.hashCode() : 0);
		}
		
		@Override
		public String toString() {
			return "port=" + port + " ip=" + ip;
		}
	}

	private ThreadPoolExecutor executorService = new ThreadPoolExecutor(0,
			Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
			new SynchronousQueue<Runnable>(), new HttpServerThreadFactory());

	public boolean isAnyThreadListeningAt(String ip, int port) {
		return portListenerMapping.get(new Port(ip, port)) != null;
	}

	public Enumeration<Port> getAllPorts() {
		return portListenerMapping.keys();
	}

	public synchronized void closePort(String ip, int port) throws IOException {
		Port p = new Port(ip, port);
		log.debug("Closing server port: " + p);
		HttpEndpointListener plt = portListenerMapping.get(p);
		if (plt == null)
			return;

		plt.closePort();
		try {
			plt.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		portListenerMapping.remove(p);

		for (IPortChangeListener listener : menuListeners) {
			listener.removePort(port);
		}

	}

	public synchronized void closeAll(boolean waitForCompletion) throws IOException {
		
		log.debug("Closing all network server sockets.");
		Enumeration<Port> enumeration = getAllPorts();
		while (enumeration.hasMoreElements()) {
			Port p = enumeration.nextElement();
			closePort(p.ip, p.port);
		}
		
		if (waitForCompletion) {
			log.debug("Waiting for running exchanges to finish.");
			executorService.shutdown();
			try {
				while (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
					log.warn("Still waiting for running exchanges to finish.");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * @param port
	 * @throws IOException
	 */
	@Override
	public synchronized void openPort(String ip, int port, SSLContext sslContext) throws IOException {
		if (isAnyThreadListeningAt(ip, port)) {
			return;
		}

		if (port == -1)
			throw new RuntimeException("The port-attribute is missing (probably on a <serviceProxy> element).");
		
		HttpEndpointListener portListenerThread = new HttpEndpointListener(
				ip, port, this, sslContext);
		portListenerMapping.put(new Port(ip, port), portListenerThread);
		portListenerThread.start();

		for (IPortChangeListener listener : menuListeners) {
			listener.addPort(port);
		}
	}

	@MCAttribute
	public void setCoreThreadPoolSize(int corePoolSize) {
		executorService.setCorePoolSize(corePoolSize);
	}

	public void setMaxThreadPoolSize(int value) {
		executorService.setMaximumPoolSize(value);
	}

	public ExecutorService getExecutorService() {
		return executorService;
	}

	public int getSocketTimeout() {
		return socketTimeout;
	}

	@MCAttribute
	public void setSocketTimeout(int timeout) {
		this.socketTimeout = timeout;
	}

	public boolean isTcpNoDelay() {
		return tcpNoDelay;
	}

	@MCAttribute
	public void setTcpNoDelay(boolean tcpNoDelay) {
		this.tcpNoDelay = tcpNoDelay;
	}

	public boolean isAutoContinue100Expected() {
		return autoContinue100Expected;
	}
	
	@MCAttribute
	public void setAutoContinue100Expected(boolean autoContinue100Expected) {
		this.autoContinue100Expected = autoContinue100Expected;
	}
	
	@Override
	public boolean isOpeningPorts() {
		return true;
	}
}
