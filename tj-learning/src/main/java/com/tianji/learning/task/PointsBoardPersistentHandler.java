package com.tianji.learning.task;

import com.tianji.common.utils.CollUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/19 14:53
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {

    private final IPointsBoardSeasonService pointsBoardSeasonService;
    private final IPointsBoardService pointsBoardService;
    private final StringRedisTemplate redisTemplate;

    // @Scheduled(cron = "0 0 3 1 * ?")
    @XxlJob("createTableJob")
    public void createPointsBoardTableOfLastSeason() {
        log.debug("创建上赛季榜单表，任务执行了");
        // 1.获取上个当前时间点
        LocalDate time = LocalDate.now().minusMonths(1);
        // 2.获取赛季表获取赛季id
        PointsBoardSeason one = pointsBoardSeasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();

        log.debug("上赛季信息 {}", one);
        if (one == null) {
            return;
        }

        // 3.创建上个赛榜单表
        pointsBoardSeasonService.createPointsBoardLatestTable(one.getId());
    }

    // 持久化上个赛季排行榜数据到db中
    @XxlJob("savePointsBoard2DB")
    public void savePointsBoard2DB() {
        // 1.获取上个月的当前时间点
        LocalDate time = LocalDate.now().minusMonths(1);
        // 2.查询赛季表获取上赛季信息
        PointsBoardSeason one = pointsBoardSeasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();

        log.debug("上赛季信息 {}", one);
        if (one == null) {
            return;
        }
        // 3.计算动态表名，并存入threadLocal
        String tableName = POINTS_BOARD_TABLE_PREFIX + one.getId();
        log.debug("动态表名为 {}", tableName);
        TableInfoContext.setInfo(tableName);
        // 4.获取redis上赛季积分榜数据是否存在 分页查询
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;

        // 分片
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();

        int pageNo = shardIndex + 1;
        int pageSize = 1000;
        while (true) {
            List<PointsBoard> pointsBoardList = pointsBoardService.queryCurrentBoard(key, pageNo, pageSize);
            if (CollUtils.isEmpty(pointsBoardList)) {
                break;
            }
            pageNo += shardTotal;
            // 5.持久化到db的相应赛季榜单中
            for (PointsBoard board : pointsBoardList) {
                board.setId(Long.valueOf(board.getRank()));
                board.setRank(null);
            }
            pointsBoardService.saveBatch(pointsBoardList);

        }

        // 6.清空threadlocal中的数据
        TableInfoContext.remove();
    }

    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis(){
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.计算key
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;
        // 3.删除
        redisTemplate.unlink(key);
    }

}
