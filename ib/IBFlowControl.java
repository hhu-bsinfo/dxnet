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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;

/**
 * Flow control implementation for IB
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 13.06.2017
 */
class IBFlowControl extends AbstractFlowControl {
    private static final Logger LOGGER = LogManager.getFormatterLogger(IBFlowControl.class.getSimpleName());

    private final IBWriteInterestManager m_writeInterestManager;

    private int m_fcDataPosted;

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
    IBFlowControl(final short p_destinationNodeId, final int p_flowControlWindowSize,
            final float p_flowControlWindowThreshold, final IBWriteInterestManager p_writeInterestManager) {
        super(p_destinationNodeId, p_flowControlWindowSize, p_flowControlWindowThreshold);
        m_writeInterestManager = p_writeInterestManager;
    }

    /**
     * Get but don't remove flow control data before a confirmation is received.
     *
     * @return The number of flow control windows to confirm
     */
    public byte getFlowControlData() {
        byte ret;

        // not using CAS here requires this to be called by a single thread, only
        ret = (byte) (m_receivedBytes.get() / m_flowControlWindowSizeThreshold);
        if (ret == 0) {
            if (m_fcDataPosted > 0) {
                throw new IllegalStateException("No fc data available but said to be posted");
            }

            return 0;
        }

        // #if LOGGER >= TRACE
        LOGGER.trace("getFlowControlData (%X): %d", m_destinationNodeID, ret);
        // #endif /* LOGGER >= TRACE */

        return (byte) (ret - m_fcDataPosted);
    }

    /**
     * Call, once flow control data is posted (but not confirmed to be sent, yet)
     *
     * @param p_fcData
     *         Fc data posted
     */
    public void flowControlDataSendPosted(final byte p_fcData) {
        m_fcDataPosted += p_fcData;
    }

    /**
     * Call, once a confirmation is received that the data was actually sent
     *
     * @param p_fcData
     *         Amount of fc data that was confirmed
     */
    public void flowControlDataSendConfirmed(final byte p_fcData) {
        int bytesLeft;

        bytesLeft = m_receivedBytes.addAndGet(-(m_flowControlWindowSizeThreshold * p_fcData));

        if (bytesLeft < 0) {
            throw new IllegalStateException("Negative flow control");
        }

        m_fcDataPosted -= p_fcData;

        if (m_fcDataPosted < 0) {
            throw new IllegalStateException("FC data posted state negative");
        }

        // #if LOGGER >= TRACE
        LOGGER.trace("flowControlDataSendConfirmed (%X): state fc left %d", m_destinationNodeID, bytesLeft);
        // #endif /* LOGGER >= TRACE */
    }

    @Override
    public void flowControlWrite() {
        m_writeInterestManager.pushBackFcInterest(getDestinationNodeId());
    }

    @Override
    public byte getAndResetFlowControlData() {
        throw new IllegalStateException("IB has to handle FC data in two phases, don't call this function");
    }
}
