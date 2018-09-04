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

package de.hhu.bsinfo.dxnet.loopback;

import lombok.Data;
import lombok.experimental.Accessors;

import com.google.gson.annotations.Expose;

import de.hhu.bsinfo.dxutils.unit.StorageUnit;
import de.hhu.bsinfo.dxutils.unit.TimeUnit;

/**
 * Dedicated configuration values for loopback
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 21.09.2017
 */
@Data
@Accessors(prefix = "m_")
public class LoopbackConfig {
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
    private StorageUnit m_flowControlWindow = new StorageUnit(80, StorageUnit.MB);

    /**
     * Get the threshold determining when a flow control message is sent
     * (receivedBytes > m_flowControlWindow * m_flowControlWindowThreshold)
     */
    @Expose
    private float m_flowControlWindowThreshold = 0.5f;

    /**
     * Size of the ring buffer for outgoing network data (per connection)
     */
    @Expose
    private StorageUnit m_outgoingRingBufferSize = new StorageUnit(4, StorageUnit.MB);

    /**
     * Verify the configuration values
     *
     * @return True if all configuration values are ok, false on invalid value, range or any other error
     */
    public boolean verify() {
        // TODO
        return true;
    }
}
