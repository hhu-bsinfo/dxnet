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

import de.hhu.bsinfo.dxnet.MessageHandlers;
import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.core.AbstractPipeIn;
import de.hhu.bsinfo.dxnet.core.LocalMessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.MessageDirectory;
import de.hhu.bsinfo.dxnet.core.RequestMap;

/**
 * Pipe in implementation (remote -> current node write) for IB
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 13.06.2017
 */
class IBPipeIn extends AbstractPipeIn {

    /**
     * Constructor
     *
     * @param p_ownNodeId
     *         Node id of the current node
     * @param p_destinationNodeId
     *         Node id of the destination this pipe is connected to
     * @param p_messageHeaderPool
     *         Pool for message header
     * @param p_flowControl
     *         Flow control instance
     * @param p_messageDirectory
     *         Message directory instance
     * @param p_requestMap
     *         Request map instance
     * @param p_messageHandlers
     *         Message handlers instance
     * @param p_benchmarkMode
     *         True to enable benchmark mode and record all RTT values to calculate percentile
     */
    IBPipeIn(final short p_ownNodeId, final short p_destinationNodeId, final LocalMessageHeaderPool p_messageHeaderPool,
            final AbstractFlowControl p_flowControl, final MessageDirectory p_messageDirectory,
            final RequestMap p_requestMap, final MessageHandlers p_messageHandlers, final boolean p_benchmarkMode) {
        super(p_ownNodeId, p_destinationNodeId, p_messageHeaderPool, p_flowControl, p_messageDirectory, p_requestMap,
                p_messageHandlers, p_benchmarkMode);
    }

    /**
     * Called when "confirmed bytes" are received from the remote (wrapper method)
     *
     * @param p_confirmedWindows
     *         Number of windows confirmed by the remote
     */
    void handleFlowControlData(final int p_confirmedWindows) {
        getFlowControl().handleFlowControlData(p_confirmedWindows);
    }

    @Override
    public void returnProcessedBuffer(final Object p_obj, final long p_bufferHandle) {
        // p_obj unused
        MsgrcJNIBinding.returnRecvBuffer(p_bufferHandle);
    }

    @Override
    public boolean isOpen() {
        return true;
    }
}
