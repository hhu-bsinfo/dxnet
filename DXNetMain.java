/*
 * Copyright (C) 2017 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxnet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import de.hhu.bsinfo.dxutils.stats.ExportStatistics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.Message;
import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxnet.core.messages.BenchmarkMessage;
import de.hhu.bsinfo.dxnet.core.messages.BenchmarkRequest;
import de.hhu.bsinfo.dxnet.core.messages.BenchmarkResponse;
import de.hhu.bsinfo.dxnet.core.messages.Messages;
import de.hhu.bsinfo.dxutils.StorageUnitGsonSerializer;
import de.hhu.bsinfo.dxutils.TimeUnitGsonSerializer;
import de.hhu.bsinfo.dxutils.serialization.ObjectSizeUtil;
import de.hhu.bsinfo.dxutils.stats.PrintStatistics;
import de.hhu.bsinfo.dxutils.unit.StorageUnit;
import de.hhu.bsinfo.dxutils.unit.TimeUnit;

/**
 * Execution: java -Dlog4j.configurationFile=config/log4j.xml -cp lib/gson-2.7.jar:lib/log4j-api-2.7.jar:lib/log4j-core-2.7.jar:dxnet.jar de.hhu.bsinfo.dxnet.DXNetMain config/dxnet.json 0 1000000 1024 2 6 7
 */
public final class DXNetMain implements MessageReceiver {
    private static final Logger LOGGER = LogManager.getFormatterLogger(DXNetMain.class.getSimpleName());

    private static DXNet ms_dxnet;

    private static int ms_workload = 0;
    private static long ms_count = 10000;
    private static int ms_size = 64;
    private static int ms_threads = 1;
    private static short ms_ownNodeId;
    private static ArrayList<Short> ms_targetNodeIds = new ArrayList<>();

    private static DXNetContext ms_context;

    private static DXNetNodeMap ms_nodeMap;

    private static long ms_totalMessages;
    private static BenchmarkResponse[] ms_responses;
    private static boolean ms_objectPooling;
    private static volatile boolean ms_remoteFinished = false;

    private static volatile long ms_timeStart;
    private static volatile long ms_timeEndReceiver;
    private static AtomicLong ms_messagesRecived = new AtomicLong(0);
    private static AtomicLong ms_messagesSent = new AtomicLong(0);

    public static void main(final String[] p_arguments) {
        Locale.setDefault(new Locale("en", "US"));

        processArgs(p_arguments);
        setupNodeMappings();
        deviceLoadAndCheck();

        AbstractWorkload[] workloads = new AbstractWorkload[] {new WorkloadA(), new WorkloadB(), new WorkloadC(), new WorkloadD()};

        // first workload, to configure remaining parameters
        AbstractWorkload workload = workloads[ms_workload];
        ms_objectPooling = workload.objectPooling();

        // now setup DXNet which already opens receiving
        setupDXNet();

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        LOGGER.info("Starting workload (%d threads)", ms_threads);
        Thread[] threadArray = new Thread[ms_threads];
        ProgressThread progressThread = new ProgressThread(1000);

        progressThread.start();

        for (int i = 0; i < ms_threads; i++) {
            threadArray[i] = new Thread(workload);
            threadArray[i].setName(String.valueOf(i));
        }

        ms_timeStart = System.nanoTime();

        for (int i = 0; i < ms_threads; i++) {
            threadArray[i].start();
        }

        for (int i = 0; i < ms_threads; i++) {
            try {
                threadArray[i].join();
            } catch (InterruptedException ignore) {
            }
        }

        LOGGER.info("Workload finished for sender.");

        if (workload.isWithRequests()) {
            // Printing statistics
            while (!ms_dxnet.isRequestMapEmpty()) {
                LockSupport.parkNanos(100);
            }
        }

        long timeEndSender = System.nanoTime();

        while (!ms_remoteFinished) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        progressThread.shutdown();

        PrintStatistics.printStatisticsToOutput(System.out);
        ExportStatistics.writeStatisticsTablesToStdout();
        printResults("SEND", timeEndSender - ms_timeStart);
        printResults("RECV", ms_timeEndReceiver - ms_timeStart);

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        ms_dxnet.close();

        System.exit(0);
    }

