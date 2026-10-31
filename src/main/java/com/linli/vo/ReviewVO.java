package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.Review;
import java.util.Arrays;
import java.util.List;

/**
 * 评价返回对象。{@code review.images} 列里是逗号分隔的地址（V1 抄苍穹留下的形状），
 * 出接口前拆成数组，前端就不用自己 split；作者复用内容域的 Author，和笔记/评论同构。
 */
public record ReviewVO(Long id, Long orderId, ContentVOs.Author author, Integer score, String content,
                       List<String> images, String createdAt) {

    public static ReviewVO from(Review review) {
        return new ReviewVO(review.getId(), review.getOrderId(),
                new ContentVOs.Author(review.getUserId(), review.getAuthorName(), review.getAuthorIcon()),
                review.getScore(), review.getContent(), split(review.getImages()),
                TimeText.format(review.getCreateTime()));
    }

    /** 存进去用的反向操作：去空白、丢空串，逗号拼接。 */
    public static String joinImages(List<String> images) {
        if (images == null || images.isEmpty()) return null;
        return String.join(",", images);
    }

    /** 包内可见：管理端那一行也要拆同一个字段，不复制第二份拆分逻辑。 */
    static List<String> split(String images) {
        if (images == null || images.isBlank()) return List.of();
        return Arrays.stream(images.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
