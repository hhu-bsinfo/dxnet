package de.hhu.bsinfo.dxnet.main.messages;

import org.junit.Assert;
import org.junit.Test;

public class DynamicMessageCreatorTest {

    private static final int[] TEST_SIZES = new int[] {0, 1, 64, 1024, 32 * 1024, 1024 * 1024, 32 * 1024 * 1024};
    private static final int MIN_SIZE = 64;
    private static final int MAX_SIZE = 96;

    @Test
    public void testCreateWorkload() {
        Assert.assertTrue(DynamicMessageCreator.createWorkload((short) 0).length > 0);
        for (int i = 0; i < TEST_SIZES.length; i++) {
            Assert.assertTrue(DynamicMessageCreator.createWorkload((short) 0, TEST_SIZES[i]).length > 0);
        }
        Assert.assertTrue(DynamicMessageCreator.createWorkload((short) 0, MIN_SIZE, MAX_SIZE).length > 0);
        
        DynamicMessageCreator.cleanup();
    }
}