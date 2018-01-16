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

package de.hhu.bsinfo.dxnet.ib;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.core.NetworkException;

/**
 * Flow control implementation for IB
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 13.06.2017
 */
class IBFlowControl extends AbstractFlowControl {
    private static final Logger LOGGER = LogManager.getFormatterLogger(IBFlowControl.class.getSimpleName());

    private final IBWriteInterestManager m_writeInterestManager;

    /**
     * Constructor
     *
     * @param p_destinationNodeId
     *         Node id of the destination connected to for this flow control
     * @param p_flowControlWindowSize
     *         Window size of the flow control (i.e. when to send a flow control msg)
     * @param p_flowControlWindowThreshold
     *         Threshold to exceed before sending a flow control msg
     * @param p_writeInterestManager
     *         Write interest manager instance
     */
    IBFlowControl(final short p_destinationNodeId, final int p_flowControlWindowSize, final float p_flowControlWindowThreshold,
            final IBWriteInterestManager p_writeInterestManager) {
        super(p_destinationNodeId, p_flowControlWindowSize, p_flowControlWindowThreshold);
        m_writeInterestManager = p_writeInterestManager;
    }

    @Override
    public void flowControlWrite() throws NetworkException {
        m_writeInterestManager.pushBackFcInterest(getDestinationNodeId());
    }

    @Override
    public byte getAndResetFlowControlData() {
        int bytesLeft;

        // not using CAS here requires this to be called by a single thread, only
        int curFcData = m_receivedBytes.get();

        if (curFcData < m_flowControlWindowSizeThreshold) {
            return 0;
        }

        bytesLeft = m_receivedBytes.addAndGet(-m_flowControlWindowSizeThreshold);

        if (bytesLeft < 0) {
            throw new IllegalStateException("Negative flow control");
        }

        if (bytesLeft >= m_flowControlWindowSizeThreshold) {
            try {
                flowControlWrite();
            } catch (final NetworkException e) {
                // #if LOGGER >= ERROR
                LOGGER.error("Could not send flow control message", e);
                // #endif /* LOGGER >= ERROR */
            }
        }

        // #if LOGGER >= TRACE
        LOGGER.trace("getAndResetFlowControlData (%X): %d", m_destinationNodeID, bytesLeft);
        // #endif /* LOGGER >= TRACE */

        //return m_flowControlWindowSizeThreshold;
        return (byte) 1;
    }
}
