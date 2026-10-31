package com.linli.handler;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。响应同时带目标字段（code/msg）和兼容字段（success/message），
 * 这样新页面可以按 code 判断，旧页面继续读 success 而不必先改前端。
 *
 * 契约要求所有失败响应都是 {code, data:null, msg}，包括 Spring 自己抛的那批
 * （请求体不合法、方法不支持、参数缺失或类型错、静态资源不存在），否则前端只能拿到
 * Spring 默认错误体——里面没有 code，还把内部 path 吐给了客户端。
 */
@RestControllerAdvice
public class CommonExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CommonExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException e) {
        return ResponseEntity.status(statusOf(e.code())).body(body(e.code(), e.getMessage()));
    }

    /**
     * 兜底：JDK、Spring、MyBatis 深处抛的 IllegalArgumentException。
     *
     * 原来这里把 {@code e.getMessage()} 原样回给客户端，还用 {@code message.contains("不存在")}
     * 在 404 和 400 之间猜——两个问题：① 这些异常的消息是写给开发者看的（类名、参数名、
     * 内部方法的措辞），透传出去等于给探测者递线索；② 错误码跟着文案走，
     * 有人改一个中文字就静默换了 HTTP 状态。
     * 本项目**没有任何业务代码主动抛 IAE**（要给用户看的提示一律走 ApiException），
     * 所以这里没有"有用的消息"可以透传，一律 40001 + 固定文案，真实消息只进日志。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handle(IllegalArgumentException e) {
        log.warn("非法参数（框架或 JDK 抛出，不透传原文）", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(ErrorCode.PARAM_INVALID, "请求参数不正确"));
    }

    /**
     * Redis 的脚本/命令错误单独一类。RedisSystemException 继承自 DataAccessException，不写这个更具体的
     * 处理器的话，Lua 脚本报错会被上一类记成"数据访问失败"，排查时被 MySQL 方向带偏（实测踩过）。
     * spring-data-redis 3.3.5 里没有统一的 RedisException 基类，所以只能按具体子类接。
     */
    @ExceptionHandler(RedisSystemException.class)
    public ResponseEntity<Map<String, Object>> handleRedis(RedisSystemException e) {
        log.error("Redis 操作失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(ErrorCode.SERVER_ERROR, "服务异常，请稍后重试"));
    }

    /** 数据库/Redis 故障不再漏成 Spring 原生 500，前端可按 code=50001 显示重试入口。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleData(DataAccessException e) {
        log.error("数据访问失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(ErrorCode.SERVER_ERROR, "服务异常，请稍后重试"));
    }

    /** 上传请求体不合法（不是 multipart、超出容器限制）不再漏成 Spring 的 500 页面。 */
    @ExceptionHandler({MultipartException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String, Object>> handleUpload(Exception e) {
        log.warn("上传请求无法处理: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(ErrorCode.PARAM_INVALID, "图片上传请求不正确，请重新选择文件"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(ErrorCode.PARAM_INVALID, "请求体格式不正确"));
    }

    /**
     * Controller 上 {@code @Valid} 触发的 Bean Validation 失败。不接这一类的话会掉进下面的兜底处理器，
     * 一个"数量填 0"的合法输入被记成 50001，前端弹"服务异常"，用户以为服务坏了。
     *
     * 只回第一条字段消息：DTO 上的 message 本来就是给人看的中文，全列出来对前端没有额外价值。
     * 嵌套的 items[0].quantity 也在这里，Spring 会把整条属性路径作为字段名。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(ObjectError::getDefaultMessage)
                .orElse("请求参数不正确");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(ErrorCode.PARAM_INVALID, msg));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(ErrorCode.PARAM_INVALID, "缺少必填参数：" + e.getParameterName()));
    }

    /** 路径或查询参数类型不对，例如 /api/shops/abc。原始值不回显，避免被当作反射面。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(ErrorCode.PARAM_INVALID, "参数类型不正确：" + e.getName()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body(ErrorCode.PARAM_INVALID, "不支持的请求方法：" + e.getMethod()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(body(ErrorCode.PARAM_INVALID, "不支持的请求内容类型"));
    }

    /** 静态资源或未知路径：仍然按契约出 code，不给 Spring 默认体。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(ErrorCode.NOT_FOUND, "资源不存在"));
    }

    /** 兜底：未知异常只回固定文案，堆栈留在日志里，不把内部信息吐给客户端。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("未处理的服务端异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(ErrorCode.SERVER_ERROR, "服务异常，请稍后重试"));
    }

    private static Map<String, Object> body(int code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("data", null);
        body.put("msg", msg);
        body.put("success", false);
        body.put("message", msg);
        return body;
    }

    private static HttpStatus statusOf(int code) {
        if (code == ErrorCode.UNAUTHORIZED) return HttpStatus.UNAUTHORIZED;
        if (code == ErrorCode.FORBIDDEN) return HttpStatus.FORBIDDEN;
        if (code == ErrorCode.NOT_FOUND) return HttpStatus.NOT_FOUND;
        if (code == ErrorCode.CONFLICT) return HttpStatus.CONFLICT;
        if (code == ErrorCode.SERVER_ERROR) return HttpStatus.INTERNAL_SERVER_ERROR;
        return HttpStatus.BAD_REQUEST;
    }
}
