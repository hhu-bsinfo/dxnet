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

package de.hhu.bsinfo.dxnet.nio;

import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.core.NetworkException;

/**
 * Extends the flow control for write signalling.
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 18.03.2017
 */
public class NIOFlowControl extends AbstractFlowControl {

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
        m_nioSelector.changeOperationInterestAsync(InterestQueue.FLOW_CONTROL, m_connection);
    }
}
