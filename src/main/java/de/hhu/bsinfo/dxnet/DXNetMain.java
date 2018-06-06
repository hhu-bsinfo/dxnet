/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science,
 * Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxnet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.AbstractPipeIn;
import de.hhu.bsinfo.dxnet.core.Message;
import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxnet.main.messages.BenchmarkMessage;
import de.hhu.bsinfo.dxnet.main.messages.BenchmarkRequest;
import de.hhu.bsinfo.dxnet.main.messages.BenchmarkResponse;
import de.hhu.bsinfo.dxnet.main.messages.LoginRequest;
import de.hhu.bsinfo.dxnet.main.messages.LoginResponse;
import de.hhu.bsinfo.dxnet.main.messages.Messages;
import de.hhu.bsinfo.dxnet.main.messages.StartMessage;
import de.hhu.bsinfo.dxutils.ManifestHelper;
import de.hhu.bsinfo.dxutils.RandomUtils;
import de.hhu.bsinfo.dxutils.StorageUnitGsonSerializer;
import de.hhu.bsinfo.dxutils.TimeUnitGsonSerializer;
import de.hhu.bsinfo.dxutils.serialization.ObjectSizeUtil;
import de.hhu.bsinfo.dxutils.stats.StatisticsManager;
import de.hhu.bsinfo.dxutils.stats.Time;
import de.hhu.bsinfo.dxutils.stats.Value;
import de.hhu.bsinfo.dxutils.unit.StorageUnit;
import de.hhu.bsinfo.dxutils.unit.TimeUnit;
import de.hhu.bsinfo.pt.PerfTimer;

/**
 * DXNet benchmark test application. Use this to run various types of
 * benchmarks like end-to-end, all-to-all, one-to-all, all-to-one
 * using the DXNet subsystem
 * Simple end-to-end example with two nodes:
 * java -Dlog4j.configurationFile=config/log4j.xml -cp
 * lib/gson-2.7.jar:lib/log4j-api-2.7.jar:lib/log4j-core-2.7.jar:dxnet.jar
 * de.hhu.bsinfo.dxnet.DXNetMain config/dxnet.json 0 100000 1000000 1024 1 0 1
 * java -Dlog4j.configurationFile=config/log4j.xml -cp
 * lib/gson-2.7.jar:lib/log4j-api-2.7.jar:lib/log4j-core-2.7.jar:dxnet.jar
 * de.hhu.bsinfo.dxnet.DXNetMain config/dxnet.json 0 100000 1000000 1024 1 1 0
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 20.12.2017
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 20.12.2017
 */
public final class DXNetMain implements MessageReceiver {
    private static final String GIT_COMMIT = "Not Available"; //@GITCOMMIT@
    private static final String BUILD_TYPE = "Not Available"; //@BUILDTYPE@

    private static final Logger LOGGER = LogManager.getFormatterLogger(DXNetMain.class.getSimpleName());

    private static DXNet ms_dxnet;

    private static int ms_printIntervalMs = 1000;
    private static int ms_workload = 0;
    private static long ms_sendCount = 10000;
    private static long ms_recvCount = 10000;
    private static int ms_size = 64;
    private static int ms_threads = 1;
    private static short ms_ownNodeId;
    private static ArrayList<Short> ms_targetNodeIds = new ArrayList<>();

    private static DXNetConfig ms_context;

    private static int ms_messagePayloadSize;

    private static int ms_totalNumNodes;
    private static DXNetNodeMap ms_nodeMap;

    private static boolean ms_isLoginCoordinator;
    private static ReentrantLock ms_loginLock = new ReentrantLock(false);
    private static HashSet<Short> ms_loggedInNodes = new HashSet<>();
    private static volatile boolean ms_startBenchmark = false;

    private static BenchmarkResponse[] ms_responses;
    private static boolean ms_objectPooling;
    private static volatile boolean ms_remoteFinished = false;

    private static volatile long ms_timeStartSend;
    private static volatile long ms_timeStartRecv;
    private static volatile long ms_timeEndReceiver;
    private static AtomicLong ms_messagesReceived = new AtomicLong(0);
    private static AtomicLong ms_messagesSent = new AtomicLong(0);
    private static AtomicLong ms_reqRespTimeouts = new AtomicLong(0);

