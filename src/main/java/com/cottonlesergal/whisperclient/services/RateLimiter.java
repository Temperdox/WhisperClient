package com.cottonlesergal.whisperclient.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiter {
    private static final RateLimiter INSTANCE = new RateLimiter();

    // Rate limiting configuration
    private static final int MAX_MESSAGES_PER_WINDOW = 4; // 4 messages
    private static final long RATE_LIMIT_WINDOW_MS = 1000; // 1 second window
    private static final long COOLDOWN_PERIOD_MS = 5000; // 5 second cooldown

    // Track message counts per user
    private final Map<String, MessageCounter> userCounters = new ConcurrentHashMap<>();

    public static RateLimiter getInstance() {
        return INSTANCE;
    }

    private RateLimiter() {}

    /**
     * Check if a message from a user should be processed or rate limited
     * @param username The user sending the message
     * @return true if message should be processed, false if rate limited
     */
    public boolean allowMessage(String username) {
        if (username == null || username.isEmpty()) {
            return true; // Allow if no username specified
        }

        long now = System.currentTimeMillis();
        MessageCounter counter = userCounters.computeIfAbsent(username, k -> new MessageCounter());

        synchronized (counter) {
            // Check if user is currently in cooldown
            if (counter.isInCooldown(now)) {
                long remainingCooldown = counter.getRemainingCooldown(now);
                System.out.println("[RateLimiter] User " + username + " is rate limited. " +
                        "Cooldown remaining: " + remainingCooldown + "ms");
                return false;
            }

            // Reset counter if window has expired
            if (now - counter.getWindowStart() > RATE_LIMIT_WINDOW_MS) {
                counter.reset(now);
            }

            // Check if user has exceeded rate limit
            if (counter.getMessageCount() >= MAX_MESSAGES_PER_WINDOW) {
                // Start cooldown period
                counter.startCooldown(now);
                System.out.println("[RateLimiter] User " + username + " exceeded rate limit (" +
                        MAX_MESSAGES_PER_WINDOW + " messages/sec). Starting " +
                        (COOLDOWN_PERIOD_MS / 1000) + "s cooldown.");
                return false;
            }

            // Allow message and increment counter
            counter.incrementCount();
            System.out.println("[RateLimiter] Message allowed for " + username +
                    " (" + counter.getMessageCount() + "/" + MAX_MESSAGES_PER_WINDOW + " in current window)");
            return true;
        }
    }

    /**
     * Check if a user is currently rate limited
     */
    public boolean isRateLimited(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }

        MessageCounter counter = userCounters.get(username);
        if (counter == null) {
            return false;
        }

        return counter.isInCooldown(System.currentTimeMillis());
    }

    /**
     * Get remaining cooldown time in milliseconds for a user
     */
    public long getRemainingCooldown(String username) {
        if (username == null || username.isEmpty()) {
            return 0;
        }

        MessageCounter counter = userCounters.get(username);
        if (counter == null) {
            return 0;
        }

        return counter.getRemainingCooldown(System.currentTimeMillis());
    }

    /**
     * Clear rate limiting for a specific user (admin/debug function)
     */
    public void clearRateLimit(String username) {
        userCounters.remove(username);
        System.out.println("[RateLimiter] Cleared rate limit for " + username);
    }

    /**
     * Clear all rate limits (admin/debug function)
     */
    public void clearAllRateLimits() {
        userCounters.clear();
        System.out.println("[RateLimiter] Cleared all rate limits");
    }

    /**
     * Get rate limiting statistics
     */
    public String getStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("Rate Limiter Stats:\n");
        stats.append("- Max messages per second: ").append(MAX_MESSAGES_PER_WINDOW).append("\n");
        stats.append("- Cooldown period: ").append(COOLDOWN_PERIOD_MS / 1000).append(" seconds\n");
        stats.append("- Currently tracked users: ").append(userCounters.size()).append("\n");

        long now = System.currentTimeMillis();
        int rateLimitedUsers = 0;
        for (Map.Entry<String, MessageCounter> entry : userCounters.entrySet()) {
            if (entry.getValue().isInCooldown(now)) {
                rateLimitedUsers++;
            }
        }
        stats.append("- Currently rate limited users: ").append(rateLimitedUsers);

        return stats.toString();
    }

    /**
     * Internal class to track message counts and cooldowns per user
     */
    private static class MessageCounter {
        private final AtomicInteger messageCount = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong cooldownStart = new AtomicLong(0);

        public void reset(long now) {
            messageCount.set(0);
            windowStart.set(now);
            cooldownStart.set(0);
        }

        public void incrementCount() {
            messageCount.incrementAndGet();
        }

        public int getMessageCount() {
            return messageCount.get();
        }

        public long getWindowStart() {
            return windowStart.get();
        }

        public void startCooldown(long now) {
            cooldownStart.set(now);
        }

        public boolean isInCooldown(long now) {
            long cooldownStartTime = cooldownStart.get();
            return cooldownStartTime > 0 && (now - cooldownStartTime) < COOLDOWN_PERIOD_MS;
        }

        public long getRemainingCooldown(long now) {
            long cooldownStartTime = cooldownStart.get();
            if (cooldownStartTime == 0) {
                return 0;
            }

            long elapsed = now - cooldownStartTime;
            if (elapsed >= COOLDOWN_PERIOD_MS) {
                return 0;
            }

            return COOLDOWN_PERIOD_MS - elapsed;
        }
    }
}