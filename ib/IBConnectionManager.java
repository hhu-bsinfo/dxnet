/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
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

package de.hhu.bsinfo.dxnet.ib;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.MessageHandlers;
import de.hhu.bsinfo.dxnet.NetworkDestinationUnreachableException;
import de.hhu.bsinfo.dxnet.NodeMap;
import de.hhu.bsinfo.dxnet.core.AbstractConnection;
import de.hhu.bsinfo.dxnet.core.AbstractConnectionManager;
import de.hhu.bsinfo.dxnet.core.AbstractExporterPool;
import de.hhu.bsinfo.dxnet.core.CoreConfig;
import de.hhu.bsinfo.dxnet.core.DynamicExporterPool;
import de.hhu.bsinfo.dxnet.core.IncomingBufferQueue;
import de.hhu.bsinfo.dxnet.core.LocalMessageHeaderPool;
import de.hhu.bsinfo.dxnet.core.MessageDirectory;
import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxnet.core.NetworkRuntimeException;
import de.hhu.bsinfo.dxnet.core.RequestMap;
import de.hhu.bsinfo.dxnet.core.StaticExporterPool;
import de.hhu.bsinfo.dxutils.ByteBufferHelper;
import de.hhu.bsinfo.dxutils.NodeID;
import de.hhu.bsinfo.dxutils.stats.StatisticsOperation;
import de.hhu.bsinfo.dxutils.stats.StatisticsRecorderManager;

/**
 * Connection manager for infiniband (note: this is the main class for the IB subsystem in the java space)
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 13.06.2017
 */
public class IBConnectionManager extends AbstractConnectionManager implements MsgrcJNIBinding.CallbackHandler {
    private static final Logger LOGGER = LogManager.getFormatterLogger(IBConnectionManager.class.getSimpleName());

    private static final StatisticsOperation SOP_SEND_NEXT_DATA = StatisticsRecorderManager.getOperation("DXNet-IBConnectionManager", "SendNextData");

    private final CoreConfig m_coreConfig;
    private final IBConfig m_config;
    private final NodeMap m_nodeMap;

    private final MessageDirectory m_messageDirectory;
    private final RequestMap m_requestMap;
    private final IncomingBufferQueue m_incomingBufferQueue;
    private final LocalMessageHeaderPool m_messageHeaderPool;
    private final MessageHandlers m_messageHandlers;

    private AbstractExporterPool m_exporterPool;

    private final IBWriteInterestManager m_writeInterestManager;

    private final boolean[] m_nodeDiscovered;

    private NextWorkPackage m_nextWorkPackage;
    private PrevWorkPackageResults m_prevWorkPackageResults;
    private CompletedWorkList m_completedWorkList;
    private ReceivedPackage m_receivedPackage;

    /**
     * Constructor
     *
     * @param p_coreConfig
     *         Core configuration instance with core config values
     * @param p_config
     *         IB configuration instance with IB specific config values
     * @param p_nodeMap
     *         Node map instance
     * @param p_messageDirectory
     *         Message directory instance
     * @param p_requestMap
     *         Request map instance
     * @param p_incomingBufferQueue
     *         Incoming buffer queue instance
     * @param p_messageHandlers
     *         Message handlers instance
     */
    public IBConnectionManager(final CoreConfig p_coreConfig, final IBConfig p_config, final NodeMap p_nodeMap, final MessageDirectory p_messageDirectory,
            final RequestMap p_requestMap, final IncomingBufferQueue p_incomingBufferQueue, final LocalMessageHeaderPool p_messageHeaderPool,
            final MessageHandlers p_messageHandlers, final boolean p_overprovisioning) {
        super(p_config.getMaxConnections(), p_overprovisioning);

        m_coreConfig = p_coreConfig;
        m_config = p_config;
        m_nodeMap = p_nodeMap;

        m_messageDirectory = p_messageDirectory;
        m_requestMap = p_requestMap;
        m_incomingBufferQueue = p_incomingBufferQueue;
        m_messageHeaderPool = p_messageHeaderPool;
        m_messageHandlers = p_messageHandlers;

        if (p_coreConfig.getExporterPoolType()) {
            m_exporterPool = new StaticExporterPool();
        } else {
            m_exporterPool = new DynamicExporterPool();
        }

        m_writeInterestManager = new IBWriteInterestManager();

        m_nodeDiscovered = new boolean[NodeID.MAX_ID];
    }

