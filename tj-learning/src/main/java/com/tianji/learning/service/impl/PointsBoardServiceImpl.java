package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author baize
 */
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {

    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;

    @Override
    public PointsBoardVO queryPointsBoardList(PointsBoardQuery query) {
        // 1.获取当前登录的用户id
        Long userId = UserContext.getUser();
        // 2.判断是查当前赛季还是历史赛季
        boolean isCurrent = query.getSeason() == null || query.getSeason() == 0;// 该字段表示是否查询当前赛季
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;
        Long season = query.getSeason();// 历史赛季id
        // 3.查询我的排名和积分
        PointsBoard board = isCurrent ? queryMyCurrentBoard(key) : queryMyHistoryBoard(season);
        /* if (isCurrent){
            queryMyCurrentBoard(key);
        }
        else {
            queryMyHistoryBoard(season);
        } */

        // 4.分页查询查询赛季的列表
        List<PointsBoard> list = isCurrent ? queryCurrentBoard(key, query.getPageNo(), query.getPageSize()) : queryHistoryBoard(query);

        Set<Long> uids = list.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> userDTOS = userClient.queryUserByIds(uids);
        if (CollUtils.isEmpty(userDTOS)){
            throw new BizIllegalException("用户不存在");
        }
        Map<Long, String> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, userDTO -> userDTO.getName()));
        //5.封装VO
        PointsBoardVO vo = new PointsBoardVO();
        vo.setRank(board.getRank());
        vo.setPoints(board.getPoints());
        List<PointsBoardItemVO> voList = new ArrayList<>();
        for (PointsBoard pointsBoard : list) {
            PointsBoardItemVO itemVO = new PointsBoardItemVO();
            itemVO.setName(userDTOMap.get(pointsBoard.getUserId()));
            itemVO.setPoints(pointsBoard.getPoints());
            itemVO.setRank(pointsBoard.getRank());
            voList.add(itemVO);
        }
        vo.setBoardList(voList);
        return vo;
    }

    private List<PointsBoard> queryHistoryBoard(PointsBoardQuery query) {
        // todo 历史赛季列表
        return null;
    }

    /**
     * 查询当前赛季排行榜列表，从redis zset
     *
     * @param key      缓存中的key
     * @param pageNo   页码
     * @param pageSize 条数
     * @return 排行集合
     */
    public List<PointsBoard> queryCurrentBoard(String key, Integer pageNo, Integer pageSize) {
        // 1.计算下标
        int start = (pageNo - 1) * pageSize;
        int end = start + pageSize - 1;

        // 2.分页查询
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        if (CollUtils.isEmpty(typedTuples)) {
            return CollUtils.emptyList();
        }
        int rank = start + 1;
        List<PointsBoard> list = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String value = typedTuple.getValue();
            Double score = typedTuple.getScore();
            if (StringUtils.isBlank(value) || score == null) {
                continue;
            }
            PointsBoard board = new PointsBoard();
            board.setUserId(Long.valueOf(value));
            board.setPoints(score.intValue());
            board.setRank(rank++);
            list.add(board);
        }
        return list;
    }

    private PointsBoard queryMyHistoryBoard(Long season) {
        // todo
        return null;
    }

    /**
     * 查询当前赛季我的积分和排名
     *
     * @param key 缓存的key
     * @return
     */
    private PointsBoard queryMyCurrentBoard(String key) {
        Long userId = UserContext.getUser();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        // 获取排名
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId.toString());
        PointsBoard board = new PointsBoard();
        board.setRank(rank == null ? 0 : rank.intValue() + 1);
        board.setPoints(score == null ? 0 : score.intValue());
        return board;
    }
}
