package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 兑换码 服务实现类
 * </p>
 *
 * @author baize
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {

    private final StringRedisTemplate redisTemplate;

    @Override
    @Async("generateExchangeCodeExecutor")
    public void asyncGenerateExchangeCode(Coupon coupon) {
        log.debug("生成兑换 线程名：{}",Thread.currentThread().getName());
        Integer totalNum = coupon.getTotalNum();
        //1.借助redis生成自增id
        Long increment = redisTemplate.opsForValue().increment(PromotionConstants.COUPON_CODE_SERIAL_KEY, totalNum);
        if (increment==null){
            return;
        }
        //2.调用工具类生成兑换码
        int maxSerialNum = increment.intValue();
        int begin = maxSerialNum - totalNum +1;
        List<ExchangeCode> list = new ArrayList<>();
        for (int serialNum = begin; serialNum <= maxSerialNum; serialNum++) {
            String code = CodeUtil.generateCode(serialNum, coupon.getId());//参数1位自增的id值，参数2为优惠券的id(内部会计算出0-15的新鲜值，然后找对应的下标的密钥)
            ExchangeCode exchangeCode = new ExchangeCode();
            exchangeCode.setId(serialNum);//po类的主键生成策略，要改成手动赋值
            exchangeCode.setCode(code);
            exchangeCode.setExchangeTargetId(coupon.getId());//优惠券id
            exchangeCode.setExpiredTime(coupon.getIssueEndTime());
            list.add(exchangeCode);
        }

        //将兑换码保存DB 批量保存
        this.saveBatch(list);
        redisTemplate.opsForZSet().add(PromotionConstants.COUPON_RANGE_KEY,coupon.getId().toString(),maxSerialNum);
        log.debug("兑换码生成完毕");
    }

    //修改兑换码 的 自增id 对应的offset值
    @Override
    public boolean updateExchangeCodeMark(long serialNum, boolean flag) {
        String key = PromotionConstants.COUPON_CODE_MAP_KEY;
        Boolean setBit = redisTemplate.opsForValue().setBit(key, serialNum, flag);
        return setBit != null && setBit;
    }
}
