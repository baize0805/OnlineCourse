package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.cache.CategoryCache;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.*;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponScopeVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tianji.promotion.enums.CouponStatus.*;

/**
 * <p>
 * 优惠券的规则信息 服务实现类
 * </p>
 *
 * @author baize
 */
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    private final ICouponScopeService couponScopeService;
    private final CategoryCache categoryCache;
    private final IExchangeCodeService exchangeCodeService;
    private final StringRedisTemplate redisTemplate;
    private final IUserCouponService userCouponService;
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveCoupon(CouponFormDTO dto) {
        //1.dto转po 保存优惠券
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        this.save(coupon);
        //2.判断是否限定了范围，dto.specific  如果为false直接return
        if (!dto.getSpecific()) {
            return;//没有说明限定范围
        }
        //3.如果dto.specific为true 需要校验dto.scopes
        List<Long> scopes = dto.getScopes();
        if (CollUtils.isEmpty(scopes)) {
            throw new BadRequestException("分类的id不能为空");
        }
        //4.保存优惠券的限定范围
        /* List<CouponScope> csList = new ArrayList<>();
        for (Long scope : scopes) {
            CouponScope couponScope = new CouponScope();
            couponScope.setCouponId(coupon.getId());
            couponScope.setBizId(scope);
            couponScope.setType(1);
            csList.add(couponScope);
        } */
        List<CouponScope> csList = scopes.stream()
                .map(scope -> new CouponScope().setCouponId(coupon.getId()).setBizId(scope).setType(1))
                .collect(Collectors.toList());
        couponScopeService.saveBatch(csList);
    }

    @Override
    public PageDTO<CouponPageVO> queryCouponPage(CouponQuery query) {
        //1.分页条件查询优惠券表
        Page<Coupon> page = this.lambdaQuery()
                .eq(query.getType() != null, Coupon::getDiscountType, query.getType())
                .eq(query.getStatus() != null, Coupon::getStatus, query.getStatus())
                .like(StringUtils.isNotBlank(query.getName()), Coupon::getName, query.getName())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Coupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }
        //2.封装vo返回
        List<CouponPageVO> voList = BeanUtils.copyList(records, CouponPageVO.class);

        return PageDTO.of(page,voList);
    }

    @Override
    public void issueCoupon(Long id, CouponIssueFormDTO dto) {
        if (id == null || !id.equals(dto.getId())){
            throw new BadRequestException("非法参数");
        }
        //校验优惠券id是否存在，
        Coupon coupon = this.getById(id);
        if (coupon == null){
            throw new BadRequestException("优惠券不存在");
        }
        //校验优惠券状态，只有待发放和暂停状态可以发放
        if (coupon.getStatus()!= DRAFT && coupon.getStatus()!= CouponStatus.PAUSE){
            throw new BizIllegalException("只有待发放和暂停中的优惠券才能发放");
        }
        //修改优惠券的日期
        LocalDateTime now = LocalDateTime.now();
        boolean isBeginIssue = dto.getIssueBeginTime() == null || !dto.getIssueBeginTime().isAfter(now);//该优惠券是否立刻发放

        Coupon tmp = BeanUtils.copyBean(dto, Coupon.class);
        if (isBeginIssue){
            tmp.setStatus(ISSUING);
            tmp.setIssueBeginTime(now);
        }else{
            tmp.setStatus(UN_ISSUE);
        }
        this.updateById(tmp);


        if (isBeginIssue){
            String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;
            redisTemplate.opsForHash().put(key,"issueBeginTime",String.valueOf(DateUtils.toEpochMilli(now)));
            redisTemplate.opsForHash().put(key,"issueEndTime",String.valueOf(DateUtils.toEpochMilli(dto.getIssueEndTime())));
            redisTemplate.opsForHash().put(key,"totalNum",String.valueOf(coupon.getTotalNum()));
            redisTemplate.opsForHash().put(key,"userLimit",String.valueOf(coupon.getUserLimit()));
        }


        //如果优惠券的领取方式为指定发放 生成兑换码

        if (coupon.getObtainWay() == ObtainType.ISSUE && coupon.getStatus() == DRAFT){
            coupon.setIssueEndTime(tmp.getIssueEndTime());//
            exchangeCodeService.asyncGenerateExchangeCode(coupon);//异步的生成兑换码
        }
    }

    @Override
    public void updateCoupon(Long id, CouponFormDTO dto) {
        if (id == null || !id.equals(dto.getId())){
            throw new BadRequestException("非法参数");
        }
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        this.updateById(coupon);
        //2.判断是否限定了范围，dto.specific  如果为false直接return
        if (!dto.getSpecific()) {
            return;//没有说明限定范围
        }
        //3.如果dto.specific为true 需要校验dto.scopes
        List<Long> scopes = dto.getScopes();
        if (CollUtils.isEmpty(scopes)) {
            throw new BadRequestException("分类的id不能为空");
        }
        //4.保存优惠券的限定范围
        /* List<CouponScope> csList = new ArrayList<>();
        for (Long scope : scopes) {
            CouponScope couponScope = new CouponScope();
            couponScope.setCouponId(coupon.getId());
            couponScope.setBizId(scope);
            couponScope.setType(1);
            csList.add(couponScope);
        } */
        List<CouponScope> csList = scopes.stream()
                .map(scope -> new CouponScope().setCouponId(coupon.getId()).setBizId(scope).setType(1))
                .collect(Collectors.toList());
        couponScopeService.updateBatchById(csList);
    }

    @Override
    public void deleteById(Long id) {
        // 1.查询
        Coupon coupon = getById(id);
        if (coupon == null || coupon.getStatus() != DRAFT) {
            throw new BadRequestException("优惠券不存在或者优惠券正在使用中");
        }
        // 2.删除优惠券
        boolean success = remove(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getId, id)
                .eq(Coupon::getStatus, DRAFT)
        );
        if (!success) {
            throw new BadRequestException("优惠券不存在或者优惠券正在使用中");
        }
        // 3.删除优惠券对应限定范围
        if(!coupon.getSpecific()){
            return;
        }
        couponScopeService.remove(new LambdaQueryWrapper<CouponScope>().eq(CouponScope::getCouponId, id));
    }

    @Override
    public CouponDetailVO queryCouponById(Long id) {
        // 1.查询优惠券
        Coupon coupon = getById(id);
        // 2.转换VO
        CouponDetailVO vo = BeanUtils.copyBean(coupon, CouponDetailVO.class);
        if (vo == null || !coupon.getSpecific()) {
            // 数据不存在，或者没有限定范围，直接结束
            return vo;
        }
        // 3.查询限定范围
        List<CouponScope> scopes = couponScopeService.lambdaQuery().eq(CouponScope::getCouponId, id).list();
        if (CollUtils.isEmpty(scopes)) {
            return vo;
        }
        List<CouponScopeVO> scopeVOS = scopes.stream()
                .map(CouponScope::getBizId)
                .map(cateId -> new CouponScopeVO(cateId, categoryCache.getNameByLv3Id(cateId)))
                .collect(Collectors.toList());
        vo.setScopes(scopeVOS);
        return vo;
    }

    @Override
    public void pauseIssue(Long id) {
        // 1.查询旧优惠券
        Coupon coupon = getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }

        // 2.当前券状态必须是未开始或进行中
        CouponStatus status = coupon.getStatus();
        if (status != UN_ISSUE && status != ISSUING) {
            // 状态错误，直接结束
            return;
        }

        // 3.更新状态
        boolean success = lambdaUpdate()
                .set(Coupon::getStatus, PAUSE)
                .eq(Coupon::getId, id)
                .in(Coupon::getStatus, UN_ISSUE, ISSUING)
                .update();
        if (!success) {
            // 可能是重复更新，结束
            log.error("重复暂停优惠券");
        }

        //4.删除缓存
        redisTemplate.delete(PromotionConstants.COUPON_CACHE_KEY_PREFIX + id);
    }

    //查询正在发放中优惠券
    @Override
    public List<CouponVO> queryIssuingCoupons() {
        //1.查询DB 优惠券表 coupon 条件：发放中 手动领取
        List<Coupon> couponList = this.lambdaQuery()
                .eq(Coupon::getStatus, ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                .list();
        if (CollUtils.isEmpty(couponList)){
            return CollUtils.emptyList();
        }


        Set<Long> couponIds = couponList.stream().map(Coupon::getId).collect(Collectors.toSet());//正在发放的优惠券集合
        //查询用户券表
        List<UserCoupon> list = userCouponService.lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUser())
                .in(UserCoupon::getCouponId, couponIds)
                .list();
        //统计当前用户针对每一个券已领数量
        Map<Long, Long> issueMap = list.stream().collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        //统计当前用户针对每一个券，已领且为使用的券数量
        Map<Long, Long> unUserMap = list.stream()
                .filter(userCoupon -> userCoupon.getStatus() == UserCouponStatus.UNUSED)
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        //2.po转vo
        List<CouponVO> voList = new ArrayList<>();
        for (Coupon c : couponList) {
            CouponVO couponVO = BeanUtils.copyBean(c, CouponVO.class);

            Long issNum = issueMap.getOrDefault(c.getId(), 0L);
            boolean available = c.getIssueNum() < c.getTotalNum() && issNum.intValue() < c.getUserLimit();
            couponVO.setAvailable(available);

            boolean received = unUserMap.getOrDefault(c.getId(),0L) >0;
            couponVO.setReceived(received);
            voList.add(couponVO);
        }

        return voList;
    }
}
