package com.lumenforge.service.material;

import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.material.MaterialSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 占位抓取器：真实抓取尚未接入，但把「为什么抓不到」说清楚。
 *
 * <p>Java 版不联网抓素材，所以这里只做三件事：
 * <ol>
 *   <li>线路为空时，报出和 Python 版一字不差的提示（引导用户去「接口管理」补线路）；</li>
 *   <li>线路非空时，返回「该素材源暂未接入抓取，请手动导入」并带上试过的线路；</li>
 *   <li>永远返回空 items —— 前端据此显示占位提示，不会误以为刷新成功。</li>
 * </ol>
 *
 * <p>真要接某个站点时，新建一个实现 {@link MaterialFetcher} 的 {@code @Component}
 * 并在它上面标 {@code @Primary}（或替换本类），分类的 CRUD 与缓存逻辑都不用动。
 */
@Component
public class MaterialPlaceholderFetcher implements MaterialFetcher {

    @Override
    public Map<String, Object> fetch(MaterialSource source, Map<String, String> headers, List<String> lines) {
        String name = source.getName() == null || source.getName().trim().isEmpty()
                ? "素材分类"
                : source.getName().trim();
        String mode = modeOf(source);
        List<String> tried = lines == null ? new ArrayList<String>() : new ArrayList<String>(lines);
        String message;
        if (tried.isEmpty()) {
            if ("m3u8".equals(mode)) {
                message = "分类「" + name + "」拼不出线路：到「接口管理」把全局线路池（基地址）和这个分类的编号都填上";
            } else {
                message = "分类「" + name + "」还没填接口地址，点「接口管理」补上";
            }
        } else {
            message = "该素材源暂未接入抓取，请手动导入";
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("items", new ArrayList<Map<String, Object>>());
        out.put("lines", tried);
        out.put("message", message);
        return out;
    }

    /** 从 config_json 里读 mode；读不出来按 api 处理（与 Python 的默认值一致）。 */
    private String modeOf(MaterialSource source) {
        Map<String, Object> config = MaterialJson.parseObject(source.getConfigJson());
        if (config == null) return "api";
        String mode = Texts.str(config, "mode");
        return mode.isEmpty() ? "api" : mode;
    }
}