    /**
     * Initialize the infiniband subsystem. This calls to the underlying Ibdxnet subsystem and requires the respective library to be loaded
     */
    public void init() {

        // can't call this in the constructor because it relies on the implemented interfaces for callbacks
        if (!MsgrcJNIBinding.init(this, m_config.getPinSendRecvThreads(), m_config.getEnableSignalHandler(), m_config.getStatisticsThreadPrintIntervalMs(),
                m_coreConfig.getOwnNodeId(), (int) m_config.getConnectionCreationTimeout().getMs(), m_config.getMaxConnections(), m_config.getSendQueueSize(),
                m_config.getSharedReceiveQueueSize(), m_config.getSharedSendCompletionQueueSize(), m_config.getSharedReceiveCompletionQueueSize(),
                (int) m_config.getOugoingRingBufferSize().getBytes(), m_config.getIncomingBufferPoolTotalSize().getBytes(),
                (int) m_config.getIncomingBufferSize().getBytes())) {

            // #if LOGGER >= DEBUG
            LOGGER.debug("Initializing ibnet failed, check ibnet logs");
            // #endif /* LOGGER >= DEBUG */

            throw new NetworkRuntimeException("Initializing ibnet failed");
        }

        // this is an ugly way of figuring out which nodes are available on startup. the ib subsystem needs that kind of information to
        // contact the nodes using an ethernet connection to exchange ib connection information
        // if you know a better/faster way of doing this here, be my guest and fix it
        for (int i = 0; i < NodeID.MAX_ID; i++) {
            if (i == (m_coreConfig.getOwnNodeId() & 0xFFFF)) {
                continue;
            }

            InetSocketAddress addr = m_nodeMap.getAddress((short) i);

            if (!"/255.255.255.255".equals(addr.getAddress().toString())) {
                byte[] bytes = addr.getAddress().getAddress();
                int val = (int) (((long) bytes[0] & 0xFF) << 24 | ((long) bytes[1] & 0xFF) << 16 | ((long) bytes[2] & 0xFF) << 8 | bytes[3] & 0xFF);
                MsgrcJNIBinding.addNode(val);
            }
        }
    }

    @Override
    public void close() {
        // #if LOGGER >= DEBUG
        LOGGER.debug("Closing connection manager");
        // #endif /* LOGGER >= DEBUG */

        super.close();

        MsgrcJNIBinding.shutdown();
    }

    @Override
    protected AbstractConnection createConnection(final short p_destination, final AbstractConnection p_existingConnection) throws NetworkException {
        IBConnection connection;

        if (!m_nodeDiscovered[p_destination & 0xFFFF]) {
            throw new NetworkDestinationUnreachableException(p_destination);
        }

        if (m_openConnections == m_maxConnections) {
            // #if LOGGER >= DEBUG
            LOGGER.debug("Connection max (%d) reached, dismissing random connection", m_maxConnections);
            // #endif /* LOGGER >= DEBUG */

            dismissRandomConnection();
        }

        // force connection creation in native subsystem
        int res = MsgrcJNIBinding.createConnection(p_destination);

        if (res != 0) {
            if (res == 1) {
                // #if LOGGER >= DEBUG
                LOGGER.debug("Connection creation (0x%X) time-out. Interval %s ms might be to small", p_destination, m_config.getConnectionCreationTimeout());
                // #endif /* LOGGER >= DEBUG */

                throw new NetworkException("Connection creation timeout occurred");
            } else {
                // #if LOGGER >= ERROR
                LOGGER.error("Connection creation (0x%X) failed", p_destination);
                // #endif /* LOGGER >= ERROR */

                throw new NetworkException("Connection creation failed");
            }
        }

        long sendBufferAddr = MsgrcJNIBinding.getSendBufferAddress(p_destination);

        if (sendBufferAddr == -1) {
            // might happen on disconnect or if connection is not established in the ibnet subsystem
            throw new NetworkDestinationUnreachableException(p_destination);
        }

        // #if LOGGER >= DEBUG
        LOGGER.debug("Node connected 0x%X, ORB native addr 0x%X", p_destination, sendBufferAddr);
        // #endif /* LOGGER >= DEBUG */

        connection = new IBConnection(m_coreConfig.getOwnNodeId(), p_destination, sendBufferAddr, (int) m_config.getOugoingRingBufferSize().getBytes(),
                (int) m_config.getFlowControlWindow().getBytes(), m_config.getFlowControlWindowThreshold(), m_messageHeaderPool, m_messageDirectory,
                m_requestMap, m_exporterPool, m_messageHandlers, m_writeInterestManager);

        connection.setPipeInConnected(true);
        connection.setPipeOutConnected(true);

        m_openConnections++;

        return connection;
    }

