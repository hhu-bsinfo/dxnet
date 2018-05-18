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

package de.hhu.bsinfo.dxnet.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxutils.NodeID;
import de.hhu.bsinfo.dxutils.stats.AbstractState;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.TimePool;

/**
 * Software flow control. Avoids that the sender is flooding the receiver if the receiver can't
 * keep up with processing the incoming buffers, deserializing and distpatching the messages.
 * Flow control confirmation of a full message is sent after it is received and fully processed
 * by the application.
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 09.06.2017
 */
public abstract class AbstractFlowControl {
    private static final Logger LOGGER = LogManager.getFormatterLogger(AbstractFlowControl.class.getSimpleName());

    protected final short m_destinationNodeID;

    protected final int m_flowControlWindowSize;
    private final float m_flowControlWindowThreshold;
    protected final int m_flowControlWindowSizeThreshold;

    private AtomicInteger m_unconfirmedBytes;
    protected AtomicInteger m_receivedBytes;

    private final TimePool m_sopWait;
    private StateStatistics m_stateStats;

    /**
     * Constructor
     *
     * @param p_destinationNodeID
     *         Node id of the destination this unit is connected to
     * @param p_flowControlWindowSize
     *         FC window in bytes. When exceeded send out a flow control message
     * @param p_flowControlWindowThreshold
     *         Threshold parameter to adjust the FC window to control when a flow control message is sent
     */
    protected AbstractFlowControl(final short p_destinationNodeID, final int p_flowControlWindowSize,
            final float p_flowControlWindowThreshold) {
        m_destinationNodeID = p_destinationNodeID;

        if (p_flowControlWindowSize == 0 || p_flowControlWindowThreshold == 0.0f) {
            m_flowControlWindowSize = 0;
            m_flowControlWindowThreshold = 0.0f;

            // #if LOGGER >= WARN
            LOGGER.warn("Flow control disabled");
            // #endif /* LOGGER >= WARN */
        } else {
            m_flowControlWindowSize = p_flowControlWindowSize;
            m_flowControlWindowThreshold = p_flowControlWindowThreshold;
        }

        m_flowControlWindowSizeThreshold = (int) (m_flowControlWindowSize * m_flowControlWindowThreshold);

        m_unconfirmedBytes = new AtomicInteger(0);
        m_receivedBytes = new AtomicInteger(0);

        m_sopWait = new TimePool(AbstractFlowControl.class, "Wait-" + NodeID.toHexStringShort(m_destinationNodeID));
        m_stateStats = new StateStatistics();

        StatisticsManager.get().registerOperation(AbstractFlowControl.class, m_sopWait);
        StatisticsManager.get().registerOperation(AbstractFlowControl.class, m_stateStats);

        // #if LOGGER >= DEBUG
        LOGGER.debug("Flow control settings for node 0x%X: window size %d, threshold %f", p_destinationNodeID,
                p_flowControlWindowSize,
                p_flowControlWindowThreshold);
        // #endif /* LOGGER >= DEBUG */
    }

    @Override
    protected void finalize() {
        StatisticsManager.get().deregisterOperation(AbstractFlowControl.class, m_sopWait);
        StatisticsManager.get().deregisterOperation(AbstractFlowControl.class, m_stateStats);
    }

    /**
     * Get the destination node id the flow control is connected to
     */
    protected short getDestinationNodeId() {
        return m_destinationNodeID;
    }

    /**
     * Writes flow control data to the destination ASAP (really, do it ASAP or you risk running into ugly deadlocking
     * issues)
     *
     * @throws NetworkException
     *         If writing the flow control data failed
     */
    public abstract void flowControlWrite() throws NetworkException;

    // call when writing flow control data

    /**
     * Get current number of "confirmed bytes" to send back to the source (to confirm data was processed)
     * and reset
     *
     * @return Number of confirmed FC windows to send back
     */
    public abstract byte getAndResetFlowControlData();

