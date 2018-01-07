/*
 * Copyright (C) 2017 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxnet.nio;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Interest queue based on an array and an ArrayList.
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 18.12.2017
 */
class InterestQueue {

    private static final Logger LOGGER = LogManager.getFormatterLogger(InterestQueue.class.getSimpleName());

    // Operations (0b1, 0b10, 0b100, 0b1000 reserved in SelectionKey)
    static final int READ = 1;
    static final int WRITE = 1 << 2;
    static final int CONNECT = 1 << 3;
    static final int READ_WRITE = READ | WRITE;
    static final int READ_FLOW_CONTROL = 1 << 5;
    static final int FLOW_CONTROL = 1 << 6;
    static final int CLOSE = 1 << 1;

    private int[] m_changeRequests;
    private ArrayList<NIOConnection> m_activeConnections;

    private ReentrantLock m_changeLock;

    // Constructors

    /**
     * Creates an instance of InterestQueue
     */
    InterestQueue() {
        m_changeRequests = new int[(int) Math.pow(2, 16)];
        m_activeConnections = new ArrayList<>();
        m_changeLock = new ReentrantLock(false);
    }

    /**
     * Adds an operation interest for given connection.
     *
     * @param p_interest
     *         the operation interest.
     * @param p_connection
     *         the connection.
     * @return true, if the interest was not yet registered.
     */
    boolean addInterest(final int p_interest, final NIOConnection p_connection) {
        boolean ret = false;
        short nodeID = p_connection.getDestinationNodeID();
        int oldInterest;

        m_changeLock.lock();
        oldInterest = m_changeRequests[nodeID & 0xFFFF];
        if (oldInterest == 0) {
            // Connection was not registered since last processing -> add to active connections
            m_activeConnections.add(p_connection);
        }

        if (oldInterest % p_interest != p_interest) {
            // Given operation interest was not yet registered for this connection
            ret = true;
        }

        m_changeRequests[nodeID & 0xFFFF] = oldInterest | p_interest;
        m_changeLock.unlock();

        return ret;
    }

