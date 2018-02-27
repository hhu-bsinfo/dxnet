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

import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.core.AbstractPipeOut;
import de.hhu.bsinfo.dxnet.core.OutgoingRingBuffer;

/**
 * Pipe out implementation (current node -> remote write) for IB
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 13.06.2017
 */
class IBPipeOut extends AbstractPipeOut {
    private final IBWriteInterestManager m_writeInterestManager;

    /**
     * Constructor
     *
     * @param p_ownNodeId
     *         Node id of the current node
     * @param p_destinationNodeId
     *         Node id of the remote node connected to
     * @param p_flowControl
     *         Flow control instance
     * @param p_outgoingBuffer
     *         Outgoing (ring) buffer instance
     * @param p_writeInterestManager
     *         Write interest manager instance
     */
    IBPipeOut(final short p_ownNodeId, final short p_destinationNodeId, final AbstractFlowControl p_flowControl, final OutgoingRingBuffer p_outgoingBuffer,
            final IBWriteInterestManager p_writeInterestManager) {
        super(p_ownNodeId, p_destinationNodeId, p_flowControl, p_outgoingBuffer);
        m_writeInterestManager = p_writeInterestManager;
    }

    // TODO adjust doc, this returns pointers which can wrap around the ring buffer
    // which needs to be handled in the native SendThread

    /**
     * Get the next available buffer/chunk of data to write to the connection
     *
     * @return Long holding the current relative position of the front pointer
     * (lower 32-bit) and the relative position of the back pointer
     * (higher 32-bit) of the ring buffer
     */
    long getNextBuffer() {
        return ((IBOutgoingRingBuffer) getOutgoingQueue()).popBack();
    }

    void dataSendPosted(final int p_numBytesPosted) {
        ((IBOutgoingRingBuffer) getOutgoingQueue()).dataSendPosted(p_numBytesPosted);
    }

    // TODO doc wrapper for outgoing ring buffer method
    void dataSendConfirmed(final int p_numBytesPosted) {
        getOutgoingQueue().shiftBack(p_numBytesPosted);
    }

    byte getFlowControlData() {
        return ((IBFlowControl) getFlowControl()).getFlowControlData();
    }

    void flowControlDataSendPosted(final byte p_fcDataPosted) {
        ((IBFlowControl) getFlowControl()).flowControlDataSendPosted(p_fcDataPosted);
    }

    void flowControlDataSendConfirmed(final byte p_fcData) {
        ((IBFlowControl) getFlowControl()).flowControlDataSendConfirmed(p_fcData);
    }

    @Override
    protected boolean isOpen() {
        return true;
    }

    @Override
    protected void bufferPosted(final int p_size) {
        m_writeInterestManager.pushBackDataInterest(getDestinationNodeID());
    }
}
