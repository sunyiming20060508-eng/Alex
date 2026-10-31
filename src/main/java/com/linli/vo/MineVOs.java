package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.Blog;
import com.linli.entity.BlogComment;
import com.linli.entity.Review;
import java.util.List;

/**
 * "我的内容"三个列表的返回对象（我发的笔记 / 我发的评论 / 我写的评价）。
 *
 * 单独一个容器而不是往 {@code ContentVOs} 里塞，是因为这几行**带的字段是给作者本人看的**：
 * 笔记要带下架状态（别人的列表里永远只有展示中的），评价要带是哪家店、哪一单（他要能想起来自己评的是什么）。
 */
public final class MineVOs {

    private MineVOs() {}

    /** 我的笔记：带 status，被下架的那条要看得见，否则作者只会觉得"我写的东西凭空消失了"。 */
    public record BlogRow(Long id, String title, String content, String cover, Long shopId, String shopName,
                          Integer likeCount, Integer commentCount, Integer status, String statusText,
                          String createdAt) {

        public static BlogRow from(Blog blog) {
            boolean offline = Integer.valueOf(0).equals(blog.getStatus());
            return new BlogRow(blog.getId(), blog.getTitle(), blog.getContent(), blog.getCover(),
                    blog.getShopId(), blog.getShopName(), nvl(blog.getLikeCount()), nvl(blog.getCommentCount()),
                    blog.getStatus(), offline ? "已下架" : "展示中", TimeText.format(blog.getCreateTime()));
        }
    }

    /** 我的评论：带它挂在谁的哪篇笔记下，否则翻自己写过什么全是断句。 */
    public record CommentRow(Long id, Long blogId, String blogTitle, String blogAuthorName,
                             String content, String createdAt) {

        public static CommentRow from(BlogComment comment) {
            return new CommentRow(comment.getId(), comment.getBlogId(), comment.getBlogTitle(),
                    comment.getBlogAuthorName(), comment.getContent(), TimeText.format(comment.getCreateTime()));
        }
    }

    /** 我写的评价：带商家和订单，删掉之后那一单会重新变成"可评价"。 */
    public record ReviewRow(Long id, Long shopId, String shopName, Long orderId, Integer score,
                            String content, List<String> images, String createdAt) {

        public static ReviewRow from(Review review) {
            return new ReviewRow(review.getId(), review.getShopId(), review.getShopName(), review.getOrderId(),
                    review.getScore(), review.getContent(), ReviewVO.split(review.getImages()),
                    TimeText.format(review.getCreateTime()));
        }
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
