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

package de.hhu.bsinfo.dxnet;

import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.MessageHeader;
import de.hhu.bsinfo.dxnet.core.MessageHeaderPool;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.Time;

/**
 * Distributes incoming exclusive messages
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 30.08.2017
 */
final class ExclusiveMessageHandler {
    private static final Logger LOGGER = LogManager.getFormatterLogger(ExclusiveMessageHandler.class.getSimpleName());

    private static final Time SOP_PUSH = new Time(ExclusiveMessageHandler.class, "Push");
    private static final Time SOP_WAIT = new Time(ExclusiveMessageHandler.class, "Wait");

    static {
        StatisticsManager.get().registerOperation(ExclusiveMessageHandler.class, SOP_PUSH);
        StatisticsManager.get().registerOperation(ExclusiveMessageHandler.class, SOP_WAIT);
    }

    // must be a power of two to work with wrap around
    private static final int EXCLUSIVE_MESSAGE_STORE_SIZE = 128;

    private final MessageHeaderStore m_exclusiveMessageHeaders;

    private final MessageHandler m_exclusiveMessageHandler;

    /**
     * Creates an instance of ExclusiveMessageHandler
     *
     * @param p_messageReceivers
     *         Provides all registered message receivers
     */
    ExclusiveMessageHandler(final MessageReceiverStore p_messageReceivers, final MessageHeaderPool p_messageHeaderPool,
            final boolean p_overprovisioning) {
        m_exclusiveMessageHeaders = new MessageHeaderStore(EXCLUSIVE_MESSAGE_STORE_SIZE);

        LOGGER.info("Network: ExclusiveMessageHandler: Initialising thread");

        m_exclusiveMessageHandler = new MessageHandler(p_messageReceivers, m_exclusiveMessageHeaders,
                p_messageHeaderPool, p_overprovisioning);
        m_exclusiveMessageHandler.setName("Network: ExclusiveMessageHandler");
        m_exclusiveMessageHandler.start();
    }

    /**
     * Closes the exclusive message handler
     */
    void shutdown() {
        m_exclusiveMessageHandler.shutdown();
        LockSupport.unpark(m_exclusiveMessageHandler);
        m_exclusiveMessageHandler.interrupt();

        try {
            m_exclusiveMessageHandler.join();
            LOGGER.info("Shutdown of ExclusiveMessageHandler successful");
        } catch (final InterruptedException e) {
            LOGGER.warn("Could not wait for exclusive message handler to finish. Interrupted");
        }
    }

    /**
     * Activate parking strategy for exclusive message handler.
     */
    void activateParking() {
        m_exclusiveMessageHandler.activateParking();
    }

    /**
     * Registers a special receive message type
     *
     * @param p_type
     *         the unique type
     * @param p_subtype
     *         the unique subtype
     */
    void registerSpecialReceiveMessageType(final byte p_type, final byte p_subtype) {
        m_exclusiveMessageHandler.registerSpecialReceiveMessageType(p_type, p_subtype);
    }

    /**
     * Enqueue a batch of message headers
     *
     * @param p_headers
     *         the message headers
     * @param p_messages
     *         the number of used entries in array
     */
    void newHeaders(final MessageHeader[] p_headers, final int p_messages) {
        // #ifdef STATISTICS
        SOP_PUSH.start();
        // #endif /* STATISTICS */

        if (!m_exclusiveMessageHeaders.pushMessageHeaders(p_headers, p_messages)) {
            for (int i = 0; i < p_messages; i++) {
                if (!m_exclusiveMessageHeaders.pushMessageHeader(p_headers[i])) {
                    // #ifdef STATISTICS
                    SOP_WAIT.start();
                    // #endif /* STATISTICS */

                    while (!m_exclusiveMessageHeaders.pushMessageHeader(p_headers[i])) {
                        LockSupport.parkNanos(100);
                    }

                    // #ifdef STATISTICS
                    SOP_WAIT.stop();
                    // #endif /* STATISTICS */
                }
            }
        }

        // #ifdef STATISTICS
        SOP_PUSH.stop();
        // #endif /* STATISTICS */
    }
}
