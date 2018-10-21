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

package de.hhu.bsinfo.dxnet.core;

import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.ConnectionManagerListener;
import de.hhu.bsinfo.dxutils.NodeID;
import de.hhu.bsinfo.dxutils.UnsafeHandler;

/**
 * Abstract class for a connection manager. Manage connections to keep only a max amount of connections
 * opened to reduce the resource footprint. Furthermore, depending on the transport type implemented,
 * handle connection creation, opening, closing and deletion.
 *
 * @author Florian Klein, florian.klein@hhu.de, 18.03.2012
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 11.08.2017
 */
public abstract class AbstractConnectionManager {
    private static final Logger LOGGER = LogManager.getFormatterLogger(AbstractConnectionManager.class.getSimpleName());

    protected final AbstractConnection[] m_connections;
    private final ReentrantLock m_connectionCreationLock;
    private final ReentrantLock[] m_connectionLocks;

    protected ConnectionManagerListener m_listener;

    protected final int m_maxConnections;
    protected int m_openConnections;

    private volatile boolean m_overprovisioning;

    /**
     * Constructor
     *
     * @param p_maxConnections
     *         Max number of connections to keep active simultaneously
     * @param p_overprovisioning
     *         True if overprovisioning is enabled
     */
    protected AbstractConnectionManager(final int p_maxConnections, final boolean p_overprovisioning) {
        m_maxConnections = p_maxConnections;
        m_connections = new AbstractConnection[65536];
        m_connectionCreationLock = new ReentrantLock(false);
        m_connectionLocks = new ReentrantLock[65536];

        m_openConnections = 0;
        m_overprovisioning = p_overprovisioning;
    }

    /**
     * Set connection manager listener to receive callbacks when a node connected or disconnected
     *
     * @param p_listener
     *         Listener to set
     */
    public void setListener(final ConnectionManagerListener p_listener) {
        m_listener = p_listener;
    }

    /**
     * Activate parking strategy for send/receive thread.
     */
    public void setOverprovisioning() {
        m_overprovisioning = true;
    }

    /**
     * Returns the status of all connections
     *
     * @return Status of all connections (debug string)
     */
    public String getConnectionStatuses() {
        StringBuilder ret = new StringBuilder();

        m_connectionCreationLock.lock();

        for (int i = 0; i < 65536; i++) {
            if (m_connections[i] != null) {
                ret.append(m_connections[i]);
            }
        }

        m_connectionCreationLock.unlock();

        return ret.toString();
    }

    /**
     * Get the connection for the given destination
     *
     * @param p_destination
     *         Node id of the destination
     * @return A valid reference if the connection is available, null otherwise
     * @throws NetworkException
     *         If the connection does not exist and creating the connection failed
     */
    public AbstractConnection getConnection(final short p_destination) throws NetworkException {
        AbstractConnection ret;
        ReentrantLock connectionLock;

        assert p_destination != NodeID.INVALID_ID;

        ret = m_connections[p_destination & 0xFFFF];

        if (ret == null || !ret.getPipeOut().isConnected()) {
            connectionLock = getConnectionLock(p_destination);
            connectionLock.lock();

            LOGGER.debug("Active create connection to: 0x%X", p_destination);

            ret = m_connections[p_destination & 0xFFFF];
            if (ret == null || !ret.getPipeOut().isConnected()) {
                try {
                    ret = createConnection(p_destination, ret);
                } catch (final NetworkException e) {
                    connectionLock.unlock();

                    throw e;
                }

                if (ret != null) {
                    LOGGER.debug("Connection created: 0x%X", p_destination);

                    m_connections[p_destination & 0xFFFF] = ret;
                } else {
                    LOGGER.warn("Connection creation was aborted!");
                }
            }

            connectionLock.unlock();
        }

        return ret;
    }

    /**
     * Closes all active connections and cleans up the connection manager
     */
    public void close() {
        closeAllConnections();

        for (int i = 0; i < m_connections.length; i++) {
            m_connections[i] = null;
        }
    }

    /**
     * Get the connection creation lock for a given NodeID.
     *
     * @param p_destination
     *         the NodeID of the remote node
     * @return the ReentrantLock
     */
    protected ReentrantLock getConnectionLock(final short p_destination) {
        ReentrantLock connectionLock;

        m_connectionCreationLock.lock();
        connectionLock = m_connectionLocks[p_destination & 0xFFFF];

        if (connectionLock == null) {
            connectionLock = new ReentrantLock(false);
            m_connectionLocks[p_destination & 0xFFFF] = connectionLock;
        }

        m_connectionCreationLock.unlock();

        return connectionLock;
    }

    /**
     * Creates a new connection to the given destination
     *
     * @param p_destination
     *         Node id of the destination
     * @param p_existingConnection
     *         Parameter required for NIO implementation
     * @return New connection to the specified destination
     * @throws NetworkException
     *         If the connection could not be created
     */
    protected abstract AbstractConnection createConnection(final short p_destination,
            final AbstractConnection p_existingConnection) throws NetworkException;

    /**
     * Close an active connection
     *
     * @param p_connection
     *         Connection to close
     * @param p_removeConnection
     *         True to remove the connection resources currently allocated as well or false to keep them to allow
     *         re-opening the connection quickly
     */
    protected abstract void closeConnection(final AbstractConnection p_connection, final boolean p_removeConnection);

    /**
     * Close all connections
     */
    private void closeAllConnections() {
        AbstractConnection connection;

        m_connectionCreationLock.lock();

        for (int i = 0; i < 65536; i++) {
            if (m_connections[i] != null) {
                connection = m_connections[i & 0xFFFF];
                m_connections[connection.getDestinationNodeID() & 0xFFFF] = null;

                closeConnection(connection, false);
            }
        }

        m_openConnections = 0;
        m_connectionCreationLock.unlock();
    }

    /**
     * Dismiss a connection randomly
     */
    protected void dismissRandomConnection() {
        int random = -1;
        AbstractConnection dismiss = null;
        Random rand;

        rand = new Random();

        while (dismiss == null) {
            random = rand.nextInt(m_connections.length);
            dismiss = m_connections[random & 0xFFFF];
        }

        LOGGER.warn("Removing randomly selected connection 0x%X", (short) random);

        m_connections[random & 0xFFFF] = null;
        UnsafeHandler.getInstance().getUnsafe().storeFence();
        m_openConnections--;

        dismiss.close(false);
    }
}
