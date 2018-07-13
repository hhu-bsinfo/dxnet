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

import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;

/**
 * Flow control implementation for IB
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 13.06.2017
 */
class IBFlowControl extends AbstractFlowControl {
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
    IBFlowControl(final short p_destinationNodeId, final int p_flowControlWindowSize,
            final float p_flowControlWindowThreshold, final IBWriteInterestManager p_writeInterestManager) {
        super(p_destinationNodeId, p_flowControlWindowSize, p_flowControlWindowThreshold);
        m_writeInterestManager = p_writeInterestManager;
    }

    /**
     * Get but don't remove flow control data before it is confirmed posted
     *
     * @return The number of flow control windows to confirm
     */
    public int getFlowControlData() {
        if (m_flowControlWindowSize != 0) {
            // not using CAS here requires this to be called by a single thread, only
            int ret = (int) (m_receivedBytes.get() / m_flowControlWindowSizeThreshold);

            if (ret == 0) {
                return ret;
            }

            if (ret < 0) {
                throw new IllegalStateException("Flow control minus posted negative: " + ret);
            }

            // happens if fc is smaller threshold and a very large chunk is posted
            // this might exceed the available range of a uint8_t in the native code
            if (ret > 255) {
                ret = 255;
            }

            return ret;
        } else {
            return 0;
        }
    }

    /**
     * Call, once flow control data is posted. We don't have to consider when it is confirmed sent
     * because there are no buffers or state we have to keep until then
     *
     * @param p_fcData
     *         Fc data posted
     */
    public void flowControlDataSendPosted(final int p_fcData) {
        long bytesLeft;

        if (m_flowControlWindowSize != 0) {
            bytesLeft = m_receivedBytes.addAndGet(-(m_flowControlWindowSizeThreshold * p_fcData));

            if (bytesLeft < 0) {
                throw new IllegalStateException("Negative flow control");
            }
        }
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
