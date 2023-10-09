package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.mq.msg.SignInMessage;
import com.tianji.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author baize
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void addPointRecord(SignInMessage msg, PointsRecordType type) {
        if (msg.getUserId() == null || msg.getPoints() == null){
            return;
        }
        int realPoint = msg.getPoints();
        //1.判断该积分类型是否有上限
        int maxPoints = type.getMaxPoints();
        if (maxPoints >0){
            //2.如果有上限，查询该用户该积分类型，今日已得积分
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startTime = DateUtils.getDayStartTime(now);
            LocalDateTime endTime = DateUtils.getDayEndTime(now);
            QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
            wrapper.select("sum(points) as totalPoints");
            wrapper.eq("user_id",msg.getUserId());
            wrapper.eq("type",type);
            wrapper.between("create_time",startTime,endTime);
            Map<String, Object> map = this.getMap(wrapper);
            int currentPoints =0;
            if (map != null){
                BigDecimal totalPoints = (BigDecimal) map.get("totalPoints");
                currentPoints = totalPoints.intValue();
            }
            //3.判断积分是否已经超过上线
            if (currentPoints >= maxPoints){
                return;
            }
            // 计算本次实际应该增加多少分
            if (currentPoints + realPoint > maxPoints){
                realPoint = maxPoints - currentPoints;
            }

        }
        //4.保存积分
        PointsRecord record = new PointsRecord();
        record.setUserId(msg.getUserId());
        record.setType(type);
        record.setPoints(realPoint);
        this.save(record);


        //5.累加并保存总积分值到redis 采用zset  当前赛季的排行榜
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX +format;

        redisTemplate.opsForZSet().incrementScore(key,msg.getUserId().toString(), realPoint);
    }

    @Override
    public List<PointsStatisticsVO> queryMyTodayPoints() {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //2.查询积分表
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = DateUtils.getDayStartTime(now);
        LocalDateTime endTime = DateUtils.getDayEndTime(now);
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.select("type","sum(points) as points");//暂时存到points字段
        wrapper.eq("user_id",userId);
        wrapper.between("create_time",startTime,endTime);
        wrapper.groupBy("type");
        List<PointsRecord> list = this.list(wrapper);
    if (CollUtils.isEmpty(list)){
        return CollUtils.emptyList();
    }
        //3.封装VO返回
        List<PointsStatisticsVO> voList = new ArrayList<>();
        for (PointsRecord record : list) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(record.getType().getDesc());
            vo.setMaxPoints(record.getType().getMaxPoints());
            vo.setPoints(record.getPoints());
            voList.add(vo);
        }

        return voList;
    }


}