    public static void main(final String[] p_arguments) {
        Locale.setDefault(new Locale("en", "US"));
        printJVMArgs();
        printCmdArgs(p_arguments);

        System.out.println("Build type: " + BUILD_TYPE);
        System.out.println("Git commit: " + GIT_COMMIT);
        System.out.println("Build date: " + ManifestHelper.getProperty(DXNetMain.class, "BuildDate"));
        System.out.println("Build user: " + ManifestHelper.getProperty(DXNetMain.class, "BuildUser"));
        System.out.println("Cwd: " + System.getProperty("user.dir"));

        processArgs(p_arguments);
        commonSetup();
        setupNodeMappings();
        deviceLoadAndCheck();

        // first workload, to configure remaining parameters
        Supplier[] workloads = new Supplier[] {WorkloadA::new, WorkloadB::new, WorkloadC::new, WorkloadD::new};

        Workload[] threadArray = new Workload[ms_threads];
        for (int i = 0; i < ms_threads; i++) {
            threadArray[i] = (Workload) workloads[ms_workload].get();
            threadArray[i].setName(String.valueOf(i));
            ms_objectPooling = threadArray[i].objectPooling();
        }

        // now setup DXNet which already opens receiving
        setupDXNet();
        coordinateStartupAndWait();

        // depending on the node id, start nodes one by one now.
        // otherwise, this results in extremely fluctuating throughput
        // and latency and not getting any good throughput/latency at all

        System.out.printf("Waiting %d before starting workload...", ms_ownNodeId);
        try {
            Thread.sleep(100 * RandomUtils.getRandomValue(0, 100));
        } catch (InterruptedException ignored) {

        }

        LOGGER.info("Starting workload (%d threads)", ms_threads);
        ProgressThread progressThread = new ProgressThread(1000);

        progressThread.start();

        ms_timeStartSend = System.nanoTime();

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

        if (threadArray.length > 0 && threadArray[0].isWithRequests()) {
            // Wait for all requests to be fulfilled
            while (!ms_dxnet.isRequestMapEmpty()) {
                LockSupport.parkNanos(100);
            }
        }

        long timeEndSender = System.nanoTime();

        if (ms_recvCount != 0) {
            while (!ms_remoteFinished) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        progressThread.shutdown();

        long minRttNs = Long.MAX_VALUE;
        long maxRttNs = 0;

        if (threadArray.length > 0 && threadArray[0].isWithRequests()) {
            for (int i = 0; i < ms_threads; i++) {
                if (threadArray[i].getRttMaxNs() > maxRttNs) {
                    maxRttNs = threadArray[i].getRttMaxNs();
                }

                if (threadArray[i].getRttMinNs() < minRttNs) {
                    minRttNs = threadArray[i].getRttMinNs();
                }
            }
        }

        try {
            // wait a moment before printing results to ensure everything's flushed
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        printResults(timeEndSender - ms_timeStartSend, ms_timeEndReceiver - ms_timeStartRecv,
                ms_sendCount * ms_targetNodeIds.size(), ms_recvCount);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        ms_dxnet.close();

        StatisticsManager.get().stopPeriodicPrinting();
        StatisticsManager.get().printStatistics(System.out, true);
        StatisticsManager.get().printStatisticTables(System.out);

        System.exit(0);
    }

    @Override
    public void onIncomingMessage(final Message p_message) {
        if (p_message.getSubtype() == Messages.SUBTYPE_LOGIN_REQUEST) {
            LoginResponse resp = new LoginResponse((LoginRequest) p_message);

            while (true) {
                try {
                    ms_dxnet.sendMessage(resp);
                } catch (NetworkException ignored) {
                    LockSupport.parkNanos(100);
                    continue;
                }

                break;
            }

            ms_loginLock.lock();

            if (!ms_startBenchmark) {
                System.out.printf("Received login message from %X\n", p_message.getSource());

                ms_loggedInNodes.add(p_message.getSource());

                // -1: Don't count coordinator
                if (ms_loggedInNodes.size() == ms_totalNumNodes - 1) {
                    System.out.println("All nodes have logged in, signaling start");

                    for (Short targetNodeId : ms_loggedInNodes) {
                        StartMessage start = new StartMessage(targetNodeId);

                        try {
                            System.out.printf("Send start message to %X...\n", targetNodeId);
                            ms_dxnet.sendMessage(start);
                        } catch (NetworkException e) {
                            System.out.printf("ERROR sending start message to node %X: %s\n", targetNodeId,
                                    e.getMessage());
                        }
                    }

                    System.out.println("Starting benchmark");
                    ms_startBenchmark = true;
                }
            }

            ms_loginLock.unlock();
        } else if (p_message.getSubtype() == Messages.SUBTYPE_START_MESSAGE) {
            System.out.println("Starting benchmark (start signal)");
            ms_startBenchmark = true;
        } else {
            long count = ms_messagesReceived.incrementAndGet();

            if (count == 1) {
                ms_timeStartRecv = System.nanoTime();
            }

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

            if (count == ms_recvCount) {
                ms_timeEndReceiver = System.nanoTime();
                ms_remoteFinished = true;
            }
        }
    }

    /**
     * Print all cmd args specified on startup
     *
     * @param p_args
     *         Main arguments
     */
    private static void printCmdArgs(final String[] p_args) {
        StringBuilder builder = new StringBuilder();
        builder.append("Cmd arguments: ");

        for (String arg : p_args) {
            builder.append(arg);
            builder.append(' ');
        }

        System.out.println(builder);
    }

    /**
     * Print all JVM args specified on startup
     */
    private static void printJVMArgs() {
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> args = runtimeMxBean.getInputArguments();

        StringBuilder builder = new StringBuilder();
        builder.append("JVM arguments: ");

        for (String arg : args) {
            builder.append(arg);
            builder.append(' ');
        }

        System.out.println(builder);
    }

    private static void processArgs(final String[] p_args) {
        // Parse command line arguments
        if (p_args.length < 1) {
            System.out.println("To generate a default configuration file:");
            System.out.println("Args: <config_file>");
            System.exit(-1);
        }

        loadConfiguration(p_args[0]);

        if (p_args.length < 8) {
            System.out.println("To execute benchmarks with a valid configuration file:");
            System.out.println(
                    "Args: <config_file> <print interval ms> <workload> <send count> <recv count> <size> " +
                            "(full message: header + payload)> <send/app threads> <node id> " +
                            "[send target node ids ...]");
            System.exit(-1);
        }

        ms_printIntervalMs = Integer.parseInt(p_args[1]);
        ms_workload = Integer.parseInt(p_args[2]);
        if (ms_workload < 0 || ms_workload > 3) {
            System.out.println("Invalid workload " + ms_workload + " specified");
            System.exit(-1);
        }

        ms_sendCount = Long.parseLong(p_args[3]);
        ms_recvCount = Long.parseLong(p_args[4]);
        ms_messagePayloadSize = Integer.parseInt(p_args[5]);
        ms_threads = Integer.parseInt(p_args[6]);
        ms_ownNodeId = Short.parseShort(p_args[7]);

        ms_size = ms_messagePayloadSize + ObjectSizeUtil.sizeofCompactedNumber(ms_messagePayloadSize) + 10;

        StringBuilder targets = new StringBuilder();

        for (int i = 8; i < p_args.length; i++) {
            ms_targetNodeIds.add(Short.parseShort(p_args[i]));
            targets.append(p_args[i]);
            targets.append(' ');
        }

        System.out.printf(
                "Parameters: print interval ms %d, workload %d, send count %d (per target), recv count %d (all), " +
                        "size %d (payload size %d), threads %d, own node id 0x%X, targets %s\n", ms_printIntervalMs,
                ms_workload, ms_sendCount, ms_recvCount, ms_size, ms_messagePayloadSize, ms_threads, ms_ownNodeId,
                targets.toString());
    }

    private static void loadConfiguration(final String p_configPath) {
        LOGGER.info("Loading configuration '%s'...", p_configPath);
        ms_context = new DXNetConfig();
        File file = new File(p_configPath);

        Gson gson = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().registerTypeAdapter(
                StorageUnit.class,
                new StorageUnitGsonSerializer()).registerTypeAdapter(TimeUnit.class, new TimeUnitGsonSerializer())
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
            ms_context = gson.fromJson(element, DXNetConfig.class);
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

    private static void commonSetup() {
        LOGGER.debug("Loading jni libs: %s", ms_context.getJNIPath());

        File jniPath = new File(ms_context.getJNIPath());
        File[] files = jniPath.listFiles();

        if (files != null) {
            for (File file : files) {
                LOGGER.info("Loading jni file %s...", file);
                System.load(file.getAbsolutePath());
            }
        }

        switch (ms_context.getPerfTimerForceType().toLowerCase()) {
            case "rdtscp":
                PerfTimer.init(PerfTimer.Type.RDTSCP);
                break;
            case "rdtsc":
                PerfTimer.init(PerfTimer.Type.RDTSC);
                break;
            case "nanotime":
                PerfTimer.init(PerfTimer.Type.SYSTEM_NANO_TIME);
                break;
            default:
                PerfTimer.init(PerfTimer.Type.SYSTEM_NANO_TIME);
                break;
        }

        LOGGER.info("Perf timer type initialized: %s", PerfTimer.getType());

        StatisticsManager.get().setPrintInterval((int) ms_context.getStatisticsManagerPrintInterval().getMs());
    }

    private static void setupNodeMappings() {
        // Set own node ID and register all participating nodes
        ms_context.getCoreConfig().setOwnNodeId(ms_ownNodeId);
        ms_nodeMap = new DXNetNodeMap(ms_context.getCoreConfig().getOwnNodeId());

        // search for own node id mapping
        boolean found = false;
        for (DXNetConfig.NodeEntry entry : ms_context.getNodeList()) {
            if (entry.getNodeId() == ms_ownNodeId) {
                found = true;
            }

            ms_totalNumNodes++;
            ms_nodeMap.addNode(entry.getNodeId(),
                    new InetSocketAddress(entry.getAddress().getIP(), entry.getAddress().getPort()));
        }

        if (!found) {
            LOGGER.error("Could not find node mapping for own node id: 0x%X (%d)", ms_ownNodeId, ms_ownNodeId);
            System.exit(-1);
        }

        found = false;
        for (Short targetNodeId : ms_targetNodeIds) {

            // find in node config
            for (DXNetConfig.NodeEntry entry : ms_context.getNodeList()) {
                if (entry.getNodeId() == targetNodeId) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                LOGGER.warn("Could not find node mapping for target node id: 0x%X (%d), target node ignored",
                        targetNodeId, targetNodeId);
                ms_targetNodeIds.remove(targetNodeId);
            }
        }

        ms_isLoginCoordinator = ms_nodeMap.getOwnNodeID() == 0;
    }

    private static void deviceLoadAndCheck() {
        // init by network device
        if ("Ethernet".equals(ms_context.getCoreConfig().getDevice())) {
            LOGGER.debug("Loading ethernet...");

            ethernetCheckSocketBound();
        } else if ("Infiniband".equals(ms_context.getCoreConfig().getDevice())) {
            LOGGER.debug("Loading infiniband...");
        } else if ("Loopback".equals(ms_context.getCoreConfig().getDevice())) {
            LOGGER.debug("Loading loopback...");
        } else {
            // #if LOGGER >= ERROR
            LOGGER.error("Unknown device %s. Valid options: Ethernet, Infiniband or Loopback.",
                    ms_context.getCoreConfig().getDevice());
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
                        System.out.printf("%s is bound to %s\n", myAddress.getHostAddress(),
                                currentNetworkInterface.getDisplayName());
                        found = true;
                        break outerloop;
                    }
                }
            }
        } catch (final SocketException ignored) {
            System.out.println("Could not get network interfaces for ip confirmation");
        } finally {
            if (!found) {
                System.out.printf("Could not find network interface with address %s\n", myAddress.getHostAddress());
                System.exit(-1);
            }
        }
    }

