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

import de.hhu.bsinfo.dxnet.MessageHandlers;
import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.core.AbstractPipeIn;
import de.hhu.bsinfo.dxnet.core.BufferPool;
import de.hhu.bsinfo.dxnet.core.IncomingBufferQueue;
import de.hhu.bsinfo.dxnet.core.LocalMessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.MessageDirectory;
import de.hhu.bsinfo.dxnet.core.RequestMap;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.TimePool;

/**
 * Created by nothaas on 6/9/17.
 */
public class LoopbackPipeIn extends AbstractPipeIn {
    private static final TimePool SOP_COPY = new TimePool(LoopbackPipeIn.class, "Copy");

    static {
        StatisticsManager.get().registerOperation(LoopbackPipeIn.class, SOP_COPY);
    }

    private final BufferPool m_bufferPool;
    private IncomingBufferQueue m_incomingBufferQueue;
    private LoopbackConnection m_parentConnection;

    private final ByteBuffer m_flowControlByte;

    LoopbackPipeIn(final short p_ownNodeId, final short p_destinationNodeId,
            final LocalMessageHeaderPool p_messageHeaderPool, final AbstractFlowControl p_flowControl,
            final MessageDirectory p_messageDirectory, final RequestMap p_requestMap,
            final MessageHandlers p_messageHandlers, final BufferPool p_bufferPool,
            final IncomingBufferQueue p_incomingBufferQueue, final LoopbackConnection p_parentConnection) {
        super(p_ownNodeId, p_destinationNodeId, p_messageHeaderPool, p_flowControl, p_messageDirectory, p_requestMap,
                p_messageHandlers);

        m_bufferPool = p_bufferPool;
        m_incomingBufferQueue = p_incomingBufferQueue;
        m_parentConnection = p_parentConnection;

        m_flowControlByte = ByteBuffer.allocateDirect(1);

        setConnected(true);
    }

    public int read(final ByteBuffer p_buffer) {
        int ret;

        if (m_incomingBufferQueue.isFull()) {
            // Abort to avoid deadlock when sending requests/responses as responses cannot be sent if send thread locks down below
            return 0;
        }

        BufferPool.DirectBufferWrapper directBufferWrapper;
        ByteBuffer buffer;

        directBufferWrapper = m_bufferPool.getBuffer();
        buffer = directBufferWrapper.getBuffer();

        // #ifdef STATISTICS
        SOP_COPY.start();
        // #endif /* STATISTICS */

        if (buffer.remaining() >= p_buffer.remaining()) {
            ret = p_buffer.remaining();
            buffer.put(p_buffer);
        } else {
            ret = buffer.remaining();

            int limit = p_buffer.limit();
            p_buffer.limit(p_buffer.position() + ret);
            buffer.put(p_buffer);
            p_buffer.limit(limit);
        }
        buffer.flip();

        // #ifdef STATISTICS
        SOP_COPY.stop();
        // #endif /* STATISTICS */

        m_incomingBufferQueue.pushBuffer(m_parentConnection, directBufferWrapper, 0, directBufferWrapper.getAddress(),
                ret);

        return ret;
    }

    ByteBuffer readFlowControlData(final ByteBuffer p_buffer) {
        // Copy flow control bytes for comparison reasons

        m_flowControlByte.rewind();
        m_flowControlByte.put(p_buffer);

        return m_flowControlByte;
    }

    @Override
    public void returnProcessedBuffer(final Object p_directBuffer, final long p_unused) {
        m_bufferPool.returnBuffer((BufferPool.DirectBufferWrapper) p_directBuffer);
    }

    @Override
    public boolean isOpen() {
        return true;
    }
}
