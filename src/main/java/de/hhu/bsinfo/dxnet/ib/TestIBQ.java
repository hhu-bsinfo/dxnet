package de.hhu.bsinfo.dxnet.ib;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.hhu.bsinfo.dxnet.core.NetworkRuntimeException;
import de.hhu.bsinfo.dxutils.UnsafeHandler;

public class TestIBQ {
    private long[] m_buffers;

    private final int m_maxCapacityBufferCount;
    private final int m_maxCapacitySize;

    private AtomicInteger m_currentBytes;

    // single producer, single consumer lock free queue (posBack and posFront are synchronized with fences and byte
    // counter)
    private int m_posBack; // 31 bits used (see incrementation)
    private int m_posFront; // 31 bits used (see incrementation)

    private AtomicLong m_queueFullCounter;

    public static class BenchThread extends Thread {
        volatile boolean m_run = false;

        private boolean m_producer;
        private long m_count;
        private TestIBQ m_ibq;

        public BenchThread(boolean producer, long count, TestIBQ ibq) {
            m_producer = producer;
            m_count = count;
            m_ibq = ibq;
        }

        public void startBench() {
            m_run = true;
        }

        public void waitForEnd() {
            try {
                join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            while (!m_run) {

            }

            if (m_producer) {
                long i = 0;
                while (i < m_count) {
                    if (m_ibq.pushBuffer(i)) {
                        i++;
                    }
                }
            } else {
                long i = 0;
                while (i < m_count) {
                    long tmp = m_ibq.popBuffer();

                    if (tmp != -1) {
                        if (tmp != i) {
                            throw new IllegalStateException("Invalid return value: " + tmp + " " + i);
                        }

                        i++;
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        TestIBQ ibq = new TestIBQ(8192, 64 * 1024 * 1024);
        long count = 100000000L;

        BenchThread[] threads = new BenchThread[2];

        threads[0] = new BenchThread(true, count, ibq);
        threads[0].start();
        threads[1] = new BenchThread(false, count, ibq);
        threads[1].start();

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("Running benchmark");

        long start = System.nanoTime();

        for (int i = 0; i < threads.length; i++) {
            threads[i].startBench();
        }

        for (int i = 0; i < threads.length; i++) {
            threads[i].waitForEnd();
        }

        System.out.printf("End bench: %f\n", (System.nanoTime() - start) / 1000.0 / 1000.0 / 1000.0);
    }

    /**
     * Creates an instance of IncomingBufferQueue
     *
     * @param p_maxCapacityBufferCount
     *         the max capacity of buffers (count) for the queue
     * @param p_maxCapacitySize
     *         the max capacity of all buffers aggregated sizes for the queue
     */
    TestIBQ(final int p_maxCapacityBufferCount, final int p_maxCapacitySize) {
        m_maxCapacityBufferCount = p_maxCapacityBufferCount;
        m_maxCapacitySize = p_maxCapacitySize;

        // must be a power of two to work with wrap around
        if ((m_maxCapacityBufferCount & m_maxCapacityBufferCount - 1) != 0) {
            throw new NetworkRuntimeException("Incoming max buffer queue capacity must be a power of 2!");
        }

        m_currentBytes = new AtomicInteger(0);

        m_posBack = 0;
        m_posFront = 0;

        m_queueFullCounter = new AtomicLong(0);

        m_buffers = new long[p_maxCapacityBufferCount];
    }

    /**
     * Returns whether the ring-buffer is full or not.
     */
    public boolean isFull() {
        return m_currentBytes.get() >= m_maxCapacitySize ||
                (m_posBack + m_maxCapacityBufferCount & 0x7FFFFFFF) == m_posFront;
    }

    /**
     * Removes one buffer from queue.
     */
    long popBuffer() {
        UnsafeHandler.getInstance().getUnsafe().loadFence();
        if (m_posBack == m_posFront) {
            // Empty
            return -1;
        }

        int back = m_posBack % m_maxCapacityBufferCount;
        long size = m_buffers[back];

        // & 0x7FFFFFFF kill sign
        m_posBack = m_posBack + 1 & 0x7FFFFFFF;
        m_currentBytes.addAndGet(-1); // Includes storeFence()
        UnsafeHandler.getInstance().getUnsafe().storeFence();

        return size;
    }

    public boolean pushBuffer(final long p_size) {
        int front;

        if (p_size == -1) {

            return true;
        }

        UnsafeHandler.getInstance().getUnsafe().loadFence();

        int curBytes = m_currentBytes.get();
        int posBack = m_posBack;
        int posFront = m_posFront;

        if (curBytes >= m_maxCapacitySize || (posBack + m_maxCapacityBufferCount & 0x7FFFFFFF) == posFront) {
            // Avoid congestion by not allowing more than a predefined number of buffers to be cached for importing

            // TODO for NIO and loopback: needs to be moved
            // TODO keep counter with statistics about full counts

            return false;
        }

        front = m_posFront % m_maxCapacityBufferCount;

        m_buffers[front] = p_size;
        // & 0x7FFFFFFF kill sign
        m_posFront = m_posFront + 1 & 0x7FFFFFFF;
        m_currentBytes.addAndGet(1); // Includes storeFence()
        UnsafeHandler.getInstance().getUnsafe().storeFence();

        return true;
    }
}
