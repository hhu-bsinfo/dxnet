/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science,
 * Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxnet;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.AbstractConnection;
import de.hhu.bsinfo.dxnet.core.AbstractConnectionManager;
import de.hhu.bsinfo.dxnet.core.CoreConfig;
import de.hhu.bsinfo.dxnet.core.LocalMessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.Message;
import de.hhu.bsinfo.dxnet.core.MessageCreationCoordinator;
import de.hhu.bsinfo.dxnet.core.MessageDirectory;
import de.hhu.bsinfo.dxnet.core.MessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxnet.core.Request;
import de.hhu.bsinfo.dxnet.core.RequestMap;
import de.hhu.bsinfo.dxnet.core.messages.DefaultMessage;
import de.hhu.bsinfo.dxnet.core.messages.Messages;
import de.hhu.bsinfo.dxnet.ib.IBConfig;
import de.hhu.bsinfo.dxnet.ib.IBConnectionManager;
import de.hhu.bsinfo.dxnet.loopback.LoopbackConfig;
import de.hhu.bsinfo.dxnet.loopback.LoopbackConnectionManager;
import de.hhu.bsinfo.dxnet.nio.NIOConfig;
import de.hhu.bsinfo.dxnet.nio.NIOConnectionManager;
import de.hhu.bsinfo.dxutils.NodeID;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.TimePool;

/**
 * DXNet main class. The network subsystem supports different types of transport. Ethernet using Java NIO and
 * InfiniBand using libibverbs through an additional JNI library (libJNIIbdxnet). The network allows you to easily
 * send messages, requests or responses to requests to another destination, receive incoming messages or requests and
 * process them in your application.
 *
 * @author Florian Klein, florian.klein@hhu.de, 18.03.2012
 * @author Marc Ewert, marc.ewert@hhu.de, 14.08.2014
 * @author Kevin Beineke, kevin.beineke@hhu.de, 20.11.2015
 * @author Stefan Nothaas, kevin.beineke@hhu.de, 11.08.2017
 */
public final class DXNet {
    private static final Logger LOGGER = LogManager.getFormatterLogger(DXNet.class.getSimpleName());

    private static final TimePool SOP_SEND = new TimePool(DXNet.class, "Send");
    private static final TimePool SOP_SEND_SYNC = new TimePool(DXNet.class, "SendSync");
    private static final TimePool SOP_WAIT_RESPONSE = new TimePool(DXNet.class, "WaitResponse");

    static {
        StatisticsManager.get().registerOperation(DXNet.class, SOP_SEND);
        StatisticsManager.get().registerOperation(DXNet.class, SOP_SEND_SYNC);
        StatisticsManager.get().registerOperation(DXNet.class, SOP_WAIT_RESPONSE);
    }

    private static final int MESSAGE_HEADER_POOL_SIZE = 1024 * 1024;
    private static final int OVERPROVISIONING_OFFSET = 25;

    private final CoreConfig m_coreConfig;
    private final NIOConfig m_nioConfig;
    private final IBConfig m_ibConfig;
    private final LoopbackConfig m_loopbackConfig;
    private final boolean m_loopbackDeviceActive;

    private final MessageReceiverStore m_messageReceivers;
    private final MessageHandlers m_messageHandlers;
    private final MessageDirectory m_messageDirectory;
    private final MessageCreationCoordinator m_messageCreationCoordinator;

    private final AtomicLongArray m_lastFailures;

    private final RequestMap m_requestMap;
    private final int m_timeOut;

    private final AbstractConnectionManager m_connectionManager;

    private int m_availableCores;
    private volatile boolean m_overprovisioning = false;
    private AtomicInteger m_sendThreads = new AtomicInteger(0);

