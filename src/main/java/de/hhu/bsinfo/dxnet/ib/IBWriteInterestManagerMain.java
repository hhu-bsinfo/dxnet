package de.hhu.bsinfo.dxnet.ib;

import java.util.concurrent.atomic.AtomicLong;

import de.hhu.bsinfo.dxutils.NodeID;
import de.hhu.bsinfo.dxutils.RandomUtils;

/**
 * For testing and debugging the IBWriteInterestManager
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 14.12.2017
 */
public final class IBWriteInterestManagerMain {
    private static AtomicLong ms_dataInterests = new AtomicLong(0);
    private static AtomicLong ms_fcInterests = new AtomicLong(0);

    /**
     * Constructor
     */
    private IBWriteInterestManagerMain() {

    }

    /**
     * Main entry point
     *
     * @param p_args
     *         Cmd args
     */
    public static void main(final String[] p_args) {
        final int numProducers = 12;
        final int timeMs = 60000;

        IBWriteInterestManager writeInterestManager = new IBWriteInterestManager();

        ProducerThread[] producerThreads = new ProducerThread[numProducers];

        for (int i = 0; i < producerThreads.length; i++) {
            producerThreads[i] = new ProducerThread(i, writeInterestManager);
        }

        ConsumerThread consumerThread = new ConsumerThread(0, writeInterestManager);

        System.out.println("Start, runtime " + timeMs + "ms ...");

        consumerThread.start();

        for (ProducerThread thread : producerThreads) {
            thread.start();
        }

        try {
            Thread.sleep(timeMs);
        } catch (InterruptedException ignored) {

        }

        for (ProducerThread thread : producerThreads) {
            thread.shutdown();
        }

        System.out.println("Producer finished");

        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {

        }

        consumerThread.shutdown();

        System.out.println("Finished: " + ms_dataInterests.get() + " | " + ms_fcInterests.get());
        System.out.println(writeInterestManager.toString().substring(0, 100));
    }

    /**
     * Producer thread creating entries
     */
    private static class ProducerThread extends Thread {
        private int m_id;
        private IBWriteInterestManager m_writeInterestManager;

        private volatile boolean m_run;

        /**
         * Constructor
         *
         * @param p_id
         *         If of the thread
         * @param p_writeInterestManager
         *         Reference to WIM instance
         */
        ProducerThread(final int p_id, final IBWriteInterestManager p_writeInterestManager) {
            m_id = p_id;
            m_writeInterestManager = p_writeInterestManager;

            m_run = true;

            setName("Producer-" + p_id);
        }

        /**
         * Shut down the thread
         */
        void shutdown() {
            m_run = false;

            try {
                join();
            } catch (InterruptedException ignored) {

            }
        }

        @Override
        public void run() {
            while (m_run) {
                if (RandomUtils.getRandomValue(0, 1) > 0) {
                    m_writeInterestManager.pushBackDataInterest((short) 0);
                    ms_dataInterests.getAndIncrement();
                } else {
                    m_writeInterestManager.pushBackFcInterest((short) 0);
                    ms_fcInterests.getAndIncrement();
                }
            }
        }
    }

    /**
     * Consumer thread consuming entries
     */
    private static class ConsumerThread extends Thread {
        private int m_id;
        private IBWriteInterestManager m_writeInterestManager;

        private volatile boolean m_run;

        /**
         * Constructor
         *
         * @param p_id
         *         Id of the consumer thread
         * @param p_writeInterestManager
         *         Reference to WIM
         */
        ConsumerThread(final int p_id, final IBWriteInterestManager p_writeInterestManager) {
            m_id = p_id;
            m_writeInterestManager = p_writeInterestManager;

            m_run = true;

            setName("Consumer-" + p_id);
        }

        /**
         * Shut down the thread
         */
        void shutdown() {
            m_run = false;

            try {
                join();
            } catch (InterruptedException ignored) {

            }
        }

        @Override
        public void run() {
            while (m_run) {
                short node = m_writeInterestManager.getNextInterests();

                if (node != NodeID.INVALID_ID) {
                    long interests = m_writeInterestManager.consumeInterests((short) 0);

                    int dataInterests = (int) interests;
                    int fcInterests = (int) (interests >> 32L);

                    if (dataInterests == 0 && fcInterests == 0) {
                        System.out.println("ERROR: No interests available but interest set in queue");
                    } else {
                        if (dataInterests > 0) {
                            ms_dataInterests.getAndAdd(-dataInterests);
                        }

                        if ((int) (interests >> 32L) > 0) {
                            ms_fcInterests.getAndAdd(-fcInterests);
                        }
                    }
                }

            }
        }
    }
}
