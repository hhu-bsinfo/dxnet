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
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.MessageHandlers;
import de.hhu.bsinfo.dxnet.NodeMap;
import de.hhu.bsinfo.dxnet.core.AbstractConnection;
import de.hhu.bsinfo.dxnet.core.AbstractExporterPool;
import de.hhu.bsinfo.dxnet.core.BufferPool;
import de.hhu.bsinfo.dxnet.core.IncomingBufferQueue;
import de.hhu.bsinfo.dxnet.core.LocalMessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.MessageDirectory;
import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxnet.core.RequestMap;

/**
 * Represents a NIO network connection.
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 18.03.2017
 */
public class NIOConnection extends AbstractConnection<NIOPipeIn, NIOPipeOut> {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOConnection.class.getSimpleName());

    private NIOSelector m_nioSelector;

    private ReentrantLock m_connectionCondLock;
    private Condition m_connectionCond;

    private volatile boolean m_connectionCreationAborted;

    /**
     * Creates a new NIO connection object for given destination. The connection has not been established when
     * returning!
     *
     * @param p_ownNodeId
     *         the NodeID of this node.
     * @param p_destination
     *         the NodeID of the node a connection is created for.
     * @param p_bufferSize
     *         the outgoing ring buffer size.
     * @param p_flowControlWindowSize
     *         the flow control window size.
     * @param p_flowControlWindowThreshold
     *         the flow control window threshold.
     * @param p_incomingBufferQueue
     *         the (shared) incoming buffer queue.
     * @param p_messageHeaderPool
     *         the (shared) message header pool.
     * @param p_messageDirectory
     *         the message directory.
     * @param p_requestMap
     *         the request map.
     * @param p_messageHandlers
     *         the message handlers.
     * @param p_bufferPool
     *         the (shared) buffer pool.
     * @param p_exporterPool
     *         the exporter pool.
     * @param p_nioSelector
     *         the NIO selector.
     * @param p_nodeMap
     *         the node map.
     * @param p_lock
     *         the lock to be used for connection creation.
     * @param p_cond
     *         the condition to signal connection completion.
     * @param p_benchmarkMode
     *         True to enable benchmark mode and record all RTT values to calculate percentile
     */
    NIOConnection(final short p_ownNodeId, final short p_destination, final int p_bufferSize,
            final int p_flowControlWindowSize, final float p_flowControlWindowThreshold,
            final IncomingBufferQueue p_incomingBufferQueue, final LocalMessageHeaderPool p_messageHeaderPool,
            final MessageDirectory p_messageDirectory, final RequestMap p_requestMap,
            final MessageHandlers p_messageHandlers, final BufferPool p_bufferPool,
            final AbstractExporterPool p_exporterPool, final NIOSelector p_nioSelector, final NodeMap p_nodeMap,
            final ReentrantLock p_lock, final Condition p_cond, final boolean p_benchmarkMode) {
        super(p_ownNodeId);

        NIOFlowControl flowControl = new NIOFlowControl(p_destination, p_flowControlWindowSize,
                p_flowControlWindowThreshold, p_nioSelector, this);
        NIOOutgoingRingBuffer outgoingBuffer = new NIOOutgoingRingBuffer(p_ownNodeId, p_bufferSize, p_exporterPool);
        NIOPipeIn pipeIn = new NIOPipeIn(p_ownNodeId, p_destination, p_messageHeaderPool, flowControl,
                p_messageDirectory, p_requestMap, p_messageHandlers, p_bufferPool, p_incomingBufferQueue, this,
                p_benchmarkMode);
        NIOPipeOut pipeOut = new NIOPipeOut(p_ownNodeId, p_destination, p_bufferSize, flowControl, outgoingBuffer,
                p_nioSelector, p_nodeMap, this);

        setPipes(pipeIn, pipeOut);

        m_nioSelector = p_nioSelector;

        m_connectionCondLock = p_lock;
        m_connectionCond = p_cond;
    }

    /**
     * Creates a new NIO connection object for given destination. The connection was created from remote side and has
     * been established when entering!
     *
     * @param p_ownNodeId
     *         the NodeID of this node.
     * @param p_destination
     *         the NodeID of the node a connection is created for.
     * @param p_bufferSize
     *         the outgoing ring buffer size.
     * @param p_flowControlWindowSize
     *         the flow control window size.
     * @param p_flowControlWindowThreshold
     *         the flow control window threshold.
     * @param p_incomingBufferQueue
     *         the (shared) incoming buffer queue.
     * @param p_messageHeaderPool
     *         the (shared) message header pool.
     * @param p_messageDirectory
     *         the message directory.
     * @param p_requestMap
     *         the request map.
     * @param p_messageHandlers
     *         the message handlers.
     * @param p_bufferPool
     *         the (shared) buffer pool.
     * @param p_exporterPool
     *         the exporter pool.
     * @param p_nioSelector
     *         the NIO selector.
     * @param p_nodeMap
     *         the node map.
     * @param p_benchmarkMode
     *         True to enable benchmark mode and record all RTT values to calculate percentile
     */
    NIOConnection(final short p_ownNodeId, final short p_destination, final int p_bufferSize,
            final int p_flowControlWindowSize, final float p_flowControlWindowThreshold,
            final IncomingBufferQueue p_incomingBufferQueue, final LocalMessageHeaderPool p_messageHeaderPool,
            final MessageDirectory p_messageDirectory, final RequestMap p_requestMap,
            final MessageHandlers p_messageHandlers, final BufferPool p_bufferPool,
            final AbstractExporterPool p_exporterPool, final NIOSelector p_nioSelector, final NodeMap p_nodeMap,
            final boolean p_benchmarkMode) {
        super(p_ownNodeId);

        NIOFlowControl flowControl = new NIOFlowControl(p_destination, p_flowControlWindowSize,
                p_flowControlWindowThreshold, p_nioSelector, this);
        NIOOutgoingRingBuffer outgoingBuffer = new NIOOutgoingRingBuffer(p_ownNodeId, p_bufferSize, p_exporterPool);
        NIOPipeIn pipeIn = new NIOPipeIn(p_ownNodeId, p_destination, p_messageHeaderPool, flowControl,
                p_messageDirectory, p_requestMap, p_messageHandlers, p_bufferPool, p_incomingBufferQueue, this,
                p_benchmarkMode);
        NIOPipeOut pipeOut = new NIOPipeOut(p_ownNodeId, p_destination, p_bufferSize, flowControl, outgoingBuffer,
                p_nioSelector, p_nodeMap, this);

        setPipes(pipeIn, pipeOut);

        m_nioSelector = p_nioSelector;

        m_connectionCondLock = new ReentrantLock(false);
        m_connectionCond = m_connectionCondLock.newCondition();
    }

    /**
     * Finishes the connection process for the given connection
     *
     * @param p_key
     *         the selection key
     */
    public void connect(final SelectionKey p_key) throws NetworkException {
        if (getPipeOut().getChannel().isConnectionPending()) {
            try {
                if (getPipeOut().getChannel().finishConnect()) {
                    connected(p_key);
                } else {
                    // #if LOGGER >= ERROR
                    LOGGER.error("Connection could not be finished: %s", this);
                    // #endif /* LOGGER >= ERROR */
                }
            } catch (final IOException ignore) {
                abortConnectionCreation();
            }
        } else {
            // #if LOGGER >= WARN
            LOGGER.warn("Connection is not pending, connect aborted: %s", this);
            // #endif /* LOGGER >= WARN */
        }
    }

    @Override
    public void close(boolean p_force) {

        if (!p_force) {
            setClosingTimestamp(System.currentTimeMillis());
            if (!getPipeOut().isOutgoingQueueEmpty()) {
                // #if LOGGER >= DEBUG
                LOGGER.debug("Waiting for all scheduled messages to be sent over to be closed connection!");
                // #endif /* LOGGER >= DEBUG */
                long start = System.currentTimeMillis();
                while (!getPipeOut().isOutgoingQueueEmpty()) {
                    Thread.yield();

                    if (System.currentTimeMillis() - start > 10000) {
                        // #if LOGGER >= ERROR
                        LOGGER.debug("Waiting for all scheduled messages to be sent over aborted, timeout");
                        // #endif /* LOGGER >= ERROR */
                        break;
                    }
                }
            }
        } else {
            setClosingTimestamp(0);
        }

        m_nioSelector.closeConnectionAsync(this);
    }

    @Override
    public void wakeup() {
        m_nioSelector.getSelector().wakeup();
    }

    /**
     * Register connect interest
     */
    protected void connect() {
        m_nioSelector.changeOperationInterestAsync(InterestQueue.CONNECT, this);
    }

    /**
     * Returns whether the connection creation was aborted or not.
     *
     * @return true if connection creation was aborted, false otherwise
     */
    boolean isConnectionCreationAborted() {
        return m_connectionCreationAborted;
    }

    /**
     * Aborts the connection creation. Is called by selector thread.
     */
    private void abortConnectionCreation() {
        m_connectionCreationAborted = true;
    }

    /**
     * Executes after the connection is established
     *
     * @param p_key
     *         the selection key
     */

    private void connected(final SelectionKey p_key) {
        ByteBuffer temp;

        m_connectionCondLock.lock();
        temp = ByteBuffer.allocateDirect(2);
        temp.putShort(getOwnNodeID());
        temp.flip();

        // Register first write access containing the NodeID
        getPipeOut().sendNodeID(temp);

        try {
            // Change operation (read <-> write) and/or connection
            p_key.interestOps(SelectionKey.OP_WRITE);
        } catch (final CancelledKeyException ignore) {
            m_connectionCond.signalAll();
            m_connectionCondLock.unlock();
            return;
        }

        setPipeOutConnected(true);

        m_connectionCond.signalAll();
        m_connectionCondLock.unlock();
    }
}