    @Override
    protected void closeConnection(final AbstractConnection p_connection, final boolean p_removeConnection) {
        // #if LOGGER >= DEBUG
        LOGGER.debug("Closing connection 0x%X", p_connection.getDestinationNodeID());
        // #endif /* LOGGER >= DEBUG */

        p_connection.setPipeInConnected(false);
        p_connection.setPipeOutConnected(false);

        AbstractConnection tmp = m_connections[p_connection.getDestinationNodeID() & 0xFFFF];
        if (p_connection.equals(tmp)) {
            p_connection.close(p_removeConnection);
            m_connections[p_connection.getDestinationNodeID() & 0xFFFF] = null;
            m_openConnections--;
        }

        // Trigger failure handling for remote node over faulty connection
        if (m_listener != null) {
            m_listener.connectionLost(p_connection.getDestinationNodeID());
        }
    }

    @Override
    public void nodeDiscovered(final short p_nodeId) {
        // #if LOGGER >= DEBUG
        LOGGER.debug("Node discovered 0x%X", p_nodeId);
        // #endif /* LOGGER >= DEBUG */

        m_nodeDiscovered[p_nodeId & 0xFFFF] = true;
    }

    @Override
    public void nodeInvalidated(final short p_nodeId) {
        // #if LOGGER >= DEBUG
        LOGGER.debug("Node invalidated 0x%X", p_nodeId);
        // #endif /* LOGGER >= DEBUG */

        m_nodeDiscovered[p_nodeId & 0xFFFF] = false;
    }

    @Override
    public void nodeDisconnected(final short p_nodeId) {
        // #if LOGGER >= DEBUG
        LOGGER.debug("Node disconnected 0x%X", p_nodeId);
        // #endif /* LOGGER >= DEBUG */

        closeConnection(m_connections[p_nodeId & 0xFFFF], true);
    }

    @Override
    public void received(long p_recvPackage) {
        try {
            // wrap on the first callback, native address is always the same
            if (m_receivedPackage == null) {
                m_receivedPackage = new ReceivedPackage(p_recvPackage, m_config.getSharedReceiveQueueSize());

                // for debugging purpose
                Thread.currentThread().setName("IBRecv-native");
            }

            int receiveCount = m_receivedPackage.getCount();

            // #if LOGGER >= TRACE
            LOGGER.trace("Received %d", receiveCount);
            // #endif /* LOGGER >= TRACE */

            for (int i = 0; i < receiveCount; i++) {
                short sourceNodeId = m_receivedPackage.getSourceNodeId(i);
                short fcData = m_receivedPackage.getFcData(i);
                long ptrDataHandle = m_receivedPackage.getData(i);
                long ptrData = m_receivedPackage.getDataRaw(i);
                int dataLength = m_receivedPackage.getDataLength(i);

                // sanity checks
                if (sourceNodeId == NodeID.INVALID_ID || fcData == 0 && (ptrDataHandle == 0 || ptrData == 0 || dataLength == 0)) {
                    // #if LOGGER >= ERROR
                    LOGGER.error("Illegal state, invalid receive package: 0x%X %d 0x%X 0x%X %d", sourceNodeId, fcData, ptrDataHandle, ptrData, dataLength);
                    // #endif /* LOGGER >= ERROR */

                    // skip package
                    continue;
                }

                IBConnection connection;

                try {
                    connection = (IBConnection) getConnection(sourceNodeId);
                } catch (final NetworkException e) {
                    // #if LOGGER >= ERROR
                    LOGGER.error("Getting connection for recv of node 0x%X failed", sourceNodeId, e);
                    // #endif /* LOGGER >= ERROR */

                    // if that happens we lose data which is quite bad...I don't see any proper fix for this atm
                    continue;
                }

                if (fcData > 0) {
                    connection.getPipeIn().handleFlowControlData((byte) fcData);
                }

                if (dataLength > 0) {
                    // TODO batch/multi push buffers?
                    m_incomingBufferQueue.pushBuffer(connection, null, ptrDataHandle, ptrData, dataLength);
                }
            }
        } catch (Exception e) {
            // print error because we disabled exception handling when executing jni calls

            // #if LOGGER >= ERROR
            LOGGER.error("received unhandled exception", e);
            // #endif /* LOGGER >= ERROR */
        }
    }

