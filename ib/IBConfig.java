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

package de.hhu.bsinfo.dxnet.ib;

import com.google.gson.annotations.Expose;

import de.hhu.bsinfo.dxutils.unit.StorageUnit;
import de.hhu.bsinfo.dxutils.unit.TimeUnit;

/**
 * Dedicated configuration values for IB
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 28.07.2017
 */
public class IBConfig {

    @Expose
    private int m_maxConnections = 100;

    @Expose
    private TimeUnit m_connectionCreationTimeout = new TimeUnit(5, TimeUnit.SEC);

    @Expose
    private TimeUnit m_requestTimeOut = new TimeUnit(100, TimeUnit.MS);

    @Expose
    private StorageUnit m_flowControlWindow = new StorageUnit(16, StorageUnit.MB);

    @Expose
    private float m_flowControlWindowThreshold = 0.8f;

    @Expose
    private StorageUnit m_outgoingRingBufferSize = new StorageUnit(4, StorageUnit.MB);

    @Expose
    private int m_ibqMaxCapacityBufferCount = 8 * 1024;

    @Expose
    private StorageUnit m_ibqMaxCapacitySize = new StorageUnit(64, StorageUnit.MB);

    @Expose
    private StorageUnit m_incomingBufferSize = new StorageUnit(32, StorageUnit.KB);

    @Expose
    private StorageUnit m_incomingBufferPoolTotalSize = new StorageUnit(1, StorageUnit.GB);

    @Expose
    private int m_sqSize = 20;

    @Expose
    private int m_srqSize = m_sqSize * m_maxConnections;

    @Expose
    private int m_sharedSCQSize = m_srqSize;

    @Expose
    private int m_sharedRCQSize = m_srqSize;

    @Expose
    private boolean m_enableSignalHandler = false;

    @Expose
    private boolean m_pinSendRecvThreads = true;

    @Expose
    private int m_statisticsThreadPrintIntervalMs = 0;

    /**
     * Default constructor
     */
    public IBConfig() {

    }

    /**
     * Max number of connections to keep before dismissing existing connections (for new ones)
     */
    public int getMaxConnections() {
        return m_maxConnections;
    }

    /**
     * Get the max time to wait for a new connection to be created
     */
    public TimeUnit getConnectionCreationTimeout() {
        return m_connectionCreationTimeout;
    }

    /**
     * Amount of time to wait until a request that did not receive a response is considered timed out.
     */
    public TimeUnit getRequestTimeOut() {
        return m_requestTimeOut;
    }

    /**
     * Number of bytes to receive on a flow control message before flow control is considered delayed
     */
    public StorageUnit getFlowControlWindow() {
        return m_flowControlWindow;
    }

    /**
     * Get the threshold determining when a flow control message is sent (receivedBytes > m_flowControlWindow * m_flowControlWindowThreshold)
     */
    public float getFlowControlWindowThreshold() {
        return m_flowControlWindowThreshold;
    }

    /**
     * Size of the ring buffer for outgoing network data (per connection)
     */
    public StorageUnit getOugoingRingBufferSize() {
        return m_outgoingRingBufferSize;
    }

    /**
     * Max number of buffers allowed in the incoming buffer queue
     */
    public int getIbqMaxCapacityBufferCount() {
        return m_ibqMaxCapacityBufferCount;
    }

    /**
     * Max number of bytes of all buffers aggregated allowed in the incoming buffer queue
     */
    public StorageUnit getIbqMaxCapacitySize() {
        return m_ibqMaxCapacitySize;
    }

    /**
     * Size of a single buffer to store incoming data (or slices of data) to
     */
    public StorageUnit getIncomingBufferSize() {
        return m_incomingBufferSize;
    }

    /**
     * Total size of the pool for buffers, each of size incomingBufferSize, to use for incoming data
     */
    public StorageUnit getIncomingBufferPoolTotalSize() {
        return m_incomingBufferPoolTotalSize;
    }

    /**
     * Get the size of the send queue (size for each connection)
     */
    public int getSendQueueSize() {
        return m_sqSize;
    }

    /**
     * Get the size of the shared receive queue (shared with every connection)
     */
    public int getSharedReceiveQueueSize() {
        return m_srqSize;
    }

    /**
     * Get the size of the shared send completion queue
     */
    public int getSharedSendCompletionQueueSize() {
        return m_sharedSCQSize;
    }

    /**
     * Get the size of the shared receive completion queue
     */
    public int getSharedReceiveCompletionQueueSize() {
        return m_sharedRCQSize;
    }

    /**
     * Enable a signal handler in the IB subsystem to catch signals and print debug info.
     * If enabled, this overwrites the signal handler of the JVM!
     */
    public boolean getEnableSignalHandler() {
        return m_enableSignalHandler;
    }

    /**
     * Pin the daemon send and recv threads to cores 0 and 1. This enhances performance but you might consider disabling
     * it if you don't have a machine with many cores (at least 4)
     */
    public boolean getPinSendRecvThreads() {
        return m_pinSendRecvThreads;
    }

    /**
     * If > 0, a dedicated thread prints statistics of the Ibdxnet subsystem every X ms (for debugging purpose)
     */
    public int getStatisticsThreadPrintIntervalMs() {
        return m_statisticsThreadPrintIntervalMs;
    }
}
