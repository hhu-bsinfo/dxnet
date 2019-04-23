package de.hhu.bsinfo.dxnet.subscribe;

import de.hhu.bsinfo.dxnet.core.Message;

@FunctionalInterface
public interface SubscriberMethod<T extends Message> {
    void handleMessage(final T p_message);
}
