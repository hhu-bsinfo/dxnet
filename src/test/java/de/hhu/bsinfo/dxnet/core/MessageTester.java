package de.hhu.bsinfo.dxnet.core;

import org.junit.Assert;

import de.hhu.bsinfo.dxutils.UnsafeHandler;

public class MessageTester {
    private MessageTester() {

    }

    public static void testMessage(final Message p_message) {
        p_message.initialize(new MessageHeader(p_message.getMessageID(),
                p_message.getCombinedFieldTypeAndExclusivity(), p_message.getType(), p_message.getSubtype(),
                p_message.getPayloadLength()), generateRandomDestination(), generateRandomDestination());

        int writeSize = p_message.getTotalSize();
        long writeBufferAddr = allocDirectMemory(writeSize);

        try {
            MessageExporterDefault exporter = new MessageExporterDefault();
            exporter.setBuffer(writeBufferAddr, writeSize);
            exporter.setPosition(0);

            try {
                p_message.writeMessage(exporter, p_message.getPayloadLength());
            } catch (final NetworkException e) {
                Assert.fail(e.getMessage());
            }

            Assert.assertEquals(writeSize, exporter.getNumberOfWrittenBytes());

            MessageImporterDefault importer = new MessageImporterDefault();
            importer.setBuffer(writeBufferAddr, writeSize, 0);
            importer.setPosition(0);

            MessageHeader readHeader = new MessageHeader();

            importer.importObject(readHeader);

            verifyMessageHeader(readHeader, p_message);

            try {
                p_message.readPayload(importer, readHeader.getPayloadSize());
            } catch (final ArrayIndexOutOfBoundsException e) {
                Assert.fail(e.getMessage());
            }

            Assert.assertEquals(readHeader.getPayloadSize(), importer.getNumberOfReadBytes() - readHeader.sizeofObject());
        } finally {
            freeDirectMemory(writeBufferAddr);
        }
    }

    private static long allocDirectMemory(final int p_size) {
        return UnsafeHandler.getInstance().getUnsafe().allocateMemory(p_size);
    }

    private static void freeDirectMemory(final long p_addr) {
        UnsafeHandler.getInstance().getUnsafe().freeMemory(p_addr);
    }

    private static void verifyMessageHeader(final MessageHeader p_header, final Message p_message) {
        Assert.assertEquals(p_header.getMessageID(), p_message.getMessageID());
        Assert.assertEquals(p_header.isExclusive(), p_message.isExclusive());
        Assert.assertEquals(p_header.getType(), p_message.getType());
        Assert.assertEquals(p_header.getSubtype(), p_message.getSubtype());
        Assert.assertEquals(p_header.getPayloadSize(), p_message.getPayloadLength());
    }

    private static short generateRandomDestination() {
        return (short) (Math.random() * 0xFFFF);
    }
}
