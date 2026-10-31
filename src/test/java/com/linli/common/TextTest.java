package com.linli.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文本公共规矩的用例。这两个方法替代的是三份 `trimToNull` 和两份 `normalizeImages` 的逐字复制，
 * 所以真正要钉住的是"复制品之间最容易分叉"的那几个边界。
 */
class TextTest {

    @Test
    @DisplayName("null、空串、纯空白都归一成 null：「没填」和「填了空格」必须是同一个分支")
    void trimToNullTreatsBlankAsMissing() {
        assertThat(Text.trimToNull(null)).isNull();
        assertThat(Text.trimToNull("")).isNull();
        assertThat(Text.trimToNull("   ")).isNull();
        assertThat(Text.trimToNull("  小明  ")).isEqualTo("小明");
    }

    @Test
    @DisplayName("图片列表：去空白项、去重，并且保持提交顺序（九宫格不能跳）")
    void distinctNonBlankKeepsOrderAndDropsBlanks() {
        List<String> input = Arrays.asList("https://a/1.jpg", null, "  ", "https://a/2.jpg", "https://a/1.jpg");

        assertThat(Text.distinctNonBlank(input)).containsExactly("https://a/1.jpg", "https://a/2.jpg");
    }

    @Test
    @DisplayName("每项都去掉首尾空格再入库，但中间空格保留（文件名式的地址不能被裁坏）")
    void trimsEachItemWithoutTouchingInnerSpaces() {
        assertThat(Text.distinctNonBlank(List.of("  https://a/one two.jpg  ")))
                .containsExactly("https://a/one two.jpg");
    }

    @Test
    @DisplayName("null 列表当空列表处理，不给调用方留 NPE")
    void nullListIsEmpty() {
        assertThat(Text.distinctNonBlank(null)).isEmpty();
    }
}
