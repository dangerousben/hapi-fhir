package ca.uhn.fhir.rest.client;

/*
 * #%L
 * HAPI FHIR - Core Library
 * %%
 * Copyright (C) 2014 - 2015 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.rest.client.api.IHttpClient;
import ca.uhn.fhir.rest.client.api.IRestfulClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.client.exceptions.FhirClientInappropriateForServerException;
import ca.uhn.fhir.rest.method.BaseMethodBinding;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.util.FhirTerser;

public abstract class RestfulClientFactory implements IRestfulClientFactory {

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(RestfulClientFactory.class);
	protected int myConnectionRequestTimeout = DEFAULT_CONNECTION_REQUEST_TIMEOUT;
	protected int myConnectTimeout = DEFAULT_CONNECT_TIMEOUT;
	protected FhirContext myContext;
	protected Map<Class<? extends IRestfulClient>, ClientInvocationHandlerFactory> myInvocationHandlers = new HashMap<Class<? extends IRestfulClient>, ClientInvocationHandlerFactory>();
	protected ServerValidationModeEnum myServerValidationMode = DEFAULT_SERVER_VALIDATION_MODE;
	protected int mySocketTimeout = DEFAULT_SOCKET_TIMEOUT;
	protected Set<String> myValidatedServerBaseUrls = Collections.synchronizedSet(new HashSet<String>());
	protected String myProxyUsername;
	protected String myProxyPassword;	

	/**
	 * Constructor
	 */
	public RestfulClientFactory() {
	}

	/**
	 * Constructor
	 * 
	 * @param theFhirContext
	 *            The context
	 */
	public RestfulClientFactory(FhirContext theFhirContext) {
		myContext = theFhirContext;
	}

	@Override
	public int getConnectionRequestTimeout() {
		return myConnectionRequestTimeout;
	}

	@Override
	public int getConnectTimeout() {
		return myConnectTimeout;
	}

	@Override
	public void setProxyCredentials(String theUsername, String thePassword) {
		myProxyUsername=theUsername;
		myProxyPassword=thePassword;
	}
	
	@Override
	public ServerValidationModeEnum getServerValidationMode() {
		return myServerValidationMode;
	}

	@Override
	public int getSocketTimeout() {
		return mySocketTimeout;
	}

	@SuppressWarnings("unchecked")
	private <T extends IRestfulClient> T instantiateProxy(Class<T> theClientType, InvocationHandler theInvocationHandler) {
		T proxy = (T) Proxy.newProxyInstance(theClientType.getClassLoader(), new Class[] { theClientType }, theInvocationHandler);
		return proxy;
	}

	/**
	 * Instantiates a new client instance
	 * 
	 * @param theClientType
	 *            The client type, which is an interface type to be instantiated
	 * @param theServerBase
	 *            The URL of the base for the restful FHIR server to connect to
	 * @return A newly created client
	 * @throws ConfigurationException
	 *             If the interface type is not an interface
	 */
	@Override
	public synchronized <T extends IRestfulClient> T newClient(Class<T> theClientType, String theServerBase) {
		if (!theClientType.isInterface()) {
			throw new ConfigurationException(theClientType.getCanonicalName() + " is not an interface");
		}

		
		ClientInvocationHandlerFactory invocationHandler = myInvocationHandlers.get(theClientType);
		if (invocationHandler == null) {
			IHttpClient httpClient = getHttpClient(theServerBase);
			invocationHandler = new ClientInvocationHandlerFactory(httpClient, myContext, theServerBase, theClientType);
			for (Method nextMethod : theClientType.getMethods()) {
				BaseMethodBinding<?> binding = BaseMethodBinding.bindMethod(nextMethod, myContext, null);
				invocationHandler.addBinding(nextMethod, binding);
			}
			myInvocationHandlers.put(theClientType, invocationHandler);
		}

		T proxy = instantiateProxy(theClientType, invocationHandler.newInvocationHandler(this));

		return proxy;
	}

	@Override
	public synchronized IGenericClient newGenericClient(String theServerBase) {
		IHttpClient httpClient = getHttpClient(theServerBase);
		return new GenericClient(myContext, httpClient, theServerBase, this);
	}

	@Override
	public void validateServerBaseIfConfiguredToDoSo(String theServerBase, IHttpClient theHttpClient, BaseClient theClient) {
		String serverBase = normalizeBaseUrlForMap(theServerBase);

		switch (myServerValidationMode) {
		case NEVER:
			break;
		case ONCE:
			if (!myValidatedServerBaseUrls.contains(serverBase)) {
				validateServerBase(serverBase, theHttpClient, theClient);
			}
			break;
		}

	}

	private String normalizeBaseUrlForMap(String theServerBase) {
		String serverBase = theServerBase;
		if (!serverBase.endsWith("/")) {
			serverBase = serverBase + "/";
		}
		return serverBase;
	}

	@Override
	public synchronized void setConnectionRequestTimeout(int theConnectionRequestTimeout) {
		myConnectionRequestTimeout = theConnectionRequestTimeout;
		resetHttpClient();
	}

	@Override
	public synchronized void setConnectTimeout(int theConnectTimeout) {
		myConnectTimeout = theConnectTimeout;
		resetHttpClient();
	}

	/**
	 * Sets the context associated with this client factory. Must not be called more than once.
	 */
	public void setFhirContext(FhirContext theContext) {
		if (myContext != null && myContext != theContext) {
			throw new IllegalStateException("RestfulClientFactory instance is already associated with one FhirContext. RestfulClientFactory instances can not be shared.");
		}
		myContext = theContext;
	}

	@Override
	public void setServerValidationMode(ServerValidationModeEnum theServerValidationMode) {
		Validate.notNull(theServerValidationMode, "theServerValidationMode may not be null");
		myServerValidationMode = theServerValidationMode;
	}

	@Override
	public synchronized void setSocketTimeout(int theSocketTimeout) {
		mySocketTimeout = theSocketTimeout;
		resetHttpClient();
	}

	@Override
	public void validateServerBase(String theServerBase, IHttpClient theHttpClient, BaseClient theClient) {

		GenericClient client = new GenericClient(myContext, theHttpClient, theServerBase, this);
		client.setEncoding(theClient.getEncoding());
		for (IClientInterceptor interceptor : theClient.getInterceptors()) {
			client.registerInterceptor(interceptor);
		}
		client.setDontValidateConformance(true);
		
		IBaseResource conformance;
		try {
			@SuppressWarnings("rawtypes")
			Class implementingClass = myContext.getResourceDefinition("Conformance").getImplementingClass();
			conformance = (IBaseResource) client.fetchConformance().ofType(implementingClass).execute();
		} catch (FhirClientConnectionException e) {
			throw new FhirClientConnectionException(myContext.getLocalizer().getMessage(RestfulClientFactory.class, "failedToRetrieveConformance", theServerBase + Constants.URL_TOKEN_METADATA), e);
		}

		FhirTerser t = myContext.newTerser();
		String serverFhirVersionString = null;
		Object value = t.getSingleValueOrNull(conformance, "fhirVersion");
		if (value instanceof IPrimitiveType) {
			serverFhirVersionString = ((IPrimitiveType<?>) value).getValueAsString();
		}
		FhirVersionEnum serverFhirVersionEnum = null;
		if (StringUtils.isBlank(serverFhirVersionString)) {
			// we'll be lenient and accept this
		} else {
			if (serverFhirVersionString.startsWith("0.80") || serverFhirVersionString.startsWith("0.0.8")) {
				serverFhirVersionEnum = FhirVersionEnum.DSTU1;
			} else if (serverFhirVersionString.startsWith("0.4")) {
				serverFhirVersionEnum = FhirVersionEnum.DSTU2;
			} else if (serverFhirVersionString.startsWith("0.5")) {
				serverFhirVersionEnum = FhirVersionEnum.DSTU2;
			} else {
				// we'll be lenient and accept this
				ourLog.debug("Server conformance statement indicates unknown FHIR version: {}", serverFhirVersionString);
			}
		}

		if (serverFhirVersionEnum != null) {
			FhirVersionEnum contextFhirVersion = myContext.getVersion().getVersion();
			if (!contextFhirVersion.isEquivalentTo(serverFhirVersionEnum)) {
				throw new FhirClientInappropriateForServerException(myContext.getLocalizer().getMessage(RestfulClientFactory.class, "wrongVersionInConformance", theServerBase + Constants.URL_TOKEN_METADATA, serverFhirVersionString, serverFhirVersionEnum, contextFhirVersion));
			}
		}
		
		myValidatedServerBaseUrls.add(normalizeBaseUrlForMap(theServerBase));

	}

	@Override
	public ServerValidationModeEnum getServerValidationModeEnum() {
		return getServerValidationMode();
	}

	@Override
	public void setServerValidationModeEnum(ServerValidationModeEnum theServerValidationMode) {
		setServerValidationMode(theServerValidationMode);
	}
	
	/**
	 * Get the http client for the given server base
	 * @param theServerBase the server base
	 * @return the http client
	 */
	protected abstract IHttpClient getHttpClient(String theServerBase);
	
	/**
	 * Reset the http client. This method is used when parameters have been set and a
	 * new http client needs to be created
	 */
	protected abstract void resetHttpClient();    

}
