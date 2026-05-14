package im.zhaojun.zfile.module.onlyoffice.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * OnlyOffice 缓存文件信息.
 *
 * <p>缓存的相等性仅以 {@code storageKey + pathAndName} 为准, 保证 {@link im.zhaojun.zfile.core.util.OnlyOfficeKeyCacheUtils}
 * 通过文件路径就能命中之前发放预览时存储的上下文(包含发起预览的用户 ID 与编辑权限).</p>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(of = {"storageKey", "pathAndName"})
public class OnlyOfficeFile {

    private String storageKey;

    private String pathAndName;

    /**
     * 发起预览的用户 ID. 用于 OnlyOffice 回调时的权限再校验与上下文切换,
     * 防止回调请求伪造 {@code users} 字段越权写入.
     */
    private Integer userId;

    /**
     * 生成预览配置时是否允许编辑保存. 回调写入前会再次校验此字段, 拒绝当时不允许编辑的请求.
     */
    private boolean allowEdit;

    /**
     * 仅用于通过 {@code storageKey + pathAndName} 查询/失效缓存的轻量构造器.
     */
    public OnlyOfficeFile(String storageKey, String pathAndName) {
        this.storageKey = storageKey;
        this.pathAndName = pathAndName;
    }

    public OnlyOfficeFile(String storageKey, String pathAndName, Integer userId, boolean allowEdit) {
        this.storageKey = storageKey;
        this.pathAndName = pathAndName;
        this.userId = userId;
        this.allowEdit = allowEdit;
    }

}