    @Override
    public void getNextDataToSend(final long p_nextWorkPackage, final long p_prevResults, final long p_completionList) {
        try {
            // FIXME see fixme above for leave
            // #ifdef STATISTICS
            //SOP_SEND_NEXT_DATA.enter();
            // #endif /* STATISTICS */

            processPrevResults(p_prevResults);
            processSendCompletions(p_completionList);
            prepareNextDataToSend(p_nextWorkPackage);

            // FIXME getting a nullptr exception here when turning on exception checking on env return in native handler
            // #ifdef STATISTICS
            //SOP_SEND_NEXT_DATA.leave();
            // #endif /* STATISTICS */
        } catch (Exception e) {
            // print error because we disabled exception handling when executing jni calls

            // #if LOGGER >= ERROR
            LOGGER.error("getNextDataToSend unhandled exception", e);
            // #endif /* LOGGER >= ERROR */
        }
    }

    // evaluate processing results of the previous work package
    private void processPrevResults(final long p_prevResults) {
        // wrap on the first callback, native address is always the same
        if (m_prevWorkPackageResults == null) {
            m_prevWorkPackageResults = new PrevWorkPackageResults(p_prevResults);

            // for debugging purpose
            Thread.currentThread().setName("IBSend-native");
        }

        short nodeId = m_prevWorkPackageResults.getNodeId();

        if (nodeId != NodeID.INVALID_ID) {
            int numBytesPosted = m_prevWorkPackageResults.getNumBytesPosted();
            int numBytesNotPosted = m_prevWorkPackageResults.getNumBytesNotPosted();
            byte fcDataPosted = m_prevWorkPackageResults.getFcDataPosted();
            byte fcDataNotPosted = m_prevWorkPackageResults.getFcDataNotPosted();

            try {
                IBConnection prevConnection = (IBConnection) getConnection(nodeId);

                prevConnection.getPipeOut().dataSendPosted(numBytesPosted);
                prevConnection.getPipeOut().flowControlDataSendPosted(fcDataPosted);
            } catch (final NetworkException e) {
                // #if LOGGER >= ERROR
                LOGGER.error("Getting connection 0x%X for processing prev results failed", nodeId);
                // #endif /* LOGGER >= ERROR */
            }

            // SendThread could not process all bytes because the queue
            // was full. don't lose the data interest because there is
            // still data to send

            if (numBytesNotPosted > 0) {
                m_writeInterestManager.pushBackDataInterest(nodeId);
            }

            if (fcDataNotPosted > 0) {
                m_writeInterestManager.pushBackFcInterest(nodeId);
            }
        }
    }

    private void processSendCompletions(final long p_completionList) {
        // wrap on first call
        if (m_completedWorkList == null) {
            m_completedWorkList = new CompletedWorkList(p_completionList, m_config.getMaxConnections());
        }

        // process callback parameters
        int numItems = m_completedWorkList.getNumNodes();

        if (numItems > 0) {
            // #if LOGGER >= TRACE
            LOGGER.trace("processSendCompletions, numItems %d", numItems);
            // #endif /* LOGGER >= TRACE */

            // also notify that previous data has been processed (if connection is still available)
            for (int i = 0; i < numItems; i++) {
                short nodeId = m_completedWorkList.getNodeId(i);
                int processedBytes = m_completedWorkList.getNumBytesWritten(nodeId);
                byte processedFcData = m_completedWorkList.getFcDataWritten(nodeId);

                try {
                    IBConnection prevConnection = (IBConnection) getConnection(nodeId);

                    prevConnection.getPipeOut().dataSendConfirmed(processedBytes);
                    prevConnection.getPipeOut().flowControlDataSendConfirmed(processedFcData);
                } catch (final NetworkException e) {
                    // #if LOGGER >= ERROR
                    LOGGER.error("Getting connection 0x%X for processing work completions failed", nodeId);
                    // #endif /* LOGGER >= ERROR */
                }
            }
        }
    }

