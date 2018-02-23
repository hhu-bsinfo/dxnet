/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
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

package de.hhu.bsinfo.dxnet.core;

/**
 * Importer collection.
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 12.07.2017
 */
public class MessageImporterCollection {

    private MessageImporterDefault m_importer;
    private MessageImporterOverflow m_importerOverflow;
    private MessageImporterUnderflow m_importerUnderflow;
    private MessageImporterUnderOverflow m_importerUnderOverflow;

    private AbstractMessageImporter m_currentUsed;
    private AbstractMessageImporter m_prevUsed;

    /**
     * Constructor
     */
    public MessageImporterCollection() {
        super();

        m_importer = new MessageImporterDefault();
        m_importerOverflow = new MessageImporterOverflow();
        m_importerUnderflow = new MessageImporterUnderflow();
        m_importerUnderOverflow = new MessageImporterUnderOverflow();
    }

    @Override
    public String toString() {
        return "m_importer [" + m_importer + "]\nm_importerOverflow [" + m_importerOverflow + "]\nm_importerUnderflow [" + m_importerUnderflow +
                "]\nm_importerUnderOverflow [" + m_importerUnderOverflow + "]\nCurrent used (" + m_currentUsed.getClass().getSimpleName() + ") [" +
                m_currentUsed + "]\nPrev used (" + m_prevUsed.getClass().getSimpleName() + ") [" + m_prevUsed + ']';
    }

    /**
     * Get corresponding importer (default, overflow, underflow, under-overflow).
     *
     * @param p_addr
     *         address of buffer to import from
     * @param p_position
     *         offset within import buffer
     * @param p_bufferSize
     *         length of import buffer
     * @param p_payloadSize
     *         size of message's payload
     * @return the AbstractMessageImporter
     */
    AbstractMessageImporter getImporter(final int p_payloadSize, final long p_addr, final int p_position, final int p_bufferSize,
            final UnfinishedImExporterOperation p_unfinishedOperation) {
        AbstractMessageImporter ret;
        int bytesCopied = p_unfinishedOperation.getBytesCopied();

        boolean hasOverflow = p_position + p_payloadSize - bytesCopied > p_bufferSize;

        if (bytesCopied != 0) {
            if (hasOverflow) {
                ret = m_importerUnderOverflow;
            } else {
                ret = m_importerUnderflow;
            }
        } else if (hasOverflow) {
            ret = m_importerOverflow;
        } else {
            ret = m_importer;
        }

        m_prevUsed = m_currentUsed;
        m_currentUsed = ret;

        ret.incrementUsed();

        // mirror ByteBuffer position and limit (range) to importer
        ret.setBuffer(p_addr, p_bufferSize, p_position);
        ret.setUnfinishedOperation(p_unfinishedOperation);
        ret.setNumberOfReadBytes(bytesCopied);

        return ret;
    }

}