    /**
     * Called when messages ("unconfirmed bytes") are written to the connection of the destination
     *
     * @param p_writtenBytes
     *         Number of bytes that were written to the destination
     */
    void dataToSend(final int p_writtenBytes) {
        // #if LOGGER >= TRACE
        LOGGER.trace("flowControlDataToSend (%X): %d", m_destinationNodeID, p_writtenBytes);
        // #endif /* LOGGER >= TRACE */

        if (m_flowControlWindowSize != 0 && m_unconfirmedBytes.get() > m_flowControlWindowSize) {
            // #ifdef STATISTICS
            m_sopWait.start();
            // #endif /* STATISTICS */

            while (m_unconfirmedBytes.get() > m_flowControlWindowSize) {
                LockSupport.parkNanos(100);
            }

            // #ifdef STATISTICS
            m_sopWait.stop();
            // #endif /* STATISTICS */
        }

        m_unconfirmedBytes.addAndGet(p_writtenBytes);
    }

    // call when data was received on a connection

    /**
     * Called when messages ("bytes to be confirmed") are received on the remote
     *
     * @param p_receivedBytes
     *         Number of bytes received
     */
    void dataReceived(final int p_receivedBytes) {
        int receivedBytes = m_receivedBytes.addAndGet(p_receivedBytes);

        if (m_flowControlWindowSizeThreshold != 0.0f && receivedBytes >= m_flowControlWindowSizeThreshold) {
            try {
                flowControlWrite();
            } catch (final NetworkException e) {
                // #if LOGGER >= ERROR
                LOGGER.error("Could not send flow control message", e);
                // #endif /* LOGGER >= ERROR */
            }
        }
    }

    /**
     * Called when "confirmed bytes" are received from the remote
     *
     * @param p_confirmedWindows
     *         Number of windows confirmed by the remote
     */
    public void handleFlowControlData(final byte p_confirmedWindows) {
        // #if LOGGER >= TRACE
        LOGGER.trace("handleFlowControlData (%X): %d", m_destinationNodeID,
                p_confirmedWindows * m_flowControlWindowSizeThreshold);
        // #endif /* LOGGER >= TRACE */

        m_unconfirmedBytes.addAndGet(-(p_confirmedWindows * m_flowControlWindowSizeThreshold));
    }

    @Override
    public String toString() {
        String str;

        str = "FlowControl[m_flowControlWindowSize " + m_flowControlWindowSize + ", m_unconfirmedBytes " +
                m_unconfirmedBytes + ", m_receivedBytes " + m_receivedBytes + ']';

        return str;
    }

    /**
     * State statistics implementation for debugging
     */
    private class StateStatistics extends AbstractState {
        /**
         * Constructor
         */
        StateStatistics() {
            super(AbstractFlowControl.class, "State-" + NodeID.toHexStringShort(m_destinationNodeID));
        }

        @Override
        public String dataToString(final String p_indent, final boolean p_extended) {
            return p_indent + "m_destinationNodeID " + NodeID.toHexStringShort(m_destinationNodeID) +
                    ";m_flowControlWindowSize " + m_flowControlWindowSize + ";m_flowControlWindowThreshold " +
                    m_flowControlWindowThreshold + ";m_flowControlWindowSizeThreshold " +
                    m_flowControlWindowSizeThreshold + ";m_unconfirmedBytes " + m_unconfirmedBytes.get() +
                    ";m_receivedBytes " + m_receivedBytes.get();
        }

        @Override
        public String generateCSVHeader(final char p_delim) {
            return "m_destinationNodeID " + p_delim + "m_flowControlWindowSize" + p_delim +
                    "m_flowControlWindowThreshold" + p_delim + "m_flowControlWindowSizeThreshold" + p_delim +
                    "m_unconfirmedBytes" + p_delim + "m_receivedBytes";
        }

        @Override
        public String toCSV(final char p_delim) {
            return NodeID.toHexStringShort(m_destinationNodeID) + p_delim + m_flowControlWindowSize + p_delim +
                    m_flowControlWindowThreshold + p_delim + m_flowControlWindowSizeThreshold + p_delim +
                    m_unconfirmedBytes.get() + p_delim + m_receivedBytes.get();
        }
    }
}
