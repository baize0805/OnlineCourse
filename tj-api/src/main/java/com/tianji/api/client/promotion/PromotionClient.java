package com.tianji.api.client.promotion;

import com.tianji.api.client.promotion.fallback.PromotionFallback;
import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/24 14:49
 */
@FeignClient(value = "promotion-service",fallbackFactory = PromotionFallback.class)
public interface PromotionClient {

    @PostMapping("/user-coupons/available")
    List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> course);

}
