package de.hhu.bsinfo.dxnet.ib;

/**
 * Binding for native library calling the Msgrc subsystem of Ibdxnet.
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 31.01.2018
 */
public final class MsgrcJNIBinding {

    /**
     * Handler for callbacks from native to java space.
     */
    public interface CallbackHandler {

        /**
         * Called when a new node was discovered (but not connection established, yet)
         *
         * @param p_nodeId
         *         Node id discovered
         */
        void nodeDiscovered(final short p_nodeId);

        /**
         * Called when a node is invalidated
         *
         * @param p_nodeId
         *         Node id of invalidated node
         */
        void nodeInvalidated(final short p_nodeId);

        /**
         * Called when a node disconnected, e.g. the infiniband connection is no longer available
         *
         * @param p_nodeId
         *         Node id of disconnected node
         */
        void nodeDisconnected(final short p_nodeId);

        /**
         * Called by the native receive thread once data was received from one or multiple remote nodes
         *
         * @param p_incomingRingBuffer
         *         Native pointer to a struct with the data received (IncomingRingBuffer)
         * @return The number of processed recv packages
         */
        int received(final long p_incomingRingBuffer);

        /**
         * Called by the native send thread to get more data to send
         *
         * @param p_nextWorkPackage
         *         Native pointer to a struct (NextWorkPackage)
         * @param p_prevResults
         *         Native pointer to a struct (PrevResults)
         * @param p_completionList
         *         Native pointer to a struct (CompletionList)
         */
        void getNextDataToSend(final long p_nextWorkPackage, final long p_prevResults, final long p_completionList);
    }

    /**
     * Initialize the native subsystem. The native library is required for this to be loaded
     *
     * @param p_callbackHandler
     *         Reference to the implemented callback handler
     * @param p_pinSendRecvThreads
     *         True to pin the native send and recv threads to cores
     * @param p_enableSignalHandler
     *         True to enable the native signal handler (for debugging)
     * @param p_statisticsThreadPrintIntervalMs
     *         Set this to non 0 to enable periodic printing of native statistics (for debugging)
     * @param p_ownNodeId
     *         Own node id
     * @param p_connectionCreationTimeoutMs
     *         Connection creation timeout in ms
     * @param p_maxNumConnections
     *         Max number of connections
     * @param p_sqSize
     *         Send queue size
     * @param p_srqSize
     *         Shared receive queue size
     * @param p_sharedSCQSize
     *         Shared send completion queue size
     * @param p_sharedRCQSize
     *         Shared receive completion queue size
     * @param p_sendBufferSize
     *         Send buffer (ORB) size in bytes
     * @param p_recvBufferPoolSize
     *         Total size of the receive buffer pool in bytes
     * @param p_recvBufferSize
     *         Size of a single receive buffer in bytes
     * @param p_maxSGEs
     *         Max number of SGEs to use for receiving data. Determines the max send and receive size
     * @return True if init successful, false on error
     */
    public static native boolean init(final CallbackHandler p_callbackHandler, final boolean p_pinSendRecvThreads,
            final boolean p_enableSignalHandler,
            final int p_statisticsThreadPrintIntervalMs, final short p_ownNodeId,
            final int p_connectionCreationTimeoutMs, final int p_maxNumConnections,
            final int p_sqSize, final int p_srqSize, final int p_sharedSCQSize, final int p_sharedRCQSize,
            final int p_sendBufferSize,
            final long p_recvBufferPoolSize, final int p_recvBufferSize, final int p_maxSGEs);

    /**
     * Shut down the subsystem
     *
     * @return True on success, false on failure
     */
    public static native boolean shutdown();

    /**
     * Add a node to allow the subsystem to discover it and set up infiniband connections
     *
     * @param p_ipv4
     *         IPV4 of the node
     */
    public static native void addNode(final int p_ipv4);

    /**
     * Actively create a new connection (e.g. we want to send data by the connection does not exist, yet)
     *
     * @param p_nodeId
     *         Target node to create a connection to
     * @return 0 on success, 1 on timeout, 2 on other error
     */
    public static native int createConnection(final short p_nodeId);

    /**
     * Get the (unsafe) address for the outgoing ring buffer to use. This allows us to serialize
     * messages directly into the send buffer without further copying
     *
     * @param p_targetNodeId
     *         Node id of the connection
     * @return Unsafe address of the send buffer (size of the buffer as set on init)
     */
    public static native long getSendBufferAddress(final short p_targetNodeId);

    /**
     * Return a used receive buffer after processing its contents
     *
     * @param p_addr
     *         Unsafe address of the receive buffer to return
     */
    public static native void returnRecvBuffer(final long p_addr);

    /**
     * Constructor
     */
    private MsgrcJNIBinding() {

    }
}
