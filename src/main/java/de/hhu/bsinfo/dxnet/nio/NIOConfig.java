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

import lombok.Data;
import lombok.experimental.Accessors;

import com.google.gson.annotations.Expose;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxutils.unit.StorageUnit;
import de.hhu.bsinfo.dxutils.unit.TimeUnit;

/**
 * Dedicated configuration values for .
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 28.07.2017
 */
@Data
@Accessors(prefix = "m_")
public class NIOConfig {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOConfig.class);

    /**
     * Max number of connections to keep before dismissing existing connections (for new ones)
     */
    @Expose
    private int m_maxConnections = 100;

    /**
     * Amount of time to wait until a request that did not receive a response is considered timed out.
     */
    @Expose
    private TimeUnit m_requestTimeOut = new TimeUnit(333, TimeUnit.MS);

    /**
     * Amount of time to try to establish a connection before giving up
     */
    @Expose
    private TimeUnit m_connectionTimeOut = new TimeUnit(333, TimeUnit.MS);

    /**
     * Number of bytes to receive on a flow control message before flow control is considered delayed
     */
    @Expose
    private StorageUnit m_flowControlWindow = new StorageUnit(512, StorageUnit.KB);

    /**
     * Get the threshold determining when a flow control message is sent
     * (receivedBytes > m_flowControlWindow * m_flowControlWindowThreshold)
     */
    @Expose
    private float m_flowControlWindowThreshold = 0.8f;

    /**
     * Size of the ring buffer for outgoing network data (per connection)
     */
    @Expose
    private StorageUnit m_outgoingRingBufferSize = new StorageUnit(2, StorageUnit.MB);

    /**
     * Verify the configuration values
     *
     * @return True if all configuration values are ok, false on invalid value, range or any other error
     */
    public boolean verify() {
        if (m_flowControlWindow.getBytes() > m_outgoingRingBufferSize.getBytes()) {
            LOGGER.error("NIO: OS buffer size must be at least twice the size of flow control window size!");
            return false;
        }

        if (m_flowControlWindow.getBytes() > Integer.MAX_VALUE) {
            LOGGER.error("NIO: Flow control window size exceeding 2 GB, not allowed");
            return false;
        }

        return true;
    }
}
