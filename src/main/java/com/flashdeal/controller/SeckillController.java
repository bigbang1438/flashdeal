package com.flashdeal.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.flashdeal.common.ApiResponse;
import com.flashdeal.dto.PayCallbackRequest;
import com.flashdeal.dto.SeckillOrderRequest;
import com.flashdeal.dto.SeckillOrderResponse;
import com.flashdeal.ratelimit.RateLimited;
import com.flashdeal.service.SeckillService;

import jakarta.validation.Valid;

/**
 * 秒杀下单接口。
 *
 * <p>核心接口 {@code POST /api/seckill/orders} 挂载了双维度限流注解：
 * 接口级默认 2000 QPS、单用户默认 50 QPS（均可通过 application.yml 覆盖）。</p>
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /** 秒杀下单。成功返回 201，业务失败由全局异常处理映射成 4xx/5xx。 */
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(key = "seckill-order")
    public ApiResponse<SeckillOrderResponse> seckill(@Valid @RequestBody SeckillOrderRequest request) {
        return ApiResponse.ok(seckillService.seckill(request));
    }

    /** 查询订单详情。 */
    @org.springframework.web.bind.annotation.GetMapping("/orders/{orderNo}")
    public ApiResponse<SeckillOrderResponse> getOrder(
            @org.springframework.web.bind.annotation.PathVariable String orderNo) {
        return ApiResponse.ok(seckillService.getOrder(orderNo));
    }

    /** 支付回调（模拟第三方支付平台）。 */
    @PostMapping("/pay/callback")
    public ApiResponse<SeckillOrderResponse> payCallback(@Valid @RequestBody PayCallbackRequest request) {
        return ApiResponse.ok(seckillService.pay(request));
    }

    /** 用户主动取消订单（会回补库存）。 */
    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<Boolean> cancelOrder(
            @org.springframework.web.bind.annotation.PathVariable String orderNo) {
        return ApiResponse.ok(seckillService.cancelOrder(orderNo));
    }
}
