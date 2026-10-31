package com.linli.service.impl;

import com.linli.common.CacheTtl;
import com.linli.common.AfterCommit;
import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.OwnImageGuard;
import com.linli.common.PageResult;
import com.linli.constant.RedisKeyConstant;
import com.linli.dto.BlogPageQueryDTO;
import com.linli.dto.BlogPublishDTO;
import com.linli.dto.CommentDTO;
import com.linli.entity.Blog;
import com.linli.entity.BlogComment;
import com.linli.entity.BlogImage;
import com.linli.entity.Notification;
import com.linli.mapper.BlogCommentMapper;
import com.linli.mapper.BlogImageMapper;
import com.linli.mapper.BlogLikeMapper;
import com.linli.mapper.BlogMapper;
import com.linli.mapper.FollowMapper;
import com.linli.mapper.ShopMapper;
import com.linli.service.BlogService;
import com.linli.service.FollowService;
import com.linli.service.NotificationService;
import com.linli.vo.ContentVOs.BlogDetail;
import com.linli.vo.ContentVOs.BlogItem;
import com.linli.vo.ContentVOs.Comment;
import com.linli.vo.ContentVOs.Feed;
import com.linli.vo.ContentVOs.LikeResult;
import com.linli.vo.MineVOs;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.linli.common.Pagination;
import com.linli.common.Text;

/**
 * 探店笔记。三条规矩：
 *
 * 1. **作者和评论人只认会话**。DTO 里没有 userId 字段，客户端传了也没人读，冒名发帖这条路不通。
 * 2. **点赞和关注的正确性由唯一索引兜底**，Service 只把"重复"翻译成当前状态而不是 500；
 *    计数是同事务里的冗余列，读列表时不再 count(*)。
 * 3. **关注 Feed 是 Redis ZSet 收件箱**，键丢了能按 follow + blog 重建，Redis 只当加速层，不担正确性。
 */
@Service
public class BlogServiceImpl implements BlogService {

    private static final Logger log = LoggerFactory.getLogger(BlogServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    /** 没传图时的封面占位，和 shop.cover 那套表情占位一脉相承，前端按 isImageSource 区分。 */
    private static final String FALLBACK_COVER = "📝";

    private final BlogMapper blogMapper;
    private final BlogImageMapper imageMapper;
    private final BlogLikeMapper likeMapper;
    private final BlogCommentMapper commentMapper;
    private final FollowMapper followMapper;
    private final ShopMapper shopMapper;
    private final FollowService followService;
    private final NotificationService notificationService;
    private final OwnImageGuard imageGuard;
    private final StringRedisTemplate redis;

    public BlogServiceImpl(BlogMapper blogMapper, BlogImageMapper imageMapper, BlogLikeMapper likeMapper,
                           BlogCommentMapper commentMapper, FollowMapper followMapper, ShopMapper shopMapper,
                           FollowService followService, NotificationService notificationService,
                           OwnImageGuard imageGuard, StringRedisTemplate redis) {
        this.blogMapper = blogMapper;
        this.imageMapper = imageMapper;
        this.likeMapper = likeMapper;
        this.commentMapper = commentMapper;
        this.followMapper = followMapper;
        this.shopMapper = shopMapper;
        this.followService = followService;
        this.notificationService = notificationService;
        this.imageGuard = imageGuard;
        this.redis = redis;
    }

    @Override
    @Transactional
    public BlogDetail publish(BlogPublishDTO form, Long userId) {
        List<String> images = normalizeImages(form.images());
        imageGuard.requireOwnAll(images, "图片");
        if (form.shopId() != null && shopMapper.getById(form.shopId()) == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商家不存在");
        }

        Blog blog = new Blog();
        blog.setUserId(userId);
        blog.setShopId(form.shopId());
        blog.setTitle(form.title().trim());
        blog.setContent(form.content().trim());
        blog.setCover(images.isEmpty() ? FALLBACK_COVER : images.get(0));
        blogMapper.insert(blog);

        if (!images.isEmpty()) {
            List<BlogImage> rows = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                BlogImage image = new BlogImage();
                image.setBlogId(blog.getId());
                image.setUrl(images.get(i));
                image.setSort(i);
                rows.add(image);
            }
            imageMapper.insertBatch(rows);
        }

        // 推送放在同一个事务里：收件箱里多一条还没提交的笔记，读详情时会 404，
        // 而少推一条只是粉丝晚看到一次——两害相权，宁可少推也不推空。
        Blog saved = blogMapper.getOnlineById(blog.getId());
        LocalDateTime createTime = saved == null || saved.getCreateTime() == null
                ? LocalDateTime.now() : saved.getCreateTime();
        followService.pushToFollowers(userId, blog.getId(), toEpochSeconds(createTime));
        return detail(blog.getId(), userId);
    }

