package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.discount.Discount;
import com.tianji.promotion.discount.DiscountStrategy;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author baize
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    // private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper rabbitMqHelper;
    private final ICouponScopeService couponScopeService;

    // 领取优惠券
    @MyLock(name = "lock:coupon:uid:#{id}")
    @Override
    // @Transactional
    public void receiveCoupon(Long id) {
        // 1.根据id查询相关优惠券信息，进行校验
        if (id == null) {
            throw new BadRequestException("参数异常");
        }
        // 从redis中获取优惠卷信息
        Coupon coupon = queryCouponByCache(id);

        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
       /*  if (coupon.getStatus() != CouponStatus.ISSUING) {
            throw new BadRequestException("优惠券非发放状态");
        } */
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
            throw new BadRequestException("优惠券已过期或未开放");
        }
        // if (coupon.getTotalNum() <= 0 || coupon.getIssueNum() >= coupon.getTotalNum()) {
        if (coupon.getTotalNum() <= 0) {
            throw new BadRequestException("优惠券库存不足");
        }


        // 获取当前用户对该优惠券已领数量
        Long userId = UserContext.getUser();

        // 统计已经领取的数量
        String key = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + id;
        Long increment = redisTemplate.opsForHash().increment(key, userId.toString(), 1);// 领取后的已领数量

        // 校验是否超过限领数量
        if (increment > coupon.getUserLimit()) {
            throw new BizIllegalException("超出限领数量");// 由于increment是加一之后的结果只能判断大于
        }

        /*  String key = "lock:coupon:uid:" + userId;
        RLock lock = redissonClient.getLock(key); */
        /* IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
        userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null); */

        // 修改优惠券的库存
        String couponKey = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;
        redisTemplate.opsForHash().increment(couponKey, "totalNum", -1);


        // 发送消息到mq
        UserCouponDTO msg = new UserCouponDTO();
        msg.setUserId(userId);
        msg.setCouponId(id);

        rabbitMqHelper.send(
                MqConstants.Exchange.PROMOTION_EXCHANGE,
                MqConstants.Key.COUPON_RECEIVE,
                msg);
    }

    /**
     * 从redis中获取优惠券信息
     *
     * @param id
     * @return
     */
    private Coupon queryCouponByCache(Long id) {
        // 拼接key
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;
        // 获取数据
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        return BeanUtils.mapToBean(entries, Coupon.class, false, CopyOptions.create());
    }

    @MyLock(name = "lock:coupon:uid:#{userId}", lockType = MyLockType.RE_ENTRANT_LOCK, lockStrategy = MyLockStrategy.FAIL_FAST)
    @Transactional
    @Override
    public void checkAndCreateUserCoupon(Long userId, Coupon coupon, Long serialNum) {

        Integer count = this.lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, coupon.getId())
                .count();
        if (count != null && count >= coupon.getUserLimit()) {
            throw new BadRequestException("优惠券已经达到领取上限");
        }

        // 2.优惠券已发放数量+1
        int incrIssueNum = couponMapper.incrIssueNum(coupon.getId());
        if (incrIssueNum == 0) {
            throw new BizIllegalException("优惠卷库存不足");
        }
        // 3.生成用户券
        saveUserCoupon(userId, coupon);
        if (serialNum != null) {
            exchangeCodeService.lambdaUpdate()
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .set(ExchangeCode::getUserId, userId)
                    .eq(ExchangeCode::getId, serialNum)
                    .update();
        }
    }


    @Transactional
    @Override
    public void checkAndCreateUserCouponNew(UserCouponDTO msg) {

        Long couponId = msg.getCouponId();
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            return;
        }

        // 2.优惠券已发放数量+1
        int incrIssueNum = couponMapper.incrIssueNum(coupon.getId());
        if (incrIssueNum == 0) {
            return;
        }

        Long userId = msg.getUserId();
        // 3.生成用户券
        saveUserCoupon(userId, coupon);
        /* if (serialNum != null) {
            exchangeCodeService.lambdaUpdate()
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .set(ExchangeCode::getUserId, userId)
                    .eq(ExchangeCode::getId, serialNum)
                    .update();
        } */
    }

    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> course) {
        // 1.查询当前用户可用的优惠券 user_coupon 和coupon 条件：当前用户， status=1
        Long userId = UserContext.getUser();
        List<Coupon> coupons = getBaseMapper().queryMyCoupon(userId);
        if (CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }
        // 2.初步筛选
        // 2.1计算订单的总金额
        int totalAmount = course.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        List<Coupon> availableCoupons = coupons.stream()
                .filter(coupon -> DiscountStrategy
                        .getDiscount(coupon.getDiscountType())
                        .canUse(totalAmount, coupon))
                .collect(Collectors.toList());

        if (CollUtils.isEmpty(availableCoupons)) {
            return CollUtils.emptyList();
        }

        // 3.细筛（需要考虑优惠券的限定范围）排列组合
        Map<Coupon, List<OrderCourseDTO>> avaMap = findAvailableCoupons(availableCoupons, course);
        if (avaMap.isEmpty()) {
            return CollUtils.emptyList();
        }
        availableCoupons = new ArrayList<>(avaMap.keySet());
        log.debug("细筛之后优惠券个数 {}", availableCoupons.size());

        List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);
        for (Coupon availableCoupon : availableCoupons) {
            solutions.add(List.of(availableCoupon));// 添加单券到方案中
        }
        log.debug("排列组合");
        for (List<Coupon> solution : solutions) {
            List<Long> cids = solution.stream().map(Coupon::getId).collect(Collectors.toList());
            log.debug("{}", cids);
        }

        log.debug("开始计算 每一种组合的优惠明细");
        // 4.计算每一种组合优惠明细
        List<CouponDiscountDTO> dtos = Collections.synchronizedList(new ArrayList<>(solutions.size()));// 线程安全的集合
        /* for (List<Coupon> solution : solutions) {
            CouponDiscountDTO dto = calculateSolutionDiscount(avaMap, course, solution);
            log.debug("方案最终优惠 {}  方案中使用了那些优惠券{} 规则{}", dto.getDiscountAmount(), dto.getIds(), dto.getRules());
            dtos.add(dto);
        } */

        // 5.筛选最优解
        log.debug("多线程计算 每一种组合优惠明细");
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture
                    .supplyAsync(() -> calculateSolutionDiscount(avaMap, course, solution))
                    .thenAccept(dto -> {
                        log.debug("方案最终优惠 {}  方案中使用了那些优惠券{} 规则{}", dto.getDiscountAmount(), dto.getIds(), dto.getRules());
                        dtos.add(dto);
                        latch.countDown();// 计数器减一
                    });
        }
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("多线程计算组合优惠明细 报错", e);
        }
        return findBestSolution(dtos);
    }

    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> solutions) {
        // 1.创建两个map分别记录 分别记录用券相同，金额最高，  金额最高用券最少
        Map<String, CouponDiscountDTO> moreDiscountMap = new HashMap<>();
        Map<Integer, CouponDiscountDTO> lessCouponMap = new HashMap<>();
        // 2.循环solutions
        for (CouponDiscountDTO solution : solutions) {
            String ids = solution.getIds().stream()
                    .sorted(Comparator.comparing(Long::longValue))
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            CouponDiscountDTO old = moreDiscountMap.get(ids);
            if (old != null && old.getDiscountAmount() >= solution.getDiscountAmount()) {
                continue;
            }
            old = lessCouponMap.get(solution.getDiscountAmount());

            int newSize = solution.getIds().size();
            if (old != null && newSize > 1 && old.getIds().size() <= newSize) {
                continue;
            }

            moreDiscountMap.put(ids, solution);
            lessCouponMap.put(solution.getDiscountAmount(), solution);
        }
        // 3.向map中记录数据   记录用券相同，金额最高，  金额最高用券最少
        // 求map中的交集
        Collection<CouponDiscountDTO> bestSolution = CollUtils.intersection(moreDiscountMap.values(), lessCouponMap.values());
        // 对最终的结果按优惠金额做倒叙
        List<CouponDiscountDTO> latestBestSolution = bestSolution.stream()
                .sorted(Comparator.comparing(CouponDiscountDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());
        return latestBestSolution;
    }

    @Override
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query) {
        // 1.获取当前用户
        Long userId = UserContext.getUser();
        // 2.分页查询用户券
        Page<UserCoupon> page = lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, query.getStatus())
                .page(query.toMpPage(new OrderItem("term_end_time", true)));
        List<UserCoupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // 3.获取优惠券详细信息
        // 3.1.获取用户券关联的优惠券id
        Set<Long> couponIds = records.stream().map(UserCoupon::getCouponId).collect(Collectors.toSet());
        // 3.2.查询
        List<Coupon> coupons = couponMapper.selectBatchIds(couponIds);

        // 4.封装VO
        return PageDTO.of(page, BeanUtils.copyList(coupons, CouponVO.class));
    }

    /**
     * 计算每一个方案优惠明细
     *
     * @param avaMap   优惠券和每一个课程的映射关系
     * @param course   订单中所有的课程
     * @param solution 方案
     * @return 方案结果
     */
    private CouponDiscountDTO calculateSolutionDiscount(Map<Coupon, List<OrderCourseDTO>> avaMap,
                                                        List<OrderCourseDTO> course,
                                                        List<Coupon> solution) {
        // 创建方案结果dto对象
        CouponDiscountDTO dto = new CouponDiscountDTO();
        // 初始化商品id和商品折扣明细的映射关系，初始折扣明细全都设置为0
        Map<Long, Integer> detailMap = course.stream().collect(Collectors.toMap(OrderCourseDTO::getId, orderCourseDTO -> 0));
        // 计算该方案的折扣明细
        // 循环方案中优惠券
        for (Coupon coupon : solution) {
            // 取出该优惠券对应的可用课程
            List<OrderCourseDTO> availableCourses = avaMap.get(coupon);
            // 计算可用课程的总金额 商品的价格 - 该商品的折扣明细
            int totalAmount = availableCourses.stream()
                    .mapToInt(value -> value.getPrice() - detailMap.get(value.getId()))
                    .sum();
            // 判断优惠券是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (!discount.canUse(totalAmount, coupon)) {
                continue;
            }
            // 计算该优惠券使用后的折扣值
            int discountAmount = discount.calculateDiscount(totalAmount, coupon);
            // 更新商品的折扣明细（更新商品id对应的折扣明细）
            calculateDetailDiscount(detailMap, availableCourses, totalAmount, discountAmount);
            // 累加每一个优惠券优惠的金额，赋值给dto对象
            dto.getIds().add(coupon.getId());
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(discountAmount + dto.getDiscountAmount());
        }
        return dto;
    }


    /**
     * 计算商品 折扣明细
     *
     * @param detailMap        商品id和商品的优惠明细映射
     * @param availableCourses 当前优惠券可用的课程集合
     * @param totalAmount      可用的课程总金额
     * @param discountAmount   当前优惠券能优惠的金额
     */
    private void calculateDetailDiscount(Map<Long, Integer> detailMap,
                                         List<OrderCourseDTO> availableCourses,
                                         int totalAmount,
                                         int discountAmount) {
        // 前面的商品按比例计算，最后一个商品 = 等于总的优惠金额 - 前面优惠的总额
        int times = 0;// 已经处理的商品个数
        int remainDiscount = discountAmount;// 剩余的优惠金额
        for (OrderCourseDTO c : availableCourses) {
            times++;
            int discount = 0;
            if (times == availableCourses.size()) {
                // 最后一个课程
                discount = remainDiscount;
            } else {
                // 前面的课程
                discount = c.getPrice() * discountAmount / totalAmount;
                remainDiscount -= discount;
            }
            // 将商品的折扣明细添加到detailMap中
            detailMap.put(c.getId(), discount + detailMap.get(c.getId()));
        }
    }

    /**
     * 细筛查询每一个优惠券对应的可用课程
     *
     * @param coupons      初筛之后的优惠卷集合
     * @param orderCourses 订单中的课程集合
     * @return 细筛后可用优惠券和订单之间的映射
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupons(List<Coupon> coupons, List<OrderCourseDTO> orderCourses) {
        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>();
        // 1.循环遍历初筛之后的优惠券集合
        for (Coupon coupon : coupons) {
            // 2.找出每一个优惠券的可用范围
            List<OrderCourseDTO> availableCourses = orderCourses;
            // 2.1判断优惠券是否限定了范围
            if (coupon.getSpecific()) {
                // 2.2查询限定范围
                List<CouponScope> scopeList = couponScopeService.lambdaQuery()
                        .eq(CouponScope::getCouponId, coupon.getId())
                        .list();
                // 2.3得到限定范围的id集合
                List<Long> scopeIds = scopeList.stream().map(CouponScope::getBizId).collect(Collectors.toList());
                // 2.4从订单集合中筛选范围内的课程
                availableCourses = orderCourses.stream()
                        .filter(orderCourseDTO -> scopeIds.contains(orderCourseDTO.getCateId()))
                        .collect(Collectors.toList());
            }
            if (CollUtils.isEmpty(availableCourses)) {
                continue;
            }

            // 3.计算该优惠券可用课程的总金额
            int totalAmount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
            // 4.判断是否可用，是则添加进map
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (discount.canUse(totalAmount, coupon)) {
                map.put(coupon, availableCourses);
            }
        }
        return map;
    }

    @Override
    public void exchangeCoupon(String code) {
        // 1.校验code是否为null
        if (StringUtils.isBlank(code)) {
            throw new BadRequestException("非法参数");
        }

        // 2.解析并校验兑换码
        long serialNum = CodeUtil.parseCode(code);
        // 3.判断兑换码是否已经被兑换
        boolean result = exchangeCodeService.updateExchangeCodeMark(serialNum, true);
        if (result) {
            // 兑换码已经被兑换
            throw new BizIllegalException("兑换码已经被使用");
        }
        try {
            // 4.判断是否存在，根据自增id查询
            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
            if (exchangeCode == null) {
                throw new BizIllegalException("兑换码不存在");
            }
            // 5.判断是否过期
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiredTime = exchangeCode.getExpiredTime();
            if (now.isAfter(expiredTime)) {
                throw new BizIllegalException("兑换码已经过期");
            }
            // 6.判断是否超出限领的数量
            Long userId = UserContext.getUser();
            Coupon coupon = couponMapper.selectById(exchangeCode.getExchangeTargetId());
            if (coupon == null) {
                throw new BizIllegalException("优惠券不存在");
            }
            // 7.优惠券已发放数量+1
            // 8.生成用户券
            // 9.更新兑换码的状态
            checkAndCreateUserCoupon(userId, coupon, serialNum);
        } catch (Exception e) {
            // 将兑换码的状态重置
            exchangeCodeService.updateExchangeCodeMark(serialNum, false);
            throw e;
        }

    }

    // 保存用户券
    private void saveUserCoupon(Long userId, Coupon coupon) {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setUserId(userId);
        userCoupon.setCouponId(coupon.getId());
        LocalDateTime termBeginTime = coupon.getTermBeginTime();
        LocalDateTime termEndTime = coupon.getTermEndTime();
        if (termBeginTime == null && termEndTime == null) {
            termBeginTime = LocalDateTime.now();
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }

        userCoupon.setTermBeginTime(termBeginTime);
        userCoupon.setTermEndTime(termEndTime);

        this.save(userCoupon);
    }
}