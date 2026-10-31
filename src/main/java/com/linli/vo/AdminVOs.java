package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.Blog;
import com.linli.entity.Review;
import java.util.List;

/**
 * 管理端的返回对象。
 *
 * 和用户端 VO 分开是刻意的：用户端的 {@code ContentVOs.BlogItem} 里根本没有 status 这个字段
 * （它只可能出现在展示中的笔记上），而管理台要的就是这个字段和它的文字。
 * 把两边揉成一个 VO 的结果，通常是用户端多吐一个内部状态、或者管理台少一列没法用。
 */
public final class AdminVOs {

    private AdminVOs() {}

    /** 笔记管理列表的一行。正文给全文：管理员判断违不违规要看上下文，截断反而要再点进去。 */
    public record BlogRow(Long id, Long authorId, String authorName, Long shopId, String shopName,
                          String title, String content, String cover,
                          Integer likeCount, Integer commentCount, Integer status, String statusText,
                          String createdAt) {

        public static BlogRow from(Blog blog) {
            return new BlogRow(blog.getId(), blog.getUserId(), blog.getAuthorName(),
                    blog.getShopId(), blog.getShopName(), blog.getTitle(), blog.getContent(), blog.getCover(),
                    nvl(blog.getLikeCount()), nvl(blog.getCommentCount()), blog.getStatus(),
                    statusText(blog.getStatus()), TimeText.format(blog.getCreateTime()));
        }

        /** 状态文字由服务端给，前端不再自己写一份 0/1 的映射表。 */
        private static String statusText(Integer status) {
            return Integer.valueOf(0).equals(status) ? "已下架" : "展示中";
        }
    }

    /** 评价审核列表的一行。 */
    public record ReviewRow(Long id, Long authorId, String authorName, Long shopId, String shopName,
                            Long orderId, String content, Integer score, List<String> images,
                            Integer status, String statusText, String createdAt) {

        public static ReviewRow from(Review review) {
            return new ReviewRow(review.getId(), review.getUserId(), review.getAuthorName(),
                    review.getShopId(), review.getShopName(), review.getOrderId(), review.getContent(),
                    review.getScore(), ReviewVO.split(review.getImages()), review.getStatus(),
                    statusText(review.getStatus()), TimeText.format(review.getCreateTime()));
        }

        private static String statusText(Integer status) {
            return Integer.valueOf(2).equals(status) ? "已驳回" : "已通过";
        }
    }

    /** 改状态的结果：管理员点完要知道"现在到底是什么状态"，所以回整行而不是 boolean。 */
    public record StatusResult(Long id, Integer status, String statusText) {
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
