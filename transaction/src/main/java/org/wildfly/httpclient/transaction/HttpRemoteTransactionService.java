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

package org.wildfly.httpclient.transaction;

import static org.wildfly.httpclient.common.MarshallingHelper.newConfig;
import static org.wildfly.httpclient.common.MarshallingHelper.newMarshaller;
import static org.wildfly.httpclient.common.MarshallingHelper.newUnmarshaller;
import static org.wildfly.httpclient.transaction.TransactionConstants.NEW_TRANSACTION;
import static org.wildfly.httpclient.transaction.TransactionConstants.EXCEPTION;
import static org.wildfly.httpclient.transaction.TransactionConstants.RECOVERY_FLAGS;
import static org.wildfly.httpclient.transaction.TransactionConstants.RECOVERY_PARENT_NAME;
import static org.wildfly.httpclient.transaction.TransactionConstants.TIMEOUT;
import static org.wildfly.httpclient.transaction.TransactionConstants.UT_BEGIN_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.UT_ROLLBACK_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.UT_COMMIT_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_BC_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_COMMIT_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_FORGET_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_PREP_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_ROLLBACK_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XA_RECOVER_PATH;
import static org.wildfly.httpclient.transaction.TransactionConstants.XID;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.function.Function;
import javax.transaction.xa.Xid;

import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.Unmarshaller;
import org.wildfly.common.function.ExceptionBiFunction;
import org.wildfly.httpclient.common.ContentType;
import org.wildfly.httpclient.common.ElytronIdentityHandler;
import org.wildfly.httpclient.common.NoFlushByteOutput;
import org.wildfly.transaction.client.ImportResult;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.LocalTransactionContext;
import org.wildfly.transaction.client.SimpleXid;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

/**
 * @author Stuart Douglas
 */
public class HttpRemoteTransactionService {

    private final LocalTransactionContext transactionContext;
    private final Function<LocalTransaction, Xid> xidResolver;

    public HttpRemoteTransactionService(LocalTransactionContext transactionContext, Function<LocalTransaction, Xid> xidResolver) {
        this.transactionContext = transactionContext;
        this.xidResolver = xidResolver;
    }

    public HttpHandler createHandler() {
        RoutingHandler routingHandler = new RoutingHandler();
        routingHandler.add(Methods.POST, UT_BEGIN_PATH, new BeginHandler());
        routingHandler.add(Methods.POST, UT_ROLLBACK_PATH, new UTRollbackHandler());
        routingHandler.add(Methods.POST, UT_COMMIT_PATH, new UTCommitHandler());
        routingHandler.add(Methods.POST, XA_BC_PATH, new XABeforeCompletionHandler());
        routingHandler.add(Methods.POST, XA_COMMIT_PATH, new XACommitHandler());
        routingHandler.add(Methods.POST, XA_FORGET_PATH, new XAForgetHandler());
        routingHandler.add(Methods.POST, XA_PREP_PATH, new XAPrepHandler());
        routingHandler.add(Methods.POST, XA_ROLLBACK_PATH, new XARollbackHandler());
        routingHandler.add(Methods.GET, XA_RECOVER_PATH, new XARecoveryHandler());
        return new BlockingHandler(new ElytronIdentityHandler(routingHandler));
    }

    abstract class AbstractTransactionHandler implements HttpHandler {

        @Override
        public final void handleRequest(HttpServerExchange exchange) throws Exception {
            ContentType contentType = ContentType.parse(exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE));
            if (contentType == null || contentType.getVersion() != 1 || !contentType.getType().equals(XID.getType())) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s has incorrect or missing content type", exchange);
                return;
            }

