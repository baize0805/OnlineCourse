package com.tianji.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.promotion.domain.po.Coupon;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 优惠券的规则信息 Mapper 接口
 * </p>
 *
 * @author baize
 */
public interface CouponMapper extends BaseMapper<Coupon> {

    //更新优惠卷已领数量
    @Update("UPDATE coupon SET issue_num = issue_num + 1 WHERE id = #{id} and issue_num < total_num")
    int incrIssueNum(Long id);
}
