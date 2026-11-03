package com.lumenforge.service.ncanvas;

import com.lumenforge.storage.StoragePaths;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /files/...} 访问地址与真实磁盘路径之间的换算。
 *
 * <p>逐函数复刻 Python 版 {@code app/node_canvas.py} 的 {@code files_prefix_dirs /
 * path_from_files_url / files_url_for / _relative_inside / _quote / is_allowed_register_path}。
 *
 * <p>前缀表的顺序不能乱：{@code generated/refs} 必须排在 {@code generated} 前面，
 * 否则参考图会被算成「图片生成结果」下的文件，登记出来的 URL 就指向别的目录了。
 */
public final class NodeCanvasFiles {

    /** {@code /files/} 之后的 URL 前缀；顺序与原版 {@code FILES_PREFIX_KEYS} 一致。 */
    private static final String[] PREFIXES = {
            "generated/refs",
            "generated",
            "uploads",
            "outputs",
            "videos",
            "video-inputs",
            "materials",
    };

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private NodeCanvasFiles() {
    }

    /** URL 前缀 → 分类目录。 */
    public static Path dirFor(String prefix, StoragePaths paths) {
        if ("generated/refs".equals(prefix)) {
            return paths.refs();
        }
        if ("generated".equals(prefix)) {
            return paths.generated();
        }
        if ("uploads".equals(prefix)) {
            return paths.uploads();
        }
        if ("outputs".equals(prefix)) {
            return paths.outputs();
        }
        if ("videos".equals(prefix)) {
            return paths.videos();
        }
        if ("video-inputs".equals(prefix)) {
            return paths.videoInputs();
        }
        if ("materials".equals(prefix)) {
            return paths.materials();
        }
        return paths.root();
    }

    /** 把 {@code /files/...} 反推成真实绝对路径；认不出前缀时按数据目录兜底。 */
    public static Path pathFromFilesUrl(String url, StoragePaths paths) {
        String raw = url == null ? "" : url;
        if (!raw.startsWith("/files/")) {
            return null;
        }
        String rel = raw.substring("/files/".length())
                .replace("%2F", "/")
                .replace("%2f", "/");
        List<String> parts = split(rel);
        for (String prefix : PREFIXES) {
            List<String> prefixParts = split(prefix);
            if (parts.size() < prefixParts.size()) {
                continue;
            }
            boolean head = true;
            for (int i = 0; i < prefixParts.size(); i++) {
                if (!prefixParts.get(i).equals(parts.get(i))) {
                    head = false;
                    break;
                }
            }
            if (!head) {
                continue;
            }
            Path dir = dirFor(prefix, paths);
            Path resolved = dir;
            for (int i = prefixParts.size(); i < parts.size(); i++) {
                resolved = resolved.resolve(percentDecode(parts.get(i)));
            }
            return resolved.normalize();
        }
        Path root = paths.root();
        for (String part : parts) {
            root = root.resolve(percentDecode(part));
        }
        return root.normalize();
    }

    /** {@code files_url_for} 的反函数：真实路径 → 可访问的 {@code /files/...}；不在已知目录里返回空串。 */
    public static String filesUrlFor(Path absPath, StoragePaths paths) {
        if (absPath == null) {
            return "";
        }
        Path target = absPath.toAbsolutePath().normalize();
        for (String prefix : PREFIXES) {
            Path rel = relativeInside(dirFor(prefix, paths), target);
            if (rel != null) {
                return "/files/" + prefix + "/" + joinEncoded(rel);
            }
        }
        Path rel = relativeInside(paths.root(), target);
        if (rel != null) {
            return "/files/" + joinEncoded(rel);
        }
        return "";
    }

    /** 允许登记的范围：七个媒体目录 + 数据目录本身。 */
    public static boolean isAllowedRegisterPath(Path absPath, StoragePaths paths) {
        if (absPath == null) {
            return false;
        }
        String target = posix(absPath.toAbsolutePath().normalize());
        for (String prefix : PREFIXES) {
            String root = posix(dirFor(prefix, paths).toAbsolutePath().normalize());
            if (target.equals(root) || target.startsWith(root + "/")) {
                return true;
            }
        }
        String root = posix(paths.root().toAbsolutePath().normalize());
        return target.equals(root) || target.startsWith(root + "/");
    }

    /** 按绝对路径去重用的键（统一正斜杠，Windows 上也能跨盘比较）。 */
    public static String pathKey(String localPath) {
        String raw = localPath == null ? "" : localPath.trim();
        if (raw.isEmpty()) {
            return "";
        }
        try {
            return posix(Paths.get(raw).toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            return raw;
        }
    }

    public static String posix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path relativeInside(Path root, Path target) {
        Path absRoot = root.toAbsolutePath().normalize();
        Path absTarget = target.toAbsolutePath().normalize();
        if (!absTarget.startsWith(absRoot)) {
            return null;
        }
        Path rel = absRoot.relativize(absTarget);
        return rel.toString().isEmpty() ? null : rel;
    }

    private static String joinEncoded(Path rel) {
        StringBuilder out = new StringBuilder();
        for (Path part : rel) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(percentEncode(part.toString()));
        }
        return out.toString();
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<String>();
        int from = 0;
        while (true) {
            int at = value.indexOf('/', from);
            if (at < 0) {
                parts.add(value.substring(from));
                return parts;
            }
            parts.add(value.substring(from, at));
            from = at + 1;
        }
    }

    /** RFC 3986 的「不保留字符」以外全部百分号编码（{@code /} 也编码）。 */
    public static String percentEncode(String value) {
        String raw = value == null ? "" : value;
        StringBuilder out = new StringBuilder();
        byte[] bytes = raw.getBytes(UTF8);
        for (byte b : bytes) {
            char ch = (char) (b & 0xFF);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '.' || ch == '-' || ch == '~') {
                out.append(ch);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /**
     * 百分号解码。
     *
     * <p>注意不能用 {@code URLDecoder.decode}：它把 {@code +} 也当空格，
     * 文件名里带加号就会被改掉（Python 的 {@code unquote} 不动 {@code +}）。
     */
    public static String percentDecode(String value) {
        String raw = value == null ? "" : value;
        if (raw.indexOf('%') < 0) {
            return raw;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int i = 0;
        while (i < raw.length()) {
            char ch = raw.charAt(i);
            if (ch == '%' && i + 2 < raw.length()) {
                int hi = hex(raw.charAt(i + 1));
                int lo = hex(raw.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    buffer.write((hi << 4) + lo);
                    i += 3;
                    continue;
                }
            }
            buffer.write(ch);
            i++;
        }
        return new String(buffer.toByteArray(), UTF8);
    }

    private static int hex(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        char lower = Character.toLowerCase(ch);
        if (lower >= 'a' && lower <= 'f') {
            return lower - 'a' + 10;
        }
        return -1;
    }

    /** 扩展名（含点、小写）；没有则空串。Python 里 {@code ".bashrc"} 也算没有后缀。 */
    public static String suffix(String name) {
        String clean = name == null ? "" : name;
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        String base = slash < 0 ? clean : clean.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return base.substring(dot).toLowerCase(Locale.ROOT);
    }
}
