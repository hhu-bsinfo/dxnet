package de.hhu.bsinfo.dxnet.main.messages;

import de.hhu.bsinfo.dxnet.core.AbstractMessageExporter;
import de.hhu.bsinfo.dxnet.core.AbstractMessageImporter;
import de.hhu.bsinfo.dxnet.core.Request;
import de.hhu.bsinfo.dxutils.serialization.ObjectSizeUtil;

/**
 * This is a benchmark request which is used in DXNetMainDeadlock.
 * It is only used as an response to another incoming request to test deadlock
 * behaviour.
 *
 * @author Christian Gesse, christian.gesse@hhu.de, 28.01.2019
 */
public class BenchmarkDeadlockRequest extends Request {

    private byte[] m_data;

    /**
     * Creates an instance of BenchmarkRequest.
     */
    public BenchmarkDeadlockRequest() {
        super();
    }

    /**
     * Creates an instance of BenchmarkRequest
     *
     * @param p_destination
     *         the destination nodeID
     * @param p_size
     *         Size of the request
     */
    public BenchmarkDeadlockRequest(final short p_destination, final int p_size) {
        super(p_destination, Messages.DXNETMAIN_MESSAGES_TYPE, Messages.SUBTYPE_BENCHMARK_DEADLOCK_REQUEST);

        m_data = new byte[p_size];
    }

    @Override
    protected final int getPayloadLength() {
        return ObjectSizeUtil.sizeofByteArray(m_data);
    }

    @Override
    protected final void writePayload(final AbstractMessageExporter p_exporter) {
        p_exporter.writeByteArray(m_data);
    }

    @Override
    protected final void readPayload(final AbstractMessageImporter p_importer) {
        m_data = p_importer.readByteArray(m_data);
    }
}