            try {
                Unmarshaller unmarshaller = newUnmarshaller(newConfig());
                unmarshaller.start(new InputStreamByteInput(exchange.getInputStream()));
                int formatId = unmarshaller.readInt();
                int len = unmarshaller.readInt();
                byte[] globalId = new byte[len];
                unmarshaller.readFully(globalId);
                len = unmarshaller.readInt();
                byte[] branchId = new byte[len];
                unmarshaller.readFully(branchId);
                SimpleXid simpleXid = new SimpleXid(formatId, globalId, branchId);
                unmarshaller.finish();

                ImportResult<LocalTransaction> transaction = transactionContext.findOrImportTransaction(simpleXid, 0);
                transaction.getTransaction().performFunction((ExceptionBiFunction<ImportResult<LocalTransaction>, HttpServerExchange, Void, Exception>) (o, exchange2) -> {
                    handleImpl(exchange2, o);
                    return null;
                }, transaction, exchange);
            } catch (Exception e) {
                sendException(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }

        protected abstract void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> localTransactionImportResult) throws Exception;
    }

    class BeginHandler implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            try {
                String timeoutString = exchange.getRequestHeaders().getFirst(TIMEOUT);
                if (timeoutString == null) {
                    exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                    HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, TIMEOUT);
                    return;
                }
                final Integer timeout = Integer.parseInt(timeoutString);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, NEW_TRANSACTION.toString());
                final LocalTransaction transaction = transactionContext.beginTransaction(timeout);
                final Xid xid = xidResolver.apply(transaction);
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                Marshaller marshaller = newMarshaller(newConfig());
                marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(out)));
                marshaller.writeInt(xid.getFormatId());
                marshaller.writeInt(xid.getGlobalTransactionId().length);
                marshaller.write(xid.getGlobalTransactionId());
                marshaller.writeInt(xid.getBranchQualifier().length);
                marshaller.write(xid.getBranchQualifier());
                marshaller.finish();
                exchange.getResponseSender().send(ByteBuffer.wrap(out.toByteArray()));
            } catch (Exception e) {
                sendException(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    class XARecoveryHandler implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            try {
                String flagsStringString = exchange.getRequestHeaders().getFirst(RECOVERY_FLAGS);
                if (flagsStringString == null) {
                    exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                    HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, RECOVERY_FLAGS);
                    return;
                }
                final int flags = Integer.parseInt(flagsStringString);
                String parentName = exchange.getRequestHeaders().getFirst(RECOVERY_PARENT_NAME);
                if (parentName == null) {
                    exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                    HttpRemoteTransactionMessages.MESSAGES.debugf("Exchange %s is missing %s header", exchange, RECOVERY_PARENT_NAME);
                    return;
                }

                final Xid[] recoveryList = transactionContext.getRecoveryInterface().recover(flags, parentName);
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                Marshaller marshaller = newMarshaller(newConfig());
                marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(out)));
                marshaller.writeInt(recoveryList.length);
                for (int i = 0; i < recoveryList.length; ++i) {
                    Xid xid = recoveryList[i];
                    marshaller.writeInt(xid.getFormatId());
                    marshaller.writeInt(xid.getGlobalTransactionId().length);
                    marshaller.write(xid.getGlobalTransactionId());
                    marshaller.writeInt(xid.getBranchQualifier().length);
                    marshaller.write(xid.getBranchQualifier());
                }
                marshaller.finish();
                exchange.getResponseSender().send(ByteBuffer.wrap(out.toByteArray()));
            } catch (Exception e) {
                sendException(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e);
            }
        }
    }

    class UTRollbackHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getTransaction().rollback();
        }
    }

    class UTCommitHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getTransaction().commit();
        }
    }

    class XABeforeCompletionHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().beforeCompletion();
        }
    }

    class XAForgetHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().forget();
        }
    }

    class XAPrepHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().prepare();
        }
    }

    class XARollbackHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            transaction.getControl().rollback();
        }
    }

    class XACommitHandler extends AbstractTransactionHandler {

        @Override
        protected void handleImpl(HttpServerExchange exchange, ImportResult<LocalTransaction> transaction) throws Exception {
            Deque<String> opc = exchange.getQueryParameters().get("opc");
            boolean onePhase = false;
            if (opc != null && !opc.isEmpty()) {
                onePhase = Boolean.parseBoolean(opc.poll());
            }
            transaction.getControl().commit(onePhase);
        }
    }

    public static void sendException(HttpServerExchange exchange, int status, Throwable e) {
        try {
            exchange.setStatusCode(status);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EXCEPTION.toString());

            final Marshaller marshaller = newMarshaller(newConfig());
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final ByteOutput byteOutput = new NoFlushByteOutput(Marshalling.createByteOutput(outputStream));
            // start the marshaller
            marshaller.start(byteOutput);
            marshaller.writeObject(e);
            marshaller.write(0);
            marshaller.finish();
            marshaller.flush();
            exchange.getResponseSender().send(ByteBuffer.wrap(outputStream.toByteArray()));
        } catch (IOException e1) {
            HttpRemoteTransactionMessages.MESSAGES.debugf(e, "Failed to write exception");
        }
    }
}