    private static void setupDXNet() {
        ms_dxnet = new DXNet(ms_context.getCoreConfig(), ms_context.getNIOConfig(), ms_context.getIBConfig(),
                ms_context.getLoopbackConfig(), ms_nodeMap);

        // register coordination messages
        ms_dxnet.registerMessageType(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_LOGIN_REQUEST,
                LoginRequest.class);
        ms_dxnet.registerMessageType(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_LOGIN_RESPONSE,
                LoginResponse.class);
        ms_dxnet.registerMessageType(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_START_MESSAGE,
                StartMessage.class);
        ms_dxnet.register(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_LOGIN_REQUEST, new DXNetMain());
        ms_dxnet.register(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_LOGIN_RESPONSE, new DXNetMain());
        ms_dxnet.register(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_START_MESSAGE, new DXNetMain());

        // Register benchmark message in DXNet
        ms_dxnet.registerMessageType(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_MESSAGE,
                BenchmarkMessage.class);
        ms_dxnet.registerMessageType(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_REQUEST,
                BenchmarkRequest.class);
        ms_dxnet.registerMessageType(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_RESPONSE,
                BenchmarkResponse.class);
        ms_dxnet.register(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_MESSAGE, new DXNetMain());
        ms_dxnet.register(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_REQUEST, new DXNetMain());
        ms_dxnet.register(Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_RESPONSE, new DXNetMain());
    }

