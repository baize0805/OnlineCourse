package com.tianji.learning.service.impl;

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.msg.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/18 20:27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignRecordServiceImpl implements ISignRecordService {

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;

    @Override
    public SignResultVO addSignRecords() {
        // 1.获取用户的id
        Long userId = UserContext.getUser();
        // 2.拼接key
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX + userId + format;

        // 3.利用bitset命令，将签到记录保存到redis的bitmap结构中。需要校验是否已经签到
        int offset = now.getDayOfMonth() - 1;
        Boolean setBit = redisTemplate.opsForValue().setBit(key, offset, true);
        if (Boolean.TRUE.equals(setBit)) {
            throw new BizIllegalException("不能重复签到");
        }
        // 4.计算连续签到的天数
        int days = countSignDays(key, now.getDayOfMonth());

        // 5.计算连续签到的奖励积分
        int rewardPoints = 0;
        switch (days) {
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }
       /*  if (days == 7){
            rewardPoints = 10;
        }else if (days == 14){
            rewardPoints = 20;
        } else if (days == 28) {
            rewardPoints=40;
        } */
        // 6.保存积分 发送消息到mq
        mqHelper.send(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, rewardPoints + 1));

        // 7.封装VO返回
        SignResultVO vo = new SignResultVO();
        vo.setSignDays(days);
        vo.setRewardPoints(rewardPoints);

        return vo;
    }

    @Override
    public Byte[] querySignRecords() {
        // 1.获取用户id
        Long userId = UserContext.getUser();
        // 2.拼接key
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX + userId + format;

        // 3.取本月第一天到现在的所有签到记录
        int dayOfMonth = now.getDayOfMonth();
        List<Long> bitField = redisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (CollUtils.isEmpty(bitField)) {
            return new Byte[0];
        }
        Long num = bitField.get(0);
        int offset = dayOfMonth - 1;
        // 4.转二进制,封装VO
        Byte[] arr = new Byte[dayOfMonth];
        while (offset >= 0) {
            arr[offset] = (byte) (num & 1);
            offset--;
            num = num >>> 1;
        }
        return arr;
    }

    /**
     * 计算连续签到多少天
     *
     * @param key        缓存中的key
     * @param dayOfMonth 本月第一天到今天的天数
     * @return 连续签到的天数
     */
    private int countSignDays(String key, int dayOfMonth) {
        // 1.求本月第一天到今天所有的签到数据，bitfiled 结果为十进制
        List<Long> bitField = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (CollUtils.isEmpty(bitField)) {
            return 0;
        }
        Long num = bitField.get(0);// 本月第一天到今天的签到数据，为十进制

        // log.debug("num  {}", num);
        // 2.num转十进制，从后往前推共有多少个1
        int count = 0;
        while ((num & 1) == 1) {
            count++;
            num = num >>> 1;
        }

        return count;
    }
}