    /**
     * Constructor
     *
     * @param p_coreConfig
     *         Configuration parameters for core
     * @param p_nioConfig
     *         Configuration parameters for NIO
     * @param p_ibConfig
     *         Configuration parameters for InfiniBand
     * @param p_loopbackConfig
     *         Configuration parameters for Loopback
     * @param p_nodeMap
     *         NodeMap implementation to lookup node ids
     */
    public DXNet(final CoreConfig p_coreConfig, final NIOConfig p_nioConfig, final IBConfig p_ibConfig,
            final LoopbackConfig p_loopbackConfig, final NodeMap p_nodeMap) {
        m_coreConfig = p_coreConfig;
        m_nioConfig = p_nioConfig;
        m_ibConfig = p_ibConfig;
        m_loopbackConfig = p_loopbackConfig;

        switch (m_coreConfig.getDevice()) {
            case ETHERNET:
                m_loopbackDeviceActive = false;
                m_messageReceivers = new MessageReceiverStore((int) m_nioConfig.getRequestTimeOut().getMs());
                break;

            case INFINIBAND:
                m_loopbackDeviceActive = false;
                m_messageReceivers = new MessageReceiverStore((int) m_ibConfig.getRequestTimeOut().getMs());
                break;

            case LOOPBACK:
                m_loopbackDeviceActive = true;
                m_messageReceivers = new MessageReceiverStore((int) m_loopbackConfig.getRequestTimeOut().getMs());
                break;

            default:
                throw new IllegalStateException();
        }

        m_availableCores =
                Runtime.getRuntime().availableProcessors() + OVERPROVISIONING_OFFSET -
                        m_coreConfig.getNumMessageHandlerThreads() - 1 /*SendReceive thread*/ -
                        1 /*MessageCreationCoordinator*/;

        if (m_availableCores <= 0) {
            m_overprovisioning = true;

            LOGGER.info("Overprovisioning detected (%d network threads on %d cores). Activating parking strategy " +
                            "for network threads.",
                    m_coreConfig.getNumMessageHandlerThreads() + 2, Runtime.getRuntime().availableProcessors());
        }

        MessageHeaderPool globalHeaderPool = new MessageHeaderPool(MESSAGE_HEADER_POOL_SIZE);
        // Local message header pool for message creation coordinator thread
        LocalMessageHeaderPool localHeaderPool = new LocalMessageHeaderPool(globalHeaderPool);
        m_messageHandlers = new MessageHandlers(m_coreConfig.getNumMessageHandlerThreads(), m_overprovisioning,
                m_messageReceivers, globalHeaderPool);

        switch (m_coreConfig.getDevice()) {
            case ETHERNET:
                m_messageDirectory = new MessageDirectory((int) m_nioConfig.getRequestTimeOut().getMs());
                break;

            case INFINIBAND:
                m_messageDirectory = new MessageDirectory((int) m_ibConfig.getRequestTimeOut().getMs());
                break;

            case LOOPBACK:
                m_messageDirectory = new MessageDirectory((int) m_loopbackConfig.getRequestTimeOut().getMs());
                break;

            default:
                throw new IllegalStateException();
        }

        m_messageDirectory.register(Messages.DEFAULT_MESSAGES_TYPE, Messages.SUBTYPE_DEFAULT_MESSAGE,
                DefaultMessage.class);

        switch (m_coreConfig.getDevice()) {
            case ETHERNET:
                m_messageCreationCoordinator = new MessageCreationCoordinator(2 * 2 * 1024,
                        (int) m_nioConfig.getOutgoingRingBufferSize().getBytes() * 8,
                        m_overprovisioning);
                break;

            case INFINIBAND:
                m_messageCreationCoordinator = new MessageCreationCoordinator(m_ibConfig.getIbqMaxCapacityBufferCount(),
                        (int) m_ibConfig.getIbqMaxCapacitySize().getBytes(), m_overprovisioning);
                break;

            case LOOPBACK:
                m_messageCreationCoordinator = new MessageCreationCoordinator(2 * 2 * 1024,
                        (int) m_loopbackConfig.getOutgoingRingBufferSize().getBytes() * 8,
                        m_overprovisioning);
                break;

            default:
                throw new IllegalStateException();
        }

        m_messageCreationCoordinator.setName("Network: MessageCreationCoordinator");
        m_messageCreationCoordinator.start();

        m_lastFailures = new AtomicLongArray(65536);

        m_requestMap = new RequestMap(m_coreConfig.getRequestMapSize());

        switch (m_coreConfig.getDevice()) {
            case ETHERNET:
                m_timeOut = (int) m_nioConfig.getRequestTimeOut().getMs();
                m_connectionManager = new NIOConnectionManager(m_coreConfig, m_nioConfig, p_nodeMap, m_messageDirectory,
                        m_requestMap,
                        m_messageCreationCoordinator.getIncomingBufferQueue(), localHeaderPool, m_messageHandlers,
                        m_overprovisioning);
                break;

            case INFINIBAND:
                m_timeOut = (int) m_ibConfig.getRequestTimeOut().getMs();
                m_connectionManager = new IBConnectionManager(m_coreConfig, m_ibConfig, p_nodeMap, m_messageDirectory,
                        m_requestMap,
                        m_messageCreationCoordinator.getIncomingBufferQueue(), localHeaderPool, m_messageHandlers,
                        m_overprovisioning);
                ((IBConnectionManager) m_connectionManager).init();
                break;

            case LOOPBACK:
                m_timeOut = (int) m_loopbackConfig.getRequestTimeOut().getMs();
                m_connectionManager = new LoopbackConnectionManager(m_coreConfig, m_loopbackConfig, p_nodeMap,
                        m_messageDirectory, m_requestMap,
                        m_messageCreationCoordinator.getIncomingBufferQueue(), localHeaderPool, m_messageHandlers,
                        m_overprovisioning);
                break;

            default:
                throw new IllegalStateException();
        }
    }

