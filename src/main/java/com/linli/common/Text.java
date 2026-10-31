package com.linli.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文本与字符串列表的公共小规矩，**只有一份实现**。
 *
 * 为什么值得单独一个类：{@code trimToNull} 之前在三个 Service 里逐字复制了三次，
 * {@code normalizeImages} 在两个 Service 里复制了两次。复制本身不会立刻出错，
 * 出错的是"以后只改了一处"——比如哪天决定"昵称要保留中间空格但去掉全角空格"，
 * 改了 UserService 没改 OrderService，同一个字段在下单和改资料里就被裁成两种值，
 * 然后数据库里出现两条看起来一样却不等的地址。
 *
 * 本项目已经有 {@link OwnImageGuard}（"只能用自己上传的地址"只写一处）和
 * {@link UploadUrl}（前缀只算一次）这两种单一出口，文本这条也该按同一个标准来。
 */
public final class Text {

    private Text() {}

    /** 去首尾空白；null 和空白都归一成 null，让"没填"和"填了空格"走同一个分支。 */
    public static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 图片列表入库前的整理：去空白项、去重、**保持用户提交的顺序**。
     *
     * 保持顺序是有意的——笔记和评价的图是按这个顺序展示的，用 Set 的无序实现会让同一份提交
     * 每次落库顺序不同，前端九宫格就会"跳"。
     */
    public static List<String> distinctNonBlank(List<String> values) {
        if (values == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String item = trimToNull(value);
            if (item != null) seen.add(item);
        }
        return new ArrayList<>(seen);
    }
}
