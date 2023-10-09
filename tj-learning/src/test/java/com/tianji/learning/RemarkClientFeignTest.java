package com.tianji.learning;

import com.tianji.api.client.remark.RemarkClient;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/17 14:37
 */
@SpringBootTest(classes = LearningApplication.class)
public class RemarkClientFeignTest {

    @Autowired
   private RemarkClient remarkClient;

    @Test
    public void test(){
        Set<Long> bizIDd = remarkClient.getLikesStatusByBizIds(Lists.list(1703220119991558146L,3232L));
        System.out.println(bizIDd);
    }
}
