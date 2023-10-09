package com.tianji.promotion.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.domain.po.CouponScope;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 优惠券作用范围信息 控制器
 * </p>
 *
 * @author baize
 */
@Api(tags = "CouponScope管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/couponScope")
public class CouponScopeController {

    private final ICouponScopeService couponScopeService;


}
