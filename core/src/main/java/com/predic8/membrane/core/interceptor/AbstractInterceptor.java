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

package com.predic8.membrane.core.interceptor;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.lang.NotImplementedException;

import com.predic8.membrane.annot.MCAttribute;
import com.predic8.membrane.core.Router;
import com.predic8.membrane.core.config.AbstractConfigElement;
import com.predic8.membrane.core.exchange.Exchange;

public class AbstractInterceptor extends AbstractConfigElement implements Interceptor {
 
	public static final String ELEMENT_NAME = "interceptor";
	
	protected String name = this.getClass().getName();
	
	private Flow flow = Flow.REQUEST_RESPONSE;
	
	protected String id;
	
	protected Router router;
	
	public AbstractInterceptor() {
		super();
	}
	
	public Outcome handleRequest(Exchange exc) throws Exception {
		return Outcome.CONTINUE;
	}

	public Outcome handleResponse(Exchange exc) throws Exception {
		return Outcome.CONTINUE;
	}
	
	public void handleAbort(Exchange exchange) {
		// do nothing
	}

	public String getDisplayName() {
		return name;
	}

	public void setDisplayName(String name) {
		this.name = name;
	}

	@Override
	protected String getElementName() {
		return ELEMENT_NAME;
	}
	
	@Override
	protected void parseAttributes(XMLStreamReader token) throws Exception {
		if (token.getAttributeValue("", "name") != null)
			name = token.getAttributeValue("", "name");	
		id = token.getAttributeValue("", "refid");	
	}
	
	@Override
	public void write(XMLStreamWriter out) throws XMLStreamException {
		if ( getId() != null ) writeReferenz(out);
		else writeInterceptor(out);
	}

	protected void writeInterceptor(XMLStreamWriter out) throws XMLStreamException {
		throw new NotImplementedException();
	}
	
	private void writeReferenz(XMLStreamWriter out) throws XMLStreamException {
		out.writeStartElement(ELEMENT_NAME);

		out.writeAttribute("refid", getId());
		
		out.writeAttribute("name", getDisplayName());
		
		out.writeEndElement();
	}

	public String getId() {
		return id;
	}

	@MCAttribute
	public void setId(String id) {
		this.id = id;
	}
	
	public void setFlow(Flow flow) {
		this.flow = flow;
	}

	
	public Flow getFlow() {
		return flow;
	}

	@Override
	public String getShortDescription() {
		return "";
	}
	
	@Override
	public String getLongDescription() {
		return getShortDescription();
	}

	@Override
	public String getHelpId() {
		return null;
	}
	
	/**
	 * Should not be overridden by subclasses. Override {@link #init()} instead.
	 */
	@Override
	public final void doAfterParsing() throws Exception {
		super.doAfterParsing();
	}
	
	/**
	 * Called after parsing is complete and this has been added to the object tree (whose root is Router).
	 */
	public void init() throws Exception {
		// do nothing here - override in subclasses.
	}
	
	public void init(Router router) throws Exception {
		this.router = router;
		init();
	}
	
	public Router getRouter() { //wird von ReadRulesConfigurationTest aufgerufen.		
		return router;
	}

}
