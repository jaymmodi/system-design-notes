import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) {
        BlockingQueue<String> queue = new Llinked<>(100);
        System.out.println("queue = " + queue.size());


        try (ExecutorService producer = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            for (int i = 0; i < 1000; i++) {
                int finalI = i;
                producer.submit(() -> queue.add("Producing " + finalI));
            }
        }


        System.out.println("queue = " + queue.size());

        try (ExecutorService consumer = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            for (int i = 0; i < 100; i++) {
                consumer.submit(() -> System.out.println(queue.poll()));
            }
        }

        System.out.println("queue = " + queue.size());
    }

    private static long getAccountBalance() {
        return 100L;
    }

    private static String getUserId() throws InterruptedException {
        Thread.sleep(1000);
        return "user-1";
    }
}