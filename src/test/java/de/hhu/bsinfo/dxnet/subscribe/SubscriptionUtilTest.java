package de.hhu.bsinfo.dxnet.subscribe;

import de.hhu.bsinfo.dxnet.main.messages.LoginRequest;
import de.hhu.bsinfo.dxnet.main.messages.Messages;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class SubscriptionUtilTest {

    private static final short TEST_DESTINATION = (short) 0xBEEF;

    @Test
    public void findSubscriptions() {
        TestClass t = new TestClass();
        List<Subscription> subscriptions = SubscriptionUtil.findSubscriptions(t);

        assertEquals(1, subscriptions.size());

        LoginRequest request = new LoginRequest();
        Subscription subscription = subscriptions.get(0);
        SubscriberMethod method = subscription.getMethod();
        method.handleMessage(request);

        assertEquals(TEST_DESTINATION, request.getDestination());
    }

    private static class TestClass {

        @Subscribe(type = Messages.DXNETMAIN_MESSAGES_TYPE, subType = Messages.SUBTYPE_LOGIN_REQUEST)
        private void handleLogin(final LoginRequest p_request) {
            p_request.setDestination(TEST_DESTINATION);
        }
    }
}