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
package com.predic8.membrane.core.interceptor.balancer;

import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.collect.Lists;
import com.predic8.membrane.annot.MCAttribute;
import com.predic8.membrane.annot.MCChildElement;
import com.predic8.membrane.annot.MCElement;
import com.predic8.membrane.core.config.AbstractXmlElement;
import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.Message;
import com.predic8.membrane.core.http.Response;
import com.predic8.membrane.core.interceptor.AbstractInterceptor;
import com.predic8.membrane.core.interceptor.Outcome;

/**
 * May only be used as interceptor in a ServiceProxy.
 */
@MCElement(name="balancer")
public class LoadBalancingInterceptor extends AbstractInterceptor {

	private static Log log = LogFactory.getLog(LoadBalancingInterceptor.class
			.getName());

	private DispatchingStrategy strategy = new RoundRobinStrategy();
	private AbstractSessionIdExtractor sessionIdExtractor;
	private boolean failOver = true;
	private final Balancer balancer = new Balancer();

	public LoadBalancingInterceptor() {
		name = "Balancer";
	}
	
	@Override
	public Outcome handleRequest(Exchange exc) throws Exception {
		log.debug("handleRequest");

		Node dispatchedNode;
		try {
			dispatchedNode = getDispatchedNode(exc.getRequest());
		} catch (EmptyNodeListException e) {
			log.error("No Node found.");
			exc.setResponse(Response.interalServerError().build());
			return Outcome.ABORT;
		}

		dispatchedNode.incCounter();
		dispatchedNode.addThread();

		exc.setProperty("dispatchedNode", dispatchedNode);

		exc.setOriginalRequestUri(dispatchedNode.getDestinationURL(exc));

		exc.getDestinations().clear();
		exc.getDestinations().add(dispatchedNode.getDestinationURL(exc));

		setFailOverNodes(exc, dispatchedNode);

		return Outcome.CONTINUE;
	}

	@Override
	public Outcome handleResponse(Exchange exc) throws Exception {
		log.debug("handleResponse");

		if (sessionIdExtractor != null) {
			String sessionId = getSessionId(exc.getResponse());

			if (sessionId != null) {
				balancer.addSession2Cluster(sessionId, Cluster.DEFAULT_NAME, (Node) exc.getProperty("dispatchedNode"));
			}
		}

		updateDispatchedNode(exc);

		return Outcome.CONTINUE;
	}

	private void setFailOverNodes(Exchange exc, Node dispatchedNode) {
		if (!failOver)
			return;

		for (Node ep : getEndpoints()) {
			if (!ep.equals(dispatchedNode))
				exc.getDestinations().add(ep.getDestinationURL(exc));
		}
	}

	private void updateDispatchedNode(Exchange exc) {
		Node n = (Node) exc.getProperty("dispatchedNode");
		n.removeThread();
		// exc.timeResSent will be overridden later as exc really
		// completes, but to collect the statistics we use the current time
		exc.setTimeResSent(System.currentTimeMillis());
		n.collectStatisticsFrom(exc);
	}

	private Node getDispatchedNode(Message msg) throws Exception {
		String sessionId;
		if (sessionIdExtractor == null
				|| (sessionId = getSessionId(msg)) == null) {
			log.debug("no session id found.");
			return strategy.dispatch(this);
		}

		Session s = getSession(sessionId);
		if (s == null)
			log.debug("no session found for id " + sessionId);
		if (s == null || s.getNode().isDown()) {
			log.debug("assigning new node for session id " + sessionId
					+ (s != null ? " (old node was " + s.getNode() + ")" : ""));
			balancer.addSession2Cluster(sessionId, Cluster.DEFAULT_NAME, strategy.dispatch(this));
		}
		s = getSession(sessionId);
		s.used();
		return s.getNode();
	}

	private Session getSession(String sessionId) {
		return balancer.getSessions(Cluster.DEFAULT_NAME).get(sessionId);
	}

	private String getSessionId(Message msg) throws Exception {
		return sessionIdExtractor.getSessionId(msg);
	}

	/**
	 * This is *NOT* {@link #setDisplayName(String)}, but the balancer's name
	 * set in the proxy configuration to identify this balancer.
	 */
	@MCAttribute
	public void setName(String name) throws Exception {
		balancer.setName(name);
	}
	
	
	/**
	 * This is *NOT* {@link #getDisplayName()}, but the balancer's name set in
	 * the proxy configuration to identify this balancer.
	 */
	public String getName() {
		return balancer.getName();
	}

