package de.hhu.bsinfo.dxnet.ib;

public class MsgrcJNIBinding {
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

        void received(final long p_recvPackage);

        void getNextDataToSend(final long p_nextWorkPackage, final long p_prevResults, final long p_completionList);
    }

    public static native boolean init(final CallbackHandler p_callbackHandler, final boolean p_pinSendRecvThreads, final boolean p_enableSignalHandler,
            final int p_statisticsThreadPrintIntervalMs, final short p_ownNodeId, final int p_connectionCreationTimeoutMs, final int p_maxNumConnections,
            final int p_sqSize, final int p_srqSize, final int p_sharedSCQSize, final int p_sharedRCQSize, final int p_sendBufferSize,
            final long p_recvBufferPoolSize, final int p_recvBufferSize);

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

}
