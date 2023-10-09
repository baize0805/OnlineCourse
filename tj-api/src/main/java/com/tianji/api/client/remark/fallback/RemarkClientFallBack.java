package com.tianji.api.client.remark.fallback;

import com.tianji.api.client.remark.RemarkClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;
import java.util.Set;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/17 14:27
 */
@Slf4j
public class RemarkClientFallBack implements FallbackFactory<RemarkClient> {
    @Override
    public RemarkClient create(Throwable cause) {
        log.error("查询remark服务异常", cause);
        return new RemarkClient() {
            @Override
            public Set<Long> getLikesStatusByBizIds(List<Long> bizIds) {
                return null;
            }
        };
    }
}