    /**
     * Processes all registered operation interests.
     *
     * @param p_selector
     *         the Selector.
     * @param p_connectionManager
     *         the connection Manager.
     * @param p_connectionTimeout
     *         the configured connection timeout.
     */
    void processInterests(final Selector p_selector, final NIOConnectionManager p_connectionManager, final int p_connectionTimeout) {
        int interest;
        int entries;
        SelectionKey key;

        m_changeLock.lock();
        entries = m_activeConnections.size();
        if (entries > 0) {
            for (NIOConnection connection : m_activeConnections) {
                // Get interest of this connection
                interest = m_changeRequests[connection.getDestinationNodeID() & 0xFFFF];
                // Reset interest for this connection
                m_changeRequests[connection.getDestinationNodeID() & 0xFFFF] = 0;

                /*
                 * By aggregating different interests (CONNECT, READ_FLOW_CONTROL, READ, FLOW_CONTROL, WRITE and CLOSE) for one connection, we loose the
                 * ordering within those interests. But, this is not a problem at all as the ordering is implicitly (we favor flow control over data transfer):
                 *     1) CONNECT              - a connection must be connected before using it
                 *     2) READ_FLOW_CONTROL    - after creating the PipeOut, the incoming stream must be registered for reading flow control updates
                 *     3) READ                 - after creating the PipeIn, the incoming stream must be registered for reading data
                 *     4) FLOW_CONTROL         - there is a new flow control update to be written to the outgoing stream of the PipeIn
                 *     5) WRITE                - there is new data to be written to the outgoing stream of the PipeOut
                 *     6) CLOSE                - connection closure is always the last operation as all following will fail (without re-opening the connection)
                 *
                 */
                if ((interest & CONNECT) == CONNECT) {
                    // CONNECT -> register with connection as attachment (ACCEPT is registered directly)
                    try {
                        connection.getPipeOut().getChannel().register(p_selector, CONNECT, connection);
                    } catch (final ClosedChannelException e) {
                        // #if LOGGER >= DEBUG
                        LOGGER.debug("Could not change operations!");
                        // #endif /* LOGGER >= DEBUG */
                    }
                }
                if ((interest & READ_FLOW_CONTROL) == READ_FLOW_CONTROL) {
                    try {
                        // This is a READ access for flow control - CALLED ONCE AFTER CONNECTION CREATION
                        try {
                            // Use outgoing channel for receiving flow control messages
                            connection.getPipeOut().getChannel().register(p_selector, READ, connection);
                        } catch (ClosedChannelException e) {
                            e.printStackTrace();
                        }
                    } catch (final CancelledKeyException e) {
                        // Ignore
                    }
                }
                if ((interest & READ) == READ) {
                    try {
                        // This is a READ access - CALLED ONCE AFTER CONNECTION CREATION
                        try {
                            // Use incoming channel for receiving messages
                            connection.getPipeIn().getChannel().register(p_selector, READ, connection);
                        } catch (ClosedChannelException e) {
                            e.printStackTrace();
                        }
                    } catch (final CancelledKeyException e) {
                        // Ignore
                    }
                }

                if ((interest & FLOW_CONTROL) == FLOW_CONTROL) {
                    try {
                        // This is a FLOW_CONTROL access - Write flow control bytes over incoming channel
                        key = connection.getPipeIn().getChannel().keyFor(p_selector);
                        if (key != null && key.interestOps() != READ_WRITE) {
                            // Key might be null if connection was closed during shutdown or due to closing a duplicate connection
                            // If key interest is READ | WRITE the interest must not be overwritten with WRITE as both incoming
                            // buffers might be filled causing a deadlock
                            key.interestOps(WRITE);
                        }
                    } catch (final CancelledKeyException e) {
                        // Ignore
                    }

                }
                if ((interest & WRITE) == WRITE) {
                    try {
                        // This is a WRITE access -> change interest only
                        key = connection.getPipeOut().getChannel().keyFor(p_selector);
                        if (key == null) {
                            // Key might be null if connection was closed during shutdown or due to closing a duplicate connection
                            // #if LOGGER >= ERROR
                            LOGGER.error("Cannot register WRITE operation as key is null for %s", connection);
                            // #endif /* LOGGER >= ERROR */
                        } else if (key.interestOps() != READ_WRITE) {
                            // If key interest is READ | WRITE the interest must not be overwritten with WRITE as both incoming
                            // buffers might be filled causing a deadlock
                            key.interestOps(WRITE);
                        }
                    } catch (final CancelledKeyException e) {
                        // Ignore
                    }
                }
                if ((interest & CLOSE) == CLOSE) {
                    // This has to be at the end to avoid missing last messages of a node
                    // CLOSE -> close connection
                    // Close connection after at least two connection timeouts since request
                    if (System.currentTimeMillis() - connection.getClosingTimestamp() > 2 * p_connectionTimeout) {
                        // #if LOGGER >= DEBUG
                        try {
                            LOGGER.debug("Closing connection to 0x%X;%s", connection.getDestinationNodeID(),
                                    connection.getPipeOut().getChannel().getRemoteAddress());
                        } catch (final IOException ignored) {
                        }
                        // #endif /* LOGGER >= DEBUG */
                        // Close connection
                        p_connectionManager.closeConnection(connection, false);
                    } else {
                        // Delay connection closure
                        m_changeRequests[connection.getDestinationNodeID() & 0xFFFF] = CLOSE;
                        m_activeConnections.add(connection);
                    }
                }
            }
            // Remove active connections as we processed all interests
            if (entries == m_activeConnections.size()) {
                m_activeConnections.clear();
            } else {
                // "New" close interests registered -> delete everything in front
                m_activeConnections.subList(0, entries).clear();
            }
        }
        m_changeLock.unlock();
    }
}
