package com.linli.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * "提交之后才允许发生"的外部副作用放这里：删缓存、写位图这类改动 Redis 的动作。
 *
 * 为什么不能在事务里直接做：事务还没提交时，数据库里的新值对外**不可见**，
 * 而缓存已经被删掉了。这时候并发进来的读请求会去查库、拿到**旧数据**、把它写回缓存，
 * 于是脏值一挂就是一个 TTL（商家详情是 30 分钟）。表现是"分明明明改了却不生效"，
 * 且无法靠重试复现——是最难查的一类 bug。
 *
 * 没有事务时（比如单测直接调、或者调用方刻意不开事务）就立即执行：
 * 副作用不能因为"这次没有事务"被悄悄丢掉，那样缓存会一直旧到 TTL 自然过期。
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
