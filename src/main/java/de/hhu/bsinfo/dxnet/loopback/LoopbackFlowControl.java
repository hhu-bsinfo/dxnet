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

import java.nio.ByteBuffer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.nio.NIOFlowControl;

/**
 * Created by nothaas on 6/12/17.
 */
public class LoopbackFlowControl extends AbstractFlowControl {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOFlowControl.class.getSimpleName());

    private final LoopbackSendThread m_loopbackSendThread;
    private LoopbackConnection m_connection;

    private final ByteBuffer m_flowControlByte;

    LoopbackFlowControl(final short p_destinationNodeId, final int p_flowControlWindowSize,
            final float p_flowControlWindowThreshold, final LoopbackSendThread p_loopbackSendThread,
            final LoopbackConnection p_connection) {
        super(p_destinationNodeId, p_flowControlWindowSize, p_flowControlWindowThreshold);

        m_loopbackSendThread = p_loopbackSendThread;
        m_connection = p_connection;

        m_flowControlByte = ByteBuffer.allocateDirect(1);
    }

    @Override
    public void flowControlWrite() {
        m_flowControlByte.rewind();
        m_flowControlByte.put(getAndResetFlowControlData());
        m_flowControlByte.rewind();

        ByteBuffer buffer = m_connection.getPipeIn().readFlowControlData(m_flowControlByte);
        handleFlowControlData(buffer.get(0));
    }

    @Override
    public byte getAndResetFlowControlData() {
        long bytesLeft;
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

        LOGGER.trace("getAndResetFlowControlData (%X): %d", m_destinationNodeID, bytesLeft);

        return ret;
    }
}