    @Override
    public void onIncomingMessage(final Message p_message) {
        if (p_message.getSubtype() == Messages.SUBTYPE_BENCHMARK_REQUEST) {
            BenchmarkResponse response;
            if (ms_objectPooling) {
                response = ms_responses[Integer.parseInt(Thread.currentThread().getName().substring(24)) - 1];
                response.reuse((BenchmarkRequest) p_message, Messages.SUBTYPE_BENCHMARK_RESPONSE);
                response.setDestination(p_message.getSource());
            } else {
                response = new BenchmarkResponse((BenchmarkRequest) p_message);
            }

            try {
                ms_dxnet.sendMessage(response);
            } catch (NetworkException e) {
                e.printStackTrace();
            }
        }

        if (ms_messagesRecived.incrementAndGet() == ms_totalMessages) {
            ms_timeEndReceiver = System.nanoTime();
            ms_remoteFinished = true;
        }
    }

    private static void processArgs(final String[] p_args) {
        // Parse command line arguments
        if (p_args.length < 1) {
            System.out.println("To generate a default configuration file:");
            System.out.println("Args: <config_file>");
            System.exit(-1);
        }

        loadConfiguration(p_args[0]);

        if (p_args.length < 7) {
            System.out.println("To execute benchmarks with a valid configuration file:");
            System.out.println("Args: <config_file> <workload> <count> <size> <send/app threads> <node id> [send target node ids ...]");
            System.exit(-1);
        }

        ms_workload = Integer.parseInt(p_args[1]);
        if (ms_workload < 0 || ms_workload > 3) {
            System.out.println("Invalid workload " + ms_workload + " specified");
            System.exit(-1);
        }

        ms_count = Long.parseLong(p_args[2]);
        ms_size = Integer.parseInt(p_args[3]);
        ms_threads = Integer.parseInt(p_args[4]);
        ms_ownNodeId = Short.parseShort(p_args[5]);

        StringBuilder targets = new StringBuilder();

        for (int i = 6; i < p_args.length; i++) {
            ms_targetNodeIds.add(Short.parseShort(p_args[i]));
            targets.append(p_args[i]);
        }

        System.out.printf("Parameters: workload %d, count %d, size %d, threads %d, own node id 0x%X, targets %s\n", ms_workload, ms_count, ms_size, ms_threads,
                ms_ownNodeId, targets.toString());

        ms_totalMessages = ms_count * ms_targetNodeIds.size();
    }

