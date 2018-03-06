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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxutils.NodeID;
import de.hhu.bsinfo.dxutils.stats.AbstractState;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;

/**
 * Manager for write interests of all connections
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 02.08.2017
 */
class IBWriteInterestManager {
    private static final Logger LOGGER = LogManager.getFormatterLogger(IBWriteInterestManager.class.getSimpleName());

    private final IBWriteInterestQueue m_interestQueue;
    private final IBWriteInterest[] m_writeInterests;

    private final StateStatistics m_stateStats;

    /**
     * Constructor
     */
    IBWriteInterestManager() {
        m_interestQueue = new IBWriteInterestQueue();
        m_writeInterests = new IBWriteInterest[NodeID.MAX_ID];

        for (int i = 0; i < m_writeInterests.length; i++) {
            m_writeInterests[i] = new IBWriteInterest((short) i);
        }

        m_stateStats = new StateStatistics();

        StatisticsManager.get().registerOperation(IBWriteInterestManager.class, m_stateStats);
    }

    @Override
    protected void finalize() {
        StatisticsManager.get().deregisterOperation(IBWriteInterestManager.class, m_stateStats);
    }

    /**
     * Add a write data interest
     *
     * @param p_nodeId
     *         Node id of connection with data available to send
     */
    void pushBackDataInterest(final short p_nodeId) {
        // #if LOGGER == TRACE
        LOGGER.trace("pushBackDataInterest: 0x%X", p_nodeId);
        // #endif /* LOGGER == TRACE */

        if (m_writeInterests[p_nodeId & 0xFFFF].addDataInterest()) {
            m_interestQueue.pushBack(p_nodeId);
        }
    }

    /**
     * Add a write FC interest
     *
     * @param p_nodeId
     *         Node id of connection with FC data available to send
     */
    void pushBackFcInterest(final short p_nodeId) {
        // #if LOGGER == TRACE
        LOGGER.trace("pushBackFCInterest: 0x%X", p_nodeId);
        // #endif /* LOGGER == TRACE */

        if (m_writeInterests[p_nodeId & 0xFFFF].addFcInterest()) {
            m_interestQueue.pushBack(p_nodeId);
        }
    }

    /**
     * Get the next node in order which has at least one write interest available.
     * This is called by the send thread. The caller has to manually consume the
     * interests after this
     *
     * @return Node id with at least a single write interest available
     */
    short getNextInterests() {
        return m_interestQueue.popFront();
    }

    /**
     * Consume all interests of a single node
     *
     * @param p_nodeId
     *         Node id of the node to consume interests
     * @return Long value holding the number of data interests
     * (lower 32-bit) and FC interests (higher 32-bit)
     */
    long consumeInterests(final short p_nodeId) {
        return m_writeInterests[p_nodeId & 0xFFFF].consumeInterests();
    }

    /**
     * Call this when a node disconnected to reset any interests
     *
     * @param p_nodeId
     *         Node id of the disconnected node
     */
    void nodeDisconnected(final short p_nodeId) {
        m_writeInterests[p_nodeId & 0xFFFF].reset();
    }

    @Override
    public String toString() {
        StringBuilder strBuilder = new StringBuilder();

        strBuilder.append(m_interestQueue);
        strBuilder.append('\n');

        for (int i = 0; i < m_writeInterests.length; i++) {
            strBuilder.append(m_writeInterests[i]);
            strBuilder.append('\n');
        }

        return strBuilder.toString();
    }

    /**
     * State statistics implementation for debugging
     */
    private class StateStatistics extends AbstractState {
        /**
         * Constructor
         */
        StateStatistics() {
            super(IBWriteInterestManager.class, "State");
        }

        @Override
        public String dataToString(final String p_indent) {
            StringBuilder builder = new StringBuilder();

            // allow data races here to avoid performance penalties
            int front = (m_interestQueue.m_front & 0x7FFFFFFF) % m_interestQueue.m_queue.length;
            int back = (m_interestQueue.m_back.get() & 0x7FFFFFFF) % m_interestQueue.m_queue.length;

            while (front != back) {
                short nodeId = m_interestQueue.m_queue[front];
                long interests = m_writeInterests[nodeId & 0xFFFF].m_interestsAvailable.get();
                int dataInterests = (int) interests;
                int fcInterests = (int) (interests >> 32);

                builder.append(p_indent);
                builder.append("nodeId ");
                builder.append(NodeID.toHexStringShort(nodeId));
                builder.append(";dataInterests ");
                builder.append(dataInterests);
                builder.append(";fcInterests ");
                builder.append(fcInterests);

                front = (front + 1) % m_interestQueue.m_queue.length;

                if (front != back) {
                    builder.append('\n');
                }
            }

            return builder.toString();
        }

        @Override
        public String generateCSVHeader(final char p_delim) {
            return "nodeId" + p_delim + "dataInterests" + p_delim + "fcInterests";
        }

        @Override
        public String toCSV(final char p_delim) {
            StringBuilder builder = new StringBuilder();

            // allow data races here to avoid performance penalties
            int front = (m_interestQueue.m_front & 0x7FFFFFFF) % m_interestQueue.m_queue.length;
            int back = (m_interestQueue.m_back.get() & 0x7FFFFFFF) % m_interestQueue.m_queue.length;

            while (front != back) {
                short nodeId = m_interestQueue.m_queue[front];
                long interests = m_writeInterests[nodeId & 0xFFFF].m_interestsAvailable.get();
                int dataInterests = (int) interests;
                int fcInterests = (int) (interests >> 32);

                builder.append(NodeID.toHexStringShort(nodeId));
                builder.append(';');
                builder.append(dataInterests);
                builder.append(';');
                builder.append(fcInterests);

                front = (front + 1) % m_interestQueue.m_queue.length;

                if (front != back) {
                    builder.append('\n');
                }
            }

            return builder.toString();
        }
    }
}
