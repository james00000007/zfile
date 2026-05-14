package im.zhaojun.zfile.module.onlyoffice.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.alibaba.fastjson2.JSONObject;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import im.zhaojun.zfile.core.exception.biz.InvalidStorageSourceBizException;
import im.zhaojun.zfile.core.exception.core.BizException;
import im.zhaojun.zfile.core.util.*;
import im.zhaojun.zfile.module.config.model.dto.SystemConfigDTO;
import im.zhaojun.zfile.module.config.service.SystemConfigService;
import im.zhaojun.zfile.module.onlyoffice.model.OnlyOfficeCallback;
import im.zhaojun.zfile.module.onlyoffice.model.OnlyOfficeFile;
import im.zhaojun.zfile.module.onlyoffice.service.OnlyOfficeConfigService;
import im.zhaojun.zfile.module.storage.annotation.CheckPassword;
import im.zhaojun.zfile.module.storage.context.StorageSourceContext;
import im.zhaojun.zfile.module.storage.model.enums.FileOperatorTypeEnum;
import im.zhaojun.zfile.module.storage.model.request.base.FileItemRequest;
import im.zhaojun.zfile.module.storage.model.result.FileItemResult;
import im.zhaojun.zfile.module.storage.service.StorageSourceService;
import im.zhaojun.zfile.module.storage.service.base.AbstractBaseFileService;
import im.zhaojun.zfile.module.storage.service.base.AbstractProxyTransferService;
import im.zhaojun.zfile.module.user.service.UserStorageSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.beans.Beans;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Tag(name = "OnlyOffice 相关接口")
@RestController
@RequestMapping("/onlyOffice")
public class OnlyOfficeController {

    @Resource
    private SystemConfigService systemConfigService;

    @Resource
    private StorageSourceService storageSourceService;

    @Resource
    private UserStorageSourceService userStorageSourceService;

    @Resource
    private OnlyOfficeConfigService onlyOfficeConfigService;

    private static final String CALLBACK_ERROR_MSG = "{\"error\":1}";

    private static final String CALLBACK_SUCCESS_MSG = "{\"error\":0}";

    /**
     * OnlyOffice 回调所有需要处理的状态(保存 + 错误), 用于清理 key 缓存.
     */
    public static final List<Integer> SUPPORTED_STATUS = List.of(2, 3, 6, 7);

    /**
     * OnlyOffice 回调真正需要保存写入存储的状态. 错误状态(3, 7)只清缓存, 不写文件.
     */
    private static final List<Integer> SAVE_STATUS = List.of(2, 6);

    private static final int ONLY_OFFICE_DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 5_000;

    private static final int ONLY_OFFICE_DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000;

    private static final int ONLY_OFFICE_DOWNLOAD_MAX_REDIRECTS = 5;

    @ApiOperationSupport(order = 3)
    @Operation(summary = "OnlyOffice 预览文件", description = "根据传入的文件信息, 生成 OnlyOffice 预览所需的 JSON 数据.")
    @PostMapping("/config/token")
    @CheckPassword(storageKeyFieldExpression = "[0].storageKey",
            pathFieldExpression = "[0].path",
            pathIsDirectory = false,
            passwordFieldExpression = "[0].password")
    public AjaxJson<JSONObject> getPreviewFileJSONInfo(@Valid @RequestBody FileItemRequest fileItemRequest) {
        // 根据存储策略获取文件信息(下载地址), 会校验权限.
        Pair<FileItemResult, Boolean> pair = getFileInfo(fileItemRequest);
        FileItemResult fileInfo = pair.getKey();
        Boolean hasUploadPermission = pair.getRight();

        Integer currentUserId = ZFileAuthUtil.getCurrentUserId();
        // 把发起预览的用户 ID 一并写入缓存, 回调时据此校验权限, 避免信任回调中的 users 字段.
        OnlyOfficeFile onlyOfficeFile = new OnlyOfficeFile(fileItemRequest.getStorageKey(), fileItemRequest.getPath(),
                currentUserId, false);

        JSONObject onlyOfficePayload = onlyOfficeConfigService.createConfig(fileInfo, onlyOfficeFile,
                Boolean.TRUE.equals(hasUploadPermission));
        return AjaxJson.getSuccessData(onlyOfficePayload);
    }