    @Override
    public PageResult<BlogItem> page(BlogPageQueryDTO query, Long viewerId) {
        BlogPageQueryDTO condition = query == null ? new BlogPageQueryDTO() : query;
        condition.setUserId(viewerId);
        long total = blogMapper.countQuery(condition);
        if (total == 0) {
            return new PageResult<>(List.of(), 0, condition.normalizedPage(), condition.normalizedPageSize());
        }
        List<BlogItem> list = blogMapper.pageQuery(condition).stream()
                .map(blog -> BlogItem.from(blog, Boolean.TRUE.equals(blog.getLiked())))
                .toList();
        return new PageResult<>(list, total, condition.normalizedPage(), condition.normalizedPageSize());
    }

    @Override
    public BlogDetail detail(Long blogId, Long viewerId) {
        Blog blog = requireOnline(blogId);
        List<String> images = imageMapper.listByBlog(blog.getId()).stream().map(BlogImage::getUrl).toList();
        boolean liked = viewerId != null && likeMapper.exists(blog.getId(), viewerId) > 0;
        boolean following = followService.isFollowing(viewerId, blog.getUserId());
        return BlogDetail.from(blog, images, liked, following);
    }

    @Override
    @Transactional
    public LikeResult like(Long blogId, Long userId) {
        Blog blog = requireOnline(blogId);
        try {
            likeMapper.insert(blogId, userId);
            blogMapper.updateLikeCount(blogId, 1);
            // 只有真插进去了一行才提醒作者；连点第二次走 catch，不会重复发。取消点赞也不撤回消息
            notificationService.record(Notification.TYPE_LIKE_BLOG, blog.getUserId(), userId, blogId, null);
        } catch (DuplicateKeyException e) {
            // 连点两次：第二次就该回"已赞 + 原计数"，不再多加一次
            log.debug("重复点赞: blogId={} userId={}", blogId, userId);
        }
        return likeResult(blogId, userId);
    }

    @Override
    @Transactional
    public LikeResult unlike(Long blogId, Long userId) {
        requireOnline(blogId);
        // 删掉了一行才说明这一赞确实是我取消的，才配减一次计数；没删掉就只是"当前未赞"
        if (likeMapper.delete(blogId, userId) > 0) {
            blogMapper.updateLikeCount(blogId, -1);
        }
        return likeResult(blogId, userId);
    }

