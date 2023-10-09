package com.tianji.learning.controller;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.service.IInteractionQuestionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 互动提问的问题表 控制器
 * </p>
 *
 * @author baize
 */
@Api(tags = "互动问题相关接口-管理端")
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/questions")
public class InteractionQuestionAdminController {

    private final IInteractionQuestionService questionService;

    @ApiOperation("分页查询问题列表-管理端")
    @GetMapping("/page")
    public PageDTO<QuestionAdminVO> queryQuestionAdminVOPage(QuestionAdminPageQuery query) {
        return questionService.queryQuestionAdminVOPage(query);
    }

    @ApiOperation("管理端根据id查询互动问题")
    @GetMapping("/{id}")
    public QuestionAdminVO queryQuestionByIdAdmin(@PathVariable("id") Long id) {
        return questionService.queryQuestionByIdAdmin(id);
    }

    @ApiOperation("隐藏或显示问题")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenQuestion(
            @ApiParam(value = "问题id", example = "1") @PathVariable("id") Long id,
            @ApiParam(value = "是否隐藏，true/false", example = "true") @PathVariable("hidden") Boolean hidden
    ) {
        questionService.hiddenQuestion(id, hidden);
    }
}