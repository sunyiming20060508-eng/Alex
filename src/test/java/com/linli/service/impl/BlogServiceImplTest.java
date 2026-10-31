package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.OwnImageGuard;
import com.linli.common.UploadUrl;
import com.linli.common.PageResult;
import com.linli.common.CacheTtlMatcher;
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
import com.linli.service.FollowService;
import com.linli.service.NotificationService;
import com.linli.vo.ContentVOs.BlogDetail;
import com.linli.vo.ContentVOs.BlogItem;
import com.linli.vo.MineVOs;
import com.linli.vo.ContentVOs.Comment;
import com.linli.vo.ContentVOs.Feed;
import com.linli.vo.ContentVOs.LikeResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * 笔记、点赞、评论和关注 Feed 的单元测试。守的是四条不能被绕过的规矩：
 * 作者只认会话、图片只认自家地址、重复点赞不重复计数、收件箱空了要能回源重建。
 */
@ExtendWith(MockitoExtension.class)
class BlogServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long BLOG_ID = 100L;
    private static final String FEED_KEY = RedisKeyConstant.followFeed(USER_ID);

    @Mock private BlogMapper blogMapper;
    @Mock private BlogImageMapper imageMapper;
    @Mock private BlogLikeMapper likeMapper;
    @Mock private BlogCommentMapper commentMapper;
    @Mock private FollowMapper followMapper;
    @Mock private ShopMapper shopMapper;
    @Mock private FollowService followService;
    @Mock private NotificationService notificationService;
    @Mock private StringRedisTemplate redis;
    @Mock private ZSetOperations<String, String> zSet;
    @Mock private ValueOperations<String, String> values;

    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BlogServiceImpl(blogMapper, imageMapper, likeMapper, commentMapper, followMapper,
                shopMapper, followService, notificationService, new OwnImageGuard(UploadUrl.fixed("/api/file/images")), redis);
    }

    // ---------- 发布 ----------

    @Test
    @DisplayName("作者取自会话：DTO 里根本没有 userId 可传，冒名发帖这条路不通")
    void authorComesFromSession() {
        Blog blog = blog(BLOG_ID, USER_ID);
        when(blogMapper.insert(any())).thenAnswer(i -> {
            i.getArgument(0, Blog.class).setId(BLOG_ID);
            return 1;
        });
        when(blogMapper.getOnlineById(BLOG_ID)).thenReturn(blog);
        when(imageMapper.listByBlog(BLOG_ID)).thenReturn(List.of());

        service.publish(new BlogPublishDTO("标题", "正文", null, null), USER_ID);

        ArgumentCaptor<Blog> inserted = ArgumentCaptor.captor();
        verify(blogMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getValue().getCover()).isEqualTo("📝");
    }

    @Test
    @DisplayName("外部图片地址一律拒，且一行都不写")
    void externalImagesRejected() {
        assertFails(() -> service.publish(
                new BlogPublishDTO("标题", "正文", null, List.of("https://evil.example.com/a.png")), USER_ID),
                40001, "图片只能使用本平台上传的图片地址");
        verify(blogMapper, never()).insert(any());
        verify(imageMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("关联的商家不存在就 40401，不会留一条指向空气的笔记")
    void unknownShopRejected() {
        when(shopMapper.getById(999L)).thenReturn(null);

        assertFails(() -> service.publish(new BlogPublishDTO("标题", "正文", 999L, null), USER_ID),
                40401, "商家不存在");
        verify(blogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("发布成功后把笔记推给粉丝，score 用的是创建时间而不是当前时间")
    void publishPushesToFollowers() {
        LocalDateTime created = LocalDateTime.now().minusMinutes(5);
        Blog blog = blog(BLOG_ID, USER_ID);
        blog.setCreateTime(created);
        when(blogMapper.insert(any())).thenAnswer(i -> {
            i.getArgument(0, Blog.class).setId(BLOG_ID);
            return 1;
        });
        when(blogMapper.getOnlineById(BLOG_ID)).thenReturn(blog);
        when(imageMapper.listByBlog(BLOG_ID)).thenReturn(List.of(image(1L, "/api/file/images/a.png")));

        service.publish(new BlogPublishDTO("标题", "正文", null,
                List.of("/api/file/images/a.png")), USER_ID);

        ArgumentCaptor<List<BlogImage>> rows = ArgumentCaptor.captor();
        verify(imageMapper).insertBatch(rows.capture());
        assertThat(rows.getValue().get(0).getBlogId()).isEqualTo(BLOG_ID);
        assertThat(rows.getValue().get(0).getSort()).isZero();
        verify(followService).pushToFollowers(eq(USER_ID), eq(BLOG_ID),
                eq(created.atZone(java.time.ZoneId.systemDefault()).toEpochSecond()));
    }

    // ---------- 列表与详情 ----------

    @Test
    @DisplayName("列表一页只发两条 SQL：count + 带 liked 的 join 查询，不逐条问\"我点过没\"")
    void pageAvoidsNPlusOne() {
        Blog first = blog(1L, 2L);
        first.setLiked(true);
        Blog second = blog(2L, 3L);
        second.setLiked(false);
        when(blogMapper.countQuery(any())).thenReturn(2L);
        when(blogMapper.pageQuery(any())).thenReturn(List.of(first, second));

        PageResult<BlogItem> page = service.page(query(1, 10), USER_ID);

        assertThat(page.list()).extracting(BlogItem::liked).containsExactly(true, false);
        assertThat(page.total()).isEqualTo(2);
        verify(blogMapper, never()).listByIds(anyList(), any());
    }

    @Test
    @DisplayName("总数为 0 时连列表查询都省了")
    void emptyPageSkipsSelect() {
        when(blogMapper.countQuery(any())).thenReturn(0L);

        assertThat(service.page(query(1, 10), USER_ID).list()).isEmpty();
        verify(blogMapper, never()).pageQuery(any());
    }

    @Test
    @DisplayName("下架或不存在的笔记：详情 40401，点赞和评论同样进不来")
    void offlineBlogIsInvisibleEverywhere() {
        when(blogMapper.getOnlineById(404L)).thenReturn(null);

        assertFails(() -> service.detail(404L, USER_ID), 40401, "笔记不存在");
        assertFails(() -> service.like(404L, USER_ID), 40401, "笔记不存在");
        assertFails(() -> service.comments(404L, 1, 10), 40401, "笔记不存在");
        verify(likeMapper, never()).insert(anyLong(), anyLong());
    }

    // ---------- 点赞 ----------

    @Test
    @DisplayName("点赞：写关系 + 计数 +1，并给笔记作者发一条消息")
    void likeAddsOnce() {
        when(blogMapper.getOnlineById(BLOG_ID)).thenReturn(blog(BLOG_ID, 2L));   // 作者是别人
        when(likeMapper.insert(BLOG_ID, USER_ID)).thenReturn(1);
        when(likeMapper.exists(BLOG_ID, USER_ID)).thenReturn(1);

        LikeResult result = service.like(BLOG_ID, USER_ID);

        verify(blogMapper).updateLikeCount(BLOG_ID, 1);
        verify(notificationService).record(Notification.TYPE_LIKE_BLOG, 2L, USER_ID, BLOG_ID, null);
        assertThat(result.liked()).isTrue();
    }

    @Test
    @DisplayName("连点两次：唯一索引接住第二次，计数只加一次，消息也不重复发")
    void duplicateLikeDoesNotDoubleCount() {
        givenOnline();
        when(likeMapper.insert(BLOG_ID, USER_ID)).thenThrow(new DuplicateKeyException("uk_blog_user"));
        when(likeMapper.exists(BLOG_ID, USER_ID)).thenReturn(1);

        LikeResult result = service.like(BLOG_ID, USER_ID);

        verify(blogMapper, never()).updateLikeCount(anyLong(), anyInt());
        verify(notificationService, never()).record(any(), any(), any(), any(), any());
        assertThat(result.liked()).isTrue();
    }

    @Test
    @DisplayName("取消赞只有真删掉一行才减计数；取消一个不存在的赞不会把计数扣成负数")
    void unlikeOnlyDecreasesWhenItDeleted() {
        givenOnline();
        when(likeMapper.delete(BLOG_ID, USER_ID)).thenReturn(0);
        when(likeMapper.exists(BLOG_ID, USER_ID)).thenReturn(0);

        LikeResult result = service.unlike(BLOG_ID, USER_ID);

        verify(blogMapper, never()).updateLikeCount(anyLong(), anyInt());
        assertThat(result.liked()).isFalse();
    }

    // ---------- 评论 ----------

    @Test
    @DisplayName("评论人取自会话，空白内容被拒；成功后计数 +1，回读出来的是评论人而不是笔记作者")
    void commentWritesAuthorAndCount() {
        givenOnline();
        when(commentMapper.insert(any())).thenAnswer(i -> {
            i.getArgument(0, BlogComment.class).setId(500L);
            return 1;
        });
        // 笔记作者是阿岚，评论人是当前登录的用户——join 带出来的必须是后者
        BlogComment saved = new BlogComment();
        saved.setId(500L);
        saved.setBlogId(BLOG_ID);
        saved.setUserId(USER_ID);
        saved.setContent("写得真好");
        saved.setAuthorName("邻里用户");
        saved.setAuthorIcon("🙂");
        saved.setCreateTime(LocalDateTime.now());
        when(commentMapper.getById(500L)).thenReturn(saved);

        Comment result = service.comment(BLOG_ID, USER_ID, new CommentDTO("  写得真好  "));

        ArgumentCaptor<BlogComment> inserted = ArgumentCaptor.captor();
        verify(commentMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getValue().getContent()).isEqualTo("写得真好");
        verify(blogMapper).increaseCommentCount(BLOG_ID);
        verify(notificationService).record(Notification.TYPE_COMMENT_BLOG, USER_ID, USER_ID, BLOG_ID, "写得真好");
        assertThat(result.author().nickname()).isEqualTo("邻里用户");
    }

    @Test
    @DisplayName("空评论不写库也不加计数")
    void blankCommentRejected() {
        givenOnline();

        assertFails(() -> service.comment(BLOG_ID, USER_ID, new CommentDTO("   ")), 40001, "请填写评论内容");
        verify(commentMapper, never()).insert(any());
        verify(blogMapper, never()).increaseCommentCount(anyLong());
    }

    // ---------- 关注 Feed ----------

    @Test
    @DisplayName("收件箱空着且没有占位：回源重建，确实没内容才写 1 分钟的\"空 Feed\"占位")
    void emptyFeedRebuildsThenMarksEmpty() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(redis.opsForValue()).thenReturn(values);
        String marker = RedisKeyConstant.feedEmptyMarker(USER_ID);
        when(redis.hasKey(marker)).thenReturn(false);
        when(zSet.reverseRangeByScoreWithScores(eq(FEED_KEY), eq(0d), eq(Double.MAX_VALUE), eq(0L), eq(10L)))
                .thenReturn(Set.of());
        when(followMapper.listFollowees(USER_ID)).thenReturn(List.of());

        Feed feed = service.followFeed(USER_ID, null, 10);

        assertThat(feed.list()).isEmpty();
        assertThat(feed.nextOffset()).isNull();
        verify(followMapper).listFollowees(USER_ID);
        // 没关注任何人 → 不该去查 blog，也不该往收件箱里写东西
        verify(blogMapper, never()).listRecentByAuthors(anyList(), anyInt());
        verify(zSet, never()).add(anyString(), anyString(), anyDouble());
        verify(values).set(eq(marker), eq("1"), CacheTtlMatcher.jittered(RedisKeyConstant.FEED_EMPTY_TTL_SECONDS));
    }

    @Test
    @DisplayName("已经有\"空 Feed\"占位时不再重建：一串假请求打不出 N 次全表查询")
    void emptyMarkerShortCircuitsRebuild() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.hasKey(RedisKeyConstant.feedEmptyMarker(USER_ID))).thenReturn(true);
        when(zSet.reverseRangeByScoreWithScores(eq(FEED_KEY), eq(0d), eq(Double.MAX_VALUE), eq(0L), eq(10L)))
                .thenReturn(Set.of());

        assertThat(service.followFeed(USER_ID, null, 10).list()).isEmpty();
        verify(followMapper, never()).listFollowees(anyLong());
    }

    @Test
    @DisplayName("Redis 被清过：按 follow + blog 重建收件箱，再正常读出一页")
    void rebuildsInboxFromDatabase() {
        LocalDateTime created = LocalDateTime.now().minusHours(2);
        long score = created.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        Blog recent = blog(BLOG_ID, 2L);
        recent.setCreateTime(created);

        when(redis.opsForZSet()).thenReturn(zSet);
        when(redis.hasKey(RedisKeyConstant.feedEmptyMarker(USER_ID))).thenReturn(false);
        when(followMapper.listFollowees(USER_ID)).thenReturn(List.of(2L));
        when(blogMapper.listRecentByAuthors(List.of(2L), RedisKeyConstant.FOLLOW_FEED_MAX))
                .thenReturn(List.of(recent));
        when(zSet.reverseRangeByScoreWithScores(eq(FEED_KEY), eq(0d), eq(Double.MAX_VALUE), eq(0L), eq(10L)))
                .thenReturn(Set.of(), tuples(BLOG_ID, score));
        when(blogMapper.listByIds(List.of(BLOG_ID), USER_ID)).thenReturn(List.of(recent));

        Feed feed = service.followFeed(USER_ID, null, 10);

        verify(zSet).add(FEED_KEY, String.valueOf(BLOG_ID), score);
        verify(redis).expire(eq(FEED_KEY), CacheTtlMatcher.jittered(RedisKeyConstant.FOLLOW_FEED_TTL_SECONDS));
        assertThat(feed.list()).extracting(BlogItem::id).containsExactly(BLOG_ID);
        // 只回了一页里的一条且不足 size，说明到底了，不该再给游标
        assertThat(feed.nextOffset()).isNull();
    }

    @Test
    @DisplayName("满页时给出下一页游标，且下一页用它当排他上界")
    void fullPageReturnsExclusiveCursor() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.reverseRangeByScoreWithScores(eq(FEED_KEY), eq(0d), eq(Double.MAX_VALUE), eq(0L), eq(3L)))
                .thenReturn(tuples(11L, 200L, 12L, 190L, 13L, 180L));
        when(zSet.reverseRangeByScoreWithScores(FEED_KEY, 0d, 179d, 0L, 3L)).thenReturn(Set.of());
        // liked 是 listByIds 那条 SQL 里的 exists 子查询算出来的，Service 不再单独问 blog_like
        Blog liked = blog(12L, 2L);
        liked.setLiked(true);
        when(blogMapper.listByIds(anyList(), eq(USER_ID)))
                .thenReturn(List.of(blog(11L, 2L), liked, blog(13L, 2L)));

        Feed feed = service.followFeed(USER_ID, null, 3);

        assertThat(feed.list()).extracting(BlogItem::id).containsExactly(11L, 12L, 13L);
        assertThat(feed.list()).extracting(BlogItem::liked).containsExactly(false, true, false);
        assertThat(feed.nextOffset()).isEqualTo(180L);
        verify(likeMapper, never()).listLikedBlogIds(anyLong(), anyList());

        service.followFeed(USER_ID, feed.nextOffset(), 3);

        // 游标是排他的：下一页区间是 [0, 179]，边界那条 180 不会重复出现。
        // 顺序必须是先小后大——写成 (179, 0) 就是倒序区间，Redis 当空区间返回，Feed 会静默变空。
        verify(zSet).reverseRangeByScoreWithScores(FEED_KEY, 0d, 179d, 0L, 3L);
    }

    @Test
    @DisplayName("笔记被删了但收件箱还有它的 id：跳过这一条而不是回一条 null 给前端")
    void danglingFeedIdsAreSkipped() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.reverseRangeByScoreWithScores(eq(FEED_KEY), eq(0d), eq(Double.MAX_VALUE), eq(0L), eq(5L)))
                .thenReturn(tuples(999L, 100L, 100L, 90L));
        when(blogMapper.listByIds(anyList(), eq(USER_ID))).thenReturn(List.of(blog(100L, 2L)));

        Feed feed = service.followFeed(USER_ID, null, 5);

        assertThat(feed.list()).extracting(BlogItem::id).containsExactly(100L);
    }

    // ---------- 删自己的笔记 ----------

    @Test
    @DisplayName("删自己的笔记：图片、点赞、评论一起清掉，主行按 id + user_id 双条件删")
    void deleteMineCascadesAndGuardsOwnership() {
        when(blogMapper.getAnyById(BLOG_ID)).thenReturn(blog(BLOG_ID, USER_ID));
        when(blogMapper.deleteByIdAndUser(BLOG_ID, USER_ID)).thenReturn(1);

        service.deleteMine(USER_ID, BLOG_ID);

        verify(imageMapper).deleteByBlog(BLOG_ID);
        verify(likeMapper).deleteByBlog(BLOG_ID);
        verify(commentMapper).deleteByBlog(BLOG_ID);
        // 关键：删主行时 user_id 也在条件里，不是"查过了就可以裸按 id 删"
        verify(blogMapper).deleteByIdAndUser(BLOG_ID, USER_ID);
        // 收件箱里的那一条也要摘掉：不摘的话粉丝那一页会永远空着一格（硬删的行回不来）
        verify(followService).removeFromFollowers(USER_ID, BLOG_ID);
    }

    @Test
    @DisplayName("笔记不存在：回 404，而且一条删除语句都不该发出去")
    void deleteMissingBlogTouchesNothing() {
        when(blogMapper.getAnyById(BLOG_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteMine(USER_ID, BLOG_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(imageMapper, likeMapper, commentMapper);
        verify(blogMapper, never()).deleteByIdAndUser(any(), any());
        // 反面对照：没删成的时候不许动任何人的收件箱
        verify(followService, never()).removeFromFollowers(any(), any());
    }

    @Test
    @DisplayName("删别人的笔记 id：和\"不存在\"回同一句话——这个接口不能用来探测别人的 id")
    void deleteOthersBlogIsIndistinguishable() {
        when(blogMapper.getAnyById(BLOG_ID)).thenReturn(blog(BLOG_ID, 9999L));

        assertThatThrownBy(() -> service.deleteMine(USER_ID, BLOG_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND))
                .hasMessage("笔记不存在");

        verifyNoInteractions(imageMapper, likeMapper, commentMapper);
        verify(blogMapper, never()).deleteByIdAndUser(any(), any());
        verify(followService, never()).removeFromFollowers(any(), any());
    }

    @Test
    @DisplayName("归属在并发窗口里变了（主行删除返回 0）：必须抛出让事务回滚，不能留下\"附属清干净了笔记还在\"的空笔记")
    void deleteRaceRollsBackInsteadOfReportingSuccess() {
        when(blogMapper.getAnyById(BLOG_ID)).thenReturn(blog(BLOG_ID, USER_ID));
        when(blogMapper.deleteByIdAndUser(BLOG_ID, USER_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteMine(USER_ID, BLOG_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(blogMapper).deleteByIdAndUser(BLOG_ID, USER_ID);
        // 这次改动最容易写错的地方：主行没删成就要抛，抛了就不能再去改粉丝的收件箱
        verify(followService, never()).removeFromFollowers(any(), any());
    }

    // ---------- helpers ----------

    private void givenOnline() {
        when(blogMapper.getOnlineById(BLOG_ID)).thenReturn(blog(BLOG_ID, USER_ID));
    }

    private static BlogPageQueryDTO query(int page, int pageSize) {
        BlogPageQueryDTO query = new BlogPageQueryDTO();
        query.setPage(page);
        query.setPageSize(pageSize);
        return query;
    }

    private static Blog blog(Long id, Long authorId) {
        Blog blog = new Blog();
        blog.setId(id);
        blog.setUserId(authorId);
        blog.setAuthorName("阿岚");
        blog.setAuthorIcon("🌿");
        blog.setTitle("标题");
        blog.setContent("正文");
        blog.setCover("📝");
        blog.setLikeCount(3);
        blog.setCommentCount(1);
        blog.setStatus(Blog.STATUS_ONLINE);
        blog.setCreateTime(LocalDateTime.now());
        return blog;
    }

    private static BlogImage image(Long id, String url) {
        BlogImage image = new BlogImage();
        image.setId(id);
        image.setBlogId(BLOG_ID);
        image.setUrl(url);
        image.setSort(0);
        return image;
    }

    /** 造一页 ZSet 结果：blogId/score 交替传入，LinkedHashSet 保住 Redis 给的新→旧顺序。 */
    private static Set<ZSetOperations.TypedTuple<String>> tuples(Long... blogIdAndScorePairs) {
        Set<ZSetOperations.TypedTuple<String>> out = new LinkedHashSet<>();
        for (int i = 0; i < blogIdAndScorePairs.length; i += 2) {
            out.add(new DefaultTypedTuple<>(String.valueOf(blogIdAndScorePairs[i]),
                    (double) blogIdAndScorePairs[i + 1]));
        }
        return out;
    }

    private static void assertFails(Runnable call, int code, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }

    @Test
    @DisplayName("我的笔记：总数为 0 就不查列表；有行时把下架状态翻成文字给作者看")
    void myBlogsExposeOfflineState() {
        Blog offline = new Blog();
        offline.setId(1L);
        offline.setUserId(7L);
        offline.setTitle("被下架的那篇");
        offline.setContent("正文");
        offline.setStatus(0);
        offline.setLikeCount(2);
        offline.setCommentCount(1);
        when(blogMapper.countMine(7L)).thenReturn(1L);
        when(blogMapper.pageMine(7L, 0, 10)).thenReturn(List.of(offline));

        PageResult<MineVOs.BlogRow> page = service.pageMine(7L, 1, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.list()).extracting(MineVOs.BlogRow::statusText).containsExactly("已下架");
    }

    @Test
    @DisplayName("我的笔记：空列表不查第二条 SQL")
    void myBlogsSkipListWhenEmpty() {
        when(blogMapper.countMine(7L)).thenReturn(0L);

        assertThat(service.pageMine(7L, 1, 10).list()).isEmpty();
        verify(blogMapper, never()).pageMine(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("我的评论：分页归一（page<1 当 1、pageSize 超上限压到 50），空结果不查列表")
    void myCommentsNormalizePaging() {
        when(commentMapper.countByAuthor(7L)).thenReturn(0L);

        assertThat(service.myComments(7L, 0, 999).pageSize()).isEqualTo(50);
        assertThat(service.myComments(7L, 3, 10).page()).isEqualTo(3);
        verify(commentMapper, never()).pageByAuthor(anyLong(), anyInt(), anyInt());
    }

}
