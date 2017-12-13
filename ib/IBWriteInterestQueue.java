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

import java.util.concurrent.atomic.AtomicInteger;

import de.hhu.bsinfo.dxutils.NodeID;

/**
 * N:1 Queue to keep track of nodes with available interests. The caller has to ensure
 * to not add a single node id multiple times (see interest manager) to ensure
 * connections are processed in a round robin fashion
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 02.08.2017
 */
class IBWriteInterestQueue {
    private final short[] m_queue;
    private volatile int m_front;
    private AtomicInteger m_backReserved;
    private AtomicInteger m_back;

    /**
     * Constructor
     */
    IBWriteInterestQueue() {
        m_queue = new short[NodeID.MAX_ID];
        m_front = 0;
        m_backReserved = new AtomicInteger(0);
        m_back = new AtomicInteger(0);
    }

    /**
     * Push a node id to the back of the queue
     *
     * @param p_nodeId
     *         Node id to push back
     */
    public void pushBack(final short p_nodeId) {
        if (p_nodeId == NodeID.INVALID_ID) {
            throw new IllegalStateException("Invalid node id is not allowed on interest queue");
        }

        int backRes;

        // reserve a slot to write to
        while (true) {
            backRes = m_backReserved.get() & 0x7FFFFFFF;

            if ((backRes + 1) % m_queue.length == (m_front & 0x7FFFFFFF) % m_queue.length) {
                throw new IllegalStateException("Interest queue cannot be full");
            }

            if (m_backReserved.compareAndSet(backRes, backRes + 1)) {
                break;
            }
        }

        // write to reserved slot
        m_queue[backRes % m_queue.length] = p_nodeId;

        // update actual back pointer to allow front to consume the element
        // other threads might do this concurrently, so wait until the back pointer has moved
        // up to our reserved position
        while (!m_back.compareAndSet(backRes, backRes + 1)) {
            Thread.yield();
        }
    }

    /**
     * Pop the next node id from the front
     *
     * @return Valid node id if available, -1 if queue empty
     */
    public short popFront() {
        int frontPos = (m_front & 0x7FFFFFFF) % m_queue.length;

        if ((m_back.get() & 0x7FFFFFFF) % m_queue.length == frontPos) {
            return NodeID.INVALID_ID;
        }

        short elem = m_queue[frontPos];
        m_front++;

        return elem;
    }
}
