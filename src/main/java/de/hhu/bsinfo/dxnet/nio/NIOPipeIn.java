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

package de.hhu.bsinfo.dxnet.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.MessageHandlers;
import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.core.AbstractPipeIn;
import de.hhu.bsinfo.dxnet.core.BufferPool;
import de.hhu.bsinfo.dxnet.core.IncomingBufferQueue;
import de.hhu.bsinfo.dxnet.core.LocalMessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.MessageDirectory;
import de.hhu.bsinfo.dxnet.core.RequestMap;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.Time;

/**
 * Enables communication with a remote node over a socket channel. The socket channel's read stream is used to receive
 * data and the write stream is for sending flow control updates (NOT for sending data!). The outgoing channel is
 * independent from the outgoing channel stored in the NIOPipeOut.
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 18.03.2017
 */
class NIOPipeIn extends AbstractPipeIn {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOPipeIn.class.getSimpleName());

    private static final Time SOP_READ = new Time(NIOPipeIn.class, "Read");
    private static final Time SOP_WRITE_FLOW_CONTROL = new Time(NIOPipeIn.class, "WriteFC");
    private static final Time SOP_IBQ_WAIT_PUSH = new Time(IncomingBufferQueue.class, "WaitPush");

    static {
        StatisticsManager.get().registerOperation(NIOPipeIn.class, SOP_READ);
        StatisticsManager.get().registerOperation(NIOPipeIn.class, SOP_WRITE_FLOW_CONTROL);
    }

    private SocketChannel m_incomingChannel;
    private final BufferPool m_bufferPool;
    private final IncomingBufferQueue m_incomingBufferQueue;
    private final ByteBuffer m_flowControlByte;

    private final NIOConnection m_parentConnection;

    private long m_queueFullCounter;

    /**
     * Creates a NIO PipeIn. Incoming channel is neither created nor binded here!
     *
     * @param p_ownNodeId
     *         this node's NodeID.
     * @param p_destinationNodeId
     *         the remote node's NodeID.
     * @param p_messageHeaderPool
     *         the (shared) message header pool.
     * @param p_flowControl
     *         the flow control for this connection.
     * @param p_messageDirectory
     *         the message directory.
     * @param p_requestMap
     *         the request map.
     * @param p_messageHandlers
     *         the message handlers.
     * @param p_bufferPool
     *         the (shared) buffer pool.
     * @param p_incomingBufferQueue
     *         the incoming buffer queue.
     * @param p_parentConnection
     *         the NIO connection this PipeIn belongs to.
     * @param p_benchmarkMode
     *         True to enable benchmark mode and record all RTT values to calculate percentile
     */
    NIOPipeIn(final short p_ownNodeId, final short p_destinationNodeId,
            final LocalMessageHeaderPool p_messageHeaderPool, final AbstractFlowControl p_flowControl,
            final MessageDirectory p_messageDirectory, final RequestMap p_requestMap,
            final MessageHandlers p_messageHandlers, final BufferPool p_bufferPool,
            final IncomingBufferQueue p_incomingBufferQueue, final NIOConnection p_parentConnection,
            final boolean p_benchmarkMode) {
        super(p_ownNodeId, p_destinationNodeId, p_messageHeaderPool, p_flowControl, p_messageDirectory, p_requestMap,
                p_messageHandlers, p_benchmarkMode);

        m_incomingChannel = null;
        m_bufferPool = p_bufferPool;
        m_incomingBufferQueue = p_incomingBufferQueue;
        m_flowControlByte = ByteBuffer.allocateDirect(1);

        m_parentConnection = p_parentConnection;
    }

    /**
     * Binds the incoming channel to this pipe.
     *
     * @param p_channel
     *         the incoming channel.
     */
    void bindIncomingChannel(final SocketChannel p_channel) {
        m_incomingChannel = p_channel;
    }

    @Override
    public boolean isOpen() {
        return m_incomingChannel != null && m_incomingChannel.isOpen();
    }

    @Override
    public void returnProcessedBuffer(final Object p_directBuffer, final long p_unused) {
        m_bufferPool.returnBuffer((BufferPool.DirectBufferWrapper) p_directBuffer);
    }

    /**
     * Returns the incoming channel.
     *
     * @return the incoming channel.
     */
    SocketChannel getChannel() {
        return m_incomingChannel;
    }

    /**
     * Reads from the given connection
     * m_buffer needs to be synchronized externally
     *
     * @return whether reading from channel was successful or not (connection is closed then)
     * @throws IOException
     *         if the data could not be read
     */
    boolean read() throws IOException {
        boolean ret = true;
        long readBytes;
        BufferPool.DirectBufferWrapper directBufferWrapper;
        ByteBuffer buffer;

        directBufferWrapper = m_bufferPool.getBuffer();
        buffer = directBufferWrapper.getBuffer();

        // #ifdef STATISTICS
        SOP_READ.start();
        // #endif /* STATISTICS */

        while (true) {
            readBytes = m_incomingChannel.read(buffer);
            if (readBytes == -1) {
                // Connection closed
                ret = false;
                break;
            } else if (readBytes == 0 && buffer.position() != 0 || readBytes >= m_bufferPool.getOSBufferSize() * 0.9) {
                // There is nothing more to read at the moment
                buffer.flip();

                // #if LOGGER >= TRACE
                LOGGER.trace("Posting receive buffer (limit %d) to connection 0x%X", buffer.limit(),
                        getDestinationNodeID());
                // #endif /* LOGGER >= TRACE */

                if (!m_incomingBufferQueue
                        .pushBuffer(m_parentConnection, directBufferWrapper, 0, directBufferWrapper.getAddress(),
                                buffer.remaining())) {
                    // #ifdef STATISTICS
                    SOP_IBQ_WAIT_PUSH.start();
                    // #endif /* STATISTICS */

                    do {
                        m_queueFullCounter++;

                        // avoid flooding the log
                        if (m_queueFullCounter % 100000 == 0) {
                            // #if LOGGER == WARN
                            LOGGER.warn("IBQ is full count: %d. If this message appears often (with a high counter) " +
                                    "you should consider increasing the number message handlers to avoid " +
                                    "performance penalties", m_queueFullCounter);
                            // #endif /* LOGGER == WARN */
                        }

                        LockSupport.parkNanos(100);

                    } while (!m_incomingBufferQueue
                            .pushBuffer(m_parentConnection, directBufferWrapper, 0, directBufferWrapper.getAddress(),
                                    buffer.remaining()));

                    // #ifdef STATISTICS
                    SOP_IBQ_WAIT_PUSH.stop();
                    // #endif /* STATISTICS */
                }

                break;
            }
        }
        // #ifdef STATISTICS
        SOP_READ.stop();
        // #endif /* STATISTICS */

        return ret;
    }

    /**
     * Write flow control data
     */
    void writeFlowControlBytes() throws IOException {
        int bytes = 0;

        // #ifdef STATISTICS
        SOP_WRITE_FLOW_CONTROL.start();
        // #endif /* STATISTICS */

        m_flowControlByte.rewind();
        byte windows = getFlowControl().getAndResetFlowControlData();
        if (windows == 0) {
            return;
        }
        m_flowControlByte.put(windows);
        m_flowControlByte.rewind();

        while (bytes != 1) {
            // Send flow control bytes over incoming channel as this is unused
            bytes += m_incomingChannel.write(m_flowControlByte);
        }

        // #ifdef STATISTICS
        SOP_WRITE_FLOW_CONTROL.stop();
        // #endif /* STATISTICS */
    }
}
