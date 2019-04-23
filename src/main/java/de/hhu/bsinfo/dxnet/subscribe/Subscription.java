package de.hhu.bsinfo.dxnet.subscribe;

public class Subscription {

    private final Subscribe m_annotation;
    private final SubscriberMethod m_method;
    private final Class<?> m_messageClass;

    public Subscription(Subscribe p_annotation, SubscriberMethod p_method, Class<?> p_messageClass) {
        m_annotation = p_annotation;
        m_method = p_method;
        m_messageClass = p_messageClass;
    }

    public Subscribe getAnnotation() {
        return m_annotation;
    }

    public SubscriberMethod getMethod() {
        return m_method;
    }

    public Class<?> getMessageClass() {
        return m_messageClass;
    }
}