    private void prepareNextDataToSend(final long p_nextWorkPackage) {
        if (m_nextWorkPackage == null) {
            m_nextWorkPackage = new NextWorkPackage(p_nextWorkPackage);
        }

        m_nextWorkPackage.reset();

        // poll for next interest
        short nodeId = m_writeInterestManager.getNextInterests();

        // no data available
        if (nodeId == NodeID.INVALID_ID) {
            // TODO move parking to java space to reduce latency if no completions to poll
            //                if (m_waitTimerStartNs == 0) {
            //                    m_waitTimerStartNs = System.nanoTime();
            //                }
            //
            //                double waitTimeMs = (System.nanoTime() - m_waitTimerStartNs) / 1000.0 / 1000.0;
            //
            //                if (waitTimeMs >= 100.0 && waitTimeMs < 1000.0) {
            //                    Thread.yield();
            //                } else if (waitTimeMs >= 1000.0) {
            //                    LockSupport.parkNanos(1);
            //                    // return to allow the send thread to shut down (on subsystem shutdown)
            //                    return 0;
            //                }
            //
            //                continue;

            return;
        }

        // #if LOGGER >= TRACE
        LOGGER.trace("Next write interests on node 0x%X", nodeId);
        // #endif /* LOGGER >= TRACE */

        IBConnection connection;

        try {
            connection = (IBConnection) getConnection(nodeId);
        } catch (final NetworkException ignored) {
            m_writeInterestManager.nodeDisconnected(nodeId);
            return;
        }

        long interests = m_writeInterestManager.consumeInterests(nodeId);

        // interest queue and interest count MUST stay on sync. otherwise, something's not right with the
        // interest manager (bug)
        if (interests == 0) {
            throw new IllegalStateException("No interests available but interest manager has write interest set");
        }

        // sets the current work request valid
        m_nextWorkPackage.setNodeId(nodeId);

        int dataInterests = (int) interests;
        int fcInterests = (int) (interests >> 32L);
        boolean nothingToSend = true;

        // process data interests
        if (dataInterests > 0) {
            long pos = connection.getPipeOut().getNextBuffer();
            int relPosFrontRel = (int) (pos >> 32 & 0x7FFFFFFF);
            int relPosBackRel = (int) (pos & 0x7FFFFFFF);

            if (relPosFrontRel != relPosBackRel) {
                // relative position of data start in buffer
                m_nextWorkPackage.setPosBackRel(relPosBackRel);
                // relative position of data end in buffer
                m_nextWorkPackage.setPosFrontRel(relPosFrontRel);

                // #if LOGGER >= TRACE
                LOGGER.trace("Next data write on node 0x%X, relPosBackRel %d, relPosFrontRel %d", nodeId, relPosBackRel, relPosFrontRel);
                // #endif /* LOGGER >= TRACE */

                nothingToSend = false;
            } else {
                // we got an interest but no data is available because the data was already sent with the previous
                // interest (non harmful data race between ORB and interest manager)
            }
        }

        // process flow control interests
        if (fcInterests > 0) {
            byte fcData = connection.getPipeOut().getFlowControlData();

            if (fcData > 0) {
                m_nextWorkPackage.setFlowControlData(fcData);

                // #if LOGGER >= TRACE
                LOGGER.trace("Next flow control write on node 0x%X, fc data %d", nodeId, fcData);
                // #endif /* LOGGER >= TRACE */

                nothingToSend = false;
            } else {
                // and again, we got an interest but no FC data is available because the FC data was already sent with the previous
                // interest (non harmful data race between ORB/flow control and interest manager)
            }
        }

        if (nothingToSend) {
            m_nextWorkPackage.reset();
        }
    }

    private class NextWorkPackage {
        private static final int SIZE_FIELD_POS_BACK_REL = Integer.BYTES;
        private static final int SIZE_FIELD_POS_FRONT_REL = Integer.BYTES;
        private static final int SIZE_FIELD_FLOW_CONTROL_DATA = Byte.BYTES;
        private static final int SIZE_FIELD_NODE_ID = Short.BYTES;

        private static final int SIZE = SIZE_FIELD_POS_BACK_REL + SIZE_FIELD_POS_FRONT_REL + SIZE_FIELD_FLOW_CONTROL_DATA + SIZE_FIELD_NODE_ID;

        private static final int IDX_POS_BACK_REL = 0;
        private static final int IDX_POS_FRONT_REL = IDX_POS_BACK_REL + SIZE_FIELD_POS_BACK_REL;
        private static final int IDX_FLOW_CONTROL_DATA = IDX_POS_FRONT_REL + SIZE_FIELD_POS_FRONT_REL;
        private static final int IDX_NODE_ID = IDX_FLOW_CONTROL_DATA + SIZE_FIELD_FLOW_CONTROL_DATA;

