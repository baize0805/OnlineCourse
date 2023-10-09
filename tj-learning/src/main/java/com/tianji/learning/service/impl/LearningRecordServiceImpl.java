package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author baize
 * @since 2023-09-04
 */
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {

    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    private final LearningRecordDelayTaskHandler taskHandler;

    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        // 1.获取当前登录用户id
        Long userId = UserContext.getUser();
        // 2.查询课表信息，条件user_id和courseId
        LearningLesson lesson = lessonService.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            throw new BizIllegalException("该课程未加入课表");
        }
        // 3.查询学习记录
        List<LearningRecord> recordList = this.lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .eq(LearningRecord::getUserId, userId)
                .list();

        // 4.封装结果返回
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        List<LearningRecordDTO> dtoList = BeanUtils.copyList(recordList, LearningRecordDTO.class);
        dto.setRecords(dtoList);

        return dto;
    }

    @Override
    public void addLearningRecord(LearningRecordFormDTO dto) {
        // 1.获取当前登录用户id
        Long userId = UserContext.getUser();
        // 2.处理学习记录
        boolean isFinished = false; // 代表本小节是否已学完
        if (dto.getSectionType().equals(SectionType.VIDEO)) {
            // 2.1 提交视频播放记录
            isFinished = handleVideoRecord(userId, dto);
        } else {
            // 2.2 提交视频考试记录
            isFinished = handleExamRecord(userId, dto);
        }

        // 3.处理课表数据
        if (!isFinished) {// 如果本小节不是第一次学完不用处理课表数据
            return;
        }
        handleLessonData(dto);
    }

    // 处理课表相关数据
    private void handleLessonData(LearningRecordFormDTO dto) {
        // 1. 查询课表中的数据，条件lesson_id
        LearningLesson lesson = lessonService.getById(dto.getLessonId());
        if (lesson == null) {
            throw new BizIllegalException("课程不存在");
        }
        // 2.判断是否为第一次学完
        boolean allFinished = false;

        // 3.远程调用课程服务得到课程信息
        CourseFullInfoDTO cinfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cinfo == null) {
            throw new BizIllegalException("课程不存在");
        }
        Integer sectionNum = cinfo.getSectionNum();// 该课程下小节的总数
        // 4.如果是第一次学完，判断该用户该在该课程下的全部小节是否学完
        Integer learnedSections = lesson.getLearnedSections();
        allFinished = learnedSections + 1 >= sectionNum;


        // 5.更新课表记录
        lessonService.lambdaUpdate()
                .set(lesson.getStatus() == LessonStatus.NOT_BEGIN, LearningLesson::getStatus, LessonStatus.LEARNING)
                .set(allFinished, LearningLesson::getStatus, LessonStatus.FINISHED)
                .set(LearningLesson::getLatestSectionId, dto.getSectionId())
                .set(LearningLesson::getLatestLearnTime, dto.getCommitTime())
                // .set(isFinished,LearningLesson::getLearnedSections,lesson.getLearnedSections()+1)
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }


    // 处理视频播放记录
    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO dto) {
        // 1.查询旧的学习记录 learning_record 条件userId lessonId section_id
        LearningRecord learningRecord = queryOldRecord(dto.getLessonId(), dto.getSectionId());

        // LearningRecord learningRecord = this.lambdaQuery()
        //         .eq(LearningRecord::getUserId, userId)
        //         .eq(LearningRecord::getLessonId, dto.getLessonId())
        //         .eq(LearningRecord::getSectionId, dto.getSectionId())
        //         .one();
        // 2.判断是否存在
        if (learningRecord == null) {
            // 3.判断是否存在
            LearningRecord record = BeanUtils.copyBean(dto, LearningRecord.class);
            record.setUserId(userId);
            boolean result = this.save(record);
            if (!result) {
                throw new DbException("新增学习记录失败");
            }
            return false;
        }
        // 4.如果存在则更新学习记录
        // 判断本小节是否为第一次学完
        boolean isFinished = !learningRecord.getFinished() && dto.getMoment() * 2 >= dto.getDuration();
        if (!isFinished) {
            LearningRecord record = new LearningRecord();
            record.setLessonId(learningRecord.getLessonId());
            record.setSectionId(learningRecord.getSectionId());
            record.setMoment(learningRecord.getMoment());
            record.setFinished(learningRecord.getFinished());
            record.setId(learningRecord.getId());
            taskHandler.addLearningRecordTask(record);
            return false;
        }
        boolean result = this.lambdaUpdate()
                .set(LearningRecord::getMoment, dto.getMoment())
                .set(isFinished, LearningRecord::getFinished, true)
                .set(isFinished, LearningRecord::getFinishTime, dto.getCommitTime())
                .eq(LearningRecord::getId, learningRecord.getId())
                .update();
        if (!result) {
            throw new DbException("更新学习记录失败");
        }
        // 清理redis缓存
        taskHandler.cleanRecordCache(dto.getLessonId(), dto.getSectionId());
        return true;

    }

    private LearningRecord queryOldRecord(Long lessonId, Long sectionId) {
        // 1.查询缓存
        LearningRecord cache = taskHandler.readRecordCache(lessonId, sectionId);
        // 2.如果命中直接返回
        if (cache != null) {
            return cache;
        }
        // 3.如果未命中，查询db
        LearningRecord dbRecord = this.lambdaQuery()
                .eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .one();
        if (dbRecord == null) {
            return null;
        }
        // 4.放入缓存
        taskHandler.writeRecordCache(dbRecord);
        return dbRecord;
    }

    // 处理考试记录
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO dto) {
        // 1.将dto转换po
        LearningRecord record = BeanUtils.copyBean(dto, LearningRecord.class);
        record.setUserId(userId);
        record.setFinished(true); // 提交考试记录 代表本小节已学完
        record.setFinishTime(dto.getCommitTime());
        // 2.将记录保存到数据库
        boolean result = this.save(record);
        if (!result) {
            throw new DbException("新增考试记录失败");
        }
        return true;
    }
}
