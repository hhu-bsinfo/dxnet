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

package de.hhu.bsinfo.dxnet.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxutils.NodeID;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.Time;

/**
 * Manages the whole communication over socket channels like selecting channels, reading and writing data to/from
 * channels, creating/connecting channels, ...
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 18.03.2017
 */
class NIOSelector extends Thread {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOSelector.class.getSimpleName());

    private static final Time SOP_ACTION = new Time(NIOSelector.class, "Action");
    private static final Time SOP_SLEEP = new Time(NIOSelector.class, "Sleep");

    static {
        StatisticsManager.get().registerOperation(NIOSelector.class, SOP_ACTION);
        StatisticsManager.get().registerOperation(NIOSelector.class, SOP_SLEEP);
    }

    // Attributes
    private ServerSocketChannel m_serverChannel;
    private Selector m_selector;

    private NIOConnectionManager m_connectionManager;

    private int m_osBufferSize;
    private int m_connectionTimeout;
    private InterestQueue m_interestQueue;

    private volatile boolean m_running;

    // Constructors

    /**
     * Creates an instance of NIOSelector
     *
     * @param p_connectionTimeout
     *         the connection timeout
     * @param p_port
     *         the port
     * @param p_osBufferSize
     *         the size of incoming and outgoing buffers
     */
    NIOSelector(final NIOConnectionManager p_connectionManager, final int p_port, final int p_connectionTimeout,
            final int p_osBufferSize) {
        m_serverChannel = null;
        m_selector = null;

        m_connectionManager = p_connectionManager;

        m_osBufferSize = p_osBufferSize;
        m_connectionTimeout = p_connectionTimeout;
        m_interestQueue = new InterestQueue();

        m_running = false;

        // Create Selector on ServerSocketChannel
        IOException exception = null;
        for (int i = 0; i < 10; i++) {
            try {
                m_selector = Selector.open();
                m_serverChannel = ServerSocketChannel.open();
                m_serverChannel.configureBlocking(false);
                m_serverChannel.socket().setReceiveBufferSize(m_osBufferSize);
                int receiveBufferSize = m_serverChannel.socket().getReceiveBufferSize();
                if (receiveBufferSize < m_osBufferSize) {
                    LOGGER.warn(
                            "Receive buffer could not be set properly. Check OS settings! Requested: %d, actual: %d",
                            m_osBufferSize, receiveBufferSize);
                }
                m_serverChannel.socket().bind(new InetSocketAddress(p_port));
                m_serverChannel.register(m_selector, SelectionKey.OP_ACCEPT);

                m_running = true;

                exception = null;
                break;
            } catch (final IOException e) {
                exception = e;

                LOGGER.error("Could not bind network address. Retry in 1s");

                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ignored) {
                }
            }
        }

        if (exception != null) {
            LOGGER.error("Could not create network channel!");
        }
    }

    /**
     * Closes the Worker
     */
    protected void close() {
        try {
            m_serverChannel.close();
        } catch (final IOException ignore) {
            LOGGER.error("Unable to shutdown server channel!");
        }

        m_running = false;

        try {
            m_serverChannel.close();
        } catch (final IOException e) {
            LOGGER.error("Unable to close channel!");
        }

        LOGGER.info("Closing ServerSocketChannel successful");

        try {
            m_selector.close();
        } catch (final IOException e) {
            LOGGER.error("Unable to shutdown selector!");
        }

        LOGGER.info("Shutdown of Selector successful");
    }

    /**
     * Returns the Selector
     *
     * @return the Selector
     */
    Selector getSelector() {
        return m_selector;
    }

    /**
     * Append the given ChangeOperationsRequest to the Queue
     *
     * @param p_interest
     *         the operation interest to register.
     * @param p_connection
     *         the connection to register the interest for.
     */
    void changeOperationInterestAsync(final byte p_interest, final NIOConnection p_connection) {
        if (m_interestQueue.addInterest(p_interest, p_connection)) {
            m_selector.wakeup();
        }
    }

    /**
     * Append the given NIOConnection to the Queue
     *
     * @param p_connection
     *         the NIOConnection to close.
     */
    void closeConnectionAsync(final NIOConnection p_connection) {
        m_interestQueue.addInterest(InterestQueue.CLOSE, p_connection);

        m_selector.wakeup();
    }

    @Override
    public void run() {
        Iterator<SelectionKey> iterator;
        Set<SelectionKey> selected;
        SelectionKey key;

        while (m_running) {
            m_interestQueue.processInterests(m_selector, m_connectionManager, m_connectionTimeout);

            try {
                SOP_SLEEP.startDebug();

                int actionsAvail = m_selector.select();

                SOP_SLEEP.stopDebug();

                // Wait for network action
                if (actionsAvail > 0 && m_selector.isOpen()) {
                    SOP_ACTION.startDebug();

                    selected = m_selector.selectedKeys();
                    iterator = selected.iterator();

                    while (iterator.hasNext()) {
                        key = iterator.next();
                        iterator.remove();
                        if (key.isValid()) {
                            if (key.isAcceptable()) {
                                accept();
                            } else {
                                dispatch(key);
                            }
                        } else {
                            LOGGER.warn("Selected key is invalid: %s", key);
                        }
                    }

                    SOP_ACTION.stopDebug();
                }
            } catch (final ClosedSelectorException e) {
                // Ignore
            } catch (final IOException e) {
                LOGGER.error("Key selection failed!");
            }
        }

    }

    /**
     * Accept a new incoming connection
     *
     * @throws IOException
     *         if the new connection could not be accesses
     */
    private void accept() throws IOException {
        SocketChannel channel;

        channel = m_serverChannel.accept();
        channel.configureBlocking(false);
        channel.socket().setSoTimeout(0);
        channel.socket().setTcpNoDelay(true);
        channel.socket().setSendBufferSize(32);

        channel.register(m_selector, SelectionKey.OP_READ);
    }

    /**
     * Create a connection.
     * The channel was created by a remote node and already accepted. Now the NodeID must be read and the connection
     * object attached.
     *
     * @param p_key
     *         the selected key
     */
    private void createIncomingConnection(final SelectionKey p_key) {
        // Channel was accepted but not used yet -> Read NodeID, create NIOConnection and attach to key
        try {
            m_connectionManager.createIncomingConnection((SocketChannel) p_key.channel());
        } catch (final IOException e) {
            LOGGER.error("Connection could not be created!");
        }
    }

    /**
     * Either read data from PipeIn or a flow control update from PipeOut.
     *
     * @param p_key
     *         the selected key
     * @param p_connection
     *         the NIOConnection
     */
    private void read(final SelectionKey p_key, final NIOConnection p_connection) {
        boolean successful;

        if (p_connection == null) {
            createIncomingConnection(p_key);
            return;
        }

        if (p_key.channel() == p_connection.getPipeIn().getChannel()) {
            // Read data from incoming stream of PipeIn
            try {
                successful = p_connection.getPipeIn().read();
            } catch (final IOException ignore) {
                successful = false;
            }
            if (!successful) {
                LOGGER.debug("Could not read from channel (0x%X)!", p_connection.getDestinationNodeID());

                m_connectionManager.closeConnection(p_connection, true);
            }
        } else {
            // Read flow control from incoming stream of PipeOut
            try {
                p_connection.getPipeOut().readFlowControlBytes();
            } catch (final IOException e) {
                LOGGER.warn("Failed to read flow control data!");
            }
        }
    }

    /**
     * Either write data to PipeOut or a flow control update to PipeIn.
     *
     * @param p_key
     *         the selected key
     * @param p_connection
     *         the NIOConnection
     */
    private void write(final SelectionKey p_key, final NIOConnection p_connection) {
        boolean complete;

        if (p_connection == null) {
            LOGGER.error("Key is writable, but connection is null!");
            return;
        }

        if (p_key.channel() == p_connection.getPipeOut().getChannel()) {
            // Write data to outgoing stream of PipeOut
            try {
                complete = p_connection.getPipeOut().write();
            } catch (final IOException ignored) {
                LOGGER.debug("Could not write to channel (0x%X)!", p_connection.getDestinationNodeID());

                m_connectionManager.closeConnection(p_connection, true);
                return;
            }

            try {
                if (!complete || !p_connection.getPipeOut().isOutgoingQueueEmpty()) {
                    // If there is still data left to write on this connection, add another write request
                    p_key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
                } else {
                    // Set interest to READ after writing; do not if channel was blocked and data is left
                    p_key.interestOps(SelectionKey.OP_READ);
                }
            } catch (final CancelledKeyException ignore) {
                // Ignore
            }
        } else {
            // Write flow control update to outgoing stream of PipeIn
            try {
                p_connection.getPipeIn().writeFlowControlBytes();
            } catch (final IOException e) {
                LOGGER.warn("Failed to write flow control data!");
            }
            try {
                // Set interest to READ after writing
                p_key.interestOps(SelectionKey.OP_READ);
            } catch (final CancelledKeyException ignore) {
                // Ignores
            }
        }
    }

    /**
     * Execute key by creating a new connection, reading from channel or writing to channel
     *
     * @param p_key
     *         the current key
     */
    private void dispatch(final SelectionKey p_key) {
        NIOConnection connection;

        connection = (NIOConnection) p_key.attachment();
        if (p_key.isValid()) {
            if (p_key.isReadable()) {
                read(p_key, connection);
            } else if (p_key.isWritable()) {
                write(p_key, connection);
            } else if (p_key.isConnectable()) {
                try {
                    connection.connect(p_key);
                } catch (final Exception e) {
                    LOGGER.error("Establishing connection to %s failed", connection);
                }
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder ret = new StringBuilder("Current keys:\n");

        try {
            if (m_selector != null && m_selector.isOpen()) {
                Set<SelectionKey> selected = m_selector.keys();
                Iterator<SelectionKey> iterator = selected.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    if (key.isValid() && key.attachment() != null) {
                        ret.append("\t [")
                                .append(NodeID.toHexString(((NIOConnection) key.attachment()).getDestinationNodeID()))
                                .append(", ").append(Integer.toBinaryString(key.interestOps())).append("]\n");
                        ret.append("\t\t").append(key.attachment()).append('\n');
                    }
                }
            }
        } catch (final ConcurrentModificationException e) {
            LOGGER.debug("Unable to print selector status.");
        }

        return ret.toString();
    }
}