        //    struct NextWorkPackage
        //    {
        //        uint32_t m_posBackRel;
        //        uint32_t m_posFrontRel;
        //        uint8_t m_flowControlData;
        //        con::NodeId m_nodeId;
        //    } __attribute__((packed));
        private ByteBuffer m_struct;

        public NextWorkPackage(final long p_addr) {
            m_struct = ByteBufferHelper.wrap(p_addr, SIZE);
            m_struct.order(ByteOrder.nativeOrder());
        }

        public void reset() {
            setPosBackRel(0);
            setPosFrontRel(0);
            setFlowControlData((byte) 0);
            setNodeId(NodeID.INVALID_ID);
        }

        public void setPosBackRel(final int p_pos) {
            if (p_pos < 0) {
                throw new IllegalStateException("NextWorkPackage posBackRel < 0: " + p_pos);
            }

            m_struct.putInt(IDX_POS_BACK_REL, p_pos);
        }

        public void setPosFrontRel(final int p_pos) {
            if (p_pos < 0) {
                throw new IllegalStateException("NextWorkPackage posFrontRel < 0: " + p_pos);
            }

            m_struct.putInt(IDX_POS_FRONT_REL, p_pos);
        }

        public void setFlowControlData(final byte p_data) {
            if (p_data < 0) {
                throw new IllegalStateException("NextWorkPackage fcData < 0: " + p_data);
            }

            m_struct.put(IDX_FLOW_CONTROL_DATA, p_data);
        }

        public void setNodeId(final short p_nodeId) {
            m_struct.putShort(IDX_NODE_ID, p_nodeId);
        }
    }

    private class PrevWorkPackageResults {
        private static final int SIZE_FIELD_NODE_ID = Short.BYTES;
        private static final int SIZE_FIELD_NUM_BYTES_POSTED = Integer.BYTES;
        private static final int SIZE_FIELD_NUM_BYTES_NOT_POSTED = Integer.BYTES;
        private static final int SIZE_FIELD_FC_DATA_POSTED = Byte.BYTES;
        private static final int SIZE_FIELD_FC_DATA_NOT_POSTED = Byte.BYTES;

        private static final int SIZE =
                SIZE_FIELD_NODE_ID + SIZE_FIELD_NUM_BYTES_POSTED + SIZE_FIELD_NUM_BYTES_NOT_POSTED + SIZE_FIELD_FC_DATA_POSTED + SIZE_FIELD_FC_DATA_NOT_POSTED;

        private static final int IDX_NODE_ID = 0;
        private static final int IDX_NUM_BYTES_POSTED = IDX_NODE_ID + SIZE_FIELD_NODE_ID;
        private static final int IDX_NUM_BYTES_NOT_POSTED = IDX_NUM_BYTES_POSTED + SIZE_FIELD_NUM_BYTES_POSTED;
        private static final int IDX_FC_DATA_POSTED = IDX_NUM_BYTES_NOT_POSTED + SIZE_FIELD_NUM_BYTES_NOT_POSTED;
        private static final int IDX_FC_DATA_NOT_POSTED = IDX_FC_DATA_POSTED + SIZE_FIELD_FC_DATA_POSTED;

        //    struct PrevWorkPackageResults
        //    {
        //        con::NodeId m_nodeId;
        //        uint32_t m_numBytesPosted;
        //        uint32_t m_numBytesNotPosted;
        //        uint8_t m_fcDataPosted;
        //        uint8_t m_fcDataNotPosted;
        //    } __attribute__((packed));
        private ByteBuffer m_struct;

        public PrevWorkPackageResults(final long p_addr) {
            m_struct = ByteBufferHelper.wrap(p_addr, SIZE);
            m_struct.order(ByteOrder.nativeOrder());
        }

        public short getNodeId() {
            return m_struct.getShort(IDX_NODE_ID);
        }

        public int getNumBytesPosted() {
            int tmp = m_struct.getInt(IDX_NUM_BYTES_POSTED);

            if (tmp < 0) {
                throw new IllegalStateException("PrevWorkPackageResults numBytesPosted < 0: " + tmp);
            }

            return tmp;
        }

