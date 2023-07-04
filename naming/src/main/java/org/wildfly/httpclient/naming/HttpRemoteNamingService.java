/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.httpclient.naming;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.PathTemplateMatch;
import io.undertow.util.StatusCodes;
import org.jboss.marshalling.ContextClassResolver;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.HttpMarshallerFactory;
import org.wildfly.httpclient.common.HttpServerHelper;
import org.wildfly.httpclient.common.HttpServiceConfig;
import org.wildfly.httpclient.common.NoFlushByteOutput;

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.NameClassPair;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Deque;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.wildfly.httpclient.naming.NamingConstants.BIND_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.CREATE_SUBCONTEXT_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.DESTROY_SUBCONTEXT_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LIST_BINDINGS_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LIST_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LOOKUP_LINK_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.LOOKUP_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.NAME_PATH_PARAMETER;
import static org.wildfly.httpclient.naming.NamingConstants.NEW_QUERY_PARAMETER;
import static org.wildfly.httpclient.naming.NamingConstants.REBIND_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.RENAME_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.UNBIND_PATH;
import static org.wildfly.httpclient.naming.NamingConstants.VALUE;

/**
 * HTTP service that handles naming invocations.
 *
 * @author Stuart Douglas
 */
public class HttpRemoteNamingService {

    private final Context localContext;
    private final Function<String, Boolean> classResolverFilter;
    private final HttpServiceConfig httpServiceConfig;

    @Deprecated
    public HttpRemoteNamingService(Context localContext) {
        this(localContext,  HttpServiceConfig.getInstance(), null);
    }

    public HttpRemoteNamingService(Context localContext, Function<String, Boolean> classResolverFilter) {
        this (localContext, HttpServiceConfig.getInstance(), classResolverFilter);
    }

    HttpRemoteNamingService(Context localContext, final HttpServiceConfig httpServiceConfig, Function<String, Boolean> classResolverFilter) {
        this.localContext = localContext;
        this.httpServiceConfig = httpServiceConfig;
        this.classResolverFilter = classResolverFilter;
    }


    public HttpHandler createHandler() {
        RoutingHandler routingHandler = new RoutingHandler();
        final String nameParamPathSuffix = "/{" + NAME_PATH_PARAMETER + "}";
        routingHandler.add(Methods.POST, LOOKUP_PATH + nameParamPathSuffix, new LookupHandler());
        routingHandler.add(Methods.GET, LOOKUP_LINK_PATH + nameParamPathSuffix, new LookupLinkHandler());
        routingHandler.add(Methods.PUT, BIND_PATH + nameParamPathSuffix, new BindHandler());
        routingHandler.add(Methods.PATCH, REBIND_PATH + nameParamPathSuffix, new RebindHandler());
        routingHandler.add(Methods.DELETE, UNBIND_PATH + nameParamPathSuffix, new UnbindHandler());
        routingHandler.add(Methods.DELETE, DESTROY_SUBCONTEXT_PATH + nameParamPathSuffix, new DestroySubcontextHandler());
        routingHandler.add(Methods.GET, LIST_PATH + nameParamPathSuffix, new ListHandler());
        routingHandler.add(Methods.GET, LIST_BINDINGS_PATH + nameParamPathSuffix, new ListBindingsHandler());
        routingHandler.add(Methods.PATCH, RENAME_PATH + nameParamPathSuffix, new RenameHandler());
        routingHandler.add(Methods.PUT, CREATE_SUBCONTEXT_PATH + nameParamPathSuffix, new CreateSubContextHandler());
        return httpServiceConfig.wrap(new BlockingHandler(new ElytronIdentityHandler(routingHandler)));
    }


    private abstract class NameHandler implements HttpHandler {

        @Override
        public final void handleRequest(HttpServerExchange exchange) throws Exception {
            PathTemplateMatch params = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
            String name = URLDecoder.decode(params.getParameters().get(NAME_PATH_PARAMETER), UTF_8.name());
            try {
                Object result = doOperation(exchange, name);
                if (exchange.isComplete()) {
                    return;
                }
                if (result == null) {
                    exchange.setStatusCode(StatusCodes.OK);
                } else if (result instanceof Context) {
                    exchange.setStatusCode(StatusCodes.NO_CONTENT);
                } else {
                    doMarshall(exchange, result);
                }
            } catch (Throwable e) {
                e.printStackTrace();
                sendException(exchange, httpServiceConfig, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }

        protected abstract Object doOperation(HttpServerExchange exchange, String name) throws NamingException;
    }

    private final class LookupHandler extends NameHandler {

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return localContext.lookup(name);
        }
    }

