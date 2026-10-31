package com.linli.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * "提交后才执行"这条规则本身的测试。
 *
 * 三个用例对应三种世界：没开事务（必须马上做，否则缓存一直旧到 TTL）、
 * 事务提交（这时候做，并发读才能看到新值）、事务回滚（**绝对不能做**——数据没改成，
 * 删缓存只会给并发读留出用旧值重建的窗口）。第三个最容易漏，也最难在集成环境里发现。
 */
class AfterCommitTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("没有事务时立即执行：副作用不能因为这次没开事务被悄悄丢掉")
    void runsImmediatelyOutsideTransaction() {
        List<String> done = new ArrayList<>();

        AfterCommit.run(() -> done.add("invalidated"));

        assertThat(done).containsExactly("invalidated");
    }

    @Test
    @DisplayName("事务里只登记，提交那一刻才执行")
    void defersUntilCommit() {
        List<String> done = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(() -> done.add("invalidated"));

        assertThat(done).as("提交前不许动缓存").isEmpty();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(done).containsExactly("invalidated");
    }

    @Test
    @DisplayName("回滚不执行：数据没改成，缓存就不该被删")
    void rollbackLeavesItUnrun() {
        List<String> done = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(() -> done.add("invalidated"));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(done).isEmpty();
    }
}
