package com.linli.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** sign_record 表实体：一人一天一条，重复签到由唯一索引挡在库里。 */
@Data
public class SignRecord {

    private Long id;

    private Long userId;

    /** 签到日期。只到"天"，所以时区按服务器本地日期算，跨零点即换下一条。 */
    private LocalDate dateSign;

    private LocalDateTime createTime;
}
