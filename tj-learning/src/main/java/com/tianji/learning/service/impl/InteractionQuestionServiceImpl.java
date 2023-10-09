package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author baize
 */
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {

    private final IInteractionReplyService replyService;
    private final UserClient userClient;
    private final SearchClient searchClient;
    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final CategoryCache categoryCache;

    @Override
    public void saveQuestion(QuestionFormDTO dto) {
        // 1.获取当前用户id
        Long userId = UserContext.getUser();
        // 2.dto转po
        InteractionQuestion question = BeanUtils.copyBean(dto, InteractionQuestion.class);
        question.setUserId(userId);
        // 3.保存
        this.save(question);
    }

    @Override
    public void updateQuestion(Long id, QuestionFormDTO dto) {
        // 1.参数校验
        if (StringUtils.isBlank(dto.getTitle()) || StringUtils.isBlank(dto.getDescription()) || dto.getAnonymity() == null) {
            throw new BadRequestException("非法参数");
        }
        // 校验id值
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            throw new BadRequestException("非法参数");
        }
        // 修改只能修改自己的互动问题
        Long userId = UserContext.getUser();
        if (!userId.equals(question.getUserId())) {
            throw new BadRequestException("不能修改别人的问题");
        }

        // 2.dto转po
        question.setTitle(dto.getTitle());
        question.setDescription(dto.getDescription());
        question.setAnonymity(dto.getAnonymity());

        // 3.修改
        this.updateById(question);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        // 1.参数校验
        if (query.getCourseId() == null) {
            throw new BadRequestException("课程id不能为空");
        }

        // 2.获取登录用户id
        Long userId = UserContext.getUser();

        // 3.分页查询互动问题表interaction_question 条件：courseId onlyMe为true的情况下才加userId 小节id不为空 hidden为false 分页查询按提问时间倒叙
        Page<InteractionQuestion> page = this.lambdaQuery()
                .select(InteractionQuestion.class, tableFieldInfo -> !tableFieldInfo.getProperty().equals("description"))
                .eq(InteractionQuestion::getCourseId, query.getCourseId())
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, userId)
                .eq(query.getSectionId() != null, InteractionQuestion::getSectionId, query.getSectionId())
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        Set<Long> latestAnswerIds = records.stream()
                .filter(interactionQuestion -> interactionQuestion.getAnonymity() != null)
                .map(InteractionQuestion::getLatestAnswerId)
                .collect(Collectors.toSet());

        Set<Long> userIds = records.stream()
                .filter(interactionQuestion -> !interactionQuestion.getAnonymity()) // 如果用户是匿名提问，则不显示用户信息
                .map(InteractionQuestion::getUserId)
                .collect(Collectors.toSet());


        // 4.根据最新回答的id去批量查询回答信息
        Map<Long, InteractionReply> replyMap = new HashMap<>();
        if (CollUtils.isNotEmpty(latestAnswerIds)) {
            // List<InteractionReply> replyList = replyService.listByIds(latestAnswerIds);
            List<InteractionReply> replyList = replyService.list(Wrappers.<InteractionReply>lambdaQuery()
                    .in(InteractionReply::getId, latestAnswerIds)
                    .eq(InteractionReply::getHidden, false)
            );
            replyMap = replyList.stream().collect(Collectors.toMap(InteractionReply::getId, interactionReply -> interactionReply));
            replyList.stream()
                    .filter(reply -> !reply.getAnonymity())
                    .forEach(reply -> userIds.add(reply.getUserId())); // 将最新回答的用户存入userIds
        }

        // 5.远程调用用户服务，获取用户信息 批量
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userDTOMap = userDTOS.stream()
                .collect(Collectors.toMap(UserDTO::getId, userDTO -> userDTO));

        // 6.封装vo返回
        List<QuestionVO> voList = new ArrayList<>();
        for (InteractionQuestion record : records) {
            QuestionVO vo = BeanUtils.copyBean(record, QuestionVO.class);
            if (!vo.getAnonymity()) {
                UserDTO userDTO = userDTOMap.get(record.getUserId());
                if (userDTO != null) {
                    vo.setUserIcon(userDTO.getIcon());
                    vo.setUserName(userDTO.getName());
                }
            }
            InteractionReply reply = replyMap.get(record.getLatestAnswerId());
            if (reply != null) {
                if (!reply.getAnonymity()) {
                    UserDTO userDTO = userDTOMap.get(reply.getUserId());
                    if (userDTO != null) {
                        vo.setLatestReplyUser(userDTO.getName());
                    }
                }
                vo.setLatestReplyContent(reply.getContent());
            }
            voList.add(vo);
        }

        return PageDTO.of(page, voList);
    }

    @Override
    public QuestionVO queryQuestionById(Long id) {
        // 1.参数校验
        if (id == null) {
            throw new BadRequestException("参数非法");
        }
        // 2.查询互动问题表，按主键查询
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            throw new BadRequestException("问题不存在");
        }

        // 3.如果该问题管理员设置了隐藏，返回空
        if (question.getHidden()) {
            return null;
        }
        // 4.封装VO
        QuestionVO questionVO = BeanUtils.copyBean(question, QuestionVO.class);
        // 5.如果问题是匿名提问的，不用查询提问者昵称和头像
        if (!question.getAnonymity()) {
            // 调用用户服务
            UserDTO userDTO = userClient.queryUserById(question.getUserId());
            if (userDTO != null) {
                questionVO.setUserName(userDTO.getName());
                questionVO.setUserIcon(userDTO.getIcon());
            }
        }
        return questionVO;
    }

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionAdminVOPage(QuestionAdminPageQuery query) {
        // 如果用户传了课程名称参数，则从es中获取对应的课程id
        String courseName = query.getCourseName();
        List<Long> cids = null;
        if (StringUtils.isNotBlank(courseName)) {
            cids = searchClient.queryCoursesIdByName(courseName);
            if (CollUtils.isEmpty(cids)) {
                return PageDTO.empty(0L, 0L);
            }

        }

        // 1.查询互动问题表 条件 参数有值则添加，无则查所有    分页 排序按提问时间倒叙
        Page<InteractionQuestion> page = this.lambdaQuery()
                .in(CollUtils.isNotEmpty(cids),InteractionQuestion::getCourseId, cids)
                .eq(query.getStatus() != null, InteractionQuestion::getStatus, query.getStatus())
                .between(query.getBeginTime() != null && query.getEndTime() != null,
                        InteractionQuestion::getCreateTime,
                        query.getBeginTime(),
                        query.getEndTime())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(0L, 0L);
        }
        Set<Long> uids = new HashSet<>();
        Set<Long> courseIds = new HashSet<>();
        Set<Long> chapterAndSectionIds = new HashSet<>();
        for (InteractionQuestion record : records) {
            uids.add(record.getUserId());
            courseIds.add(record.getCourseId());
            chapterAndSectionIds.add(record.getChapterId());
            chapterAndSectionIds.add(record.getSectionId());
        }

        // 2.远程调用用户服务，获取用户信息
        List<UserDTO> userDTOS = userClient.queryUserByIds(uids);
        if (CollUtils.isEmpty(userDTOS)) {
            throw new BizIllegalException("用户不存在");
        }
        Map<Long, UserDTO> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, userDTO -> userDTO));

        // 3.远程调用课程服务，获取课程信息
        List<CourseSimpleInfoDTO> cinfos = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(cinfos)) {
            throw new BizIllegalException("课程不存在");
        }
        Map<Long, CourseSimpleInfoDTO> cinfoMap = cinfos.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, courseSimpleInfoDTO -> courseSimpleInfoDTO));

        // 4.远程调用课程服务，获取章节信息
        List<CataSimpleInfoDTO> cataSimpleInfoDTOS = catalogueClient.batchQueryCatalogue(chapterAndSectionIds);
        if (CollUtils.isEmpty(cataSimpleInfoDTOS)) {
            throw new BizIllegalException("章节信息不存在");
        }
        Map<Long, String> cataInfoDTO = cataSimpleInfoDTOS.stream().collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));



        // 6.封装VO返回
        /* List<QuestionAdminVO> voList = new ArrayList<>();
        for (InteractionQuestion record : records) {
            QuestionAdminVO adminVO = BeanUtils.copyBean(record, QuestionAdminVO.class);
            adminVO.setUserName();
            adminVO.setCourseName();
            adminVO.setChapterName();
            adminVO.setSectionName();
            adminVO.setCategoryName();
            voList.add(adminVO);
        } */
        List<QuestionAdminVO> voList = records.stream()
                .map(record -> {
                    QuestionAdminVO adminVO = BeanUtils.copyBean(record, QuestionAdminVO.class);
                    UserDTO userDTO = userDTOMap.get(record.getUserId());
                    if (userDTO != null) {
                        adminVO.setUserName(userDTO.getName());
                    }
                    CourseSimpleInfoDTO cinfoDTO = cinfoMap.get(record.getCourseId());
                    if (cinfoDTO != null) {
                        adminVO.setCourseName(cinfoDTO.getName());
                        // 5.获取分类信息
                        List<Long> categoryIds = cinfoDTO.getCategoryIds();
                        String categoryNames = categoryCache.getCategoryNames(categoryIds);
                        adminVO.setCategoryName(categoryNames);
                    }
                    adminVO.setChapterName(cataInfoDTO.get(record.getChapterId()));
                    adminVO.setSectionName(cataInfoDTO.get(record.getSectionId()));
                    return adminVO;
                })
                .collect(Collectors.toList());


        return PageDTO.of(page, voList);
    }

    @Override
    public void removeQuestion(Long id) {
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            throw new BadRequestException("非法参数");
        }
        // 修改只能修改自己的互动问题
        Long userId = UserContext.getUser();
        if (!userId.equals(question.getUserId())) {
            throw new BadRequestException("不能删除别人的问题");
        }

        this.removeById(id);
    }

    @Override
    public void hiddenQuestion(Long id, Boolean hidden) {
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            throw new BadRequestException("非法参数");
        }
        question.setHidden(hidden);
        this.updateById(question);
    }

    @Override
    public QuestionAdminVO queryQuestionByIdAdmin(Long id) {
        // 1.根据id查询问题
        InteractionQuestion question = getById(id);
        if (question == null) {
            return null;
        }
        // 2.转PO为VO
        QuestionAdminVO vo = BeanUtils.copyBean(question, QuestionAdminVO.class);
        // 3.查询提问者信息
        UserDTO user = userClient.queryUserById(question.getUserId());
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }
        // 4.查询课程信息
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(
                question.getCourseId(), false, true);
        if (cInfo != null) {
            // 4.1.课程名称信息
            vo.setCourseName(cInfo.getName());
            // 4.2.分类信息
            vo.setCategoryName(categoryCache.getCategoryNames(cInfo.getCategoryIds()));
            // 4.3.教师信息
            List<Long> teacherIds = cInfo.getTeacherIds();
            List<UserDTO> teachers = userClient.queryUserByIds(teacherIds);
            if(CollUtils.isNotEmpty(teachers)) {
                vo.setTeacherName(teachers.stream()
                        .map(UserDTO::getName).collect(Collectors.joining("/")));
            }
        }
        // 5.查询章节信息
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(
                List.of(question.getChapterId(), question.getSectionId()));
        Map<Long, String> cataMap = new HashMap<>(catas.size());
        if (CollUtils.isNotEmpty(catas)) {
            cataMap = catas.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }
        vo.setChapterName(cataMap.getOrDefault(question.getChapterId(), ""));
        vo.setSectionName(cataMap.getOrDefault(question.getSectionId(), ""));

        // 修改为已查看
        question.setStatus(QuestionStatus.CHECKED);
        this.updateById(question);
        // 6.封装VO
        return vo;
    }
}
