package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.PointsRecord;
import java.time.LocalDate;

/**
 * 签到与积分的返回对象。
 *
 * 三个"天数"口径分开给：{@code signedToday}（今天签没签）、{@code monthDays}（本月签了几天）、
 * {@code continuousDays}（连着签了几天）。合成一个数字的话，界面上"已签到 3 次"和"连续 3 天"
 * 就永远分不清，而奖励规则挂在后者上。
 */
public final class SignVOs {

    private SignVOs() {}

    /** 签到页打开时的状态。 */
    public record Status(boolean signedToday, int monthDays, int continuousDays, int points, LocalDate today) {}

    /** 点一下签到之后的结果：加了几个分、为什么加。 */
    public record Result(boolean signed, int awarded, int points, int monthDays, int continuousDays, String message) {}

    /** 积分流水一行。balance 是这一笔之后的余额，前端不再自己累加。 */
    public record PointsRow(Long id, Integer delta, Integer balance, String type, String content, String createdAt) {

        public static PointsRow from(PointsRecord record) {
            return new PointsRow(record.getId(), record.getDelta(), record.getBalance(), record.getType(),
                    record.getContent(), TimeText.format(record.getCreateTime()));
        }
    }
}
