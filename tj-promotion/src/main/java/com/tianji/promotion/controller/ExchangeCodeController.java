package com.tianji.promotion.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.domain.po.ExchangeCode;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 兑换码 控制器
 * </p>
 *
 * @author baize
 */
@Api(tags = "ExchangeCode管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/exchangeCode")
public class ExchangeCodeController {

    private final IExchangeCodeService exchangeCodeService;


}
