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
package com.predic8.membrane.core.interceptor.rewrite;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Required;

import com.googlecode.jatl.Html;
import com.predic8.membrane.annot.MCAttribute;
import com.predic8.membrane.annot.MCChildElement;
import com.predic8.membrane.annot.MCElement;
import com.predic8.membrane.core.config.GenericComplexElement;
import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.Response;
import com.predic8.membrane.core.interceptor.AbstractInterceptor;
import com.predic8.membrane.core.interceptor.Outcome;
import com.predic8.membrane.core.util.TextUtil;
import com.predic8.membrane.core.util.URLUtil;

@MCElement(name="rewriter")
public class RewriteInterceptor extends AbstractInterceptor {

	public enum Type {
		REWRITE,
		REDIRECT_TEMPORARY,
		REDIRECT_PERMANENT,
	}
	
	@MCElement(name="map", group="rewriter", global=false)
	public static class Mapping {
		public String to;
		public String from;
		private Type do_;
		
		private Pattern pattern;
		
		public Mapping() {
		}
		
		public Mapping(String from, String to, String do_) {
			this.from = from;
			this.to = to;
			
			if (StringUtils.isEmpty(do_))
				this.do_ = getDo();
			else if (do_.equals("rewrite"))
				this.do_ = Type.REWRITE;
			else if (do_.equals("redirect") || do_.equals("redirect-temporary"))
				this.do_ = Type.REDIRECT_TEMPORARY;
			else if (do_.equals("redirect-permanent"))
				this.do_ = Type.REDIRECT_PERMANENT;
			else
				throw new IllegalArgumentException("Unknown value '" + do_ + "' for rewriter/@do.");

			pattern = Pattern.compile(from);
		}
		
		public boolean matches(String uri) {
			return pattern.matcher(uri).find();
		}
		
		public String getFrom() {
			return from;
		}
		
		@Required
		@MCAttribute
		public void setFrom(String from) {
			this.from = from;
			if (from == null)
				pattern = null;
			else
				pattern = Pattern.compile(from);
		}
		
		public String getTo() {
			return to;
		}

		@Required
		@MCAttribute
		public void setTo(String to) {
			this.to = to;
		}
		
		public Type getDo() {
			if (do_ == null)
				do_ = to.contains("://") ? Type.REDIRECT_TEMPORARY : Type.REWRITE;
			return do_;
		}
		
		@MCAttribute
		public void setDo(Type do_) {
			this.do_ = do_;
		}
		
		
	}

	private static Log log = LogFactory.getLog(RewriteInterceptor.class.getName());

	private List<Mapping> mappings = new ArrayList<Mapping>();

	public RewriteInterceptor() {
		name = "URL Rewriter";
		setFlow(Flow.REQUEST);
	}

	@Override
	public Outcome handleRequest(Exchange exc) throws Exception {
	
		logMappings();
		
		ListIterator<String>  it = exc.getDestinations().listIterator();
		while ( it.hasNext() ) {
			String dest = it.next();
			
			String pathQuery = URLUtil.getPathQuery(dest);
			int pathBegin = dest.lastIndexOf(pathQuery);
			String schemaHostPort = pathBegin == -1 ? dest : dest.substring(0, pathBegin); // TODO check -1 case
			
			log.debug("pathQuery: " + pathQuery);
			log.debug("schemaHostPort: " + schemaHostPort);
			
			Mapping mapping = findFirstMatchingRegEx(pathQuery);
			if (mapping == null)
				continue;
			
			log.debug("match found: " + mapping.from);
			log.debug("replacing with: " + mapping.to);
			log.debug("for type: " + mapping.do_);
			
			String newDest = replace(pathQuery, mapping);

			if (mapping.do_ == Type.REDIRECT_PERMANENT || mapping.do_ == Type.REDIRECT_TEMPORARY) {
				exc.setResponse(Response.redirect(newDest, mapping.do_ == Type.REDIRECT_PERMANENT).build());
				return Outcome.RETURN;
			}

			if (!newDest.contains("://")) {
				// prepend schema, host and port from original uri
				newDest = schemaHostPort + newDest;
			}
			
			it.set(newDest);
		}
		return Outcome.CONTINUE;
	}

	private void logMappings() {
		for (Mapping m : mappings) {
			log.debug("[from:"+m.from+"],[to:"+m.to+"],[do:"+m.do_+"]");
		}
	}
	
	private String replace(String uri, Mapping mapping) {
		String replaced = uri.replaceAll(mapping.from, mapping.to);

		log.debug("replaced URI: " + replaced);

		return replaced;
	}

	private Mapping findFirstMatchingRegEx(String uri) {
		for (Mapping m : mappings) {
			if (m.matches(uri))
				return m;
		}
		return null;
	}

	public List<Mapping> getMappings() {
		return mappings;
	}

	@Required
	@MCChildElement
	public void setMappings(List<Mapping> mappings) {
		this.mappings = mappings;
	}

	@Override
	protected void writeInterceptor(XMLStreamWriter out)
			throws XMLStreamException {

		out.writeStartElement("rewriter");

		for (Mapping m : mappings) {
			out.writeStartElement("map");

			out.writeAttribute("from", m.from);
			out.writeAttribute("to", m.to);
			out.writeAttribute("do", m.getDo().toString().toLowerCase().replace('_', '-'));

			out.writeEndElement();
		}

		out.writeEndElement();
	}

	@Override
	protected void parseChildren(XMLStreamReader token, String child)
			throws Exception {
		if (token.getLocalName().equals("map")) {
			GenericComplexElement mapping = new GenericComplexElement();
			mapping.parse(token);
			mappings.add(new RewriteInterceptor.Mapping(mapping
					.getAttribute("from"), mapping.getAttribute("to"), mapping.getAttribute("do")));
		} else {
			super.parseChildren(token, child);
		}
	}
	
	@Override
	public String getHelpId() {
		return "rewriter";
	}
	
	@Override
	public String getShortDescription() {
		EnumSet<Type> s = EnumSet.noneOf(Type.class);
		for (Mapping m : mappings)
			s.add(m.do_);
		
		StringBuilder sb = new StringBuilder();
		sb.append(TextUtil.capitalize(TextUtil.toEnglishList("or", 
				s.contains(Type.REDIRECT_PERMANENT) || s.contains(Type.REDIRECT_TEMPORARY) ?
						TextUtil.toEnglishList("or", 
								s.contains(Type.REDIRECT_PERMANENT) ? "permanently" : null, 
								s.contains(Type.REDIRECT_TEMPORARY) ? "temporarily" : null) + 
						" redirects" : null,
				s.contains(Type.REWRITE) ? "rewrites" : null)));
		sb.append(" URLs.");
		return sb.toString();
	}
	
	@Override
	public String getLongDescription() {
		StringWriter sw = new StringWriter();
		new Html(sw) {{
			text(getShortDescription());
			
			table().style("margin-top: 5pt;");
			thead();
				tr();
					th().text("From").end();
					th().text("To").end();
					th().text("Action").end();
				end();
			end();
			tbody();
			for (Mapping m : mappings) {
				tr();
					td().text(m.from).end();
					td().text(m.to).end();
					td().text(m.do_.toString()).end();
				end();
			}
			end();
			end();
		}};
		return sw.toString();
	}

}
