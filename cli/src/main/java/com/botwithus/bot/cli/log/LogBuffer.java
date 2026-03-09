package com.botwithus.bot.cli.log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class LogBuffer {

    private static final int DEFAULT_CAPACITY = 10_000;

    private final LogEntry[] buffer;
    private final ReentrantLock lock = new ReentrantLock();
    private int head = 0;
    private int size = 0;

    public LogBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public LogBuffer(int capacity) {
        this.buffer = new LogEntry[capacity];
    }

    public void add(LogEntry entry) {
        lock.lock();
        try {
            buffer[head] = entry;
            head = (head + 1) % buffer.length;
            if (size < buffer.length) size++;
        } finally {
            lock.unlock();
        }
    }

    public List<LogEntry> tail(int count) {
        lock.lock();
        try {
            int n = Math.min(count, size);
            List<LogEntry> result = new ArrayList<>(n);
            int start = (head - n + buffer.length) % buffer.length;
            for (int i = 0; i < n; i++) {
                result.add(buffer[(start + i) % buffer.length]);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public List<LogEntry> since(Instant cutoff) {
        lock.lock();
        try {
            List<LogEntry> result = new ArrayList<>();
            int start = (head - size + buffer.length) % buffer.length;
            for (int i = 0; i < size; i++) {
                LogEntry entry = buffer[(start + i) % buffer.length];
                if (!entry.timestamp().isBefore(cutoff)) {
                    result.add(entry);
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            head = 0;
            size = 0;
        } finally {
            lock.unlock();
        }
    }
}