        public int getNumBytesNotPosted() {
            int tmp = m_struct.getInt(IDX_NUM_BYTES_NOT_POSTED);

            if (tmp < 0) {
                throw new IllegalStateException("PrevWorkPackageResults numBytesNotPosted < 0: " + tmp);
            }

            return tmp;
        }

        public byte getFcDataPosted() {
            byte tmp = m_struct.get(IDX_FC_DATA_POSTED);

            if (tmp < 0) {
                throw new IllegalStateException("PrevWorkPackageResults fcDataPosted < 0: " + tmp);
            }

            return tmp;
        }

        public byte getFcDataNotPosted() {
            byte tmp = m_struct.get(IDX_FC_DATA_NOT_POSTED);

            if (tmp < 0) {
                throw new IllegalStateException("PrevWorkPackageResults fcDataNotPosted < 0: " + tmp);
            }

            return tmp;
        }
    }

    private class CompletedWorkList {
        private static final int SIZE_FIELD_NUM_NODES = Short.BYTES;
        private static final int SIZE_FIELD_NUM_BYTES_WRITTEN = Integer.BYTES;
        private static final int SIZE_FIELD_NUM_BYTES_WRITTEN_ARRAY = SIZE_FIELD_NUM_BYTES_WRITTEN * 0xFFFF;
        private static final int SIZE_FIELD_FC_DATA_WRITTEN = Byte.BYTES;
        private static final int SIZE_FIELD_FC_DATA_WRITTEN_ARRAY = SIZE_FIELD_FC_DATA_WRITTEN * 0xFFFF;
        private static final int SIZE_FIELD_NODE_ID = Short.BYTES;
        private final int SIZE_FIELD_NODE_IDS_ARRAY;

        // depends on max num connections, must be initializated in the constructor
        private final int SIZE;

        private static final int IDX_NUM_ITEMS = 0;
        private static final int IDX_BYTES_WRITTEN = IDX_NUM_ITEMS + SIZE_FIELD_NUM_NODES;
        private static final int IDX_FC_DATA_WRITTEN = IDX_BYTES_WRITTEN + SIZE_FIELD_NUM_BYTES_WRITTEN_ARRAY;
        private static final int IDX_NODE_IDS = IDX_FC_DATA_WRITTEN + SIZE_FIELD_FC_DATA_WRITTEN_ARRAY;

        //    struct CompletedWorkList
        //    {
        //        uint16_t m_numNodes;
        //        uint32_t m_numBytesWritten[con::NODE_ID_MAX_NUM_NODES];
        //        uint8_t m_fcDataWritten[con::NODE_ID_MAX_NUM_NODES];
        //        con::NodeId m_nodeIds[];
        //    } __attribute__((packed));
        private ByteBuffer m_struct;

        public CompletedWorkList(final long p_addr, final int p_numNodes) {
            SIZE_FIELD_NODE_IDS_ARRAY = SIZE_FIELD_NODE_ID * p_numNodes;
            SIZE = SIZE_FIELD_NUM_NODES + SIZE_FIELD_NUM_BYTES_WRITTEN_ARRAY + SIZE_FIELD_FC_DATA_WRITTEN_ARRAY + SIZE_FIELD_NODE_ID;

            m_struct = ByteBufferHelper.wrap(p_addr, SIZE);
            m_struct.order(ByteOrder.nativeOrder());
        }

        public int getNumNodes() {
            return m_struct.getShort(IDX_NUM_ITEMS) & 0xFFFF;
        }

        public int getNumBytesWritten(final int p_idx) {
            int tmp = m_struct.getInt(IDX_BYTES_WRITTEN + p_idx * SIZE_FIELD_NUM_BYTES_WRITTEN);

            if (tmp < 0) {
                throw new IllegalStateException("CompletedWorkList bytesWritten < 0: " + tmp);
            }

            return tmp;
        }

        public byte getFcDataWritten(final int p_idx) {
            byte tmp = m_struct.get(IDX_FC_DATA_WRITTEN + p_idx * SIZE_FIELD_FC_DATA_WRITTEN);

            if (tmp < 0) {
                throw new IllegalStateException("CompletedWorkList fcData < 0: " + tmp);
            }

            return tmp;
        }

        public short getNodeId(final int p_idx) {
            return m_struct.getShort(IDX_NODE_IDS + p_idx * SIZE_FIELD_NODE_ID);
        }
    }

    private class ReceivedPackage {
        private static final int SIZE_FIELD_COUNT = Integer.BYTES;
        private final int SIZE_FIELD_ENTRIES_ARRAY;

