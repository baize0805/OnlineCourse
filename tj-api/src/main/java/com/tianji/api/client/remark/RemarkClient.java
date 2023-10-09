package com.tianji.api.client.remark;

import com.tianji.api.client.remark.fallback.RemarkClientFallBack;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/17 14:15
 */
@FeignClient(value = "remark-service",fallbackFactory = RemarkClientFallBack.class)
public interface RemarkClient {

    @GetMapping("/likes/list")
    Set<Long> getLikesStatusByBizIds(@RequestParam("bizIds") List<Long> bizIds);

}
