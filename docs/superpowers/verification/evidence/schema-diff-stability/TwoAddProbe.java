import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** External synthetic probe for the exact two-add operation used by the recording fixture. */
public class TwoAddProbe {
    private static volatile List<Integer> opened;

    public static void main(String[] args) throws Exception {
        boolean safe = args.length > 0 && args[0].equals("safe");
        int pairs = 200_000;
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var lostPairs = new AtomicInteger();
        var recorded = new AtomicInteger();
        var start = new CyclicBarrier(2, () -> opened = safe
                ? Collections.synchronizedList(new ArrayList<>()) : new ArrayList<>());
        var finish = new CyclicBarrier(2, () -> {
            recorded.addAndGet(opened.size());
            if (opened.size() != 2 || !opened.contains(0) || !opened.contains(1)) {
                lostPairs.incrementAndGet();
            }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int side = 0; side < 2; side++) {
                int id = side;
                tasks.add(executor.submit(() -> {
                    for (int pair = 0; pair < pairs; pair++) {
                        start.await(5, TimeUnit.SECONDS);
                        try { opened.add(id); }
                        catch (Throwable failure) { failures.add(failure); }
                        finish.await(5, TimeUnit.SECONDS);
                    }
                    return null;
                }));
            }
            for (var task : tasks) task.get(60, TimeUnit.SECONDS);
        }
        System.out.println("safe=" + safe + ", pairs=" + pairs + ", expected=" + pairs * 2
                + ", recorded=" + recorded + ", invalidPairs=" + lostPairs
                + ", rawFailures=" + failures.size());
        if (!failures.isEmpty()) failures.peek().printStackTrace(System.out);
        if (lostPairs.get() > 0 || !failures.isEmpty()) System.exit(1);
    }
}