    private static void loadConfiguration(final String p_configPath) {
        LOGGER.info("Loading configuration '%s'...", p_configPath);
        ms_context = new DXNetContext();
        File file = new File(p_configPath);

        Gson gson = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation()
                .registerTypeAdapter(StorageUnit.class, new StorageUnitGsonSerializer()).registerTypeAdapter(TimeUnit.class, new TimeUnitGsonSerializer())
                .create();

        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    LOGGER.error("Creating new config file %s failed", file);
                    System.exit(-1);
                }
            } catch (final IOException e) {
                LOGGER.error("Creating new config file %s failed: %s", file, e.getMessage());
                System.exit(-1);
            }

            String jsonString = gson.toJson(ms_context);
            try {
                PrintWriter writer = new PrintWriter(file);
                writer.print(jsonString);
                writer.close();
            } catch (final FileNotFoundException e) {
                // we can ignored this here, already checked that
            }

            LOGGER.info("New configuration file created: %s", file);
        }

        JsonElement element = null;
        try {
            element = gson.fromJson(new String(Files.readAllBytes(Paths.get(p_configPath))), JsonElement.class);
        } catch (final Exception e) {
            LOGGER.error("Could not load configuration '%s': %s", p_configPath, e.getMessage());
            System.exit(-1);
        }

        if (element == null) {
            LOGGER.error("Could not load configuration '%s': empty configuration file", p_configPath);
            System.exit(-1);
        }

        try {
            ms_context = gson.fromJson(element, DXNetContext.class);
        } catch (final Exception e) {
            LOGGER.error("Loading configuration '%s' failed: %s", p_configPath, e.getMessage());
            System.exit(-1);
        }

        if (ms_context == null) {
            LOGGER.error("Loading configuration '%s' failed: context null", p_configPath);
            System.exit(-1);
        }

        // Verify configuration values
        if (!ms_context.verify()) {
            System.exit(-1);
        }
    }

    private static void setupNodeMappings() {
        // Set own node ID and register all participating nodes
        ms_context.getCoreConfig().setOwnNodeId(ms_ownNodeId);
        ms_nodeMap = new DXNetNodeMap(ms_context.getCoreConfig().getOwnNodeId());

        // search for own node id mapping
        boolean found = false;
        for (DXNetContext.NodeEntry entry : ms_context.getNodeList()) {
            if (entry.getNodeId() == ms_ownNodeId) {
                ms_nodeMap.addNode(entry.getNodeId(), new InetSocketAddress(entry.getAddress().getIP(), entry.getAddress().getPort()));
                found = true;
                break;
            }
        }

        if (!found) {
            LOGGER.error("Could not find node mapping for own node id: 0x%X (%d)", ms_ownNodeId, ms_ownNodeId);
            System.exit(-1);
        }

        found = false;
        for (Short targetNodeId : ms_targetNodeIds) {

            // find in node config
            for (DXNetContext.NodeEntry entry : ms_context.getNodeList()) {
                if (entry.getNodeId() == targetNodeId) {
                    ms_nodeMap.addNode(entry.getNodeId(), new InetSocketAddress(entry.getAddress().getIP(), entry.getAddress().getPort()));
                    found = true;
                    break;
                }
            }

            if (!found) {
                LOGGER.warn("Could not find node mapping for target node id: 0x%X (%d), target node ignored", targetNodeId, targetNodeId);
                ms_targetNodeIds.remove(targetNodeId);
            }
        }
    }

    private static void deviceLoadAndCheck() {
        // init by network device
        if ("Ethernet".equals(ms_context.getCoreConfig().getDevice())) {
            LOGGER.debug("Loading ethernet...");

            ethernetCheckSocketBound();
        } else if ("Infiniband".equals(ms_context.getCoreConfig().getDevice())) {
            LOGGER.debug("Loading infiniband...");

            File jniPath = new File(ms_context.getJNIPath());
            File[] files = jniPath.listFiles();

            if (files != null) {
                for (File file : files) {
                    LOGGER.debug("Loading jni file %s...", file);
                    System.load(file.getAbsolutePath());
                }
            }
        } else if ("Loopback".equals(ms_context.getCoreConfig().getDevice())) {
            LOGGER.debug("Loading loopback...");
        } else {
            // #if LOGGER >= ERROR
            LOGGER.error("Unknown device %s. Valid options: Ethernet, Infiniband or Loopback.", ms_context.getCoreConfig().getDevice());
            // #endif /* LOGGER >= ERROR */
            System.exit(-1);
        }
    }

    private static void ethernetCheckSocketBound() {
        // Check if given ip address is bound to one of this node's network interfaces
        boolean found = false;
        InetSocketAddress socketAddress = ms_nodeMap.getAddress(ms_ownNodeId);
        InetAddress myAddress = socketAddress.getAddress();
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            outerloop:
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface currentNetworkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> addresses = currentNetworkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress currentAddress = addresses.nextElement();
                    if (myAddress.equals(currentAddress)) {
                        System.out.printf("%s is bound to %s\n", myAddress.getHostAddress(), currentNetworkInterface.getDisplayName());
                        found = true;
                        break outerloop;
                    }
                }
            }
        } catch (final SocketException e1) {
            System.out.printf("Could not get network interfaces for ip confirmation\n");
        } finally {
            if (!found) {
                System.out.printf("Could not find network interface with address %s\n", myAddress.getHostAddress());
                System.exit(-1);
            }
        }
    }

    private static void setupDXNet() {
        ms_dxnet = new DXNet(ms_context.getCoreConfig(), ms_context.getNIOConfig(), ms_context.getIBConfig(), ms_context.getLoopbackConfig(), ms_nodeMap);

        // Register benchmark message in DXNet
        ms_dxnet.registerMessageType(Messages.DEFAULT_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_MESSAGE, BenchmarkMessage.class);
        ms_dxnet.registerMessageType(Messages.DEFAULT_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_REQUEST, BenchmarkRequest.class);
        ms_dxnet.registerMessageType(Messages.DEFAULT_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_RESPONSE, BenchmarkResponse.class);
        ms_dxnet.register(Messages.DEFAULT_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_MESSAGE, new DXNetMain());
        ms_dxnet.register(Messages.DEFAULT_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_REQUEST, new DXNetMain());
        ms_dxnet.register(Messages.DEFAULT_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_RESPONSE, new DXNetMain());
    }

    private static void printResults(final String p_name, final long p_timeDiffNs) {
        System.out.printf("[%s RESULTS]\n" + "[%s WORKLOAD] %d\n" + "[%s MSG SIZE] %d\n" + "[%s THREADS] %d\n" + "[%s MSG HANDLERS] %d\n" +
                        "[%s RUNTIME] %d ms\n" + "[%s TIME PER MESSAGE] %d ns\n" + "[%s THROUGHPUT] %f MB/s\n" + "[%s THROUGHPUT OVERHEAD] %f MB/s\n", p_name, p_name,
                ms_workload, p_name, ms_size, p_name, ms_threads, p_name, ms_context.getCoreConfig().getNumMessageHandlerThreads(), p_name,
                p_timeDiffNs / 1000 / 1000, p_name, p_timeDiffNs / ms_totalMessages, p_name,
                (double) ms_totalMessages * ms_size / 1024 / 1024 / ((double) p_timeDiffNs / 1000 / 1000 / 1000), p_name,
                (double) ms_totalMessages * (ms_size + ObjectSizeUtil.sizeofCompactedNumber(ms_size) + 10) / 1024 / 1024 /
                        ((double) p_timeDiffNs / 1000 / 1000 / 1000));
    }

    private static abstract class AbstractWorkload implements Runnable {
        private boolean m_withRequests;
        private boolean m_objectPooling;

        AbstractWorkload(final boolean p_withRequests, final boolean p_objectPooling) {
            m_withRequests = p_withRequests;
            m_objectPooling = p_objectPooling;
        }

        boolean isWithRequests() {
            return m_withRequests;
        }

        boolean objectPooling() {
            return m_objectPooling;
        }
    }

    // msg + pooling = a
    private static class WorkloadA extends AbstractWorkload {

        WorkloadA() {
            super(false, true);
        }

        @Override
        public void run() {
            long messageCount = ms_count / ms_threads;
            if (Integer.parseInt(Thread.currentThread().getName()) == ms_threads - 1) {
                messageCount += ms_count % ms_threads;
            }

            BenchmarkMessage[] messages = new BenchmarkMessage[ms_targetNodeIds.size()];
            for (int i = 0; i < messages.length; i++) {
                messages[i] = new BenchmarkMessage(ms_targetNodeIds.get(i), ms_size);
            }

            for (int i = 0; i < messageCount; i++) {
                for (int j = 0; j < ms_targetNodeIds.size(); j++) {
                    try {
                        ms_dxnet.sendMessage(messages[j]);
                    } catch (NetworkException e) {
                        // e.printStackTrace();
                        // repeat until successful
                        --j;
                        LockSupport.parkNanos(100);
                    }

                    ms_messagesSent.incrementAndGet();
                }
            }
        }
    }

    // msg + no pooling = b
    private static class WorkloadB extends AbstractWorkload {

        WorkloadB() {
            super(false, false);
        }

        @Override
        public void run() {
            long messageCount = ms_count / ms_threads;
            if (Integer.parseInt(Thread.currentThread().getName()) == ms_threads - 1) {
                messageCount += ms_count % ms_threads;
            }

            for (int i = 0; i < messageCount; i++) {
                for (int j = 0; j < ms_targetNodeIds.size(); j++) {
                    try {
                        BenchmarkMessage message = new BenchmarkMessage(ms_targetNodeIds.get(j), ms_size);
                        ms_dxnet.sendMessage(message);
                    } catch (NetworkException e) {
                        // e.printStackTrace();
                        // repeat until successful
                        --j;
                        LockSupport.parkNanos(100);
                    }

                    ms_messagesSent.incrementAndGet();
                }
            }
        }
    }

    // req + pooling = c
    private static class WorkloadC extends AbstractWorkload {

        WorkloadC() {
            super(true, true);

            int numberOfMessageHandler = ms_context.getCoreConfig().getNumMessageHandlerThreads();
            ms_responses = new BenchmarkResponse[numberOfMessageHandler];
            for (int i = 0; i < numberOfMessageHandler; i++) {
                ms_responses[i] = new BenchmarkResponse();
            }
        }

        @Override
        public void run() {
            long messageCount = ms_count / ms_threads;
            if (Integer.parseInt(Thread.currentThread().getName()) == ms_threads - 1) {
                messageCount += ms_count % ms_threads;
            }

            BenchmarkRequest[] requests = new BenchmarkRequest[ms_targetNodeIds.size()];
            for (int i = 0; i < requests.length; i++) {
                requests[i] = new BenchmarkRequest(ms_targetNodeIds.get(i), ms_size);
            }

            for (int i = 0; i < messageCount; i++) {
                for (int j = 0; j < ms_targetNodeIds.size(); j++) {
                    try {
                        requests[j].reuse();
                        ms_dxnet.sendSync(requests[j], -1, true);
                    } catch (NetworkException e) {
                        // e.printStackTrace();
                        // repeat until successful
                        --j;
                        LockSupport.parkNanos(100);
                    }

                    ms_messagesSent.incrementAndGet();
                }
            }
        }
    }

    // req + no pooling = d
    private static class WorkloadD extends AbstractWorkload {

        WorkloadD() {
            super(true, false);
        }

        @Override
        public void run() {
            long messageCount = ms_count / ms_threads;
            if (Integer.parseInt(Thread.currentThread().getName()) == ms_threads - 1) {
                messageCount += ms_count % ms_threads;
            }

            for (int i = 0; i < messageCount; i++) {
                for (int j = 0; j < ms_targetNodeIds.size(); j++) {
                    try {
                        BenchmarkRequest request = new BenchmarkRequest(ms_targetNodeIds.get(j), ms_size);
                        ms_dxnet.sendSync(request, -1, true);
                    } catch (NetworkException e) {
                        // e.printStackTrace();
                        // repeat until successful
                        --j;
                        LockSupport.parkNanos(100);
                    }

                    ms_messagesSent.incrementAndGet();
                }
            }
        }
    }

    private static class ProgressThread extends Thread {
        private volatile boolean m_run = true;
        private int m_intervalMs;

        public ProgressThread(final int p_intervalMs) {
            m_intervalMs = p_intervalMs;
        }

        public void shutdown() {
            m_run = false;

            try {
                join();
            } catch (InterruptedException ignored) {

            }
        }

        @Override
        public void run() {
            while (m_run) {
                try {
                    Thread.sleep(m_intervalMs);
                } catch (InterruptedException ignored) {

                }

                long messagesSent = ms_messagesSent.get();
                long messagesRecv = ms_messagesRecived.get();

                long timeDiff = System.nanoTime() - ms_timeStart;
                System.out.printf("[PROGRESS] %d sec: Sent %d%% (%d), Recv %d%% (%d), TX %f, RX %f, TXO %f, RXO %f\n", timeDiff / 1000 / 1000 / 1000,
                        (int) ((float) messagesSent / ms_totalMessages * 100), messagesSent, (int) ((float) messagesRecv / ms_totalMessages * 100),
                        messagesRecv, (double) messagesSent * ms_size / 1024 / 1024 / ((double) timeDiff / 1000 / 1000 / 1000),
                        (double) messagesRecv * ms_size / 1024 / 1024 / ((double) timeDiff / 1000 / 1000 / 1000),
                        (double) messagesSent * (ms_size + ObjectSizeUtil.sizeofCompactedNumber(ms_size) + 10) / 1024 / 1024 /
                                ((double) timeDiff / 1000 / 1000 / 1000),
                        (double) messagesRecv * (ms_size + ObjectSizeUtil.sizeofCompactedNumber(ms_size) + 10) / 1024 / 1024 /
                                ((double) timeDiff / 1000 / 1000 / 1000));
            }
        }
    }
}
