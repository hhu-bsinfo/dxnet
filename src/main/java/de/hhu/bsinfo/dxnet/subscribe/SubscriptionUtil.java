package de.hhu.bsinfo.dxnet.subscribe;

import de.hhu.bsinfo.dxnet.core.Message;

import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.invoke.MethodHandles.Lookup.*;

public final class SubscriptionUtil {

    private static final String SUBSCRIBER_METHOD_NAME = SubscriberMethod.class.getMethods()[0].getName();

    /**
     * Returns all subscriptions present within the target object.
     *
     * @param p_target The target.
     * @return All subscriptions within the target.
     */
    public static List<Subscription> findSubscriptions(final Object p_target) {
        return Arrays.stream(p_target.getClass().getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Subscribe.class))
                .map(method -> convert(p_target, method))
                .collect(Collectors.toList());
    }

    private static Subscription convert(final Object p_target, final Method p_method) {
        final Class<?> declaringClass = p_method.getDeclaringClass();
        if (p_target.getClass() != declaringClass) {
            throw new IllegalArgumentException(String.format("Method %s does not belong to target's class %s",
                    p_method.getName(), p_target.getClass().getSimpleName()));
        }

        final Class<?>[] parameterTypes = p_method.getParameterTypes();
        if (parameterTypes.length != 1) {
            throw new IllegalSubscriberException("The subscriber method must have exactly one argument of type Message");
        }

        final Class<?> messageClass = parameterTypes[0];
        if (!Message.class.isAssignableFrom(messageClass)) {
            throw new IllegalSubscriberException("The subscriber method's argument type is not a subclass of Message");
        }

        p_method.setAccessible(true);

        try {
            final MethodHandles.Lookup lookup = privateLookupIn(declaringClass);
            final MethodHandle actualMethod = lookup.unreflect(p_method);
            final MethodType actualType = MethodType.methodType(void.class, messageClass);
            final MethodType interfaceType = MethodType.methodType(void.class, Message.class);
            final MethodType callSiteType = MethodType.methodType(SubscriberMethod.class, declaringClass);
            final CallSite site = LambdaMetafactory.metafactory(
                    lookup,
                    SUBSCRIBER_METHOD_NAME,
                    callSiteType,
                    interfaceType,
                    actualMethod,
                    actualType);

            final MethodHandle factory = site.getTarget();
            final SubscriberMethod subscriberMethod = (SubscriberMethod) factory.bindTo(p_target).invokeExact();
            return new Subscription(p_method.getAnnotation(Subscribe.class), subscriberMethod, messageClass);
        } catch (Throwable e) {
            throw new IllegalSubscriberException("Could not create subscriber method", e);
        }
    }

    private static MethodHandles.Lookup privateLookupIn(final Class<?> p_target)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        final Constructor<MethodHandles.Lookup> constructor =
                MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);

        constructor.setAccessible(true);
        int allowedModes = ( PACKAGE | PRIVATE | PROTECTED | PUBLIC );
        return constructor.newInstance(p_target, allowedModes);
    }
}
