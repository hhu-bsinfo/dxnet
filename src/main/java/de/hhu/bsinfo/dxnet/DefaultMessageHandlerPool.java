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

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.MessageHeader;
import de.hhu.bsinfo.dxnet.core.MessageHeaderPool;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.Time;

/**
 * Distributes incoming default messages
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 19.07.2016
 */
final class DefaultMessageHandlerPool {
    private static final Logger LOGGER = LogManager.getFormatterLogger(DefaultMessageHandlerPool.class.getSimpleName());

    private static final Time SOP_PUSH = new Time(DefaultMessageHandlerPool.class, "Push");
    private static final Time SOP_WAIT = new Time(DefaultMessageHandlerPool.class, "Wait");

    static {
        StatisticsManager.get().registerOperation(DefaultMessageHandlerPool.class, SOP_PUSH);
        StatisticsManager.get().registerOperation(DefaultMessageHandlerPool.class, SOP_WAIT);
    }

    // must be a power of two to work with wrap around
    private static final int SIZE_MESSAGE_STORE = 16 * 1024;

    private final MessageHeaderStore m_defaultMessageHeaders;

    private final ArrayList<MessageHandler> m_threads;

    private static AtomicInteger m_blockedMessageHandlers;

    private static AtomicInteger m_numMessageHandlers;

    private  static MessageReceiverStore m_messageReceivers;

    private static MessageHeaderPool m_messageHeaderPool;

    private static boolean m_overprovisioning;


    /**
     * Creates an instance of DefaultMessageHandlerPool
     *
     * @param p_numMessageHandlerThreads
     *         the number of default message handler
     */
    DefaultMessageHandlerPool(final MessageReceiverStore p_messageReceivers,
            final MessageHeaderPool p_messageHeaderPool, final int p_numMessageHandlerThreads,
            final boolean p_overprovisioning) {
        m_defaultMessageHeaders = new MessageHeaderStore(SIZE_MESSAGE_STORE);

        LOGGER.info("Network: DefaultMessageHandlerPool: Initialising %d threads", p_numMessageHandlerThreads);

        MessageHandler t;
        m_threads = new ArrayList<>();

        m_blockedMessageHandlers = new AtomicInteger(0);
        m_numMessageHandlers = new AtomicInteger(p_numMessageHandlerThreads);

        m_messageReceivers = p_messageReceivers;
        m_messageHeaderPool = p_messageHeaderPool;
        m_overprovisioning = p_overprovisioning;

        for (int i = 0; i < p_numMessageHandlerThreads; i++) {
            t = new MessageHandler(p_messageReceivers, m_defaultMessageHeaders, p_messageHeaderPool,
                    p_overprovisioning);
            t.setName("Network: MessageHandler " + (i + 1));
            m_threads.add(t);
            t.start();
        }
    }

    /**
     * Closes all default message handler
     */
    void shutdown() {
        for (MessageHandler t : m_threads) {
            t.shutdown();
            LockSupport.unpark(t);
            t.interrupt();

            try {
                t.join();
                LOGGER.info("Shutdown of " + t.getName() + " successful");
            } catch (final InterruptedException e) {
                LOGGER.warn("Could not wait for default message handler to finish. Interrupted");
            }
        }
    }

    /**
     * Activate parking strategy for all default message handlers.
     */
    void activateParking() {
        for (MessageHandler t : m_threads) {
            t.activateParking();
        }
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
        SOP_PUSH.startDebug();

        if (!m_defaultMessageHeaders.pushMessageHeaders(p_headers, p_messages)) {
            for (int i = 0; i < p_messages; i++) {
                if (!m_defaultMessageHeaders.pushMessageHeader(p_headers[i])) {
                    SOP_WAIT.start();

                    while (!m_defaultMessageHeaders.pushMessageHeader(p_headers[i])) {
                        LockSupport.parkNanos(100);
                    }

                    SOP_WAIT.stop();
                }
            }
        }

        SOP_PUSH.stopDebug();
    }

    void incBlockedMessageHandlers() {
        int blockedMessageHandlers = m_blockedMessageHandlers.incrementAndGet();
        int numMessageHandlers = m_numMessageHandlers.get();

        LOGGER.debug("Current number of blocked MessageHandlers increased: " + blockedMessageHandlers + " of " + numMessageHandlers);

        if(blockedMessageHandlers == numMessageHandlers) {
            int numOfMessageHandlers = m_numMessageHandlers.incrementAndGet();
            LOGGER.warn("All Message Handlers are blocked - system might be running into deadlock");

            MessageHandler t = new MessageHandler(m_messageReceivers, m_defaultMessageHeaders, m_messageHeaderPool,
                    m_overprovisioning);
            t.setName("Network: MessageHandler " + numOfMessageHandlers);
            m_threads.add(t);
            t.start();

            LOGGER.debug("Added MessageHandler - Number of threads is now set to " + numOfMessageHandlers);

        }
    }

    void decBlockedMessageHandlers() {

        int blockedMessageHandlers = m_blockedMessageHandlers.decrementAndGet();
        int numMessageHandlers = m_numMessageHandlers.get();

        LOGGER.debug("Current number of blocked MessageHandlers decreased: " + blockedMessageHandlers + " of " + numMessageHandlers);

        if(numMessageHandlers > blockedMessageHandlers + 1) {
            numMessageHandlers = m_numMessageHandlers.decrementAndGet();
            MessageHandler t = m_threads.remove(m_threads.size() - 1);
            t.shutdown();
            LockSupport.unpark(t);
            t.interrupt();

            LOGGER.debug("Removed MessageHandler - Number of threads is now set to " + numMessageHandlers);
        }


    }
}
