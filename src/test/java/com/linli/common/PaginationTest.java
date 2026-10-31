package com.linli.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分页收敛的用例。它替代的是散在八个 Service 里逐字相同的两行代码，
 * 所以这里的用例就是那八处**共同**的行为契约：越界不报错、只被压回边界。
 */
class PaginationTest {

    @Test
    @DisplayName("页码小于 1 一律当第 1 页，偏移量随之为 0")
    void pageBelowOneFallsBack() {
        assertThat(Pagination.of(0, 10, 6, 50)).isEqualTo(new Pagination(1, 10));
        assertThat(Pagination.of(-3, 10, 6, 50).offset()).isZero();
    }

    @Test
    @DisplayName("条数没传或传非法值用默认条数；超过上限压到上限而不是报错")
    void sizeUsesDefaultThenCaps() {
        assertThat(Pagination.of(1, 0, 6, 50).size()).isEqualTo(6);
        assertThat(Pagination.of(1, -5, 6, 50).size()).isEqualTo(6);
        assertThat(Pagination.of(1, 9999, 6, 50).size()).isEqualTo(50);
    }

    @Test
    @DisplayName("偏移量按「归一之后」的页码和条数算，不能拿客户端给的原值去乘")
    void offsetUsesNormalizedValues() {
        assertThat(Pagination.of(3, 20, 6, 50).offset()).isEqualTo(40);
        // 客户端想要 9999 条：被压到 50，第二页的偏移就该是 50 而不是 9999
        assertThat(Pagination.of(2, 9999, 6, 50).offset()).isEqualTo(50);
    }
}