    @Override
    public PageResult<Comment> comments(Long blogId, int page, int pageSize) {
        requireOnline(blogId);
        Pagination query = Pagination.of(page, pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = query.page();
        int safeSize = query.size();
        long total = commentMapper.countByBlog(blogId);
        List<Comment> list = total == 0 ? List.of()
                : commentMapper.pageByBlog(blogId, (safePage - 1) * safeSize, safeSize).stream()
                        .map(Comment::from).toList();
        return new PageResult<>(list, total, safePage, safeSize);
    }

    @Override
    public PageResult<MineVOs.BlogRow> pageMine(Long userId, int page, int pageSize) {
        Pagination query = Pagination.of(page, pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = query.page();
        int safeSize = query.size();
        long total = blogMapper.countMine(userId);
        List<MineVOs.BlogRow> list = total == 0 ? List.of()
                : blogMapper.pageMine(userId, (safePage - 1) * safeSize, safeSize).stream()
                        .map(MineVOs.BlogRow::from).toList();
        return new PageResult<>(list, total, safePage, safeSize);
    }

    @Override
    @Transactional
    public void deleteMine(Long userId, Long id) {
        Blog blog = id == null ? null : blogMapper.getAnyById(id);
        // 不存在和"存在但不是你的"回同一句话：否则这个接口能用来探测别人的笔记 id。
        // 和删自己的评价那条完全一致——两条同构的接口必须长得一样，不然会被问"为什么不一样"。
        if (blog == null || !Objects.equals(userId, blog.getUserId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "笔记不存在");
        }
        // 子表先清、主行后删：顺序反了也不会错（同一个事务），但读起来就是级联的方向。
        int images = imageMapper.deleteByBlog(id);
        int likes = likeMapper.deleteByBlog(id);
        int comments = commentMapper.deleteByBlog(id);
        int deleted = blogMapper.deleteByIdAndUser(id, userId);
        if (deleted == 0) {
            // 查的时候还是他的，删的时候不是了：这中间归属变了。
            // 这里必须抛，让**整个事务回滚**——否则图片/点赞/评论已经清干净了，笔记却还在，
            // 留下一条"打不开也没有作者"的空笔记，比删失败严重得多。
            throw new ApiException(ErrorCode.NOT_FOUND, "笔记不存在");
        }
        log.debug("用户删除自己的笔记: blogId={} userId={} 连带图片 {} 张、点赞 {} 条、评论 {} 条",
                id, userId, images, likes, comments);
        // 收件箱里的这一条也要摘掉，而且只能在提交之后：事务回滚了却把粉丝的收件箱改了，
        // 结果是"笔记还在、粉丝的 Feed 里再也没有"——比不摘严重得多。不摘本身不会报错
        // （读的时候按 id 回库、status=1 过滤），但那一格会一直空着，硬删的行又永远回不来。
        AfterCommit.run(() -> followService.removeFromFollowers(userId, id));
    }

    @Override
    public PageResult<MineVOs.CommentRow> myComments(Long userId, int page, int pageSize) {
        Pagination query = Pagination.of(page, pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = query.page();
        int safeSize = query.size();
        long total = commentMapper.countByAuthor(userId);
        List<MineVOs.CommentRow> list = total == 0 ? List.of()
                : commentMapper.pageByAuthor(userId, (safePage - 1) * safeSize, safeSize).stream()
                        .map(MineVOs.CommentRow::from).toList();
        return new PageResult<>(list, total, safePage, safeSize);
    }

    @Override
    @Transactional
    public Comment comment(Long blogId, Long userId, CommentDTO form) {
        Blog blog = requireOnline(blogId);
        if (form == null || form.content() == null || form.content().isBlank()) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "请填写评论内容");
        }
        BlogComment comment = new BlogComment();
        comment.setBlogId(blog.getId());
        comment.setUserId(userId);
        comment.setContent(form.content().trim());
        commentMapper.insert(comment);
        blogMapper.increaseCommentCount(blog.getId());
        notificationService.record(Notification.TYPE_COMMENT_BLOG, blog.getUserId(), userId, blog.getId(),
                comment.getContent());
        // 评论人是当前会话的用户，不是笔记的作者。昵称头像按列表那条 join 回读一次，
        // 发出去的响应和刷新后看到的内容才是同一个人。
        return Comment.from(commentMapper.getById(comment.getId()));
    }

    @Override
    public Feed followFeed(Long userId, Long offset, int pageSize) {
        int size = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String key = RedisKeyConstant.followFeed(userId);
        List<Entry> page = readPage(key, offset, size);

        if (page.isEmpty() && offset == null && !hasEmptyMarker(userId)) {
            // 收件箱空着：可能真没内容，也可能是 Redis 重启过。回源重建一次再读，只重建这一次。
            rebuild(userId, key);
            page = readPage(key, null, size);
        }
        if (page.isEmpty()) {
            if (offset == null) markEmpty(userId);
            return new Feed(List.of(), null);
        }

        List<Long> ids = page.stream().map(Entry::blogId).toList();
        Map<Long, Blog> byId = new HashMap<>();
        blogMapper.listByIds(ids, userId).forEach(blog -> byId.put(blog.getId(), blog));
        // 顺序按 ZSet 返回的来（新→旧），SQL 不参与排序，所以这里按 ids 排回去
        List<BlogItem> list = ids.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(blog -> BlogItem.from(blog, Boolean.TRUE.equals(blog.getLiked())))
                .toList();
        Long next = page.size() < size ? null : page.get(page.size() - 1).score();
        return new Feed(list, next);
    }

    // ---- helpers ----

    /** ZSet 里的一项：笔记 id + 当 score 用的发布时间戳。 */
    private record Entry(Long blogId, long score) {}

    /**
     * 滚动分页读收件箱。offset 传 null 表示第一页（上界取 +∞ 那一头）；
     * 传上一页最后一个 score 时，用它减 1 当上界，边界那条不会重复出现。
     *
     * 注意参数顺序：{@code reverseRangeByScoreWithScores(key, min, max, offset, count)} 的区间参数是
     * **先小后大**，和 {@code rangeByScore…} 一样，只有返回顺序反过来（分数从大到小）。名字里的
     * reverse 容易骗人，写成 (max, min) 就是倒序区间，Redis 认为区间为空，Feed 会稳定返回空列表且不报错——
     * 这里踩过一次，所以单测把两个边界值都断言死了，不许再用 anyDouble 放过。
     */
    private List<Entry> readPage(String key, Long offset, int size) {
        double max = offset == null ? Double.MAX_VALUE : offset - 1;
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, 0, size);
        if (tuples == null || tuples.isEmpty()) return List.of();
        List<Entry> out = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null) continue;
            out.add(new Entry(Long.valueOf(tuple.getValue()),
                    tuple.getScore() == null ? 0L : tuple.getScore().longValue()));
        }
        return out;
    }

    /** 按 follow + blog 重建收件箱：Redis 被清或到期后，第一个读 Feed 的人付一次查询成本。 */
    private void rebuild(Long userId, String key) {
        List<Long> authors = followMapper.listFollowees(userId);
        if (authors.isEmpty()) return;
        List<Blog> recent = blogMapper.listRecentByAuthors(authors, RedisKeyConstant.FOLLOW_FEED_MAX);
        if (recent.isEmpty()) return;
        for (Blog blog : recent) {
            redis.opsForZSet().add(key, String.valueOf(blog.getId()), toEpochSeconds(blog.getCreateTime()));
        }
        trim(key);
        redis.expire(key, CacheTtl.of(RedisKeyConstant.FOLLOW_FEED_TTL_SECONDS));
        log.info("关注 Feed 已重建: userId={} 关注作者数={} 笔记数={}", userId, authors.size(), recent.size());
    }

    /** 只留最近 FOLLOW_FEED_MAX 条：按分数升序削掉最老的。 */
    private void trim(String key) {
        redis.opsForZSet().removeRange(key, 0, -1 - RedisKeyConstant.FOLLOW_FEED_MAX);
    }

    private boolean hasEmptyMarker(Long userId) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeyConstant.feedEmptyMarker(userId)));
    }

    /** "重建过但确实是空的"占位，只留 1 分钟；关注了新人之后最多等一分钟就能看到新内容。 */
    private void markEmpty(Long userId) {
        redis.opsForValue().set(RedisKeyConstant.feedEmptyMarker(userId), "1",
                CacheTtl.of(RedisKeyConstant.FEED_EMPTY_TTL_SECONDS));
    }

    private Blog requireOnline(Long blogId) {
        Blog blog = blogId == null ? null : blogMapper.getOnlineById(blogId);
        if (blog == null) throw new ApiException(ErrorCode.NOT_FOUND, "笔记不存在或已下架");
        return blog;
    }

    private LikeResult likeResult(Long blogId, Long userId) {
        Blog blog = blogMapper.getOnlineById(blogId);
        int count = blog == null || blog.getLikeCount() == null ? 0 : blog.getLikeCount();
        return new LikeResult(likeMapper.exists(blogId, userId) > 0, count);
    }

    private static List<String> normalizeImages(List<String> images) {
        return Text.distinctNonBlank(images);
    }

    private static long toEpochSeconds(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
