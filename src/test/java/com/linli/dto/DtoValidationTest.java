package com.linli.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.linli.constant.AuthConstant;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DTO 注解的单元测试。P1-5 把手写校验换成了 Bean Validation，这些用例就是换的凭据：
 * 每条 message 都是原来 Service 里那句中文，改了注解也不能把话改跑调。
 *
 * 这里直接调 Validator，不起 Spring 上下文——@Valid 由 Controller 触发那一段留给接口实测。
 */
class DtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("完整合法的下单请求不产生任何违规")
    void validOrderPasses() {
        assertThat(validator.validate(order(1L, 20L, 1))).isEmpty();
        // 联系人这几项都可选：团购到店消费，不填也能下单
        OrderSubmitDTO minimal = order(1L, 20L, 1);
        minimal.setConsignee(null);
        minimal.setPhone(null);
        minimal.setAddress(null);
        minimal.setRemark(null);
        assertThat(validator.validate(minimal)).isEmpty();
    }

    @Test
    @DisplayName("缺商家 id、明细为空、明细超 20 行：都按原来 Service 的文案拒绝")
    void orderShapeRejected() {
        OrderSubmitDTO noShop = order(null, 20L, 1);
        assertThat(messages(noShop)).containsExactly("缺少商家 id");

        OrderSubmitDTO noItems = order(1L, 20L, 1);
        noItems.setItems(List.of());
        assertThat(messages(noItems)).containsExactly("订单至少要选一个套餐");

        OrderSubmitDTO tooMany = order(1L, 20L, 1);
        List<OrderItemDTO> items = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            items.add(item(20L + i, 1));
        }
        tooMany.setItems(items);
        assertThat(messages(tooMany)).containsExactly("单笔订单最多 20 个套餐");
    }

    @Test
    @DisplayName("明细行里的错误带得出嵌套路径：前端能定位到是哪一行")
    void itemViolationsAreNested() {
        OrderSubmitDTO form = order(1L, 20L, 1);
        form.setItems(List.of(item(20L, 0)));

        assertThat(validator.validate(form))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("items[0].quantity");
                    assertThat(v.getMessage()).isEqualTo("套餐数量至少为 1");
                });
    }

    @Test
    @DisplayName("数量为空、为 0、超过 99 都拒绝——不再静默按 1 或 99 纠正")
    void quantityMustBeInRange() {
        assertThat(messages(item(20L, null))).containsExactly("请填写套餐数量");
        assertThat(messages(item(20L, 0))).containsExactly("套餐数量至少为 1");
        assertThat(messages(item(20L, -3))).containsExactly("套餐数量至少为 1");
        assertThat(messages(item(20L, 100))).containsExactly("单个套餐一次最多购买 99 份");
        assertThat(messages(item(null, 1))).containsExactly("缺少套餐 id");
    }

    @Test
    @DisplayName("手机号要么不填，要填就得是 11 位；姓名/地址/备注按列宽卡长度")
    void contactFields() {
        assertThat(messagesOfContact("13800138000")).isEmpty();
        assertThat(messagesOfContact(null)).isEmpty();
        assertThat(messagesOfContact("")).isEmpty();
        assertThat(messagesOfContact("1380013800")).containsExactly("联系电话格式不正确");
        assertThat(messagesOfContact("23800138000")).containsExactly("联系电话格式不正确");

        OrderSubmitDTO longName = order(1L, 20L, 1);
        longName.setConsignee("字".repeat(51));
        assertThat(messages(longName)).containsExactly("联系人姓名最多 50 个字符");

        OrderSubmitDTO longRemark = order(1L, 20L, 1);
        longRemark.setRemark("字".repeat(256));
        assertThat(messages(longRemark)).containsExactly("备注最多 255 个字符");
    }

    @Test
    @DisplayName("资料表单：昵称可缺省（不改），但一旦传了就得是 1-20 个字符")
    void profileFormRules() {
        assertThat(validator.validate(new ProfileForm(null, null, null))).isEmpty();
        assertThat(validator.validate(new ProfileForm("小明", null, null))).isEmpty();
        assertThat(validator.validate(new ProfileForm("字".repeat(20), null, null))).isEmpty();
        assertThat(messages(new ProfileForm("字".repeat(21), null, null)))
                .containsExactly("昵称长度需为 1-20 个字符");
        assertThat(messages(new ProfileForm("", null, null))).containsExactly("昵称长度需为 1-20 个字符");
    }

    @Test
    @DisplayName("注册表单：手机号和验证码按形状卡死，密码必须 8-32 位且含字母和数字")
    void registerFormRules() {
        assertThat(validator.validate(new RegisterForm("13800138000", "123456", "abcd1234", null))).isEmpty();
        assertThat(validator.validate(new RegisterForm("13800138000", "123456", "Abcd1234!", "小明")))
                .isEmpty();

        assertThat(messages(new RegisterForm("1380013800", "123456", "abcd1234", null)))
                .containsExactly("手机号格式不正确");
        assertThat(messages(new RegisterForm("13800138000", "12345", "abcd1234", null)))
                .containsExactly("验证码是 6 位数字");
        // 纯数字、纯字母、太短、太长、带空格，五种弱密码全拒
        for (String weak : new String[]{"12345678", "abcdefgh", "ab1", "a".repeat(8) + "b".repeat(25),
                "abcd 1234"}) {
            assertThat(messages(new RegisterForm("13800138000", "123456", weak, null)))
                    .as("密码 %s 应被拒", weak)
                    .containsExactly(AuthConstant.PASSWORD_MESSAGE);
        }
        assertThat(messages(new RegisterForm("13800138000", "123456", "", null)))
                .contains("请填写密码");
        assertThat(messages(new RegisterForm("13800138000", "123456", "abcd1234", "字".repeat(21))))
                .containsExactly("昵称长度需为 1-20 个字符");
    }

    @Test
    @DisplayName("密码表单：新密码同样受强度约束，改密码接口不重复校验旧密码格式")
    void passwordFormRules() {
        assertThat(validator.validate(new PasswordForm("old12345", "abcd1234"))).isEmpty();
        // 从没设过密码的账号可以不带原密码
        assertThat(validator.validate(new PasswordForm(null, "abcd1234"))).isEmpty();
        assertThat(messages(new PasswordForm("abcd1234", "12345678")))
                .containsExactly(AuthConstant.PASSWORD_MESSAGE);
        assertThat(messages(new PasswordForm("abcd1234", null))).containsExactly("请填写新密码");
    }

    @Test
    @DisplayName("密码登录：手机号仍按形状卡，密码只要求非空（老密码可能定于早期规则）")
    void passwordLoginRules() {
        assertThat(validator.validate(new PasswordLoginForm("13800138000", "anything"))).isEmpty();
        assertThat(messages(new PasswordLoginForm("123", "anything"))).containsExactly("手机号格式不正确");
        assertThat(messages(new PasswordLoginForm("13800138000", "  ")))
                .containsExactly("请填写密码");
    }

    // ---- helpers ----

    private static Set<ConstraintViolation<Object>> raw(Object target) {
        return validator.validate(target);
    }

    private static List<String> messages(Object target) {
        return raw(target).stream().map(v -> v.getMessage()).sorted().toList();
    }

    private static List<String> messagesOfContact(String phone) {
        OrderSubmitDTO form = order(1L, 20L, 1);
        form.setPhone(phone);
        return messages(form);
    }

    private static OrderSubmitDTO order(Long shopId, Long goodsId, int quantity) {
        OrderSubmitDTO form = new OrderSubmitDTO();
        form.setShopId(shopId);
        form.setItems(List.of(item(goodsId, quantity)));
        form.setConsignee("小明");
        form.setPhone("13800138000");
        form.setAddress("幸福路 1 号");
        form.setRemark("少放辣");
        return form;
    }

    private static OrderItemDTO item(Long goodsId, Integer quantity) {
        OrderItemDTO item = new OrderItemDTO();
        item.setGoodsId(goodsId);
        item.setQuantity(quantity);
        return item;
    }
}
