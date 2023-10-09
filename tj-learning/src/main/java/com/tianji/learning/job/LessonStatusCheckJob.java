package com.tianji.learning.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/5 21:59
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LessonStatusCheckJob {

    private final ILearningLessonService lessonService;

    @Scheduled(cron = "0 0 * * * ?")
    public void lessonStatusCheck(){
        // 1.查询状态为未过期课程
        List<LearningLesson> list = lessonService.list(Wrappers.<LearningLesson>lambdaQuery()
                .ne(LearningLesson::getStatus, LessonStatus.EXPIRED));
        // 2.判断是否过期 过期时间是否小于当前时间
        LocalDateTime now = LocalDateTime.now();
        for (LearningLesson lesson : list) {
            if (now.isAfter(lesson.getExpireTime())){
                lesson.setStatus(LessonStatus.EXPIRED);
            }
        }
        // 3.更新 批量
        lessonService.updateBatchById(list);
    }

}
