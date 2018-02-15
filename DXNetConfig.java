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
import java.util.ArrayList;

import com.google.gson.annotations.Expose;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.dxnet.core.CoreConfig;
import de.hhu.bsinfo.dxnet.ib.IBConfig;
import de.hhu.bsinfo.dxnet.loopback.LoopbackConfig;
import de.hhu.bsinfo.dxnet.nio.NIOConfig;
import de.hhu.bsinfo.dxutils.unit.IPV4Unit;

/**
 * Configuration object with settings for DXNet
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 21.09.2017
 */
public class DXNetConfig {
    private static final Logger LOGGER = LogManager.getFormatterLogger(DXNetConfig.class.getSimpleName());

    /**
     * DXNet specific settings
     */
    @Expose
    private String m_jniPath = "jni";

    @Expose
    private ArrayList<NodeEntry> m_nodesConfig = new ArrayList<NodeEntry>() {
        {
            // default values for local testing
            add(new NodeEntry((short) 0, new IPV4Unit("127.0.0.1", 22221)));
            add(new NodeEntry((short) 1, new IPV4Unit("127.0.0.1", 22222)));
        }
    };

    @Expose
    private CoreConfig m_coreConfig = new CoreConfig();

    @Expose
    private NIOConfig m_nioConfig = new NIOConfig();

    @Expose
    private IBConfig m_ibConfig = new IBConfig();

    @Expose
    private LoopbackConfig m_loopbackConfig = new LoopbackConfig();

    /**
     * Constructor
     */
    DXNetConfig() {

    }

    /**
     * Get the path to the jni folder (e.g. IB jni lib)
     *
     * @return Path to folder with jni libs
     */
    String getJNIPath() {
        return m_jniPath;
    }

    /**
     * Get the nodes list
     *
     * @return Array with node entries
     */
    ArrayList<NodeEntry> getNodeList() {
        return m_nodesConfig;
    }

    /**
     * Get the core configuration
     *
     * @return Configuration
     */
    CoreConfig getCoreConfig() {
        return m_coreConfig;
    }

    /**
     * Get the nio configuration
     *
     * @return Configuration
     */
    NIOConfig getNIOConfig() {
        return m_nioConfig;
    }

    /**
     * Get the ib configuration
     *
     * @return Configuration
     */
    IBConfig getIBConfig() {
        return m_ibConfig;
    }

    /**
     * Get the loopback configuration
     *
     * @return Configuration
     */
    LoopbackConfig getLoopbackConfig() {
        return m_loopbackConfig;
    }

    /**
     * @return
     */
    protected boolean verify() {
        if (!new File(m_jniPath).exists()) {
            // #if LOGGER >= ERROR
            LOGGER.error("Path for JNI libs %s does not exist", m_jniPath);
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_nodesConfig.size() < 2) {
            // #if LOGGER >= ERROR
            LOGGER.error("Less than two nodes found in nodes config. At least two nodes required");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_coreConfig.getRequestMapSize() <= (int) Math.pow(2, 15)) {
            // #if LOGGER >= WARN
            LOGGER.warn("Request map entry count is rather small. Requests might be discarded!");
            // #endif /* LOGGER >= WARN */
            return true;
        }

        if ((m_coreConfig.getRequestMapSize() & m_coreConfig.getRequestMapSize() - 1) != 0) {
            // #if LOGGER >= ERROR
            LOGGER.error("Request map size must be pow2!");
            // #endif /* LOGGER >= ERROR */
            return true;
        }

        if (m_nioConfig.getFlowControlWindow().getBytes() * 2 > m_nioConfig.getOugoingRingBufferSize().getBytes()) {
            // #if LOGGER >= ERROR
            LOGGER.error("NIO: OS buffer size must be at least twice the size of flow control window size!");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_ibConfig.getIncomingBufferSize().getBytes() > m_ibConfig.getOugoingRingBufferSize().getBytes()) {
            // #if LOGGER >= ERROR
            LOGGER.error("IB in buffer size must be <= outgoing ring buffer size");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_nioConfig.getFlowControlWindow().getBytes() > Integer.MAX_VALUE) {
            // #if LOGGER >= ERROR
            LOGGER.error("NIO: Flow control window size exceeding 2 GB, not allowed");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_nioConfig.getFlowControlWindowThreshold() < 1 / Byte.MAX_VALUE) {
            // #if LOGGER >= ERROR
            LOGGER.error("NIO: Flow control window threshold is too small");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_loopbackConfig.getFlowControlWindowThreshold() < 1 / Byte.MAX_VALUE) {
            // #if LOGGER >= ERROR
            LOGGER.error("Loopback: Flow control window threshold is too small");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_ibConfig.getFlowControlWindow().getBytes() > Integer.MAX_VALUE) {
            // #if LOGGER >= ERROR
            LOGGER.error("IB: Flow control window size exceeding 2 GB, not allowed");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_ibConfig.getIncomingBufferSize().getGBDouble() > 2.0) {
            // #if LOGGER >= ERROR
            LOGGER.error("IB: Exceeding max incoming buffer size of 2GB");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        if (m_ibConfig.getOugoingRingBufferSize().getGBDouble() > 2.0) {
            // #if LOGGER >= ERROR
            LOGGER.error("IB: Exceeding max outgoing buffer size of 2GB");
            // #endif /* LOGGER >= ERROR */
            return false;
        }

        return true;
    }

    /**
     * Describes a nodes configuration entry
     *
     * @author Stefan Nothaas, stefan.nothaas@hhu.de, 29.11.2017
     */
    static final class NodeEntry {

        /**
         * Node id of the instance
         */
        @Expose
        private short m_nodeId = -1;

        /**
         * Address and port of the instance
         */
        @Expose
        private IPV4Unit m_address = new IPV4Unit("127.0.0.1", 22222);

        /**
         * Creates an instance of NodeEntry
         */
        NodeEntry() {

        }

        /**
         * Creates an instance of NodeEntry
         *
         * @param p_nodeId
         *         node id of the node
         * @param p_address
         *         address of the node
         */
        NodeEntry(final short p_nodeId, final IPV4Unit p_address) {
            m_nodeId = p_nodeId;
            m_address = p_address;
        }

        /**
         * Get the node id of the node
         *
         * @return node id
         */
        public short getNodeId() {
            return m_nodeId;
        }

        /**
         * Gets the address of the node
         *
         * @return the address of the node
         */
        public IPV4Unit getAddress() {
            return m_address;
        }

        @Override
        public String toString() {
            return "NodeEntry [m_nodeId=" + m_nodeId + "m_address=" + m_address + ']';
        }
    }
}
