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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.NodeMap;
import de.hhu.bsinfo.dxnet.core.AbstractFlowControl;
import de.hhu.bsinfo.dxnet.core.AbstractPipeOut;
import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxnet.core.OutgoingRingBuffer;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.Time;

/**
 * Enables communication with a remote node over a socket channel. The socket channel's write stream is used to send
 * data and the read stream is for receiving flow control updates (NOT for reading data!). The outgoing channel is
 * independent from the incoming channel stored in the NIOPipeIn.
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 18.03.2017
 */
public class NIOPipeOut extends AbstractPipeOut {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOPipeOut.class.getSimpleName());

    private static final Time SOP_WRITE = new Time(NIOPipeOut.class, "Write");
    private static final Time SOP_READ_FLOW_CONTROL = new Time(NIOPipeOut.class, "ReadFC");

    static {
        StatisticsManager.get().registerOperation(NIOPipeOut.class, SOP_WRITE);
        StatisticsManager.get().registerOperation(NIOPipeOut.class, SOP_READ_FLOW_CONTROL);
    }

    private final int m_bufferSize;

    private SocketChannel m_outgoingChannel;
    private NIOConnection m_connection;

    private final NIOSelector m_nioSelector;
    private final NodeMap m_nodeMap;
    private final ByteBuffer m_flowControlByte;

    /**
     * Creates a NIO PipeOut. Outgoing channel is not created/opened here!
     *
     * @param p_ownNodeId
     *         this node's NodeID.
     * @param p_destinationNodeId
     *         the NodeID of the remote node.
     * @param p_bufferSize
     *         the outgoing ring buffer size.
     * @param p_flowControl
     *         the flow control.
     * @param p_outgoingBuffer
     *         the outgoing ring buffer. Must be created prior to the PipeOut.
     * @param p_nioSelector
     *         the NIO selector thread.
     * @param p_nodeMap
     *         the node map.
     * @param p_parentConnection
     *         the NIO connection this PipeOut belongs to.
     */
    NIOPipeOut(final short p_ownNodeId, final short p_destinationNodeId, final int p_bufferSize,
            final AbstractFlowControl p_flowControl,
            final OutgoingRingBuffer p_outgoingBuffer, final NIOSelector p_nioSelector, final NodeMap p_nodeMap,
            final NIOConnection p_parentConnection) {
        super(p_ownNodeId, p_destinationNodeId, p_flowControl, p_outgoingBuffer);

        m_bufferSize = p_bufferSize;

        m_outgoingChannel = null;
        m_connection = p_parentConnection;

        m_nioSelector = p_nioSelector;
        m_nodeMap = p_nodeMap;
        m_flowControlByte = ByteBuffer.allocateDirect(1);
    }

    /**
     * Creates and connects the outgoing channel.
     *
     * @param p_nodeID
     *         the remote NodeID.
     * @throws NetworkException
     *         if creating the outgoing channel failed
     */
    void createOutgoingChannel(final short p_nodeID) throws NetworkException {
        try {
            m_outgoingChannel = SocketChannel.open();
            m_outgoingChannel.configureBlocking(false);
            m_outgoingChannel.socket().setSoTimeout(0);
            m_outgoingChannel.socket().setTcpNoDelay(true);
            m_outgoingChannel.socket().setReceiveBufferSize(32);
            m_outgoingChannel.socket().setSendBufferSize(m_bufferSize);
            int sendBufferSize = m_outgoingChannel.socket().getSendBufferSize();
            if (sendBufferSize < m_bufferSize) {
                LOGGER.warn("Send buffer size could not be set properly. Check OS settings! Requested: %d, actual: %d",
                        m_bufferSize, sendBufferSize);
            }

            m_outgoingChannel.connect(m_nodeMap.getAddress(p_nodeID));
        } catch (final IOException ignored) {
            throw new NetworkException("Creating outgoing channel failed");
        }
    }

    @Override
    protected boolean isOpen() {
        return m_outgoingChannel != null && m_outgoingChannel.isOpen();
    }

    @Override
    protected void bufferPosted(final int p_size) {
        // Change operation (read <-> write) and/or connection
        m_nioSelector.changeOperationInterestAsync(InterestQueue.WRITE, m_connection);
    }

    /**
     * Returns the outgoing channel.
     *
     * @return the outgoing channel.
     */
    SocketChannel getChannel() {
        return m_outgoingChannel;
    }

    /**
     * Sends the own NodeID after connection establishment.
     *
     * @param p_buffer
     *         the two byte long ByteBuffer containing the node's NodeID
     */
    void sendNodeID(final ByteBuffer p_buffer) {
        ((NIOOutgoingRingBuffer) getOutgoingQueue()).pushNodeID(p_buffer);
        bufferPosted(p_buffer.remaining());
    }

    /**
     * Writes to the given connection
     *
     * @return whether all data could be written
     * @throws IOException
     *         if the data could not be written
     */
    boolean write() throws IOException {
        boolean ret = true;
        int writtenBytes = 0;
        int bytes;
        ByteBuffer buffer;

        // #ifdef STATISTICS
        SOP_WRITE.start();
        // #endif /* STATISTICS */

        buffer = ((NIOOutgoingRingBuffer) getOutgoingQueue()).pop();
        if (buffer != null) {
            while (buffer.remaining() > 0) {

                bytes = m_outgoingChannel.write(buffer);
                if (bytes == 0) {
                    // Read-buffer on the other side is full. Abort writing and schedule buffer for next write
                    ret = false;
                    break;
                }
                writtenBytes += bytes;
            }
            getOutgoingQueue().shiftBack(writtenBytes);
        }

        // #ifdef STATISTICS
        SOP_WRITE.stop();
        // #endif /* STATISTICS */

        return ret;
    }

    /**
     * Read flow control data
     */
    void readFlowControlBytes() throws IOException {
        int readBytes;

        // #ifdef STATISTICS
        SOP_READ_FLOW_CONTROL.start();
        // #endif /* STATISTICS */

        // This is a flow control byte
        m_flowControlByte.rewind();
        while (true) {
            readBytes = m_outgoingChannel.read(m_flowControlByte);

            if (readBytes == 1) {
                break;
            }

            if (readBytes == -1) {
                // Channel was closed

                // #ifdef STATISTICS
                SOP_READ_FLOW_CONTROL.stop();
                // #endif /* STATISTICS */

                return;
            }
        }

        getFlowControl().handleFlowControlData(m_flowControlByte.get(0));

        // #ifdef STATISTICS
        SOP_READ_FLOW_CONTROL.stop();
        // #endif /* STATISTICS */
    }
}
