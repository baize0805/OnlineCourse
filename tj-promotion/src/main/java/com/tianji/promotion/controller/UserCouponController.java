package com.tianji.promotion.controller;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.IUserCouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 控制器
 * </p>
 *
 * @author baize
 */
@Api(tags = "用户券相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/user-coupons")
public class UserCouponController {

    private final IUserCouponService userCouponService;

    @ApiOperation("领取优惠券")
    @PostMapping("/{id}/receive")
    public void receiveCoupon(@PathVariable("id") Long id){
        userCouponService.receiveCoupon(id);
    }

    @ApiOperation("兑换码兑换优惠券")
    @PostMapping("/{code}/exchange")
    public void exchangeCoupon(@PathVariable String code){
        userCouponService.exchangeCoupon(code);
    }

    /**
     * 查询可用优惠方案
     * @param course 订单中课程信息
     * @return 可用方案集合
     */
    @ApiOperation("查询可用优惠卷方案")
    @PostMapping("/available")
    public List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> course){
        return userCouponService.findDiscountSolution(course);
    }

    @ApiOperation("分页查询我的优惠券接口")
    @GetMapping("page")
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query){
        return userCouponService.queryMyCouponPage(query);
    }

}
