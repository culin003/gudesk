package com.gudesk.common.session;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接门卫：按来源（IP）的密码验证失败锁定（防暴力猜测）。
 *
 * <p>规则：同一来源连续失败达 {@code maxFails} 次后锁定 {@code lockDuration}，
 * 锁定期间该来源的新连接直接拒绝（不给密码验证机会）；成功验证清零计数；
 * 锁定到期后自动解除（懒检查，无需后台线程）。
 *
 * <p>时钟可注入（{@link Clock}），测试用假时钟推进验证 60s 到期。
 */
public final class ConnectionGatekeeper {

    /** 默认连续失败锁定阈值（次） */
    public static final int DEFAULT_MAX_FAILS = 5;
    /** 默认锁定时长 */
    public static final Duration DEFAULT_LOCK_DURATION = Duration.ofSeconds(60);

    private final int maxFails;
    private final Duration lockDuration;
    private final Clock clock;
    private final ConcurrentHashMap<String, FailCounter> failures = new ConcurrentHashMap<>();

    /** 单来源失败计数（非原子字段，按 counter 对象加锁保护） */
    private static final class FailCounter {
        int count;
        long lockedUntilMillis; // epoch 毫秒；0 = 未锁定
    }

    /**
     * @param maxFails     连续失败锁定阈值（&ge; 1）
     * @param lockDuration 锁定时长（正数）
     * @param clock        时钟（测试注入假时钟）
     */
    public ConnectionGatekeeper(int maxFails, Duration lockDuration, Clock clock) {
        if (maxFails < 1) {
            throw new IllegalArgumentException("maxFails 必须 >= 1: " + maxFails);
        }
        Objects.requireNonNull(lockDuration, "lockDuration");
        if (lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("lockDuration 必须为正: " + lockDuration);
        }
        this.maxFails = maxFails;
        this.lockDuration = lockDuration;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConnectionGatekeeper(int maxFails, Duration lockDuration) {
        this(maxFails, lockDuration, Clock.systemDefaultZone());
    }

    /**
     * 该来源是否处于锁定状态（懒检查：已到期的锁定在此自动解除并清零计数）。
     */
    public boolean isLocked(String source) {
        FailCounter counter = failures.get(source);
        if (counter == null) {
            return false;
        }
        synchronized (counter) {
            long now = clock.millis();
            if (counter.lockedUntilMillis <= 0) {
                return false;
            }
            if (counter.lockedUntilMillis > now) {
                return true;
            }
            // 锁定已过期：自动解除并清零计数
            counter.lockedUntilMillis = 0;
            counter.count = 0;
            failures.remove(source, counter);
            return false;
        }
    }

    /**
     * 记录一次验证失败；连续失败达到阈值时进入锁定（计数清零，锁定到期后重新计数）。
     * 已处于锁定状态时忽略（不延长锁定）。
     */
    public void recordFailure(String source) {
        FailCounter counter = failures.computeIfAbsent(source, k -> new FailCounter());
        synchronized (counter) {
            long now = clock.millis();
            if (counter.lockedUntilMillis > now) {
                return; // 已锁定：不重复计数
            }
            counter.count++;
            if (counter.count >= maxFails) {
                counter.lockedUntilMillis = now + lockDuration.toMillis();
                counter.count = 0;
            }
        }
    }

    /**
     * 记录一次验证成功：清零该来源的失败计数。
     */
    public void recordSuccess(String source) {
        failures.remove(source);
    }

    /** 当前累计失败次数（未锁定时；锁定期间为 0） */
    public int failCount(String source) {
        FailCounter counter = failures.get(source);
        if (counter == null) {
            return 0;
        }
        synchronized (counter) {
            return counter.count;
        }
    }
}
