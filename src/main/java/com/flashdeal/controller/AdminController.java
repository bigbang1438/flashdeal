package com.flashdeal.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flashdeal.common.ApiResponse;
import com.flashdeal.common.BizException;
import com.flashdeal.common.ErrorCode;
import com.flashdeal.common.PageQuery;
import com.flashdeal.common.PageResult;
import com.flashdeal.domain.ActivityStatus;
import com.flashdeal.domain.Product;
import com.flashdeal.domain.SeckillActivity;
import com.flashdeal.dto.CreateActivityRequest;
import com.flashdeal.dto.CreateProductRequest;
import com.flashdeal.repository.ProductRepository;
import com.flashdeal.repository.SeckillActivityRepository;
import com.flashdeal.service.ActivityService;
import com.flashdeal.stock.MemoryStockService;
import com.flashdeal.stock.StockService;

import jakarta.validation.Valid;

/**
 * 商品与秒杀活动管理接口（管理台使用）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ProductRepository productRepository;
    private final SeckillActivityRepository activityRepository;
    private final ActivityService activityService;
    private final StockService stockService;

    public AdminController(ProductRepository productRepository,
                           SeckillActivityRepository activityRepository,
                           ActivityService activityService,
                           StockService stockService) {
        this.productRepository = productRepository;
        this.activityRepository = activityRepository;
        this.activityService = activityService;
        this.stockService = stockService;
    }

    // ---------------- 商品 ----------------

    /** 分页查询商品。 */
    @GetMapping("/products")
    public ApiResponse<PageResult<Product>> listProducts(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        List<Product> list = productRepository.findPage(query.offset(), query.size());
        return ApiResponse.ok(PageResult.of(productRepository.count(), query.page(), query.size(), list));
    }

    /** 商品详情。 */
    @GetMapping("/products/{id}")
    public ApiResponse<Product> getProduct(@PathVariable long id) {
        return ApiResponse.ok(productRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "商品不存在")));
    }

    /** 创建商品。 */
    @PostMapping("/products")
    public ApiResponse<Product> createProduct(@Valid @RequestBody CreateProductRequest request) {
        long id = productRepository.insert(request.name(), request.description(),
                request.priceCents(), request.imageUrl(), 1);
        return ApiResponse.ok(productRepository.findById(id).orElseThrow());
    }

    /** 上架/下架商品。 */
    @PostMapping("/products/{id}/status")
    public ApiResponse<Boolean> updateProductStatus(@PathVariable long id, @RequestParam int status) {
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 只能是 0 或 1");
        }
        int updated = productRepository.updateStatus(id, status);
        if (updated == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return ApiResponse.ok(true);
    }

    // ---------------- 秒杀活动 ----------------

    /** 分页查询活动。 */
    @GetMapping("/activities")
    public ApiResponse<PageResult<SeckillActivity>> listActivities(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        List<SeckillActivity> list = activityRepository.findPage(query.offset(), query.size());
        return ApiResponse.ok(PageResult.of(activityRepository.count(), query.page(), query.size(), list));
    }

    /** 活动详情（走多级缓存，可观察命中率变化）。 */
    @GetMapping("/activities/{id}")
    public ApiResponse<SeckillActivity> getActivity(@PathVariable long id) {
        return ApiResponse.ok(activityService.getActivity(id));
    }

    /** 创建秒杀活动，并立刻做库存预热（缓存 + 内存预扣余额）。 */
    @PostMapping("/activities")
    public ApiResponse<SeckillActivity> createActivity(@Valid @RequestBody CreateActivityRequest request) {
        if (!request.timeRangeValid()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "开始时间必须早于结束时间");
        }
        if (productRepository.findById(request.productId()).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int status = request.startTime().isAfter(now) ? ActivityStatus.PENDING.code() : ActivityStatus.RUNNING.code();
        long id = activityRepository.insert(request.productId(), request.title(),
                request.seckillPriceCents(), request.totalStock(), request.startTime(), request.endTime(), status);

        // 库存预热：写入 L1/L2 缓存，内存策略下同时预热内存余额
        activityService.warmUp(id);
        if (stockService instanceof MemoryStockService memoryStockService) {
            memoryStockService.preload(id, request.totalStock());
        }
        return ApiResponse.ok(activityService.getActivity(id));
    }

    /** 手动开启/关闭活动。 */
    @PostMapping("/activities/{id}/status")
    public ApiResponse<Boolean> updateActivityStatus(@PathVariable long id, @RequestParam int status) {
        int updated = activityRepository.updateStatus(id, status);
        if (updated == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        activityService.evict(id);
        return ApiResponse.ok(true);
    }

    /**
     * 库存预热：把数据库库存重新加载到缓存与内存策略。
     *
     * <p>压测前调用可以保证「第一枪」就在预热后的状态下发出，
     * 让压测结果可复现。</p>
     */
    @PostMapping("/activities/{id}/warmup")
    public ApiResponse<Integer> warmUp(@PathVariable long id) {
        SeckillActivity activity = activityRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.ACTIVITY_NOT_FOUND));
        activityService.warmUp(id);
        if (stockService instanceof MemoryStockService memoryStockService) {
            memoryStockService.preload(id, activity.stock());
        }
        return ApiResponse.ok(stockService.currentStock(id));
    }
}
