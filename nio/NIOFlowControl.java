/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.core.NetworkException;

/**
 * Extends the flow control for write signalling.
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 18.03.2017
 */
public class NIOFlowControl extends AbstractFlowControl {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOFlowControl.class.getSimpleName());

    private final NIOSelector m_nioSelector;
    private final NIOConnection m_connection;

    /**
     * Creates an instance of NIOFlowControl
     *
     * @param p_destinationNodeId
     *         the remote node's NodeID.
     * @param p_flowControlWindowSize
     *         the flow control window size.
     * @param p_flowControlWindowThreshold
     *         the flow control threshold.
     * @param p_nioSelector
     *         the NIO selector thread.
     * @param p_connection
     *         the NIO connection.
     */
    NIOFlowControl(final short p_destinationNodeId, final int p_flowControlWindowSize, final float p_flowControlWindowThreshold,
            final NIOSelector p_nioSelector, final NIOConnection p_connection) {
        super(p_destinationNodeId, p_flowControlWindowSize, p_flowControlWindowThreshold);

        m_nioSelector = p_nioSelector;
        m_connection = p_connection;
    }

    @Override
    public void flowControlWrite() throws NetworkException {
        m_nioSelector.changeOperationInterestAsync(InterestQueue.WRITE_FLOW_CONTROL, m_connection);
    }

    @Override
    public byte getAndResetFlowControlData() {
        int bytesLeft;
        byte ret;

        // not using CAS here requires this to be called by a single thread, only
        ret = (byte) (m_receivedBytes.get() / m_flowControlWindowSizeThreshold);
        if (ret == 0) {
            return 0;
        }

        bytesLeft = m_receivedBytes.addAndGet(-(m_flowControlWindowSizeThreshold * ret));

        if (bytesLeft < 0) {
            throw new IllegalStateException("Negative flow control");
        }

        // #if LOGGER >= TRACE
        LOGGER.trace("getAndResetFlowControlData (%X): %d", m_destinationNodeID, bytesLeft);
        // #endif /* LOGGER >= TRACE */

        return ret;
    }
}