    private Pair<FileItemResult, Boolean> getFileInfo(FileItemRequest fileItemRequest) {
        String storageKey = fileItemRequest.getStorageKey();
        Integer storageId = storageSourceService.findIdByKey(storageKey);
        if (storageId == null) {
            throw new InvalidStorageSourceBizException(storageKey);
        }

        // 处理请求参数默认值
        fileItemRequest.handleDefaultValue();

        // 获取文件信息
        AbstractBaseFileService<?> fileService = StorageSourceContext.getByStorageId(storageId);
        try {
            FileItemResult fileItem = fileService.getFileItem(fileItemRequest.getPath());
            if (fileItem == null) {
                throw new BizException("文件不存在");
            }

            String currentUserBasePath = fileService.getCurrentUserBasePath();
            fileItemRequest.setPath(StringUtils.concat(currentUserBasePath, fileItemRequest.getPath()));

            boolean hasUploadPermission = userStorageSourceService.hasCurrentUserStorageOperatorPermission(storageId, FileOperatorTypeEnum.UPLOAD);
            return Pair.of(fileItem, hasUploadPermission);
        } catch (Exception e) {
            throw new BizException("获取文件信息失败: " + e.getMessage());
        }
    }


    @RequestMapping("/callback")
    public String callBack(@RequestBody OnlyOfficeCallback onlyOfficeCallback) {
        if (log.isDebugEnabled()) {
            log.debug("OnlyOffice 回调信息: {}, {}", onlyOfficeCallback.getStatus(), onlyOfficeCallback);
        }

        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        String onlyOfficeSecret = systemConfig.getOnlyOfficeSecret();
        // 未配置 secret 时, callback 无法验签, 直接拒绝, 避免被未授权请求触发存储写入.
        if (StrUtil.isBlank(onlyOfficeSecret)) {
            log.warn("OnlyOffice 回调被拒绝: 未配置 Secret, key={}, status={}",
                    onlyOfficeCallback.getKey(), onlyOfficeCallback.getStatus());
            return CALLBACK_ERROR_MSG;
        }

        if (StrUtil.isBlank(onlyOfficeCallback.getToken())) {
            log.error("OnlyOffice 回调 Token 为空: key={}, status={}",
                    onlyOfficeCallback.getKey(), onlyOfficeCallback.getStatus());
            return CALLBACK_ERROR_MSG;
        }

        if (!JWTUtil.verify(onlyOfficeCallback.getToken(), StrUtil.bytes(onlyOfficeSecret, StandardCharsets.UTF_8))) {
            log.error("OnlyOffice 回调 Token 验证失败: key={}, status={}",
                    onlyOfficeCallback.getKey(), onlyOfficeCallback.getStatus());
            return CALLBACK_ERROR_MSG;
        }

        // 仅在真正需要保存写入存储时再执行 URL 下载与上传; 错误状态(3, 7)仍然清掉 key 缓存.
        if (!SUPPORTED_STATUS.contains(onlyOfficeCallback.getStatus())) {
            return CALLBACK_SUCCESS_MSG;
        }

        String key = onlyOfficeCallback.getKey();
        OnlyOfficeFile onlyOfficeFile = OnlyOfficeKeyCacheUtils.removeByKey(key);
        // 文件不存在或者存储策略不存在, 直接返回错误信息.
        if (onlyOfficeFile == null) {
            return CALLBACK_ERROR_MSG;
        }

        ReentrantLock lock = OnlyOfficeKeyCacheUtils.getLock(onlyOfficeFile);
        lock.lock();
        if (log.isDebugEnabled()) {
            log.debug("开始处理 OnlyOffice 文件: {}, 加锁", key);
        }
        try {
            // 错误状态仅清缓存, 不写入存储.
            if (!SAVE_STATUS.contains(onlyOfficeCallback.getStatus())) {
                return CALLBACK_SUCCESS_MSG;
            }

            // 校验回调 body 中的 key 是否与 token payload 中的 key 一致, 防止用其他文档的 token 写本文档.
            if (!isCallbackTokenKeyMatched(onlyOfficeCallback)) {
                log.warn("OnlyOffice 回调 Token 与 key 不匹配: key={}, status={}",
                        key, onlyOfficeCallback.getStatus());
                return CALLBACK_ERROR_MSG;
            }

            // 预览发放时不允许编辑, 即使签名有效也拒绝写入.
            if (!onlyOfficeFile.isAllowEdit()) {
                log.warn("OnlyOffice 回调保存被拒绝: 预览配置不允许编辑, key={}, storageKey={}, path={}",
                        key, onlyOfficeFile.getStorageKey(), onlyOfficeFile.getPathAndName());
                return CALLBACK_ERROR_MSG;
            }

            AbstractBaseFileService<?> storageServiceByKey = StorageSourceContext.getByStorageKey(onlyOfficeFile.getStorageKey());
            if (storageServiceByKey == null) {
                return CALLBACK_ERROR_MSG;
            }

            Integer cachedUserId = onlyOfficeFile.getUserId();
            Integer storageId = storageSourceService.findIdByKey(onlyOfficeFile.getStorageKey());
            // 使用预览时缓存的用户身份重新校验权限, 而非信任 callback 中的 users.
            if (storageId == null
                    || !userStorageSourceService.hasUserStorageOperatorPermission(cachedUserId, storageId, FileOperatorTypeEnum.UPLOAD)) {
                log.warn("OnlyOffice 回调保存被拒绝: 缓存用户无上传权限, key={}, storageId={}, userId={}",
                        key, storageId, cachedUserId);
                return CALLBACK_ERROR_MSG;
            }

            // 切换为预览时记录的用户身份, 让下游 service 的 getCurrentUserBasePath / 权限判断生效.
            if (cachedUserId != null) {
                StpUtil.login(cachedUserId);
            }

            if (log.isDebugEnabled()) {
                log.debug("开始保存 OnlyOffice 文件: {}, {}", onlyOfficeFile.getStorageKey(), onlyOfficeFile.getPathAndName());
            }

            if (Beans.isInstanceOf(storageServiceByKey, AbstractProxyTransferService.class)) {
                AbstractProxyTransferService<?> proxyUploadService = (AbstractProxyTransferService<?>) storageServiceByKey;
                try {
                    URLConnection connection = openOnlyOfficeDownloadConnection(onlyOfficeCallback.getUrl(), systemConfig.getOnlyOfficeUrl());
                    long contentLength = connection.getContentLengthLong();
                    try (InputStream inputStream = connection.getInputStream()) {
                        String pathAndName = onlyOfficeFile.getPathAndName();
                        proxyUploadService.uploadFile(pathAndName, inputStream, contentLength);
                    }
                } catch (IOException | URISyntaxException e) {
                    log.warn("OnlyOffice 回调下载地址拒绝或访问失败: key={}, urlHost={}, reason={}",
                            key, safeHost(onlyOfficeCallback.getUrl()), e.getMessage());
                    return CALLBACK_ERROR_MSG;
                } catch (Exception e) {
                    log.error("回调保存 OnlyOffice 文件失败: key={}, storageKey={}",
                            key, onlyOfficeFile.getStorageKey(), e);
                    return CALLBACK_ERROR_MSG;
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("完成保存 OnlyOffice 文件: {}, {}", onlyOfficeFile.getStorageKey(), onlyOfficeFile.getPathAndName());
            }
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("完成处理 OnlyOffice 文件: {}, 解锁", key);
            }
            lock.unlock();
        }
        return CALLBACK_SUCCESS_MSG;
    }

