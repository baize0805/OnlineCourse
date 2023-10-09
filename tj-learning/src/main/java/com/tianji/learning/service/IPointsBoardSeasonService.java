package com.tianji.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.po.PointsBoardSeason;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author baize
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {

    void createPointsBoardLatestTable(Integer id);
}
