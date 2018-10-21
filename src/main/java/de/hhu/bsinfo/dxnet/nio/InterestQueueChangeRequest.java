package de.hhu.bsinfo.dxnet.nio;

import java.util.concurrent.atomic.AtomicInteger;

public class InterestQueueChangeRequest {
    private final AtomicInteger m_changeRequest;

    InterestQueueChangeRequest() {
        m_changeRequest = new AtomicInteger(0);
    }

    boolean applyNewInterest(final int p_interest) {
        while (true) {
            int oldInterest = m_changeRequest.get();

            if (oldInterest == p_interest) {
                return false;
            }

            if (oldInterest == 0) {
                // new interests, have to schedule connection after return

                if (!m_changeRequest.compareAndSet(oldInterest, p_interest)) {
                    continue;
                }

                return true;
            } else {
                // add flags to existing. connection should already be scheduled
                if (!m_changeRequest.compareAndSet(oldInterest, oldInterest | p_interest)) {
                    continue;
                }

                return false;
            }
        }
    }

    int removeInterests() {
        return m_changeRequest.getAndSet(0);
    }
}
