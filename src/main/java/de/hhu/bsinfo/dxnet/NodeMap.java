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

import java.net.InetSocketAddress;
import java.util.List;

/**
 * An interface to map NodeIDs
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 20.11.2015
 */
public interface NodeMap {
    /**
     * Listener for node map events
     */
    public interface Listener {
        /**
         * A node mapping was added
         *
         * @param p_nodeId
         *         Node id of added mapping
         * @param p_address
         *         Address of node
         */
        void nodeMappingAdded(final short p_nodeId, final InetSocketAddress p_address);

        /**
         * A node mapping was removed
         *
         * @param p_nodeId
         *         Node id of removed mapping
         */
        void nodeMappingRemoved(final short p_nodeId);
    }

    /**
     * Returns the NodeID
     *
     * @return the NodeID
     */
    short getOwnNodeID();

    /**
     * Returns the address
     *
     * @param p_nodeID
     *         the NodeID
     * @return the address
     */
    InetSocketAddress getAddress(final short p_nodeID);

    /**
     * Get all currently available mappings
     *
     * @return List of mappings
     */
    List<Mapping> getAvailableMappings();

    /**
     * Register a listener for node mapping events
     *
     * @param p_listener
     *         Listener to register
     */
    void registerListener(final Listener p_listener);

    /**
     * Node mapping to socket address
     */
    public static class Mapping {
        private final short m_nodeId;
        private final InetSocketAddress m_address;

        /**
         * Constructor
         *
         * @param p_nodeId
         *         Node id
         * @param p_address
         *         Socket address
         */
        public Mapping(final short p_nodeId, final InetSocketAddress p_address) {
            m_nodeId = p_nodeId;
            m_address = p_address;
        }

        /**
         * Get the node id
         *
         * @return Node id
         */
        public short getNodeId() {
            return m_nodeId;
        }

        /**
         * Get the socket address
         *
         * @return Socket address
         */
        public InetSocketAddress getAddress() {
            return m_address;
        }
    }
}
