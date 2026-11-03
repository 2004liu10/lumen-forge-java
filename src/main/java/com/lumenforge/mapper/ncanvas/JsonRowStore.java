package com.lumenforge.mapper.ncanvas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.entity.ncanvas.NodeCanvasStorageException;
import com.lumenforge.storage.StoragePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一张「快照表」的落盘实现：整份记录序列化成 {@code data/<name>.json}。
 *
 * <p>为什么不是 JPA：V1 迁移脚本（{@code V1__initial_schema.sql}）里没有
 * {@code node_canvases} / {@code node_canvas_chats} / {@code node_assets} 三张表，
 * 而 V1 的 checksum 已上线不可改，{@code ddl-auto=validate} 又不允许凭空多出实体。
 * Python 版这个模块的数据本来就是「一个 id + 一整份 payload JSON」（{@code app/db.py} 里
 * {@code node_canvases} / {@code node_assets} 都是 {@code id, payload, created_at, updated_at} 四列），
 * 所以这里用同样的语义：一份 JSON 数组存全部行，读写都在 {@code app.storage.root} 之下。
 *
 * <p>并发：所有读改写都走 {@link #mutate}，方法整体 {@code synchronized}，
 * 避免两个浏览器标签页同时自动保存把对方的节点冲掉。
 */
public abstract class JsonRowStore {

    private static final TypeReference<List<Map<String, Object>>> ROW_LIST =
            new TypeReference<List<Map<String, Object>>>() {
            };

    private final StoragePaths paths;
    private final ObjectMapper mapper;
    private final String fileName;

    protected JsonRowStore(StoragePaths paths, ObjectMapper mapper, String fileName) {
        this.paths = paths;
        this.mapper = mapper;
        this.fileName = fileName;
    }

    /** 落盘文件的位置（数据根目录下）。 */
    public Path file() {
        return paths.root().resolve(fileName);
    }

    /** 只读快照：每次调用都从磁盘重新解析，调用方随便改都不会污染别人。 */
    public synchronized List<Map<String, Object>> snapshot() {
        return read();
    }

    /**
     * 读 → 改 → 需要时写回。
     *
     * <p>回调里拿到的 {@code rows} 是本次独享的一份（刚解析出来的），可以直接增删改；
     * 只有 {@link Dirty#mark()} 过才会落盘，纯读请求不会把文件重写一遍。
     */
    public synchronized <T> T mutate(RowMutation<T> mutation) {
        List<Map<String, Object>> rows = read();
        Dirty dirty = new Dirty();
        T value = mutation.apply(rows, dirty);
        if (dirty.isMarked()) {
            write(rows);
        }
        return value;
    }

    private List<Map<String, Object>> read() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return new ArrayList<Map<String, Object>>();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new ArrayList<Map<String, Object>>();
            }
            List<Map<String, Object>> rows = mapper.readValue(bytes, ROW_LIST);
            return rows == null ? new ArrayList<Map<String, Object>>() : rows;
        } catch (IOException err) {
            throw new NodeCanvasStorageException("读取节点画布数据失败：" + err.getMessage(), err);
        }
    }

    private void write(List<Map<String, Object>> rows) {
        Path file = file();
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = mapper.writeValueAsBytes(rows);
            Files.write(temp, bytes);
            // 先写临时文件再原子替换：写一半崩掉也不会留下半份 JSON 把下次启动搞挂
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException err) {
            throw new NodeCanvasStorageException("写入节点画布数据失败：" + err.getMessage(), err);
        }
    }

    /** 一次读改写的结果：要不要落盘由回调自己说。 */
    public interface RowMutation<T> {
        T apply(List<Map<String, Object>> rows, Dirty dirty);
    }

    /** 回调里用来标记「这次真的改了」。 */
    public static final class Dirty {
        private boolean marked;

        public void mark() {
            this.marked = true;
        }

        public boolean isMarked() {
            return marked;
        }
    }
}
