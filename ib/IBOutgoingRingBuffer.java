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

import de.hhu.bsinfo.dxnet.core.AbstractExporterPool;
import de.hhu.bsinfo.dxnet.core.OutgoingRingBuffer;

/**
 * Implementation of the outgoing ring buffer for IB
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 17.07.2017
 */
class IBOutgoingRingBuffer extends OutgoingRingBuffer {

    private int m_posBackDataPosted;

    /**
     * Constructor
     *
     * @param p_bufferAddr
     *         Unsafe address of the ring buffer
     * @param p_bufferSize
     *         Size of the buffer
     * @param p_exporterPool
     *         Exporter pool instance
     */
    IBOutgoingRingBuffer(final long p_bufferAddr, final int p_bufferSize, final AbstractExporterPool p_exporterPool) {
        super(p_exporterPool);

        setBuffer(p_bufferAddr, p_bufferSize);
    }

    // TODO doc: posted but NOT processed: we have to introduce another back pointer
    // which marks this position so we don't send data from the ORB twice
    // because the callback when the data is actually sent (which has to move the
    // ORBs real back pointer) comes way later
    public void dataSendPosted(final int p_bytesPosted) {
        // wipe sign to avoid bugs on overflows
        m_posBackDataPosted = m_posBackDataPosted + p_bytesPosted & 0x7FFFFFFF;
    }

    // TODO doc, native SendThread handles wrap around, so we also allow it to happen
    protected long popBack() {
        int posFrontRelative;
        int posBackRelative;

        posFrontRelative = ((int) m_posFrontConsumer.get()) % m_bufferSize;
        posBackRelative = m_posBackDataPosted % m_bufferSize;

        return (long) posFrontRelative << 32 | (long) posBackRelative;
    }
}
