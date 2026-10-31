package com.linli.service;

import com.linli.common.PageResult;
import com.linli.dto.BlogPageQueryDTO;
import com.linli.dto.BlogPublishDTO;
import com.linli.dto.CommentDTO;
import com.linli.vo.ContentVOs.BlogDetail;
import com.linli.vo.ContentVOs.BlogItem;
import com.linli.vo.ContentVOs.Comment;
import com.linli.vo.ContentVOs.Feed;
import com.linli.vo.ContentVOs.LikeResult;
import com.linli.vo.MineVOs;

/** 探店笔记：发布、列表、详情、点赞、评论，以及关注 Feed 的读取。 */
public interface BlogService {

    /** 发布笔记（作者取自会话），成功后已经把笔记推给作者的粉丝。 */
    BlogDetail publish(BlogPublishDTO form, Long userId);

    /** 推荐流：按时间倒序分页，可按作者或商家过滤。 */
    PageResult<BlogItem> page(BlogPageQueryDTO query, Long viewerId);

    /** 笔记详情：带图片、我是否点过赞、我是否关注了作者。 */
    BlogDetail detail(Long blogId, Long viewerId);

    /** 点赞；重复点返回当前状态，不产生第二行也不多加一次数。 */
    LikeResult like(Long blogId, Long userId);

    /** 取消点赞；没点过也返回成功状态。 */
    LikeResult unlike(Long blogId, Long userId);

    PageResult<Comment> comments(Long blogId, int page, int pageSize);

    /** 我发的笔记：**含被下架的**。作者本人必须看得见"我写的东西现在是什么状态"，而不是它凭空消失。 */
    PageResult<MineVOs.BlogRow> pageMine(Long userId, int page, int pageSize);

    /** 我发出的评论，带它挂在谁的哪篇笔记下。 */
    PageResult<MineVOs.CommentRow> myComments(Long userId, int page, int pageSize);

    /**
     * 硬删自己的笔记，连带清掉它的图片、点赞、评论。
     *
     * 为什么是硬删而不是改 {@code status}：{@code status=0} 已经被"管理端下架"占了，
     * 再拿一个值表示"作者自己删的"，等于把两个主体的两个动作挤进同一列，事后分不出来；
     * 要做真软删得单独加 {@code deleted} 列并让所有读路径都带上它，那是另一个量级的改动。
     * 而且作者要的语义就是"这条没了"，和已上线的"删自己的评价"保持同构。
     *
     * 不连带动的是通知：别人收到的"赞了你的笔记"是**已经发生过的事实**，
     * 而且那是别人的收件箱，我没有权限替他删。点进去会看到"笔记不存在"，这是刻意的边界。
     */
    void deleteMine(Long userId, Long id);

    Comment comment(Long blogId, Long userId, CommentDTO form);

    /**
     * 关注 Feed：Redis ZSet 收件箱 + 滚动分页。
     *
     * @param offset 上一页返回的 nextOffset（这一页最小的时间戳）；null 表示第一页
     */
    Feed followFeed(Long userId, Long offset, int pageSize);
}