    /**
     * 校验 callback body 中的 key 与 JWT payload 中的 key 一致.
     * 防止: 攻击者拿到文档 A 的 token, 在 body 中改 key 指向文档 B.
     */
    private boolean isCallbackTokenKeyMatched(OnlyOfficeCallback onlyOfficeCallback) {
        try {
            JWT jwt = JWTUtil.parseToken(onlyOfficeCallback.getToken());
            Object callbackKey = jwt.getPayload("key");
            if (callbackKey != null && Objects.equals(onlyOfficeCallback.getKey(), callbackKey.toString())) {
                return true;
            }

            // 预览生成时, key 嵌套在 document.key 中.
            Object document = jwt.getPayload("document");
            if (document instanceof java.util.Map) {
                Object documentKey = ((java.util.Map<?, ?>) document).get("key");
                if (documentKey != null && Objects.equals(onlyOfficeCallback.getKey(), documentKey.toString())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("OnlyOffice 回调 Token 解析失败: key={}, status={}",
                    onlyOfficeCallback.getKey(), onlyOfficeCallback.getStatus());
            return false;
        }
    }

    /**
     * 严格校验 OnlyOffice 回调中的下载地址必须与系统配置中的 OnlyOffice Document Server 同源,
     * 同时限制重定向次数与超时, 避免 SSRF 触达内网或任意域名.
     */
    private URLConnection openOnlyOfficeDownloadConnection(String callbackUrl, String onlyOfficeUrl) throws IOException, URISyntaxException {
        URI currentUri = parseHttpUri(callbackUrl);
        URI onlyOfficeUri = parseHttpUri(onlyOfficeUrl);

        for (int i = 0; i <= ONLY_OFFICE_DOWNLOAD_MAX_REDIRECTS; i++) {
            validateOnlyOfficeUrl(currentUri, onlyOfficeUri);
            URLConnection connection = currentUri.toURL().openConnection();
            connection.setConnectTimeout(ONLY_OFFICE_DOWNLOAD_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(ONLY_OFFICE_DOWNLOAD_READ_TIMEOUT_MILLIS);

            if (connection instanceof HttpURLConnection httpConnection) {
                // 关闭自动重定向, 手动校验每一跳目标依然命中白名单.
                httpConnection.setInstanceFollowRedirects(false);
                int responseCode = httpConnection.getResponseCode();
                if (isRedirect(responseCode)) {
                    String location = httpConnection.getHeaderField("Location");
                    httpConnection.disconnect();
                    if (StrUtil.isBlank(location)) {
                        throw new IOException("OnlyOffice 下载地址重定向缺少 Location");
                    }
                    currentUri = currentUri.resolve(location);
                    continue;
                }
            }

            return connection;
        }

        throw new IOException("OnlyOffice 下载地址重定向次数超过限制");
    }

    private URI parseHttpUri(String rawUrl) throws URISyntaxException {
        if (StrUtil.isBlank(rawUrl)) {
            throw new URISyntaxException("", "OnlyOffice URL 不能为空");
        }
        URI uri = new URI(rawUrl).normalize();
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new URISyntaxException(rawUrl, "OnlyOffice URL 仅支持 http/https");
        }
        if (StrUtil.isBlank(uri.getHost())) {
            throw new URISyntaxException(rawUrl, "OnlyOffice URL host 不能为空");
        }
        return uri;
    }

    private void validateOnlyOfficeUrl(URI callbackUri, URI onlyOfficeUri) throws IOException {
        if (!normalizeScheme(callbackUri).equals(normalizeScheme(onlyOfficeUri))
                || !normalizeHost(callbackUri).equals(normalizeHost(onlyOfficeUri))
                || normalizePort(callbackUri) != normalizePort(onlyOfficeUri)) {
            throw new IOException("OnlyOffice 下载地址与配置的 Document Server 不匹配");
        }
    }

    private String normalizeScheme(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private String normalizeHost(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private int normalizePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isRedirect(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                || responseCode == 307
                || responseCode == 308;
    }

    private String safeHost(String rawUrl) {
        try {
            return new URI(rawUrl).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
