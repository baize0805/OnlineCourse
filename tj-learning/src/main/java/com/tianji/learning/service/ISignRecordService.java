package com.tianji.learning.service;

import com.tianji.learning.domain.vo.SignResultVO;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/18 20:26
 */
public interface ISignRecordService {
    SignResultVO addSignRecords();

    Byte[] querySignRecords();
}