    private static void coordinateStartupAndWait() {
        // Loopback doesn't need any startup coordination
        if (ms_context.getCoreConfig().isDeviceLoopback()) {
            return;
        }

        if (!ms_isLoginCoordinator) {
            LoginRequest req = new LoginRequest((short) 0);

            System.out.println("I am login slave, send login message to coordinator");

            // try contacting coordinator until successful
            while (true) {
                try {
                    ms_dxnet.sendSync(req, -1, true);
                    break;
                } catch (NetworkException ignored) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored2) {

                    }
                }
            }

            System.out.println("Logged in at coordinator, waiting...");
        } else {
            // -1: Don't count coordinator (us)
            System.out.printf("I am login coordinator, waiting for %d nodes to login...\n", ms_totalNumNodes - 1);
        }

        // wait until all nodes have logged in and the coordinator sent the start message
        while (!ms_startBenchmark) {
            LockSupport.parkNanos(100);
        }
    }

    private static void printResults(final double p_timeSendNs, final long p_timeRecvNs, final long p_sendTotalMessages,
            final long p_recvTotalMessages) {
        long rttCount = 0;
        double rttMinUs = 0;
        double rttMaxUs = 0;
        double rttAvgUs = 0;
        double rtt95Us = 0;
        double rtt99Us = 0;
        double rtt999Us = 0;

        // we sent requests, print with rtt values and percentiles if available
        if (AbstractPipeIn.SOP_REQ_RESP_RTT.getAvg() != 0 ||
                AbstractPipeIn.SOP_REQ_RESP_RTT_VAL.getAvgValue() != 0) {
            // "benchmark mode" enabled which records percentiles
            if (AbstractPipeIn.SOP_REQ_RESP_RTT.getAvg() != 0) {
                // sort results first
                AbstractPipeIn.SOP_REQ_RESP_RTT.sortValues();

                rttCount = AbstractPipeIn.SOP_REQ_RESP_RTT.getCounter();
                rttMinUs = AbstractPipeIn.SOP_REQ_RESP_RTT.getMin(Time.Prefix.MICRO);
                rttMaxUs = AbstractPipeIn.SOP_REQ_RESP_RTT.getMax(Time.Prefix.MICRO);
                rttAvgUs = AbstractPipeIn.SOP_REQ_RESP_RTT.getAvg(Time.Prefix.MICRO);
                rtt95Us = AbstractPipeIn.SOP_REQ_RESP_RTT.getPercentileScore(0.95f, Time.Prefix.MICRO);
                rtt99Us = AbstractPipeIn.SOP_REQ_RESP_RTT.getPercentileScore(0.99f, Time.Prefix.MICRO);
                rtt999Us = AbstractPipeIn.SOP_REQ_RESP_RTT.getPercentileScore(0.999f, Time.Prefix.MICRO);
            } else {
                // min, max and avg only
                rttCount = AbstractPipeIn.SOP_REQ_RESP_RTT_VAL.getCounter();
                rttMinUs = AbstractPipeIn.SOP_REQ_RESP_RTT_VAL.getMinValue(Value.Prefix.KILO);
                rttMaxUs = AbstractPipeIn.SOP_REQ_RESP_RTT_VAL.getMaxValue(Value.Prefix.KILO);
                rttAvgUs = (long) AbstractPipeIn.SOP_REQ_RESP_RTT_VAL.getAvgValue(Value.Prefix.KILO);
            }
        }

        double timeSendSec = p_timeSendNs / 1000.0 / 1000.0 / 1000.0;
        double timeRecvSec = p_timeRecvNs / 1000.0 / 1000.0 / 1000.0;

        double sendTp =
                p_sendTotalMessages != 0 ? (double) p_sendTotalMessages * ms_size / 1024 / 1024 / timeSendSec : 0;
        double sendTpPayload = p_sendTotalMessages != 0 ?
                (double) p_sendTotalMessages * ms_messagePayloadSize / 1024 / 1024 / timeSendSec : 0;
        double sendTpMMsg = p_sendTotalMessages != 0 ? (double) p_sendTotalMessages / 1000.0 / 1000.0 / timeSendSec : 0;

        double recvTp =
                p_recvTotalMessages != 0 ? (double) p_recvTotalMessages * ms_size / 1024 / 1024 / timeRecvSec : 0;
        double recvTpPayload = p_recvTotalMessages != 0 ?
                (double) p_recvTotalMessages * ms_messagePayloadSize / 1024 / 1024 / timeRecvSec : 0;
        double recvTpMMsg = p_recvTotalMessages != 0 ? (double) p_recvTotalMessages / 1000.0 / 1000.0 / timeRecvSec : 0;

        System.out.printf(
                "=========================================================================================\n" +
                        "[RESULTS]\n" +
                        "[RESULTS PARAMS: Workload=%d, MsgSize=%d, MsgPayloadSize=%d, Threads=%d, MsgHandlers=%d]\n" +
                        "[RESULTS SEND: Runtime=%.3f sec, Msgs=%d, X=%.3f mb/s, XP=%.3f mb/s, XM=%.6f milmsg/s]\n" +
                        "[RESULTS RECV: Runtime=%.3f sec, Msgs=%d, X=%.3f mb/s, XP=%.3f mb/s, XM=%.6f milmsg/s]\n" +
                        "[RESULTS LATENCY: Msgs=%d, Avg=%.3f us, Min=%.3f us, Max=%.3f us, 95th=%.3f us, " +
                        "99th=%.3f us, 99.9th=%.3f us]\n" +
                        "[RESULTS ERRORS: ReqRespTimeouts=%d]\n" +
                        "=========================================================================================\n",
                ms_workload, ms_size, ms_messagePayloadSize, ms_threads,
                ms_context.getCoreConfig().getNumMessageHandlerThreads(),
                timeSendSec, p_sendTotalMessages, sendTp, sendTpPayload, sendTpMMsg,
                timeRecvSec, p_recvTotalMessages, recvTp, recvTpPayload, recvTpMMsg,
                rttCount, rttAvgUs, rttMinUs, rttMaxUs, rtt95Us, rtt99Us, rtt999Us,
                ms_reqRespTimeouts.get());
    }

    private static class Workload extends Thread {
        private boolean m_withRequests;
        private boolean m_objectPooling;

        long m_rttMinNs;
        long m_rttMaxNs;

        Workload(final boolean p_withRequests, final boolean p_objectPooling) {
            m_withRequests = p_withRequests;
            m_objectPooling = p_objectPooling;

            m_rttMinNs = Long.MAX_VALUE;
            m_rttMaxNs = 0;
        }

        long getRttMinNs() {
            return m_rttMinNs;
        }

        long getRttMaxNs() {
            return m_rttMaxNs;
        }

        boolean isWithRequests() {
            return m_withRequests;
        }

        boolean objectPooling() {
            return m_objectPooling;
        }
    }

    // msg + pooling = a
    private static class WorkloadA extends Workload {

        WorkloadA() {
            super(false, true);
        }

        @Override
        public void run() {
            // generate random order when for sending messages to nodes for every thread
            ArrayList<Short> destinationList = new ArrayList<>(ms_targetNodeIds);
            Collections.shuffle(destinationList);

            long messageCount = ms_sendCount / ms_threads;
            if (Integer.parseInt(Thread.currentThread().getName()) == ms_threads - 1) {
                messageCount += ms_sendCount % ms_threads;
            }

            BenchmarkMessage[] messages = new BenchmarkMessage[destinationList.size()];
            for (int i = 0; i < destinationList.size(); i++) {
                messages[i] = new BenchmarkMessage(destinationList.get(i), ms_messagePayloadSize);
            }

            for (int i = 0; i < messageCount; i++) {
                for (int j = 0; j < messages.length; j++) {
                    try {
                        ms_dxnet.sendMessage(messages[j]);

                        ms_messagesSent.incrementAndGet();
                    } catch (NetworkException e) {
                        // repeat until successful
                        --j;
                    }
                }
            }
        }
    }

    // msg + no pooling = b
    private static class WorkloadB extends Workload {

        WorkloadB() {
            super(false, false);
        }

        @Override
        public void run() {
            // generate random order when for sending messages to nodes for every thread
            ArrayList<Short> destinationList = new ArrayList<>(ms_targetNodeIds);
            Collections.shuffle(destinationList);

            long messageCount = ms_sendCount / ms_threads;
            if (Integer.parseInt(Thread.currentThread().getName()) == ms_threads - 1) {
                messageCount += ms_sendCount % ms_threads;
            }

            for (int i = 0; i < messageCount; i++) {
                for (int j = 0; j < destinationList.size(); j++) {
                    try {
                        BenchmarkMessage message = new BenchmarkMessage(destinationList.get(j), ms_messagePayloadSize);
                        ms_dxnet.sendMessage(message);

                        ms_messagesSent.incrementAndGet();
                    } catch (NetworkException e) {
                        // repeat until successful
                        --j;
                    }
                }
            }
        }
    }

    // req + pooling = c
    private static class WorkloadC extends Workload {

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
            // generate random order when for sending messages to nodes for every thread
            ArrayList<Short> destinationList = new ArrayList<>(ms_targetNodeIds);
            Collections.shuffle(destinationList);

            long messageCount = ms_sendCount / ms_threads;
            if (Integer.parseInt(Thread.currentThread().getName()) == ms_threads - 1) {
                messageCount += ms_sendCount % ms_threads;
            }

            BenchmarkRequest[] requests = new BenchmarkRequest[destinationList.size()];
            for (int i = 0; i < requests.length; i++) {
                requests[i] = new BenchmarkRequest(destinationList.get(i), ms_messagePayloadSize);
            }

            for (int i = 0; i < messageCount; i++) {
                for (int j = 0; j < ms_targetNodeIds.size(); j++) {
                    try {
                        requests[j].reuse();

                        ms_dxnet.sendSync(requests[j], -1, true);

                        ms_messagesSent.incrementAndGet();
                    } catch (NetworkException e) {
                        // repeat until successful
                        --j;
                        ms_reqRespTimeouts.incrementAndGet();
                    }
                }
            }
        }
    }

    // req + no pooling = d
    private static class WorkloadD extends Workload {

        WorkloadD() {
            super(true, false);
        }

        @Override
        public void run() {
            // generate random order when for sending messages to nodes for every thread
            ArrayList<Short> destinationList = new ArrayList<>(ms_targetNodeIds);
            Collections.shuffle(destinationList);

            long messageCount = ms_sendCount / ms_threads;
            if (Integer.parseInt(Thread.currentThread().getName()) == ms_threads - 1) {
                messageCount += ms_sendCount % ms_threads;
            }

            for (int i = 0; i < messageCount; i++) {
                for (int j = 0; j < destinationList.size(); j++) {
                    try {
                        BenchmarkRequest request = new BenchmarkRequest(destinationList.get(j), ms_messagePayloadSize);

                        ms_dxnet.sendSync(request, -1, true);

                        ms_messagesSent.incrementAndGet();
                    } catch (NetworkException e) {
                        // repeat until successful
                        --j;
                        ms_reqRespTimeouts.incrementAndGet();
                    }
                }
            }
        }
    }

    private static class ProgressThread extends Thread {
        private volatile boolean m_run = true;
        private int m_intervalMs;

        ProgressThread(final int p_intervalMs) {
            m_intervalMs = p_intervalMs;
        }

        public void shutdown() {
            m_run = false;

            try {
                join();
            } catch (InterruptedException ignored) {

            }
        }

        private long m_prevSent;
        private long m_prevRecv;
        private long m_prevTime = System.nanoTime();

        @Override
        public void run() {
            while (m_run) {
                try {
                    Thread.sleep(m_intervalMs);
                } catch (InterruptedException ignored) {

                }

                long totalMessagesSent = ms_messagesSent.get();
                long totalMessagesRecv = ms_messagesReceived.get();
                long time = System.nanoTime();

                long messagesSentDelta = totalMessagesSent - m_prevSent;
                long messagesRecvDelta = totalMessagesRecv - m_prevRecv;

                long timeDiffSendDelta = ms_timeStartSend != 0 ? time - m_prevTime : 1;
                long timeDiffRecvDelta = ms_timeStartRecv != 0 ? time - m_prevTime : 1;

                long timeDiffSend = ms_timeStartSend != 0 ? time - ms_timeStartSend : 1;
                long timeDiffRecv = ms_timeStartRecv != 0 ? time - ms_timeStartRecv : 1;

                System.out.printf(
                        "[PROGRESS] %d sec [TOTAL: TXM %d%% (%d), RXM %d%% (%d), TXM-RXM-DELTA %d]" +
                                "[AVG: TX=%f, RX=%f, TXP=%f, RXP=%f, TXM=%f, RXM=%f]" +
                                "[CUR: TX=%f, RX=%f, TXP=%f, RXP=%f, TXM=%f, RXM=%f][ReqRespTimeouts=%d]\n",
                        timeDiffSend / 1000 / 1000 / 1000,
                        ms_sendCount != 0 ?
                                (int) ((float) totalMessagesSent / ms_sendCount / ms_targetNodeIds.size() * 100) : 0,
                        totalMessagesSent,
                        ms_recvCount != 0 ? (int) ((float) totalMessagesRecv / ms_recvCount * 100) : 0,
                        totalMessagesRecv,
                        totalMessagesSent - totalMessagesRecv,
                        (double) totalMessagesSent * ms_size / 1024 / 1024 /
                                ((double) timeDiffSend / 1000 / 1000 / 1000),
                        (double) totalMessagesRecv * ms_size / 1024 / 1024 /
                                ((double) timeDiffRecv / 1000 / 1000 / 1000),
                        (double) totalMessagesSent * ms_messagePayloadSize / 1024 / 1024 /
                                ((double) timeDiffSend / 1000 / 1000 / 1000),
                        (double) totalMessagesRecv * ms_messagePayloadSize / 1024 / 1024 /
                                ((double) timeDiffRecv / 1000 / 1000 / 1000),
                        (double) totalMessagesSent / ((double) timeDiffSend / 1000 / 1000 / 1000) / 1000 / 1000,
                        (double) totalMessagesRecv / ((double) timeDiffRecv / 1000 / 1000 / 1000) / 1000 / 1000,
                        (double) messagesSentDelta * ms_size / 1024 / 1024 /
                                ((double) timeDiffSendDelta / 1000 / 1000 / 1000),
                        (double) messagesRecvDelta * ms_size / 1024 / 1024 /
                                ((double) timeDiffRecvDelta / 1000 / 1000 / 1000),
                        (double) messagesSentDelta * ms_messagePayloadSize / 1024 / 1024 /
                                ((double) timeDiffSendDelta / 1000 / 1000 / 1000),
                        (double) messagesRecvDelta * ms_messagePayloadSize / 1024 / 1024 /
                                ((double) timeDiffRecvDelta / 1000 / 1000 / 1000),
                        (double) messagesSentDelta / ((double) timeDiffSendDelta / 1000 / 1000 / 1000) / 1000 / 1000,
                        (double) messagesRecvDelta / ((double) timeDiffRecvDelta / 1000 / 1000 / 1000) / 1000 / 1000,
                        ms_reqRespTimeouts.get());

                // for delta/current values
                m_prevSent = totalMessagesSent;
                m_prevRecv = totalMessagesRecv;
                m_prevTime = time;
            }
        }
    }
}