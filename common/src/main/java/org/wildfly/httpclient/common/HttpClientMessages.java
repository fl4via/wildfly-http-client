package org.wildfly.httpclient.common;

import java.io.IOException;

import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Cause;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;
import org.wildfly.client.config.ConfigXMLParseException;
import io.undertow.client.ClientResponse;

@MessageLogger(projectCode = "WFHTTP")
interface HttpClientMessages extends BasicLogger {

    HttpClientMessages MESSAGES = Logger.getMessageLogger(HttpClientMessages.class, HttpClientMessages.class.getPackage().getName());

    @Message(id = 1, value = "Connection in wrong state")
    IllegalStateException connectionInWrongState();

    @Message(id = 2, value = "Port value %s out of range")
    ConfigXMLParseException portValueOutOfRange(int port);

    @Message(id = 3, value = "Failed to acquire session")
    @LogMessage(level = Logger.Level.ERROR)
    void failedToAcquireSession(@Cause Throwable t);

    @Message(id = 4, value = "Invalid response type %s")
    IOException invalidResponseType(ContentType type);

    @Message(id = 5, value = "Invalid response code %s (full response %s)")
    IOException invalidResponseCode(int responseCode, ClientResponse response);

    @LogMessage(level = Logger.Level.ERROR)
    @Message(id = 6, value = "Failed to write exception")
    void failedToWriteException(@Cause Exception ex);

}