	public DispatchingStrategy getDispatchingStrategy() {
		return strategy;
	}

	@MCChildElement(order=3)
	public void setDispatchingStrategy(DispatchingStrategy strategy) {
		this.strategy = strategy;
	}

	public List<Node> getEndpoints() {
		return balancer.getAvailableNodesByCluster(Cluster.DEFAULT_NAME);
	}

	public AbstractSessionIdExtractor getSessionIdExtractor() {
		return sessionIdExtractor;
	}

	@MCChildElement(order=1)
	public void setSessionIdExtractor(
			AbstractSessionIdExtractor sessionIdExtractor) {
		this.sessionIdExtractor = sessionIdExtractor;
	}

	public boolean isFailOver() {
		return failOver;
	}

	public void setFailOver(boolean failOver) {
		this.failOver = failOver;
	}
	
	public Balancer getClusterManager() {
		return balancer;
	}
	
	@MCChildElement(order=2)
	public void setClustersFromSpring(List<Balancer> balancers) {
		List<Cluster> clusters = new ArrayList<Cluster>();
		for (Balancer balancer : balancers)
			clusters.addAll(balancer.getClusters());
		this.balancer.setClusters(clusters);
	}
	
	public List<Balancer> getClustersFromSpring() {
		return new ArrayList<Balancer>(Lists.newArrayList(balancer)) {
			private static final long serialVersionUID = 1L;

			@Override
			public boolean add(Balancer e) {
				balancer.setClusters(e.getClusters());
				return super.add(e);
			}
			
			@Override
			public Balancer set(int index, Balancer element) {
				balancer.setClusters(element.getClusters());
				return super.set(index, element);
			}
		};
	}

	public long getSessionTimeout() {
		return balancer.getSessionTimeout();
	}

	@MCAttribute
	public void setSessionTimeout(long sessionTimeout) {
		balancer.setSessionTimeout(sessionTimeout);
	}

	@Override
	protected void writeInterceptor(XMLStreamWriter out)
			throws XMLStreamException {

		out.writeStartElement("balancer");

		out.writeAttribute("name", balancer.getName());
		if (getSessionTimeout() != SessionCleanupThread.DEFAULT_TIMEOUT)
			out.writeAttribute("sessionTimeout", ""+getSessionTimeout());

		if (sessionIdExtractor != null) {
			sessionIdExtractor.write(out);
		}

		balancer.write(out);

		((AbstractXmlElement) strategy).write(out);

		out.writeEndElement();
	}

	protected void parseAttributes(XMLStreamReader token) throws Exception {
		if (token.getAttributeValue("", "name") != null)
			setName(token.getAttributeValue("", "name"));
		else
			setName(Balancer.DEFAULT_NAME);
		if (token.getAttributeValue("", "sessionTimeout") != null)
			setSessionTimeout(Integer.parseInt(token.getAttributeValue("", "sessionTimeout")));
	}

	
	@Override
	protected void parseChildren(XMLStreamReader token, String child)
			throws Exception {
		
		if (token.getLocalName().equals("xmlSessionIdExtractor")) {
			sessionIdExtractor = new XMLElementSessionIdExtractor();
			sessionIdExtractor.parse(token);
		} else if (token.getLocalName().equals("jSessionIdExtractor")) {
			sessionIdExtractor = new JSESSIONIDExtractor();
			sessionIdExtractor.parse(token);
		} else if (token.getLocalName().equals("clusters")) {
			balancer.parse(token);
		} else if (token.getLocalName().equals("byThreadStrategy")) {
			parseByThreadStrategy(token);
		} else if (token.getLocalName().equals("roundRobinStrategy")) {
			parseRoundRobinStrategy(token);
		} else {
			super.parseChildren(token, child);
		}
	}

	private void parseByThreadStrategy(XMLStreamReader token) throws Exception {
		ByThreadStrategy byTStrat = new ByThreadStrategy();
		byTStrat.parse(token);
		strategy = byTStrat;
	}

	private void parseRoundRobinStrategy(XMLStreamReader token)
			throws Exception {
		RoundRobinStrategy rrStrat = new RoundRobinStrategy();
		rrStrat.parse(token);
		strategy = rrStrat;
	}

	@Override
	public String getHelpId() {
		return "balancer";
	}
	
	@Override
	public String getShortDescription() {
		return "Performs load-balancing between <a href=\"/admin/balancers\">several nodes</a>.";
	}
	
	@Override
	public void init() throws Exception {
		for (Cluster c : balancer.getClusters())
			for (Node n : c.getNodes())
				c.nodeUp(n);
	}

}
