package org.wildfly.httpclient.common;

import static java.security.AccessController.doPrivileged;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.undertow.UndertowOptions;
import org.wildfly.common.context.ContextManager;
import org.wildfly.common.context.Contextual;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.DefaultByteBufferPool;

/**
 * Represents the current configured state of the HTTP contexts.
 *
 * @author Stuart Douglas
 */
public class WildflyHttpContext implements Contextual<WildflyHttpContext> {

    /**
     * The context manager for HTTP endpoints.
     */
    static ContextManager<WildflyHttpContext> HTTP_CONTEXT_MANAGER = doPrivileged((PrivilegedAction<ContextManager<WildflyHttpContext>>) () -> {
        final ContextManager<WildflyHttpContext> contextManager = new ContextManager<>(WildflyHttpContext.class, "jboss-ejb-http-client.http-context");
        contextManager.setGlobalDefaultSupplierIfNotSet(ConfigurationHttpContextSupplier::new);
        return contextManager;
    });

    /**
     * TODO: figure out some way to remove these when all the connections are closed, it has the potential to be very racey
     */
    private final Map<URI, HttpTargetContext> uriConnectionPools = new ConcurrentHashMap<>();

    private final ConfigSection[] targets;

    private final int maxConnections;
    private final int maxStreamsPerConnection;
    private final long idleTimeout;
    private final boolean eagerlyAcquireAffinity;
    private final XnioWorker worker;
    private final ByteBufferPool pool;
    private final boolean enableHttp2;

    WildflyHttpContext(ConfigSection[] targets, int maxConnections, int maxStreamsPerConnection, long idleTimeout, boolean eagerlyAcquireAffinity, XnioWorker worker, ByteBufferPool pool, boolean enableHttp2) {
        this.targets = targets;
        this.maxConnections = maxConnections;
        this.maxStreamsPerConnection = maxStreamsPerConnection;
        this.idleTimeout = idleTimeout;
        this.eagerlyAcquireAffinity = eagerlyAcquireAffinity;
        this.worker = worker;
        this.pool = pool;
        this.enableHttp2 = enableHttp2;
    }

    public static WildflyHttpContext getCurrent() {
        return HttpContextGetterHolder.SUPPLIER.get();
    }

    @Override
    public ContextManager<WildflyHttpContext> getInstanceContextManager() {
        return HTTP_CONTEXT_MANAGER;
    }

    public HttpTargetContext getTargetContext(final URI uri) {
        return getConnectionPoolForURI(uri);
    }

    private HttpTargetContext getConnectionPoolForURI(URI uri) {
        HttpTargetContext context = uriConnectionPools.get(uri);
        if (context != null) {
            context.init();
            return context;
        }
        for (ConfigSection target : targets) {
            if (target.getUri().equals(uri)) {
                target.getHttpTargetContext().init();
                uriConnectionPools.put(uri, target.getHttpTargetContext());
                return target.getHttpTargetContext();
            }
        }
        synchronized (this) {
            context = uriConnectionPools.get(uri);
            if (context != null) {
                return context;
            }
            HttpConnectionPool pool = new HttpConnectionPool(maxConnections, maxStreamsPerConnection, worker, this.pool, OptionMap.create(UndertowOptions.ENABLE_HTTP2, enableHttp2), new HostPool(uri), idleTimeout);
            uriConnectionPools.put(uri, context = new HttpTargetContext(pool, eagerlyAcquireAffinity));
            context.init();
            return context;
        }
    }

    static class ConfigSection {
        private final HttpTargetContext httpTargetContext;
        private final URI uri;

        ConfigSection(HttpTargetContext httpTargetContext, URI uri) {
            this.httpTargetContext = httpTargetContext;
            this.uri = uri;
        }

        public HttpTargetContext getHttpTargetContext() {
            return httpTargetContext;
        }

        public URI getUri() {
            return uri;
        }
    }

    static class Builder {
        private InetSocketAddress defaultBindAddress;
        private long idleTimeout;
        private int maxConnections;
        private int maxStreamsPerConnection;
        private Boolean eagerlyAcquireSession;
        private final List<HttpConfigBuilder> targets = new ArrayList<>();
        private Boolean enableHttp2;

