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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.LocalMessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.Message;
import de.hhu.bsinfo.dxnet.core.MessageHeader;
import de.hhu.bsinfo.dxnet.core.MessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.MessageImporterCollection;
import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.TimePool;

/**
 * Executes incoming default messages
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 19.07.2016
 */
class MessageHandler extends Thread {
    private static final Logger LOGGER = LogManager.getFormatterLogger(MessageHandler.class.getSimpleName());

    private static final TimePool SOP_CREATE = new TimePool(MessageHandler.class, "CreateAndImport");
    private static final TimePool SOP_EXECUTE = new TimePool(MessageHandler.class, "Execute");

    static {
        StatisticsManager.get().registerOperation(MessageHandler.class, SOP_CREATE);
        StatisticsManager.get().registerOperation(MessageHandler.class, SOP_EXECUTE);
    }

    // optimized values determined by experiments
    private static final int THRESHOLD_TIME_CHECK = 100000;

    private final MessageReceiverStore m_messageReceivers;
    private final MessageHeaderStore m_messages;
    private final MessageImporterCollection m_importers;
    private final LocalMessageHeaderPool m_messageHeaderPool;

    private byte m_specialReceiveType = -1;
    private byte m_specialReceiveSubtype = -1;

    private AtomicBoolean m_markedForParking;

    private volatile boolean m_overprovisioning;
    private volatile boolean m_shutdown;

    // Constructors

    /**
     * Creates an instance of MessageHandler
     *
     * @param p_queue
     *         the message queue
     */
    MessageHandler(final MessageReceiverStore p_messageReceivers, final MessageHeaderStore p_queue,
            final MessageHeaderPool p_messageHeaderPool, final boolean p_overprovisioning) {
        m_messageReceivers = p_messageReceivers;
        m_messages = p_queue;
        m_importers = new MessageImporterCollection();
        m_messageHeaderPool = new LocalMessageHeaderPool(p_messageHeaderPool);

        m_overprovisioning = p_overprovisioning;

        m_markedForParking = new AtomicBoolean(false);
    }

    /**
     * Closes the handler
     */
    public void shutdown() {
        m_shutdown = true;
    }

    /**
     * Activate parking strategy.
     */
    void activateParking() {
        m_overprovisioning = true;
    }

    void markForParking() {
        m_markedForParking.set(true);
    }

    void unmarkForParking() {
        m_markedForParking.set(false);
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
        if (m_specialReceiveType == -1 && m_specialReceiveSubtype == -1) {
            m_specialReceiveType = p_type;
            m_specialReceiveSubtype = p_subtype;
        } else {
            LOGGER.error("Special receive type already registered: %d, %d!", p_type, p_subtype);
        }
    }

    // Methods
    @Override
    public void run() {
        int counter = 0;
        long lastSuccessfulPop = 0;
        MessageHeader header;
        Message message;
        MessageReceiver messageReceiver;
        byte type;
        byte subtype;
        boolean pollWait = true;

        while (!m_shutdown) {
            if(m_markedForParking.get() == true) {
                LockSupport.park();
            } else {
                header = m_messages.popMessageHeader();

                if (header == null) {
                    if (m_overprovisioning) {
                        LockSupport.parkNanos(1);
                    } else {
                        if (pollWait) {
                            if (++counter >= THRESHOLD_TIME_CHECK) {
                                if (System.currentTimeMillis() - lastSuccessfulPop >
                                        100) { // No message header for over a second -> sleep
                                    pollWait = false;
                                }
                            }
                        }

                        if (!pollWait) {
                            LockSupport.parkNanos(1000);
                        }
                    }

                    continue;
                }

                pollWait = true;

                lastSuccessfulPop = System.currentTimeMillis();
                counter = 0;

                SOP_CREATE.startDebug();

                type = header.getType();
                subtype = header.getSubtype();

                if (type == m_specialReceiveType && subtype == m_specialReceiveSubtype) {

                    // This is a special case for DXRAM's logging to deserialize the message's chunks directly into the write buffer.
                    // Do not use this method without considering all other possibilities!
                    if (!header.isIncomplete()) {
                        messageReceiver = m_messageReceivers.getReceiver(type, subtype);

                        if (messageReceiver != null) {
                            SOP_EXECUTE.startDebug();

                            ((SpecialMessageReceiver) messageReceiver).onIncomingHeader(header);
                            header.finishHeader(m_messageHeaderPool);

                            SOP_EXECUTE.stopDebug();
                        } else {
                            LOGGER.error("No message receiver was registered for %d, %d! Dropping received messages...",
                                    type, subtype);
                        }
                        continue;
                    } else {
                        // If header is incomplete, the deserialization was already started and a message object
                        // created by the MessageCreationCoordinator. In this case use the default way by continuing
                        // the deserialization and finishing the message object.
                    }
                }

                try {
                    message = header.createAndImportMessage(m_importers, m_messageHeaderPool);
                } catch (NetworkException e) {
                    e.printStackTrace();
                    continue;
                }

                SOP_CREATE.stopDebug();

                if (message != null) {
                    messageReceiver = m_messageReceivers.getReceiver(type, subtype);

                    if (messageReceiver != null) {
                        SOP_EXECUTE.startDebug();

                        messageReceiver.onIncomingMessage(message);

                        SOP_EXECUTE.stopDebug();
                    } else {
                        LOGGER.error("No message receiver was registered for %d, %d! Dropping received messages...",
                                type, subtype);
                    }
                }
            }
        }
    }
}
