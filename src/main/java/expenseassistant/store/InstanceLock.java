package expenseassistant.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

public final class InstanceLock implements AutoCloseable {
    private static final Set<Path> HELD_IN_PROCESS = new HashSet<>();
    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(Path lockPath, FileChannel channel, FileLock lock) {
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
    }

    public static InstanceLock acquire(Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
            Path lockPath = dataDirectory.toRealPath().resolve(".expense-assistant.lock");
            synchronized (HELD_IN_PROCESS) {
                if (HELD_IN_PROCESS.contains(lockPath)) {
                    throw locked(dataDirectory, null);
                }
                return open(lockPath, dataDirectory);
            }
        } catch (IOException failure) {
            throw new StoreException("STORE_UNAVAILABLE", "无法取得用户数据目录的文件锁", dataDirectory, failure);
        }
    }

    private static InstanceLock open(Path lockPath, Path dataDirectory) throws IOException {
        FileChannel candidate = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock acquired = candidate.tryLock();
            if (acquired == null) {
                throw locked(dataDirectory, null);
            }
            HELD_IN_PROCESS.add(lockPath);
            return new InstanceLock(lockPath, candidate, acquired);
        } catch (OverlappingFileLockException occupied) {
            var error = locked(dataDirectory, occupied);
            closeCandidate(candidate, error);
            throw error;
        } catch (IOException failure) {
            closeCandidate(candidate, failure);
            throw failure;
        } catch (RuntimeException failure) {
            closeCandidate(candidate, failure);
            throw failure;
        }
    }

    private static StoreException locked(Path dataDirectory, Throwable cause) {
        return new StoreException("INSTANCE_LOCKED", "已有报销助手正在使用该数据目录", dataDirectory, cause);
    }

    private static void closeCandidate(FileChannel candidate, Throwable original) {
        try {
            candidate.close();
        } catch (IOException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (HELD_IN_PROCESS) {
            if (!channel.isOpen()) {
                return;
            }
            try {
                try {
                    lock.release();
                } finally {
                    channel.close();
                }
            } finally {
                HELD_IN_PROCESS.remove(lockPath);
            }
        }
    }
}