    /**
     * Get the status of the network system (debug string)
     *
     * @return Status as string
     */
    public String getStatus() {
        String str = "";

        str += m_connectionManager.getConnectionStatuses();

        return str;
    }

    /**
     * Returns whether the request map is empty or not.
     * Is used for benchmarking (DXNetMain), only.
     *
     * @return whether the request map is empty or not
     */
    boolean isRequestMapEmpty() {
        return m_requestMap.isEmpty();
    }

    /**
     * Set the ConnectionManager listener
     *
     * @param p_listener
     *         Listener to set
     */
    public void setConnectionManagerListener(final ConnectionManagerListener p_listener) {
        m_connectionManager.setListener(p_listener);
    }

    /**
     * Registers a new message type
     *
     * @param p_type
     *         the unique type
     * @param p_subtype
     *         the unique subtype
     * @param p_class
     *         the calling class
     */
    public void registerMessageType(final byte p_type, final byte p_subtype, final Class<?> p_class) {
        boolean ret;

        ret = m_messageDirectory.register(p_type, p_subtype, p_class);

        if (!ret) {
            LOGGER.warn(
                    "Registering network message %s for type %s and subtype %s failed, type and subtype already used",
                    p_class.getSimpleName(), p_type,
                    p_subtype);
        }
    }

    /**
     * Registers a special receive message type
     *
     * @param p_type
     *         the unique type
     * @param p_subtype
     *         the unique subtype
     */
    public void registerSpecialReceiveMessageType(final byte p_type, final byte p_subtype) {
        m_messageHandlers.registerSpecialReceiveMessageType(p_type, p_subtype);
    }

    /**
     * Closes the network
     */
    public void close() {
        m_connectionManager.close();

        m_messageCreationCoordinator.shutdown();

        m_messageHandlers.close();
    }

    /**
     * Registers a message receiver
     *
     * @param p_type
     *         the message type
     * @param p_subtype
     *         the message subtype
     * @param p_receiver
     *         the receiver
     */
    public void register(final byte p_type, final byte p_subtype, final MessageReceiver p_receiver) {
        m_messageReceivers.register(p_type, p_subtype, p_receiver);
    }

    /**
     * Unregisters a message receiver
     *
     * @param p_type
     *         the message type
     * @param p_subtype
     *         the message subtype
     * @param p_receiver
     *         the receiver
     */
    public void unregister(final byte p_type, final byte p_subtype, final MessageReceiver p_receiver) {
        m_messageReceivers.unregister(p_type, p_subtype, p_receiver);
    }

    /**
     * Try to force connect to a specific node. When sending messages, the system tries to connect to the destination
     * first, if it is not connected, automatically. This call is mainly used to detect node failures.
     *
     * @param p_nodeID
     *         Node to connect to
     * @throws NetworkException
     *         If connecting to the node failed
     */
    public void connectNode(final short p_nodeID) throws NetworkException {
        LOGGER.trace("Entering connectNode with: p_nodeID=0x%X", p_nodeID);

        try {
            if (m_connectionManager.getConnection(p_nodeID) == null) {
                throw new NetworkException(
                        "Connection to " + NodeID.toHexString(p_nodeID) + " could not be established");
            }
        } catch (final NetworkException e) {
            LOGGER.debug("IOException during connection lookup", e);
            throw new NetworkDestinationUnreachableException(p_nodeID);
        }

        LOGGER.trace("Exiting connectNode");
    }

