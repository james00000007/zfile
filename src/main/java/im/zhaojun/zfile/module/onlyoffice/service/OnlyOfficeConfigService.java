package im.zhaojun.zfile.module.onlyoffice.service;

import cn.hutool.jwt.JWTUtil;
import com.alibaba.fastjson2.JSONObject;
import im.zhaojun.zfile.core.util.FileUtils;
import im.zhaojun.zfile.core.util.OnlyOfficeKeyCacheUtils;
import im.zhaojun.zfile.core.util.StringUtils;
import im.zhaojun.zfile.core.util.ZFileAuthUtil;
import im.zhaojun.zfile.module.config.model.dto.SystemConfigDTO;
import im.zhaojun.zfile.module.config.service.SystemConfigService;
import im.zhaojun.zfile.module.onlyoffice.model.OnlyOfficeFile;
import im.zhaojun.zfile.module.storage.model.result.FileItemResult;
import im.zhaojun.zfile.module.user.model.entity.User;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * OnlyOffice 预览配置生成服务. 同时被普通预览与分享预览复用,
 * 统一处理 Key 缓存、JWT 签名以及编辑权限判定, 避免两个入口对预览配置的处理逻辑出现偏差.
 *
 * @author zhaojun
 */
@Service
public class OnlyOfficeConfigService {

    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 生成 OnlyOffice 预览配置.
     *
     * <p>同时将发起预览的用户 ID、是否允许编辑写入 {@link OnlyOfficeFile} 缓存, 供回调时校验.</p>
     *
     * @param fileItemResult 文件信息
     * @param onlyOfficeFile 文件缓存信息(包含 storageKey/fullPath/userId)
     * @param allowEdit      预览发起时的编辑权限
     * @return OnlyOffice 预览配置 JSON
     */
    public JSONObject createConfig(FileItemResult fileItemResult,
                                   OnlyOfficeFile onlyOfficeFile,
                                   boolean allowEdit) {
        SystemConfigDTO systemConfig = systemConfigService.getSystemConfig();
        String onlyOfficeSecret = systemConfig.getOnlyOfficeSecret();
        // 未配置 Secret 时回调无法验签, 直接降级为只读, 防止生成虚假的编辑配置.
        boolean effectiveAllowEdit = allowEdit && StringUtils.isNotEmpty(onlyOfficeSecret);
        onlyOfficeFile.setAllowEdit(effectiveAllowEdit);
        String key = OnlyOfficeKeyCacheUtils.getKeyOrPutNew(onlyOfficeFile, 3000);
        return buildPayload(fileItemResult, key, effectiveAllowEdit, systemConfig);
    }

    private JSONObject buildPayload(FileItemResult fileItemResult, String key, boolean allowEdit, SystemConfigDTO systemConfig) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("width", "100%");
        jsonObject.put("height", "100%");

        jsonObject.put("document", new JSONObject()
                .fluentPut("fileType", FileUtils.getExtension(fileItemResult.getName()))
                .fluentPut("key", key)
                .fluentPut("permissions", new JSONObject()
                        .fluentPut("edit", allowEdit))
                .fluentPut("title", fileItemResult.getName())
                .fluentPut("url", fileItemResult.getUrl())
                .fluentPut("lang", "zh-CN"));

        User currentUser = ZFileAuthUtil.getCurrentUser();
        String onlyOfficeSecret = systemConfig.getOnlyOfficeSecret();

        JSONObject userJson = new JSONObject();
        if (currentUser != null) {
            userJson.fluentPut("id", currentUser.getId())
                    .fluentPut("name", StringUtils.firstNonNull(currentUser.getNickname(), currentUser.getUsername()));
        }

        jsonObject.put("editorConfig", new JSONObject()
                .fluentPut("callbackUrl", StringUtils.concat(systemConfigService.getAxiosFromDomainOrSetting(), "/onlyOffice/callback"))
                .fluentPut("lang", "zh-CN")
                .fluentPut("user", userJson));

        if (StringUtils.isNotEmpty(onlyOfficeSecret)) {
            String token = JWTUtil.createToken(jsonObject, onlyOfficeSecret.getBytes(StandardCharsets.UTF_8));
            jsonObject.put("token", token);
        }

        return jsonObject;
    }

}