        WildflyHttpContext build() {
            try {
                Xnio xnio = Xnio.getInstance();
                XnioWorker worker = xnio.createWorker(OptionMap.EMPTY); //TODO
                ByteBufferPool pool = new DefaultByteBufferPool(true, 1024); //TODO
                //TODO: ssl config
                WildflyHttpContext.ConfigSection[] connections = new WildflyHttpContext.ConfigSection[this.targets.size()];

                long idleTimout = this.idleTimeout > 0 ? this.idleTimeout : 60000;
                int maxConnections = this.maxConnections > 0 ? this.maxConnections : 10;
                int maxStreamsPerConnection = this.maxStreamsPerConnection > 0 ? this.maxStreamsPerConnection : 10;


                for (int i = 0; i < this.targets.size(); ++i) {
                    HttpConfigBuilder sb = this.targets.get(i);
                    HostPool hp = new HostPool(sb.getUri());
                    boolean eager = this.eagerlyAcquireSession == null ? false : this.eagerlyAcquireSession;
                    if (sb.getEagerlyAcquireSession() != null && sb.getEagerlyAcquireSession()) {
                        eager = true;
                    }
                    boolean http2 = this.enableHttp2 == null ? true : this.enableHttp2;
                    if(sb.getEnableHttp2() != null) {
                        http2 = sb.getEnableHttp2();
                    }
                    WildflyHttpContext.ConfigSection connection = new WildflyHttpContext.ConfigSection(new HttpTargetContext(new HttpConnectionPool(sb.getMaxConnections() > 0 ? sb.getMaxConnections() : maxConnections, sb.getMaxStreamsPerConnection() > 0 ? sb.getMaxStreamsPerConnection() : maxStreamsPerConnection, worker, pool, OptionMap.create(UndertowOptions.ENABLE_HTTP2, enableHttp2), hp, sb.getIdleTimeout() > 0 ? sb.getIdleTimeout() : idleTimout), eager), sb.getUri());
                    connections[i] = connection;
                }
                return new WildflyHttpContext(connections, maxConnections, maxStreamsPerConnection, idleTimeout, eagerlyAcquireSession == null ? false : eagerlyAcquireSession, worker, pool, enableHttp2 == null ? true : enableHttp2);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        void setDefaultBindAddress(InetSocketAddress defaultBindAddress) {
            this.defaultBindAddress = defaultBindAddress;
        }

        InetSocketAddress getDefaultBindAddress() {
            return defaultBindAddress;
        }

        HttpConfigBuilder addConfig(URI uri) {
            HttpConfigBuilder builder = new HttpConfigBuilder(uri);
            targets.add(builder);
            return builder;
        }

        List<HttpConfigBuilder> getTargets() {
            return targets;
        }


        long getIdleTimeout() {
            return idleTimeout;
        }

        void setIdleTimeout(long idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        int getMaxConnections() {
            return maxConnections;
        }

        void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        int getMaxStreamsPerConnection() {
            return maxStreamsPerConnection;
        }

        void setMaxStreamsPerConnection(int maxStreamsPerConnection) {
            this.maxStreamsPerConnection = maxStreamsPerConnection;
        }

        Boolean getEagerlyAcquireSession() {
            return eagerlyAcquireSession;
        }

        void setEagerlyAcquireSession(Boolean eagerlyAcquireSession) {
            this.eagerlyAcquireSession = eagerlyAcquireSession;
        }

        public void setEnableHttp2(Boolean enableHttp2) {
            this.enableHttp2 = enableHttp2;
        }

        public Boolean getEnableHttp2() {
            return enableHttp2;
        }

        class HttpConfigBuilder {
            final URI uri;
            private InetSocketAddress bindAddress;
            private long idleTimeout;
            private int maxConnections;
            private int maxStreamsPerConnection;
            private Boolean eagerlyAcquireSession;
            private Boolean enableHttp2;

            HttpConfigBuilder(URI uri) {
                this.uri = uri;
            }

            public URI getUri() {
                return uri;
            }

            void setBindAddress(InetSocketAddress bindAddress) {
                this.bindAddress = bindAddress;
            }

            InetSocketAddress getBindAddress() {
                return bindAddress;
            }

            long getIdleTimeout() {
                return idleTimeout;
            }

            void setIdleTimeout(long idleTimeout) {
                this.idleTimeout = idleTimeout;
            }

            int getMaxConnections() {
                return maxConnections;
            }

            void setMaxConnections(int maxConnections) {
                this.maxConnections = maxConnections;
            }

            int getMaxStreamsPerConnection() {
                return maxStreamsPerConnection;
            }

            void setMaxStreamsPerConnection(int maxStreamsPerConnection) {
                this.maxStreamsPerConnection = maxStreamsPerConnection;
            }

            Boolean getEagerlyAcquireSession() {
                return eagerlyAcquireSession;
            }

            void setEagerlyAcquireSession(Boolean eagerlyAcquireSession) {
                this.eagerlyAcquireSession = eagerlyAcquireSession;
            }

            public void setEnableHttp2(Boolean enableHttp2) {
                this.enableHttp2 = enableHttp2;
            }

            public Boolean getEnableHttp2() {
                return enableHttp2;
            }
        }
    }

}