        private static final int SIZE_FIELD_ENTRY_SOURCE_NODE_ID = Short.BYTES;
        private static final int SIZE_FIELD_ENTRY_FC_DATA = Byte.BYTES;
        private static final int SIZE_FIELD_ENTRY_DATA = Long.BYTES;
        private static final int SIZE_FIELD_ENTRY_DATA_RAW = Long.BYTES;
        private static final int SIZE_FIELD_DATA_LENGTH = Integer.BYTES;

        private static final int SIZE_ENTRY_STRUCT =
                SIZE_FIELD_ENTRY_SOURCE_NODE_ID + SIZE_FIELD_ENTRY_FC_DATA + SIZE_FIELD_ENTRY_DATA + SIZE_FIELD_ENTRY_DATA_RAW + SIZE_FIELD_DATA_LENGTH;

        // depends on the shared recv queue size, must be initializated in the constructor
        private final int SIZE;

        private static final int IDX_COUNT = 0;
        private static final int IDX_ENTRIES = IDX_COUNT + SIZE_FIELD_COUNT;

        private static final int IDX_ENTRY_SOURCE_NODE_ID = 0;
        private static final int IDX_ENTRY_FC_DATA = IDX_ENTRY_SOURCE_NODE_ID + SIZE_FIELD_ENTRY_SOURCE_NODE_ID;
        private static final int IDX_ENTRY_PTR_DATA = IDX_ENTRY_FC_DATA + SIZE_FIELD_ENTRY_FC_DATA;
        private static final int IDX_ENTRY_PTR_DATA_RAW = IDX_ENTRY_PTR_DATA + SIZE_FIELD_ENTRY_DATA;
        private static final int IDX_ENTRY_DATA_LENGTH = IDX_ENTRY_PTR_DATA_RAW + SIZE_FIELD_ENTRY_DATA_RAW;

        //    struct ReceivedPackage
        //    {
        //        uint32_t m_count = 0;
        //
        //        struct Entry {
        //            con::NodeId m_sourceNodeId;
        //            uint8_t m_fcData;
        //            core::IbMemReg* m_data;
        //            void* m_dataRaw;
        //            uint32_t m_dataLength;
        //        } __attribute__((__packed__)) m_entries[];
        //    } __attribute__((__packed__));
        private ByteBuffer m_struct;

        public ReceivedPackage(final long p_addr, final int p_maxCount) {
            SIZE_FIELD_ENTRIES_ARRAY = SIZE_ENTRY_STRUCT * p_maxCount;
            SIZE = SIZE_FIELD_COUNT + SIZE_FIELD_ENTRIES_ARRAY;

            m_struct = ByteBufferHelper.wrap(p_addr, SIZE);
            m_struct.order(ByteOrder.nativeOrder());
        }

        public int getCount() {
            int tmp = m_struct.getInt(IDX_COUNT);

            if (tmp < 0) {
                throw new IllegalStateException("RecvPackage count < 0: " + tmp);
            }

            return tmp;
        }

        public short getSourceNodeId(final int p_idx) {
            return m_struct.getShort(IDX_ENTRIES + p_idx * SIZE_ENTRY_STRUCT + IDX_ENTRY_SOURCE_NODE_ID);
        }

        public byte getFcData(final int p_idx) {
            byte tmp = m_struct.get(IDX_ENTRIES + p_idx * SIZE_ENTRY_STRUCT + IDX_ENTRY_FC_DATA);

            if (tmp < 0) {
                throw new IllegalStateException("RecvPackage fcData < 0: " + tmp);
            }

            return tmp;
        }

        public long getData(final int p_idx) {
            return m_struct.getLong(IDX_ENTRIES + p_idx * SIZE_ENTRY_STRUCT + IDX_ENTRY_PTR_DATA);
        }

        public long getDataRaw(final int p_idx) {
            return m_struct.getLong(IDX_ENTRIES + p_idx * SIZE_ENTRY_STRUCT + IDX_ENTRY_PTR_DATA_RAW);
        }

        public int getDataLength(final int p_idx) {
            int tmp = m_struct.getInt(IDX_ENTRIES + p_idx * SIZE_ENTRY_STRUCT + IDX_ENTRY_DATA_LENGTH);

            if (tmp < 0) {
                throw new IllegalStateException("RecvPackage data length < 0: " + tmp);
            }

            return tmp;
        }
    }
}
