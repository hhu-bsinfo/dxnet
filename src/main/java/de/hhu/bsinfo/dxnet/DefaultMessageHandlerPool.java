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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.MessageHeader;
import de.hhu.bsinfo.dxnet.core.MessageHeaderPool;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.Time;

/**
 * Manages default MessageHandler thread and dynamic scaling.
 * Distributes incoming default messages
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 19.07.2016
 * @author Christian Gesse, christian.gesse@hhu.de, 15.01.2019
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

    // List of active MessageHandlers
    private final LinkedBlockingQueue<MessageHandler> m_activeHandlers;

    // List of blocked (by synchronous calls) MessageHandlers
    private final LinkedBlockingQueue<MessageHandler> m_blockedHandlers;

    // List of parked (currently deactivated) MessageHandlers
    private final LinkedBlockingQueue<MessageHandler> m_parkedHandlers;

    // Global count for unique naming of MessageHandlers
    private static int m_globalMessageHandlerID = 0;

    private  static MessageReceiverStore m_messageReceivers;

    private static MessageHeaderPool m_messageHeaderPool;

    private static boolean m_overprovisioning;

    // amount of started MessageHandlers during initialization
    private static int m_initialMessageHandlerCount;

    // bitmap for thread-IDs of MessageHandlers
    private static long[] m_messageHandlerMap;


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

        // init Message Handler lists
        m_activeHandlers = new LinkedBlockingQueue<>();
        m_blockedHandlers = new LinkedBlockingQueue<>();
        m_parkedHandlers = new LinkedBlockingQueue<>();

        m_messageHandlerMap = new long[4096];


        m_initialMessageHandlerCount = p_numMessageHandlerThreads;

        m_messageReceivers = p_messageReceivers;
        m_messageHeaderPool = p_messageHeaderPool;
        m_overprovisioning = p_overprovisioning;

        // initialize first MessageHandlers
        for (int i = 0; i < p_numMessageHandlerThreads; i++) {
            addNewMessageHandler();
        }
    }

    /**
     * Closes all default message handler
     */
    void shutdown() {
        // shutdown active MessageHandlers
        for (MessageHandler t : m_activeHandlers) {
            t.shutdown();
            t.unmarkForParking();
            LockSupport.unpark(t);
            t.interrupt();

            try {
                t.join();
                LOGGER.info("Shutdown of " + t.getName() + " successful");
            } catch (final InterruptedException e) {
                LOGGER.warn("Could not wait for default message handler to finish. Interrupted");
            }
        }
        // shutdown parked MessageHandlers
        for (MessageHandler t : m_parkedHandlers) {
            t.shutdown();
            t.unmarkForParking();
            LockSupport.unpark(t);
            t.interrupt();

            try {
                t.join();
                LOGGER.info("Shutdown of " + t.getName() + " successful");
            } catch (final InterruptedException e) {
                LOGGER.warn("Could not wait for default message handler to finish. Interrupted");
            }
        }
        // shutdown blocked Message Handlers
        for (MessageHandler t : m_blockedHandlers) {
            t.shutdown();
            t.unmarkForParking();
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
        for (MessageHandler t : m_activeHandlers) {
            t.activateParking();
        }

        for (MessageHandler t : m_parkedHandlers) {
            t.activateParking();
        }

        for (MessageHandler t : m_blockedHandlers) {
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

    /**
     * Creates a new MessageHandler-Thread and adds it to list of active MessageHandlers
     */
    private synchronized void addNewMessageHandler() {
        // create new Message Handler
        MessageHandler mh = new MessageHandler(m_messageReceivers, m_defaultMessageHeaders, m_messageHeaderPool,
                m_overprovisioning);
        mh.setName("Network: MessageHandler " + (m_globalMessageHandlerID++));

        // set entry for thread-ID in bitmap
        long threadId = mh.getId();
        int idx = (int) (threadId / 64);
        long pos = (int) (threadId % 64);
        m_messageHandlerMap[idx] |= (1L << pos);

        // add new Message Handler
        m_activeHandlers.add(mh);
        // start thread
        mh.start();

        LOGGER.info("Added new " + mh.getName() + " - current numbers: [Active " + m_activeHandlers.size() +
                "][Blocked " + m_blockedHandlers.size() + "][Parked " + m_parkedHandlers.size() + "]");
    }

    /**
     * Activates a parked MessageHandler and puts it back into active-queue.
     *
     * @return MessageHandler or null if no parked MessageHandler available
     */
    private synchronized MessageHandler activateMessageHandler() {
        // get parked MessageHandler from queue
        MessageHandler mh = m_parkedHandlers.poll();

        if(mh != null){
            // add to list of active handlers
            m_activeHandlers.add(mh);
            // first, unmark from parking so that thread does not park itself when scheduled
            mh.unmarkForParking();

            // wake up thread from parking
            LockSupport.unpark(mh);

            LOGGER.info("Reactivated " + mh.getName() + " - current numbers: [Active " + m_activeHandlers.size() +
                    "][Blocked " + m_blockedHandlers.size() + "][Parked " + m_parkedHandlers.size() + "]");
        }

        return mh;

    }

    /**
     * Prepares (or marks) a MessageHandler for parking. The handler will park itself next time it is scheduled
     * and has finished its current tasks.
     */
    private synchronized void prepareParkMessageHandler() {
        // get an active handler
        MessageHandler mh = m_activeHandlers.poll();

        if(mh != null) {
            // mark handler for parking
            mh.markForParking();
            // put into list
            m_parkedHandlers.add(mh);

            LOGGER.info("Marked " + mh.getName() + " for parking - current numbers: [Active " + m_activeHandlers.size() +
                    "][Blocked " + m_blockedHandlers.size() + "][Parked " + m_parkedHandlers.size() + "]");
        }

    }

    /**
     * Deletes a MessageHandler from system.
     *
     * @return The deleted MessageHandler
     */
    private synchronized MessageHandler deleteMessageHandler() {
        MessageHandler mh;

        // try to get from parked handlers first, then from active
        if((mh = m_parkedHandlers.poll()) == null) {
            mh = m_activeHandlers.poll();
        }

        if (mh != null) {
            mh.shutdown();
            mh.unmarkForParking();
            LockSupport.unpark(mh);
            mh.interrupt();

            try {
                mh.join();
                LOGGER.info("Shutdown of " + mh.getName() + " successful");
            } catch (final InterruptedException e) {
                LOGGER.warn("Could not wait for default message handler to finish. Interrupted");
            }
        }

        return mh;
    }

    /**
     * Increases the count of blocked MessageHandler and applies dynamic scaling.
     */
    public synchronized void incBlockedMessageHandlers() {
        // remove current Message handler from queue - two cases possible
        // First: Handler is already parked, so it has to be removed from parked queue
        if(m_parkedHandlers.contains(Thread.currentThread())) {
            m_parkedHandlers.remove(Thread.currentThread());
            ((MessageHandler) Thread.currentThread()).unmarkForParking();
        // second: Handler is active and has to be removed from corresponding queue
        } else {
            m_activeHandlers.remove(Thread.currentThread());
        }
        // add to blocked handlers
        m_blockedHandlers.add((MessageHandler) Thread.currentThread());

        LOGGER.info("Increased blocked Message Handlers - current numbers: [Active " + m_activeHandlers.size() +
                "][Blocked " + m_blockedHandlers.size() + "][Parked " + m_parkedHandlers.size() + "]");

        // check if remaining active handlers are available
        if(m_activeHandlers.size() == 0) {
            LOGGER.warn("All Message Handlers are blocked - add or reactivate MessageHandler");

            // activate parked handler or create new one
            if(activateMessageHandler() == null) {
                addNewMessageHandler();
            }
        }

        LOGGER.info("Leave Increase Block - current numbers: [Active " + m_activeHandlers.size() +
                "][Blocked " + m_blockedHandlers.size() + "][Parked " + m_parkedHandlers.size() + "]");

    }

    /**
     * Decreases the count of blocked MessageHandler threads and applies dynamic scaling.
     */
    public  synchronized void decBlockedMessageHandlers() {
        // remove current Message Handler from blocked queue
        m_blockedHandlers.remove(Thread.currentThread());

        // add current handler to active handler queue
        m_activeHandlers.add((MessageHandler) Thread.currentThread());

        LOGGER.info("Decreased blocked Message Handlers - current numbers: [Active " + m_activeHandlers.size() +
                "][Blocked " + m_blockedHandlers.size() + "][Parked " + m_parkedHandlers.size() + "]");

        // if there are enough unblocked handlers park an active one
        if(m_activeHandlers.size() > 1 &&  m_activeHandlers.size() > m_initialMessageHandlerCount) {
            prepareParkMessageHandler();
        }
        LOGGER.info("Leave Decrease Block: Blocked - current numbers: [Active " + m_activeHandlers.size() +
                "][Blocked " + m_blockedHandlers.size() + "][Parked " + m_parkedHandlers.size() + "]");

    }

    /**
     * Check if thread-ID belongs to a MessageHandler.
     * @param p_threadId
     *         Thread-ID
     * @return Boolean if MessageHandler or not
     */
    boolean isDefaultMessageHandler(long p_threadId) {
        // get index and offset into long-array
        int idx = (int) (p_threadId / 64);
        long pos = (int) (p_threadId % 64);

        long tmp = m_messageHandlerMap[idx];
        int res = (int) ((tmp >>> pos) & 1L);

        return (res > 0);
    }
}
