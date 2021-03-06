/* Copyright 2012 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.core.config.spring;

import java.util.*;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

import com.predic8.membrane.core.interceptor.authentication.BasicAuthenticationInterceptor;

public class BasicAuthenticationInterceptorParser extends AbstractParser {

	protected Class<?> getBeanClass(Element element) {
		return BasicAuthenticationInterceptor.class;
	}

	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
		setIdIfNeeded(element, parserContext, "basicAuthentication");
		builder.addPropertyValue("users",getUsers(element));
	}

	private Map<String, String> getUsers(Element e) {
		Map<String, String> users = new HashMap<String, String>();
		for (Element user : DomUtils.getChildElementsByTagName(e, "user")) {
			users.put(user.getAttribute("name"), user.getAttribute("password"));
		}
		return users;
	}
}