    private final class LookupLinkHandler extends NameHandler {

        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return localContext.lookupLink(name);
        }
    }

    private class CreateSubContextHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            return localContext.createSubcontext(name);
        }
    }

    private class UnbindHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            localContext.unbind(name);
            return null;
        }
    }

    private class ListBindingsHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            final NamingEnumeration<Binding> namingEnumeration = localContext.listBindings(name);
            return Collections.list(namingEnumeration);
        }
    }

    private class RenameHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            Deque<String> newName = exchange.getQueryParameters().get(NEW_QUERY_PARAMETER);
            if (newName == null || newName.isEmpty()) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.endExchange();
                return null;
            }
            try {
                String nn = URLDecoder.decode(newName.getFirst(), UTF_8.name());
                localContext.rename(name, nn);
                return null;
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private class DestroySubcontextHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            localContext.destroySubcontext(name);
            return null;
        }
    }

    private class ListHandler extends NameHandler {
        @Override
        protected Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            final NamingEnumeration<NameClassPair> namingEnumeration = localContext.list(name);
            return Collections.list(namingEnumeration);
        }
    }

    private class RebindHandler extends BindHandler {

        @Override
        protected void doOperation(String name, Object object) throws NamingException {
            localContext.rebind(name, object);
        }
    }

    private class BindHandler extends NameHandler {
        @Override
        protected final Object doOperation(HttpServerExchange exchange, String name) throws NamingException {
            ContentType contentType = ContentType.parse(exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE));
            if (contentType == null || !contentType.getType().equals(VALUE.getType()) || contentType.getVersion() != 1) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.endExchange();
                return null;
            }
            final HttpMarshallerFactory marshallerFactory = httpServiceConfig.getHttpUnmarshallerFactory(exchange);
            try (InputStream inputStream = exchange.getInputStream()) {
                Unmarshaller unmarshaller = classResolverFilter != null ?
                        marshallerFactory.createUnmarshaller(new FilterClassResolver(classResolverFilter)):
                        marshallerFactory.createUnmarshaller();
                unmarshaller.start(new InputStreamByteInput(inputStream));
                Object object = unmarshaller.readObject();
                unmarshaller.finish();
                doOperation(name, object);
            } catch (Exception e) {
                if (e instanceof NamingException) {
                    throw (NamingException)e;
                }
                NamingException nm = new NamingException(e.getMessage());
                nm.initCause(e);
                throw nm;
            }
            return null;
        }

        protected void doOperation(String name, Object object) throws NamingException {
            localContext.bind(name, object);
        }
    }

    private void doMarshall(HttpServerExchange exchange, Object result) throws IOException {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, VALUE.toString());
        Marshaller marshaller = httpServiceConfig.getHttpMarshallerFactory(exchange).createMarshaller();
        marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(exchange.getOutputStream())));
        marshaller.writeObject(result);
        marshaller.finish();
        marshaller.flush();
    }

    @Deprecated
    public static void sendException(HttpServerExchange exchange, int status, Throwable e) throws IOException {
        HttpServerHelper.sendException(exchange, HttpServiceConfig.getInstance(), status, e);
    }

    public static void sendException(HttpServerExchange exchange, HttpServiceConfig httpServiceConfig, int status, Throwable e) throws IOException {
        HttpServerHelper.sendException(exchange, httpServiceConfig, status, e);
    }

    private static class FilterClassResolver extends ContextClassResolver {
        private final Function<String, Boolean> filter;

        private FilterClassResolver(Function<String, Boolean> filter) {
            this.filter = filter;
        }

        @Override
        public Class<?> resolveClass(Unmarshaller unmarshaller, String name, long serialVersionUID) throws IOException, ClassNotFoundException {
            checkFilter(name);
            return super.resolveClass(unmarshaller, name, serialVersionUID);
        }

        @Override
        public Class<?> resolveProxyClass(Unmarshaller unmarshaller, String[] interfaces) throws IOException, ClassNotFoundException {
            for (String name : interfaces) {
                checkFilter(name);
            }
            return super.resolveProxyClass(unmarshaller, interfaces);
        }

        private void checkFilter(String className) throws InvalidClassException {
            if (filter.apply(className) != Boolean.TRUE) {
                throw HttpNamingClientMessages.MESSAGES.cannotResolveFilteredClass(className);
            }
        }
    }
}
