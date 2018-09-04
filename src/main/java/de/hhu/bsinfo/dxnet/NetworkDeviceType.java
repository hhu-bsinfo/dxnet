package de.hhu.bsinfo.dxnet;

/**
 * Represents a supported network device/transport type
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 04.09.2018
 */
public enum NetworkDeviceType {
    ETHERNET("ethernet"),
    INFINIBAND("infiniband"),
    LOOPBACK("loopback"),
    INVALID("invalid");

    public static final String ETHERNET_STR = "ethernet";
    public static final String INFINIBAND_STR = "infiniband";
    public static final String LOOPBACK_STR = "loopback";
    public static final String INVALID_STR = "invalid";

    private final String m_name;

    /**
     * Constructor
     *
     * @param p_name
     *         String representation of device
     */
    NetworkDeviceType(final String p_name) {
        m_name = p_name;
    }

    /**
     * Convert a string to a NetworkDeviceType enum entry
     *
     * @param p_str
     *         String of network device
     * @return Corresponding enum entry (INVALID if no device is matching)
     */
    public static NetworkDeviceType toNetworkDeviceType(final String p_str) {
        switch (p_str.toLowerCase()) {
            case ETHERNET_STR:
                return ETHERNET;
            case INFINIBAND_STR:
                return INFINIBAND;
            case LOOPBACK_STR:
                return LOOPBACK;
            default:
                return INVALID;
        }
    }
}
