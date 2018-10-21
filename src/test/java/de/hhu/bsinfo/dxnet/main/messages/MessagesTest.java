package de.hhu.bsinfo.dxnet.main.messages;

import org.junit.Test;

import de.hhu.bsinfo.dxnet.core.MessageTester;

public class MessagesTest {
    @Test
    public void testBenchmarkMessage() {
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 1));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 2));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 4));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 8));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 16));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 32));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 64));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 128));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 256));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 512));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 1024));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 2048));
        MessageTester.testMessage(new BenchmarkMessage((short) 0, 8192));
    }

    @Test
    public void testBenchmarkRequestResponse() {
        BenchmarkRequest request = new BenchmarkRequest((short) 0, 1);
        MessageTester.testMessage(request);

        BenchmarkResponse response = new BenchmarkResponse(request);
        MessageTester.testMessage(response);
    }

    @Test
    public void testLoginRequestResponse() {
        LoginRequest request = new LoginRequest((short) 0);
        MessageTester.testMessage(request);

        LoginResponse response = new LoginResponse(request);
        MessageTester.testMessage(response);
    }

    @Test
    public void testStartMessage() {
        MessageTester.testMessage(new StartMessage((short) 0));
    }
}
