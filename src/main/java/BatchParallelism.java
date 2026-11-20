import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

final class BatchParallelism {
    static final int AUTO = 0;
    static final long MIB = 1L << 20;
    static final long GIB = 1L << 30;
    private static final long SAMPLE_NS = 1_000_000_000L;
    private static final long PHYSICAL_RESERVE = 2 * GIB;
    private static final long HEAP_RESERVE = 512 * MIB;
    private static final long HEAP_PER_EXTRA_WORKER = 256 * MIB;
    private static final int REQUIRED_SAMPLES = 2;
    private final int ceiling;
    private final boolean automatic;
    private final long physicalPerExtraWorker;
    private int workers = 1;
    private int lowCpuSamples, highCpuSamples;
    private boolean sampled;
    private long lastSample;

    BatchParallelism(ImageOcr.Mode mode, int requested, int logicalProcessors) {
        automatic = requested == AUTO && mode != ImageOcr.Mode.VISION;
        boolean basic = mode == ImageOcr.Mode.OFF || mode == ImageOcr.Mode.WINDOWS;
        ceiling = automatic ? Math.max(1, logicalProcessors / (mode == ImageOcr.Mode.PADDLE ? 4 : 2))
                : basic && requested >= 2 ? 2 : 1;
        // PaddleOCR runs four CPU threads and was observed to use about 950MiB in one process.
        physicalPerExtraWorker = (mode == ImageOcr.Mode.PADDLE ? 1280 : 384) * MIB;
    }

    int observe() {
        if (ceiling == 1) return 1;
        long now = System.nanoTime();
        var runtime = Runtime.getRuntime();
        long heapHeadroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        if (!(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean system)) return 1;
        double cpuLoad = automatic && sampleDue(now) ? system.getCpuLoad() : -1;
        return update(now, system.getFreeMemorySize(), heapHeadroom, cpuLoad);
    }

    int update(long now, long availablePhysical, long heapHeadroom, double cpuLoad) {
        if (ceiling == 1 || !automatic) {
            return availablePhysical < PHYSICAL_RESERVE || heapHeadroom < HEAP_RESERVE ? 1 : ceiling;
        }
        // Apply a memory bound before submitting anything else. Workers already converting may finish.
        long physicalCapacity = 1 + Math.max(0, availablePhysical - PHYSICAL_RESERVE) / physicalPerExtraWorker;
        long heapCapacity = 1 + Math.max(0, heapHeadroom - HEAP_RESERVE) / HEAP_PER_EXTRA_WORKER;
        int capacity = (int) Math.min(ceiling, Math.min(physicalCapacity, heapCapacity));
        if (workers > capacity) {
            workers = capacity;
            resetSamples(now);
            return workers;
        }
        if (!sampleDue(now)) return workers;
        sampled = true;
        lastSample = now;
        boolean cpuKnown = Double.isFinite(cpuLoad) && cpuLoad >= 0 && cpuLoad <= 1;
        lowCpuSamples = cpuKnown && cpuLoad <= .65 && workers < capacity ? lowCpuSamples + 1 : 0;
        highCpuSamples = (!cpuKnown || cpuLoad >= .90) && workers > 1 ? highCpuSamples + 1 : 0;
        if (lowCpuSamples == REQUIRED_SAMPLES) {
            workers++;
            resetSamples(now);
        } else if (highCpuSamples == REQUIRED_SAMPLES) {
            workers--;
            resetSamples(now);
        }
        return workers;
    }

    private boolean sampleDue(long now) {
        return !sampled || now - lastSample >= SAMPLE_NS;
    }

    private void resetSamples(long now) {
        sampled = true;
        lastSample = now;
        lowCpuSamples = 0;
        highCpuSamples = 0;
    }
}
