package com.tianji.learning.controller;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.service.IInteractionReplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 互动问题的回答或评论 控制器
 * </p>
 *
 * @author baize
 */
@Api(tags = "回答问题相关接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/replies")
public class InteractionReplyController {

    private final IInteractionReplyService replyService;

    @ApiOperation("新增回答或评论")
    @PostMapping
    public void saveReply(@RequestBody @Validated ReplyDTO replyDTO){
        replyService.saveReply(replyDTO);
    }

    @ApiOperation("客户端分页查询回答或评论列表")
    @GetMapping("/page")
    public PageDTO<ReplyVO> queryReplyVoPage(ReplyPageQuery query){
        return replyService.queryReplyVoPage(query,false);
    }

}
