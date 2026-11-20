import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BatchParallelismTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long PLENTY = 16L << 30;

    @Test void automaticRampsOneSlotAtATimeAndIgnoresOneNoisySample() {
        var policy = new BatchParallelism(ImageOcr.Mode.WINDOWS, BatchParallelism.AUTO, 8);
        assertEquals(1, policy.update(0, PLENTY, PLENTY, .25));
        assertEquals(1, policy.update(SECOND / 2, PLENTY, PLENTY, .25));
        assertEquals(2, policy.update(SECOND, PLENTY, PLENTY, .25));
        assertEquals(2, policy.update(2 * SECOND, PLENTY, PLENTY, .95));
        assertEquals(2, policy.update(3 * SECOND, PLENTY, PLENTY, .75));
        assertEquals(2, policy.update(4 * SECOND, PLENTY, PLENTY, .20));
        assertEquals(3, policy.update(5 * SECOND, PLENTY, PLENTY, .20));
        assertEquals(3, policy.update(6 * SECOND, PLENTY, PLENTY, .96));
        assertEquals(2, policy.update(7 * SECOND, PLENTY, PLENTY, .96));
    }

    @Test void memoryLossImmediatelyLimitsNewSubmissionsAndRecoveryRampsAgain() {
        var policy = new BatchParallelism(ImageOcr.Mode.WINDOWS, BatchParallelism.AUTO, 8);
        policy.update(0, PLENTY, PLENTY, .20);
        assertEquals(2, policy.update(SECOND, PLENTY, PLENTY, .20));
        assertEquals(1, policy.update(SECOND + 1, 1L << 30, PLENTY, .20));
        assertEquals(1, policy.update(2 * SECOND + 1, PLENTY, PLENTY, .20));
        assertEquals(2, policy.update(3 * SECOND + 1, PLENTY, PLENTY, .20));
        assertEquals(1, policy.update(3 * SECOND + 2, PLENTY, 300L << 20, .20));
    }

    @Test void engineCapsAndPaddleMemoryBudgetAreApplied() {
        var vision = new BatchParallelism(ImageOcr.Mode.VISION, BatchParallelism.AUTO, 64);
        var paddleFourCores = new BatchParallelism(ImageOcr.Mode.PADDLE, BatchParallelism.AUTO, 4);
        var paddleSixteen = new BatchParallelism(ImageOcr.Mode.PADDLE, BatchParallelism.AUTO, 16);
        var windowsTwo = new BatchParallelism(ImageOcr.Mode.WINDOWS, 2, 16);
        assertEquals(2, windowsTwo.update(0, PLENTY, PLENTY, -1));
        assertEquals(1, windowsTwo.update(1, -1, PLENTY, -1));
        for (int second = 0; second < 10; second++) {
            assertEquals(1, vision.update(second * SECOND, PLENTY, PLENTY, .20));
            assertEquals(1, paddleFourCores.update(second * SECOND, PLENTY, PLENTY, .20));
            assertEquals(1, paddleSixteen.update(second * SECOND, 3L << 30, PLENTY, .20));
        }
        assertEquals(1, paddleSixteen.update(10 * SECOND, PLENTY, PLENTY, .20));
        assertEquals(2, paddleSixteen.update(11 * SECOND, PLENTY, PLENTY, .20));
    }
}
