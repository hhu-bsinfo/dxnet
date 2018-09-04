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

import lombok.Data;
import lombok.experimental.Accessors;

import com.google.gson.annotations.Expose;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxutils.unit.StorageUnit;
import de.hhu.bsinfo.dxutils.unit.TimeUnit;

/**
 * Dedicated configuration values for IB
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 28.07.2017
 */
@Data
@Accessors(prefix = "m_")
public class IBConfig {
    private static final Logger LOGGER = LogManager.getFormatterLogger(IBConfig.class);

    /**
     * Max number of connections to keep before dismissing existing connections (for new ones)
     */
    @Expose
    private int m_maxConnections = 100;

    /**
     * Get the max time to wait for a new connection to be created
     */
    @Expose
    private TimeUnit m_connectionCreationTimeout = new TimeUnit(5, TimeUnit.SEC);

    /**
     * Amount of time to wait until a request that did not receive a response is considered timed out.
     */
    @Expose
    private TimeUnit m_requestTimeOut = new TimeUnit(100, TimeUnit.MS);

    /**
     * Number of bytes to receive on a flow control message before flow control is considered delayed
     */
    @Expose
    private StorageUnit m_flowControlWindow = new StorageUnit(16, StorageUnit.MB);

    /**
     * Get the threshold determining when a flow control message is sent
     * (receivedBytes > m_flowControlWindow * m_flowControlWindowThreshold)
     */
    @Expose
    private float m_flowControlWindowThreshold = 0.1f;

    /**
     * Size of the ring buffer for outgoing network data (per connection)
     */
    @Expose
    private StorageUnit m_outgoingRingBufferSize = new StorageUnit(4, StorageUnit.MB);

    /**
     * Max number of buffers allowed in the incoming buffer queue
     */
    @Expose
    private int m_ibqMaxCapacityBufferCount = 8 * 1024;

    /**
     * Max number of bytes of all buffers aggregated allowed in the incoming buffer queue
     */
    @Expose
    private StorageUnit m_ibqMaxCapacitySize = new StorageUnit(64, StorageUnit.MB);

    /**
     * Size of a single buffer to store incoming data (or slices of data) to
     */
    @Expose
    private StorageUnit m_incomingBufferSize = new StorageUnit(32, StorageUnit.KB);

    /**
     * Total size of the pool for buffers, each of size incomingBufferSize, to use for incoming data
     */
    @Expose
    private StorageUnit m_incomingBufferPoolTotalSize = new StorageUnit(2, StorageUnit.GB);

    /**
     * Get the size of the send queue (size for each connection)
     */
    @Expose
    private int m_sqSize = 20;

    /**
     * Get the size of the shared receive queue (shared with every connection)
     */
    @Expose
    private int m_srqSize = m_sqSize * m_maxConnections;

    /**
     * Get the size of the shared send completion queue
     */
    @Expose
    private int m_sharedSCQSize = m_srqSize;

    /**
     * Get the size of the shared receive completion queue
     */
    @Expose
    private int m_sharedRCQSize = m_srqSize;

    /**
     * Get the max number of SGEs to use for a single WRQ (when receiving data). This also determines the max
     * size to send to a remote (as a single chunk)
     */
    @Expose
    private int m_maxSGEs = 4;

    /**
     * Enable a signal handler in the IB subsystem to catch signals and print debug info.
     * If enabled, this overwrites the signal handler of the JVM!
     */
    @Expose
    private boolean m_enableSignalHandler = false;

    /**
     * Pin the daemon send and recv threads to cores 0 and 1. This enhances performance but you might consider disabling
     * it if you don't have a machine with many cores (at least 4)
     */
    @Expose
    private boolean m_pinSendRecvThreads = false;

    /**
     * If > 0, a dedicated thread prints statistics of the Ibdxnet subsystem every X ms (for debugging purpose)
     */
    @Expose
    private int m_statisticsThreadPrintIntervalMs = 0;

    /**
     * Verify the configuration values
     *
     * @return True if all configuration values are ok, false on invalid value, range or any other error
     */
    public boolean verify() {
        if (m_incomingBufferSize.getBytes() > m_outgoingRingBufferSize.getBytes()) {
            LOGGER.error("IB in buffer size must be <= outgoing ring buffer size");
            return false;
        }

        if (m_srqSize < m_sqSize * m_maxConnections) {
            LOGGER.warn("IB m_srqSize < m_sqSize * m_maxConnections: This may result in performance " +
                    " penalties when too many nodes are active");
        }

        if (m_sharedSCQSize < m_sqSize * m_maxConnections) {
            LOGGER.warn("IB m_sharedSCQSize < m_sqSize * m_maxConnections: This may result in performance " +
                    "penalties when too many nodes are active");
        }

        if (m_srqSize < m_sharedRCQSize) {
            LOGGER.warn("IB m_srqSize < m_sharedRCQSize: This may result in performance penalties when too " +
                    "many nodes are active");
        }

        if (m_flowControlWindow.getBytes() > Integer.MAX_VALUE) {
            LOGGER.error("IB: Flow control window size exceeding 2 GB, not allowed");
            return false;
        }

        if (m_incomingBufferSize.getGBDouble() > 2.0) {
            LOGGER.error("IB: Exceeding max incoming buffer size of 2GB");
            return false;
        }

        if (m_outgoingRingBufferSize.getGBDouble() > 2.0) {
            LOGGER.error("IB: Exceeding max outgoing buffer size of 2GB");
            return false;
        }

        return true;
    }
}
