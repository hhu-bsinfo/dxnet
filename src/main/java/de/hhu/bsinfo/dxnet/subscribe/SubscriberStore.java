package de.hhu.bsinfo.dxnet.subscribe;

import de.hhu.bsinfo.dxnet.MessageReceiver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.locks.ReentrantLock;

public class SubscriberStore {

    private static final Logger LOGGER = LogManager.getFormatterLogger(SubscriberStore.class);

    private final ReentrantLock m_lock = new ReentrantLock();

    private SubscriberMethod[][] m_subscribers;

    public SubscriberMethod getSubscriber(final byte p_type, final byte p_subType) {
        return m_subscribers[p_type][p_subType];
    }

    public void register(final byte p_type, final byte p_subType, final SubscriberMethod p_subscriber) {
        m_lock.lock();
        ensureCapacity(p_type, p_subType);

        if (m_subscribers[p_type][p_subType] != null) {
            LOGGER.warn("Subscriber for [%04d,%04d] is already registered", p_type, p_subType);
        }

        m_subscribers[p_type][p_subType] = p_subscriber;
        m_lock.unlock();
    }

    private void ensureCapacity(final byte p_type, final byte p_subType) {
        if (m_subscribers.length <= p_type) {
            final SubscriberMethod[][] newArray = new SubscriberMethod[p_type + 1][];
            System.arraycopy(m_subscribers, 0, newArray, 0, m_subscribers.length);
            m_subscribers = newArray;
        }

        if (m_subscribers[p_type] == null) {
            m_subscribers[p_type] = new SubscriberMethod[p_subType + 1];
        }

        if (m_subscribers[p_type].length <= p_subType) {
            final SubscriberMethod[] newArray = new SubscriberMethod[p_subType + 1];
            System.arraycopy(m_subscribers[p_type], 0, newArray, 0, m_subscribers[p_type].length);
            m_subscribers[p_type] = newArray;
        }
    }

}
