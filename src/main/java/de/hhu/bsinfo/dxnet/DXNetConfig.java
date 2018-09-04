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

import lombok.Data;
import lombok.experimental.Accessors;

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
import de.hhu.bsinfo.dxutils.unit.TimeUnit;

/**
 * Configuration object with settings for DXNet
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 21.09.2017
 */
@Data
@Accessors(prefix = "m_")
public class DXNetConfig {
    private static final Logger LOGGER = LogManager.getFormatterLogger(DXNetConfig.class.getSimpleName());

    /**
     * Get the path to the jni folder (e.g. IB jni lib)
     */
    @Expose
    private String m_jniPath = "jni";

    /**
     * Get the interval for the statistics manager to print the current stats to stdout (0 to disable)
     */
    @Expose
    private TimeUnit m_statisticsManagerPrintInterval = new TimeUnit(0, TimeUnit.SEC);

    /**
     * Get the nodes list
     */
    @Expose
    private ArrayList<NodeEntry> m_nodesConfig = new ArrayList<NodeEntry>() {
        {
            // default values for local testing
            add(new NodeEntry((short) 0, new IPV4Unit("127.0.0.1", 22221)));
            add(new NodeEntry((short) 1, new IPV4Unit("127.0.0.1", 22222)));
        }
    };

    /**
     * Get the core configuration
     */
    @Expose
    private CoreConfig m_coreConfig = new CoreConfig();

    /**
     * Get the nio configuration
     */
    @Expose
    private NIOConfig m_nioConfig = new NIOConfig();

    /**
     * Get the ib configuration
     */
    @Expose
    private IBConfig m_ibConfig = new IBConfig();

    /**
     * Get the loopback configuration
     */
    @Expose
    private LoopbackConfig m_loopbackConfig = new LoopbackConfig();

    /**
     * Verify the configuration values
     *
     * @return True if all configuration values are ok, false on invalid value, range or any other error
     */
    protected boolean verify() {
        if (!new File(m_jniPath).exists()) {
            LOGGER.error("Path for JNI libs %s does not exist", m_jniPath);
            return false;
        }

        if (m_nodesConfig.size() < 2 && m_coreConfig.getDevice() != NetworkDeviceType.LOOPBACK) {
            LOGGER.error("Less than two nodes found in nodes config. At least two nodes required (non loopback)");
            return false;
        }

        if (m_coreConfig.getRequestMapSize() <= (int) Math.pow(2, 15)) {
            LOGGER.warn("Request map entry count is rather small. Requests might be discarded!");
            return true;
        }

        if ((m_coreConfig.getRequestMapSize() & m_coreConfig.getRequestMapSize() - 1) != 0) {
            LOGGER.error("Request map size must be pow2!");
            return true;
        }

        if (m_nioConfig.getFlowControlWindow().getBytes() * 2 > m_nioConfig.getOutgoingRingBufferSize().getBytes()) {
            LOGGER.error("NIO: OS buffer size must be at least twice the size of flow control window size!");
            return false;
        }

        if (m_ibConfig.getIncomingBufferSize().getBytes() > m_ibConfig.getOutgoingRingBufferSize().getBytes()) {
            LOGGER.error("IB in buffer size must be <= outgoing ring buffer size");
            return false;
        }

        if (m_nioConfig.getFlowControlWindow().getBytes() > Integer.MAX_VALUE) {
            LOGGER.error("NIO: Flow control window size exceeding 2 GB, not allowed");
            return false;
        }

        if (m_nioConfig.getFlowControlWindowThreshold() < 1 / Byte.MAX_VALUE) {
            LOGGER.error("NIO: Flow control window threshold is too small");
            return false;
        }

        if (m_loopbackConfig.getFlowControlWindowThreshold() < 1 / Byte.MAX_VALUE) {
            LOGGER.error("Loopback: Flow control window threshold is too small");
            return false;
        }

        if (m_ibConfig.getFlowControlWindow().getBytes() > Integer.MAX_VALUE) {
            LOGGER.error("IB: Flow control window size exceeding 2 GB, not allowed");
            return false;
        }

        if (m_ibConfig.getIncomingBufferSize().getGBDouble() > 2.0) {
            LOGGER.error("IB: Exceeding max incoming buffer size of 2GB");
            return false;
        }

        if (m_ibConfig.getOutgoingRingBufferSize().getGBDouble() > 2.0) {
            LOGGER.error("IB: Exceeding max outgoing buffer size of 2GB");
            return false;
        }

        return true;
    }

    /**
     * Describes a nodes configuration entry
     *
     * @author Stefan Nothaas, stefan.nothaas@hhu.de, 29.11.2017
     */
    @Data
    @Accessors(prefix = "m_")
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
        public NodeEntry() {

        }

        /**
         * Creates an instance of NodeEntry
         *
         * @param p_nodeId
         *         node id of the node
         * @param p_address
         *         address of the node
         */
        public NodeEntry(final short p_nodeId, final IPV4Unit p_address) {
            m_nodeId = p_nodeId;
            m_address = p_address;
        }
    }
}
