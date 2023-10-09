package com.tianji.learning.constants;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/18 20:30
 */
public interface RedisConstants {


    /**
     * 签到记录的key前缀，完整的格式为    sign:uid:用户id:年月
     */
    String SIGN_RECORD_KEY_PREFIX = "sign:uid:";

    /**
     * 积分排行榜key前缀，完整格式位 boards:年月
     */
    String POINTS_BOARD_KEY_PREFIX="boards:";
}
