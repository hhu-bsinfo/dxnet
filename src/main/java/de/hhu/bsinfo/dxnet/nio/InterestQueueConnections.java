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

import java.util.concurrent.atomic.AtomicInteger;

import de.hhu.bsinfo.dxutils.NodeID;

/**
 * N:1 Queue to keep track of nodes with available interests. The caller has to ensure
 * to not add a single node id multiple times (see interest manager) to ensure
 * connections are processed in a round robin fashion
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 02.08.2017
 */
class InterestQueueConnections {
    // package private for state stats in manager
    final NIOConnection[] m_queue;
    volatile int m_front;
    private AtomicInteger m_backReserved;
    AtomicInteger m_back;

    /**
     * Constructor
     */
    InterestQueueConnections() {
        m_queue = new NIOConnection[NodeID.MAX_ID];
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
    public boolean pushBack(final NIOConnection p_nodeId) {
        if (p_nodeId == null) {
            throw new IllegalStateException("Invalid null connection not allowed on interest queue");
        }

        int backResSigned;
        int backRes;
        int front;

        // reserve a slot to write to
        backResSigned = m_backReserved.getAndIncrement();
        backRes = backResSigned & 0x7FFFFFFF;
        front = m_front & 0x7FFFFFFF;

        if ((backRes + 1 & 0x7FFFFFFF) % m_queue.length == front % m_queue.length) {
            throw new IllegalStateException("Interest queue cannot be full: m_back " + m_back.get() + ", backRes " +
                    backRes + ", front " + front);
        }

        // write to reserved slot
        m_queue[backRes % m_queue.length] = p_nodeId;

        // update actual back pointer to allow front to consume the element
        // other threads might do this concurrently, so wait until the back pointer has moved
        // up to our reserved position
        while (!m_back.compareAndSet(backResSigned, backResSigned + 1)) {
            Thread.yield();
        }

        // was queue empty before adding new element?
        return backRes % m_queue.length == front % m_queue.length;
    }

    /**
     * Pop the next node id from the front
     *
     * @return Valid node id if available, -1 if queue empty
     */
    public NIOConnection popFront() {
        int frontPos = (m_front & 0x7FFFFFFF) % m_queue.length;
        int backPos = m_back.get() & 0x7FFFFFFF;

        if (backPos % m_queue.length == frontPos) {
            return null;
        }

        NIOConnection elem = m_queue[frontPos];
        m_front++;

        return elem;
    }

    @Override
    public String toString() {
        return "m_front " + m_front + ", m_backReserved " + m_backReserved.get() + ", m_back " + m_back.get();
    }
}
