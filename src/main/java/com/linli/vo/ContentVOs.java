package com.linli.vo;

import com.linli.common.ImageVariant;
import com.linli.common.TimeText;
import com.linli.entity.Blog;
import com.linli.entity.BlogComment;
import java.util.List;

/**
 * 内容域的返回对象。作者信息统一收成 AuthorVO，而不是把 user 实体往外抛——
 * user 里可能有手机号，笔记列表页不需要这些。
 */
public final class ContentVOs {

    private ContentVOs() {}

    /**
     * 笔记作者。avatarThumb 是从 avatar 派生出来的第二个规格，不是新数据——列表页那些 24~30px 的
     * 小圈没道理去下载原图；emoji 头像和本地存储的地址会在派生时原样返回，所以前端两个字段随便用哪个
     * 都不会拿到空。
     */
    public record Author(Long id, String nickname, String avatar, String avatarThumb) {

        public Author(Long id, String nickname, String avatar) {
            this(id, nickname, avatar, ImageVariant.avatarThumb(avatar));
        }
    }

    /** 列表项：卡片上要看的东西，正文截断由前端做，这里给全文。 */
    public record BlogItem(Long id, Author author, Long shopId, String shopName, String title, String cover,
                           Integer likeCount, Integer commentCount, boolean liked, String createdAt) {

        public static BlogItem from(Blog blog, boolean liked) {
            return new BlogItem(blog.getId(), new Author(blog.getUserId(), blog.getAuthorName(), blog.getAuthorIcon()),
                    blog.getShopId(), blog.getShopName(), blog.getTitle(), blog.getCover(),
                    nvl(blog.getLikeCount()), nvl(blog.getCommentCount()), liked,
                    TimeText.format(blog.getCreateTime()));
        }
    }

    /** 详情：比列表多正文和图片，另外带上前人/后人的关系状态。 */
    public record BlogDetail(Long id, Author author, Long shopId, String shopName, String title, String content,
                             String cover, List<String> images, Integer likeCount, Integer commentCount,
                             boolean liked, boolean followingAuthor, String createdAt) {

        public static BlogDetail from(Blog blog, List<String> images, boolean liked, boolean followingAuthor) {
            return new BlogDetail(blog.getId(),
                    new Author(blog.getUserId(), blog.getAuthorName(), blog.getAuthorIcon()),
                    blog.getShopId(), blog.getShopName(), blog.getTitle(), blog.getContent(), blog.getCover(),
                    images, nvl(blog.getLikeCount()), nvl(blog.getCommentCount()), liked, followingAuthor,
                    TimeText.format(blog.getCreateTime()));
        }
    }

    /** 评论项。 */
    public record Comment(Long id, Author author, String content, String createdAt) {

        public static Comment from(BlogComment comment) {
            return new Comment(comment.getId(),
                    new Author(comment.getUserId(), comment.getAuthorName(), comment.getAuthorIcon()),
                    comment.getContent(), TimeText.format(comment.getCreateTime()));
        }
    }

    /** 点赞/取消赞的结果：前端要同时更新"图标状态"和"数字"，所以两个一起回。 */
    public record LikeResult(boolean liked, int likeCount) {}

    /**
     * 关注/取关的结果。followerCount 是**被关注对象**的粉丝数——作者卡片上那个数字。
     * 不顺手带"我自己关注了多少"：那是另一个人的视角，混在一个返回体里迟早有人用错。
     */
    public record FollowResult(boolean following, long followerCount) {}

    /** 关注 Feed：list + 下一次游标。offset 为 null 表示到底了。 */
    public record Feed(List<BlogItem> list, Long nextOffset) {}

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
