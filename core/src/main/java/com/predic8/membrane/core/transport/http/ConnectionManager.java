/* Copyright 2011, 2012 predic8 GmbH, www.predic8.com

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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.base.Objects;
import com.predic8.membrane.core.transport.SSLContext;

/**
 * Pools TCP/IP connections, holding them open for a configurable number of milliseconds.
 * 
 * With keep-alive use as follows:
 * <code>
 * Connection connection = connectionManager.getConnection(...);
 * try {
 *   ...
 * } finally {
 *   connection.release();
 * }
 * </code>
 * 
 * Without keep-alive replace {@link Connection#release()} by {@link Connection#close()}.
 * 
 * Note that you should call {@link Connection#release()} exactly once, or alternatively
 * {@link Connection#close()} at least once.
 */
public class ConnectionManager {

	private static Log log = LogFactory.getLog(ConnectionManager.class.getName());
	
	private final long keepAliveTimeout; 
	private final long autoCloseInterval; 
	
	private static class ConnectionKey {
		public final InetAddress host;
		public final int port;
		
		public ConnectionKey(InetAddress host, int port) {
			this.host = host;
			this.port = port;
		}
		
		@Override
		public int hashCode() {
			return Objects.hashCode(host, port);
		}
		
		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof ConnectionKey) || obj == null)
				return false;
			ConnectionKey other = (ConnectionKey)obj;
			return host.equals(other.host) && port == other.port;
		}
	}
	
	private static class OldConnection {
		public final Connection connection;
		public final long timeout;
		
		public OldConnection(Connection connection, long keepAliveTimeout) {
			this.connection = connection;
			this.timeout = System.currentTimeMillis() + keepAliveTimeout;
		}
	}
	
	private AtomicInteger numberInPool = new AtomicInteger();
	private HashMap<ConnectionKey, ArrayList<OldConnection>> availableConnections =
			new HashMap<ConnectionManager.ConnectionKey, ArrayList<OldConnection>>(); // guarded by this
	private Timer timer;
	private volatile boolean shutdownWhenDone = false;

	public ConnectionManager(long keepAliveTimeout) {
		this.keepAliveTimeout = keepAliveTimeout;
		this.autoCloseInterval = keepAliveTimeout * 2;
		timer = new Timer("Connection Closer", true);
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (closeOldConnections() == 0 && shutdownWhenDone)
					timer.cancel();
			}
		}, autoCloseInterval, autoCloseInterval);
	}
	
	public Connection getConnection(InetAddress host, int port, String localHost, SSLContext sslContext) throws UnknownHostException, IOException {
		
		log.debug("connection requested for host: " + host + " and port: " + port);
		
		log.debug("Number of connections in pool: " + numberInPool.get());
		
		ConnectionKey key = new ConnectionKey(host, port);
		long now = System.currentTimeMillis();
		
		synchronized(this) {
			ArrayList<OldConnection> l = availableConnections.get(key);
			if (l != null) {
				while(l.size() > 0) {
					OldConnection c = l.remove(l.size()-1);
					if (c.timeout > now)
						return c.connection;
				}
			}
		}

		Connection result = Connection.open(host, port, localHost, sslContext, this);
		numberInPool.incrementAndGet();
		return result;
	}
	
	public void releaseConnection(Connection connection) {
		if (connection == null)
			return;
		
		if (connection.isClosed()) {
			numberInPool.decrementAndGet();
			return;
		}
		
		ConnectionKey key = new ConnectionKey(connection.socket.getInetAddress(), connection.socket.getPort());
		OldConnection o = new OldConnection(connection, keepAliveTimeout);
		ArrayList<OldConnection> l;
		synchronized(this) {
			l = availableConnections.get(key);
			if (l == null) {
				l = new ArrayList<OldConnection>();
				availableConnections.put(key, l);
			}
			l.add(o);
		}
	}
	
	private int closeOldConnections() {
		ArrayList<ConnectionKey> toRemove = new ArrayList<ConnectionKey>();
		long now = System.currentTimeMillis();
		log.debug("closing old connections");
		int closed = 0, remaining;
		synchronized(this) {
			// close connections after their timeout
			for (Map.Entry<ConnectionKey, ArrayList<OldConnection>> e : availableConnections.entrySet()) {
				ArrayList<OldConnection> l = e.getValue();
				for (int i = 0; i < l.size(); i++) {
					OldConnection o = l.get(i);
					if (o.timeout < now) {
						// replace [i] by [last]
						if (i == l.size() - 1)
							l.remove(i);
						else
							l.set(i, l.remove(l.size() - 1));
						--i;
						closed++;
					}
				}
				if (l.size() == 0)
					toRemove.add(e.getKey());
			}
			for (ConnectionKey remove : toRemove)
				availableConnections.remove(remove);
			remaining = availableConnections.size();
		}
		numberInPool.addAndGet(-closed);
		if (closed != 0)
			log.debug("closed " + closed + " connections");
		return remaining;
	}
	
	public void shutdownWhenDone() {
		shutdownWhenDone = true;
	}
}
