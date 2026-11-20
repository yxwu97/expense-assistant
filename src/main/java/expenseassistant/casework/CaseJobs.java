package expenseassistant.casework;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Reserves one mutation per case while staging work outside the database transaction. */
public final class CaseJobs {
    private final ConcurrentHashMap<String, Job> active = new ConcurrentHashMap<>();

    public Job begin(String caseId) {
        var job = new Job(caseId);
        var existing = active.putIfAbsent(caseId, job);
        if (existing != null) {
            throw new CaseException("BUSY", "这笔报销已有任务在运行。", existing.id(), null);
        }
        return job;
    }

    public boolean cancel(String id) {
        for (var job : active.values()) {
            if (job.id().equals(id)) {
                return job.cancel();
            }
        }
        return false;
    }

    public final class Job implements AutoCloseable {
        private final String caseId;
        private final String id = UUID.randomUUID().toString();
        private Runnable cancellationAction;
        private boolean cancelled;
        private boolean committed;
        private boolean closed;

        private Job(String caseId) {
            this.caseId = caseId;
        }

        public String id() {
            return id;
        }

        public String caseId() {
            return caseId;
        }

        public synchronized void checkActive() {
            if (cancelled || closed) {
                throw new CancellationException("报销任务已取消或结束");
            }
        }

        public void onCancel(Runnable action) {
            synchronized (this) {
                if (!cancelled) {
                    checkActive();
                    cancellationAction = action;
                    return;
                }
            }
            action.run();
        }

        public synchronized <T> T commit(Supplier<T> transaction) {
            checkActive();
            if (committed) {
                throw new IllegalStateException("报销任务已提交");
            }
            T result = transaction.get();
            committed = true;
            return result;
        }

        private boolean cancel() {
            Runnable action;
            synchronized (this) {
                if (cancelled || committed || closed) {
                    return false;
                }
                cancelled = true;
                action = cancellationAction;
                cancellationAction = null;
            }
            if (action != null) {
                action.run();
            }
            return true;
        }

        @Override
        public void close() {
            synchronized (this) {
                closed = true;
                cancellationAction = null;
            }
            active.remove(caseId, this);
        }
    }
}
