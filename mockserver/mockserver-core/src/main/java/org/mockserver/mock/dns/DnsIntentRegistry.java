package org.mockserver.mock.dns;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe bounded registry that records the hostname-to-IP answers MockServer's
 * DNS server has handed out. When MockServer's DNS server responds with an A or AAAA
 * record mapping a queried hostname to an IP address, that mapping is recorded here
 * ({@code answeredIP -> queriedHostname}).
 * <p>
 * The DNS-intent transparent-proxy resolver ({@code DnsIntentOriginalDestinationResolver})
 * consumes this registry to recover the intended hostname for a connection that arrived
 * at a given IP — enabling expectation matching and forwarding by host even when the
 * client connected by IP because MockServer's DNS pointed the name at that IP.
 * <p>
 * Oldest-inserted entries are evicted (insertion-order / FIFO, not access-order LRU)
 * when the registry reaches its maximum capacity; reads ({@link #recover}) stay fully
 * concurrent. If the same IP is answered for a different hostname the most-recent answer
 * wins (the value is updated in place without changing eviction order).
 */
public class DnsIntentRegistry {

    private static final DnsIntentRegistry INSTANCE = new DnsIntentRegistry(10_000);

    private final int maxSize;
    private final LinkedHashMap<InetAddress, String> entries;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public DnsIntentRegistry(int maxSize) {
        this.maxSize = maxSize;
        // accessOrder=false (insertion-order): evicts the oldest-inserted entry when full.
        // put() on an existing key updates the value in place (most-recent-answer-wins).
        this.entries = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<InetAddress, String> eldest) {
                return size() > DnsIntentRegistry.this.maxSize;
            }
        };
    }

    public static DnsIntentRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Records that MockServer's DNS server answered with the given IP for the given hostname.
     * Trailing dots are stripped from DNS qnames (e.g. {@code "host.example.com."} becomes
     * {@code "host.example.com"}).
     *
     * @param answeredIp the IP address from an A or AAAA answer record
     * @param hostname   the queried DNS name
     */
    public void record(InetAddress answeredIp, String hostname) {
        if (answeredIp == null || hostname == null) {
            return;
        }
        String normalised = hostname.endsWith(".") ? hostname.substring(0, hostname.length() - 1) : hostname;
        lock.writeLock().lock();
        try {
            entries.put(answeredIp, normalised);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Recovers the hostname that MockServer's DNS server mapped to the given IP address.
     *
     * @param connectedIp the IP address the client connected to
     * @return the hostname, or {@code null} if no mapping is known
     */
    public String recover(InetAddress connectedIp) {
        if (connectedIp == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            return entries.get(connectedIp);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            entries.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the number of entries currently stored (useful for testing).
     */
    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
