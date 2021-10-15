package one.jasyncfio;

import one.jasyncfio.natives.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

public class EventExecutor {
    private static final boolean AWAKE = true;
    private static final boolean WAIT = false;

    private final AtomicBoolean state = new AtomicBoolean(WAIT);
    private final Queue<Runnable> tasks = new ConcurrentLinkedDeque<>();
    private final Map<Integer, CompletableFuture<Integer>> pendings = new HashMap<>();
    private final int entries = Integer.parseInt(System.getProperty("JASYNCFIO_RING_ENTRIES", "4096"));
    private final long eventfdReadBuf = MemoryUtils.allocateMemory(8);
    private final CompletionCallback callback = this::handle;


    private final int eventFd;
    private final Uring ring;
    private final IntSupplier sequencer;
    private final Thread t;

    EventExecutor() {
        sequencer = new IntSupplier() {
            private int i = 0;

            @Override
            public int getAsInt() {
                return Math.abs(i++ % 16_777_215);
            }
        };
        ring = Native.setupIoUring(entries, 0);
        eventFd = Native.getEventFd();
        t = new Thread(this::run);
        t.start();
    }

    private void run() {
        CompletionQueue completionQueue = ring.getCompletionQueue();
        SubmissionQueue submissionQueue = ring.getSubmissionQueue();

        addEventFdRead(submissionQueue);

        for (;;) {
            try {
                state.set(WAIT);
                if (!hasTasks() && !completionQueue.hasCompletions()) {
                    submissionQueue.submitAndWait();
                }
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                state.set(AWAKE);
            }
            boolean moreWork = true;
            do {
                try {
                    int processed = completionQueue.processEvents(callback);
                    boolean run = runAllTasks();
                    moreWork = processed != 0 || run;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            } while (moreWork);
        }
    }


    private void handle(int fd, int res, int flags, byte op, int data) {
        if (op == Native.IORING_OP_READ && fd == eventFd) {
            addEventFdRead(ring.getSubmissionQueue());
        } else {
            CompletableFuture<Integer> userCallback = pendings.remove(data);
            if (userCallback != null) {
                if (res < 0) {
                    userCallback.completeExceptionally(ErrnoDecoder.decodeError(res));
                } else {
                    userCallback.complete(res);
                }
            }
        }
    }

    private void addEventFdRead(SubmissionQueue submissionQueue) {
        submissionQueue.addEventFdRead(eventFd, eventfdReadBuf, 0, 8, 0);
    }

    public void execute(Runnable task) {
        boolean inEventLoop = inEventLoop();
        addTask(task);
        wakeup(inEventLoop);
    }

    private void addTask(Runnable task) {
        tasks.add(task);
    }

    private void wakeup(boolean inEventLoop) {
        if (!inEventLoop && state.get() != AWAKE) {
            // write to the eventfd which will then wake-up submitAndWait
            Native.eventFdWrite(eventFd, 1L);
        }
    }

    private boolean hasTasks() {
        return !tasks.isEmpty();
    }

    private boolean runAllTasks() {
        Runnable t = tasks.poll();
        if (t == null) {
            return false;
        }
        for (;;) {
            safeExec(t);
            t = tasks.poll();
            if (t == null) {
                return true;
            }
        }
    }

    private static void safeExec(Runnable task) {
        try {
            task.run();
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
    }

    private boolean inEventLoop() {
        return t == Thread.currentThread();
    }

    protected static void handleLoopException(Throwable t) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
            // ignore
        }
    }

    public void scheduleRead(int fd, long bufferAddress, int pos, int limit) {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        execute(() -> {
            int opId = sequencer.getAsInt();
            pendings.put(opId, f);
            ring.getSubmissionQueue().addRead(fd, bufferAddress, pos, limit, opId);
        });
    }

    public CompletableFuture<Integer> scheduleOpenBuffered(int dirFd, long pathAddress, int openFlags) {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        execute(() -> {
            int opId = sequencer.getAsInt();
            pendings.put(opId, f);
            ring.getSubmissionQueue().addOpenAt(dirFd, pathAddress, openFlags, opId);
        });
        return f;
    }
}