    /**
     * Sends a message
     *
     * @param p_message
     *         the message to send
     * @throws NetworkException
     *         If sending the message failed
     */
    public void sendMessage(final Message p_message) throws NetworkException {
        AbstractConnection connection;

        LOGGER.trace("Entering sendMessage with: p_message=%s", p_message);

        SOP_SEND.startDebug();

        if (!m_overprovisioning && m_sendThreads.incrementAndGet() > m_availableCores) {
            m_overprovisioning = true;
            m_connectionManager.setOverprovisioning();
            m_messageCreationCoordinator.activateParking();
            m_messageHandlers.activateParking();

            LOGGER.info("Overprovisioning detected (%d network threads and >= %d application threads on %d cores)." +
                            "Activating parking strategy for network threads.",
                    m_coreConfig.getNumMessageHandlerThreads() + 2, m_sendThreads.get(),
                    Runtime.getRuntime().availableProcessors());
        }

        if (p_message.getDestination() == m_coreConfig.getOwnNodeId() && !m_loopbackDeviceActive) {
            LOGGER.error("Invalid destination 0x%X. No loopback allowed.", p_message.getDestination());
        } else {
            try {
                connection = m_connectionManager.getConnection(p_message.getDestination());
            } catch (final NetworkException ignored) {
                LOGGER.debug("Connection to 0x%X could not be established!", p_message.getDestination());
                throw new NetworkDestinationUnreachableException(p_message.getDestination());
            }

            try {
                if (connection != null) {
                    connection.postMessage(p_message);
                } else {
                    long timestamp = m_lastFailures.get(p_message.getDestination() & 0xFFFF);

                    if (timestamp == 0 || timestamp + 1000 < System.currentTimeMillis()) {
                        m_lastFailures.set(p_message.getDestination() & 0xFFFF, System.currentTimeMillis());

                        LOGGER.debug(
                                "Connection invalid. Ignoring connection excepts regarding 0x%X during the next second!",
                                p_message.getDestination());
                        throw new NetworkDestinationUnreachableException(p_message.getDestination());
                    }
                }
            } catch (final NetworkException e) {
                LOGGER.warn("Sending data failed: %s", e.getMessage());
                throw new NetworkException("Sending data failed ", e);
            }
        }

        if (!m_overprovisioning) {
            m_sendThreads.decrementAndGet();
        }

        SOP_SEND.stopDebug();

        LOGGER.trace("Exiting sendMessage");
    }

    /**
     * Send request and wait for fulfillment (wait for response).
     *
     * @param p_request
     *         The request to send.
     * @param p_timeout
     *         The amount of time to wait for a response
     * @param p_waitForResponses
     *         Set to false to not wait/block until the response arrived
     * @throws NetworkException
     *         If sending the message failed or waiting for the response failed (timeout)
     */
    public void sendSync(final Request p_request, final int p_timeout, final boolean p_waitForResponses)
            throws NetworkException {
        LOGGER.trace("Sending request (sync): %s", p_request);

        SOP_SEND_SYNC.startDebug();

        try {
            m_requestMap.put(p_request);
            sendMessage(p_request);
        } catch (final NetworkException e) {
            m_requestMap.remove(p_request.getRequestID());
            throw e;
        }

        LOGGER.trace("Waiting for response to request: %s", p_request);

        int timeout = p_timeout != -1 ? p_timeout : m_timeOut;

        if (p_waitForResponses) {
             try {
                SOP_WAIT_RESPONSE.start();

                if(Thread.currentThread().getName().contains("MessageHandler")) {
                    LOGGER.debug(Thread.currentThread().getName() + " is blocked.");
                    m_messageHandlers.incBlockedMessageHandlers();
                }

                p_request.waitForResponse(timeout);

                SOP_WAIT_RESPONSE.stop();
            } catch (final NetworkResponseDelayedException e) {
                SOP_WAIT_RESPONSE.stop();

                SOP_SEND_SYNC.stopDebug();

                LOGGER.warn("Sending sync, waiting for responses to %s failed, timeout: %d ms", p_request, timeout);

                m_requestMap.remove(p_request.getRequestID());

                throw e;
            } catch (final NetworkResponseCancelledException e) {
                SOP_WAIT_RESPONSE.stop();

                SOP_SEND_SYNC.stopDebug();

                LOGGER.warn("Sending sync, waiting for responses to %s failed, cancelled: %d ms", p_request, timeout);

                throw e;
            } finally {
                 if(Thread.currentThread().getName().contains("MessageHandler")) {
                     m_messageHandlers.devBlockedMessageHandlers();
                     LOGGER.debug(Thread.currentThread().getName() + " is deblocked.");
                 }
             }
        }

        SOP_SEND_SYNC.stopDebug();
    }

    /**
     * Cancel all pending requests waiting for a response. Also used on node failure, only
     *
     * @param p_nodeId
     *         Node id of the target node the requests
     *         are waiting for a response
     */
    public void cancelAllRequests(final short p_nodeId) {
        m_requestMap.removeAll(p_nodeId);
    }

}
