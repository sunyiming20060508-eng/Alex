package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.dto.AddressForm;
import com.linli.entity.UserAddress;
import com.linli.mapper.UserAddressMapper;
import com.linli.service.UserAddressService;
import com.linli.vo.AddressVO;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收货地址的实现。三条规则都是能对着界面讲的那种：
 *
 * 1. **每人最多 5 条**（{@value #MAX_ADDRESSES}）。上限是给"选地址"这个交互用的，不是存储配额：
 *    超过 5 条的下拉框已经没人翻了，与其做一个要排序要折叠的地址簿，不如把上限说死。
 * 2. **第一条自动成为默认地址**，省一次点击，也保证"有地址但没有默认"这种状态不会由新增产生。
 * 3. **只有设默认，没有取消默认**：把唯一的默认取消掉会让下单弹窗失去预填，而"换一条当默认"
 *    本来就是设默认。所以 {@code isDefault=false} 在更新时是"不动"，不是"清掉"。
 *
 * 删掉默认地址后**不自动补**一个默认——默认是用户表达过的意图，系统不该替他猜；
 * 界面此时显示"还没有默认地址"，让他自己点一次。
 */
@Service
public class UserAddressServiceImpl implements UserAddressService {

    /**
     * 日志只打 id 和 userId，**不打收货人、手机号、地址**：这三样是个人信息，
     * 一旦进了控制台就会被复制进日志文件、采集进监控系统，而排查问题只需要知道"哪一条、谁的那一条"。
     */
    private static final Logger log = LoggerFactory.getLogger(UserAddressServiceImpl.class);

    private static final int MAX_ADDRESSES = 5;

    private final UserAddressMapper addressMapper;

    public UserAddressServiceImpl(UserAddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    @Override
    public List<AddressVO> list(Long userId) {
        return addressMapper.listByUser(userId).stream().map(AddressVO::from).toList();
    }

    @Override
    @Transactional
    public AddressVO add(Long userId, AddressForm form) {
        // 带 for update 的计数：原来是裸 count(*)，"数到 4 个"和"插第 5 个"之间并发进来一个请求，
        // 两边都数到 4、都插，上限就成了 6 条。守卫放在同一句 SQL 的锁里才真正封住。
        long count = addressMapper.countOfUserForUpdate(userId);
        if (count >= MAX_ADDRESSES) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "最多保存 " + MAX_ADDRESSES + " 条收货地址");
        }
        boolean asDefault = count == 0 || Boolean.TRUE.equals(form.isDefault());
        if (asDefault) {
            addressMapper.clearDefault(userId);
        }
        UserAddress entity = new UserAddress();
        entity.setUserId(userId);
        apply(entity, form);
        entity.setIsDefault(asDefault ? 1 : 0);
        addressMapper.insert(entity);
        log.debug("收货地址已新增: id={} userId={} 作为默认={}", entity.getId(), userId, asDefault);
        // 回读一次再返回：create_time 是表的默认值，不读回来响应里就是 null，
        // 前端拿到 null 的创建时间只能显示空白（评论作者那个 bug 是同一个教训）。
        return AddressVO.from(addressMapper.getByIdAndUser(entity.getId(), userId));
    }

    @Override
    @Transactional
    public AddressVO update(Long userId, Long id, AddressForm form) {
        UserAddress entity = requireOwn(userId, id);
        apply(entity, form);
        addressMapper.update(entity);
        boolean alsoDefault = Boolean.TRUE.equals(form.isDefault());
        if (alsoDefault) {
            addressMapper.clearDefault(userId);
            addressMapper.markDefault(id, userId);
        }
        log.debug("收货地址已更新: id={} userId={} 顺带设为默认={}", id, userId, alsoDefault);
        return AddressVO.from(addressMapper.getByIdAndUser(id, userId));
    }

    @Override
    @Transactional
    public void delete(Long userId, Long id) {
        requireOwn(userId, id);
        int rows = addressMapper.delete(id, userId);
        // 删掉的如果是默认地址，这里不会补一个默认（默认是用户表达过的意图，系统不替他猜）
        log.debug("收货地址已删除: id={} userId={} 影响行数={}", id, userId, rows);
    }

    @Override
    @Transactional
    public void setDefault(Long userId, Long id) {
        requireOwn(userId, id);
        int cleared = addressMapper.clearDefault(userId);
        addressMapper.markDefault(id, userId);
        log.debug("默认地址已切换: userId={} 新默认={} 清掉旧默认={} 条", userId, id, cleared);
    }

    /** 取不到就是"不存在或不是你的"，两种情况回同一句话，不做区分。 */
    private UserAddress requireOwn(Long userId, Long id) {
        UserAddress entity = addressMapper.getByIdAndUser(id, userId);
        if (entity == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        return entity;
    }

    private static void apply(UserAddress entity, AddressForm form) {
        entity.setConsignee(form.consignee().trim());
        entity.setPhone(form.phone().trim());
        entity.setAddress(form.address().trim());
        String tag = form.tag() == null ? null : form.tag().trim();
        entity.setTag(tag == null || tag.isEmpty() ? null : tag);
    }
}
