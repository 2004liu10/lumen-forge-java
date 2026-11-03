package com.lumenforge.service.story;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 本地 Codex 桥（本地 GPT）。
 *
 * <p>用户机器上装着 Codex 桌面版（OpenAI 的本地 CLI，登录态就是他自己的 ChatGPT 账号），
 * 它能 {@code codex exec} 非交互跑一次，把回复以 JSONL 事件流吐回来。于是「写剧本」这一步
 * 可以完全在本地完成，不用另配 API Key。定位：<b>只负责写剧本（文字），不碰分镜结构</b>。
 *
 * <p>已知取舍（照抄 Python 版注释）：{@code codex exec} 一次约 10~20 秒，比直接调 API 慢，
 * 但完全白嫖；输出走 JSONL 事件流，只认 {@code item.completed} 里
 * {@code type == 'agent_message'} 的最后一条；用 {@code --ephemeral} 不落会话文件，
 * 用 {@code -s read-only} 禁止它动文件系统，{@code -C} 指到临时目录，避免它在项目目录里
 * 加载 AGENTS.md 之类的规则。
 *
 * <p>与 Python 版的一处差异：Python 用 asyncio 的 {@code creationflags} 加
 * {@code CREATE_NO_WINDOW} 不弹黑窗，JDK 8 的 {@link ProcessBuilder} 没有对应入口，
 * 所以 Windows 上探测/执行时可能闪一下控制台窗口，其余行为一致。
 */
@Component
public class CodexCli {

    /** 一次 exec 的默认上限：写一版剧本实测 10~20 秒，给足 3 分钟。 */
    public static final int DEFAULT_TIMEOUT_MS = 180000;
    /** 事件流里可能混进非 JSON 的调试行，最多留多少字节用来报错。 */
    private static final int RAW_KEEP = 1200;
    /** 探测版本号的上限：8 秒拿不到就 kill。 */
    private static final long PROBE_TIMEOUT_MS = 8000L;

    /** 尾部残留的特殊 token，只在<b>末尾</b>剪，正文里正常出现的尖括号不动。 */
    private static final Pattern TAIL_NOISE_A =
            Pattern.compile("(</?\\|[a-z_]+\\|>)+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL_NOISE_B =
            Pattern.compile("(<\\|[a-z_]+\\|>)+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEWLINE = Pattern.compile("\r?\n");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // ============================================================== 找 exe

    /**
     * Codex 可能的安装位置。
     *
     * <p>桌面版装在 {@code LocalAppData\OpenAI\Codex\bin\<hash>\codex.exe}，
     * npm 装的在 {@code Roaming\npm}（那是个 .cmd/.ps1 包装，起进程麻烦，这里优先认 exe）。
     */
    public List<String> codexExeCandidates() {
        List<String> out = new ArrayList<String>();
        String envExe = env("CODEX_EXE");
        if (!envExe.isEmpty()) {
            out.add(envExe);
        }

        String local = env("LOCALAPPDATA");
        if (!local.isEmpty()) {
            File binDir = new File(local, "OpenAI" + File.separator + "Codex" + File.separator + "bin");
            String[] names = binDir.list();
            if (names != null) {
                List<String> sorted = new ArrayList<String>(Arrays.asList(names));
                Collections.sort(sorted);
                for (String name : sorted) {
                    out.add(new File(new File(binDir, name), "codex.exe").getPath());
                }
            }
        }

        String appData = env("APPDATA");
        if (!appData.isEmpty()) {
            File npm = new File(appData, "npm" + File.separator + "node_modules" + File.separator
                    + "@openai" + File.separator + "codex" + File.separator + "bin" + File.separator + "codex.exe");
            out.add(npm.getPath());
        }
        return out;
    }

    /** 找出 codex.exe；找不到返回空串。 */
    public String findCodexExe() {
        for (String candidate : codexExeCandidates()) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            try {
                if (Files.exists(Paths.get(candidate))) {
                    return candidate;
                }
            } catch (RuntimeException ignored) {
                // 权限异常或路径非法，继续找下一个
            }
        }
        return "";
    }

    // ============================================================== 事件流解析

    /** 把 stdout 的 JSONL 事件流收成「最后一条助手消息」。 */
    public String pickLastMessage(String raw) {
        String last = "";
        String text = raw == null ? "" : raw;
        for (String line : NEWLINE.split(text)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            Object event;
            try {
                event = ParseSupport.parse(line);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!(event instanceof Map)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) event;
            if (!"item.completed".equals(asString(map.get("type")))) {
                continue;
            }
            Object itemRaw = map.get("item");
            if (!(itemRaw instanceof Map)) {
                continue;
            }
            Map<?, ?> item = (Map<?, ?>) itemRaw;
            if (!"agent_message".equals(asString(item.get("type")))) {
                continue;
            }
            String body = asString(item.get("text"));
            if (!body.trim().isEmpty()) {
                last = body;
            }
        }
        return last;
    }

    /** 模型偶尔会在结尾粘一段自己的特殊 token（实测出现过 {@code </|fim_suffix|>}）。 */
    public String stripTailNoise(String value) {
        String text = value == null ? "" : value;
        text = TAIL_NOISE_A.matcher(text).replaceAll("");
        text = TAIL_NOISE_B.matcher(text).replaceAll("");
        return text.trim();
    }

    // ============================================================== 探测

    /**
     * 这台机器上 Codex 能不能用：给界面「检测」按钮和报错文案用。
     *
     * <p>版本能打出来只说明 exe 是好的；能不能真的跑一轮要看登录态，那要花十几秒，
     * 所以这里只报「装了」，真跑失败会在 {@link #ask} 的报错里说清楚。
     */
    public Map<String, Object> probe() {
        String exe = findCodexExe();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (exe.isEmpty()) {
            out.put("ok", false);
            out.put("reason", "no-exe");
            out.put("exe", "");
            out.put("version", "");
            return out;
        }

        String version = "";
        Process process;
        try {
            process = new ProcessBuilder(exe, "--version").start();
        } catch (IOException | RuntimeException err) {
            out.put("ok", false);
            out.put("reason", "no-run");
            out.put("exe", exe);
            out.put("version", "");
            return out;
        }

        Drain drain = new Drain(process);
        drain.start();
        boolean finished;
        try {
            finished = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
            }
        }
        drain.await(3000L);
        if (finished) {
            version = new String(drain.stdout(), StandardCharsets.UTF_8).trim();
        }
        out.put("ok", !version.isEmpty());
        out.put("reason", version.isEmpty() ? "no-run" : "");
        out.put("exe", exe);
        out.put("version", version);
        return out;
    }

    // ============================================================== 跑一轮

    /**
     * 让本地 GPT 写一段东西，返回它的文字回复。
     *
     * <p>{@code prompt} 走 stdin（参数写 {@code -}）：剧本动辄几千字，走命令行参数在 Windows 上
     * 容易被长度限制和引号转义搞坏。
     */
    public String ask(String prompt, int timeoutMs) {
        String text = prompt == null ? "" : prompt.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("提示词不能为空");
        }

        String exe = findCodexExe();
        if (exe.isEmpty()) {
            throw new IllegalArgumentException(
                    "没找到本地 Codex（codex.exe）。装一个 Codex 桌面版并登录，"
                            + "或者用环境变量 CODEX_EXE 指定 codex.exe 的完整路径；"
                            + "不想用 GPT 写剧本就把「剧本模型」的引擎切回云端模型。");
        }

        // 单独给个空目录当工作根：不让它在项目里读到 AGENTS.md / 代码，只当一台「会写字的机器」用。
        String workDir = tempDir() + File.separator + "lumen-forge-codex";
        try {
            Files.createDirectories(Paths.get(workDir));
        } catch (IOException | RuntimeException ignored) {
            // 已存在 / 无权限都无所谓
        }

        List<String> command = new ArrayList<String>();
        command.add(exe);
        command.add("exec");
        command.add("--json");              // 事件流，好稳定地抠最后一条回复
        command.add("--skip-git-repo-check");
        command.add("--ephemeral");         // 不落会话文件，别在用户目录里堆垃圾
        command.add("-s");
        command.add("read-only");           // 只许读，不许它动任何文件
        command.add("--color");
        command.add("never");
        command.add("-C");
        command.add(workDir);
        command.add("-");                   // 提示词从 stdin 读

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(workDir));
        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException err) {
            throw new IllegalArgumentException("本地 Codex 启动失败：" + messageOf(err));
        }

        Drain drain = new Drain(process);
        drain.start();
        writeStdin(process, text);

        long timeoutMsSafe = Math.max(15000L, (long) timeoutMs);
        boolean finished;
        try {
            finished = process.waitFor(timeoutMsSafe, TimeUnit.MILLISECONDS);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
            }
            drain.await(3000L);
            throw new IllegalArgumentException("本地 GPT 一直没有回复（超过 "
                    + Math.round(timeoutMs / 1000.0) + " 秒），换个引擎或稍后再试");
        }
        drain.await(5000L);

        String stdout = new String(drain.stdout(), StandardCharsets.UTF_8);
        String stderr = new String(drain.stderr(), StandardCharsets.UTF_8);
        int code = exitCodeOf(process);

        String answer = stripTailNoise(pickLastMessage(stdout));
        if (!answer.isEmpty()) {
            return answer;
        }

        String detail = WHITESPACE.matcher(stderr.isEmpty() ? stdout : stderr).replaceAll(" ").trim();
        if (detail.length() > RAW_KEEP) {
            detail = detail.substring(0, RAW_KEEP);
        }
        // 未登录 / 额度用完 / 网络不通都会走到这里，把原文带出去让用户能自己判断
        StringBuilder message = new StringBuilder("本地 GPT 没有返回内容");
        if (code != 0) {
            message.append("（codex 退出码 ").append(code).append("）");
        }
        if (!detail.isEmpty()) {
            message.append("：").append(detail);
        } else {
            message.append("。检查 Codex 是不是已登录");
        }
        throw new IllegalArgumentException(message.toString());
    }

    // ============================================================== 工具

    private static void writeStdin(Process process, String text) {
        OutputStream stdin = process.getOutputStream();
        try {
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ignored) {
            // 进程秒退（未登录 / 参数不认），stdin 写不进去；错误会从退出码报出来
        } finally {
            try {
                stdin.close();
            } catch (IOException ignored) {
                // 已经关了
            }
        }
    }

    private static int exitCodeOf(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException err) {
            return 0;
        }
    }

    private static String tempDir() {
        String tmp = System.getProperty("java.io.tmpdir");
        return tmp == null || tmp.trim().isEmpty() ? "." : tmp;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String messageOf(Throwable err) {
        String message = err.getMessage();
        return message == null || message.trim().isEmpty() ? err.getClass().getSimpleName() : message;
    }

    /** 后台把 stdout / stderr 各读进一个缓冲区：不读会把子进程的管道写满，直接卡死。 */
    private static final class Drain {

        private final Process process;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final Thread outThread;
        private final Thread errThread;

        Drain(Process process) {
            this.process = process;
            this.outThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    copy(process.getInputStream(), out);
                }
            }, "codex-stdout");
            this.errThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    copy(process.getErrorStream(), err);
                }
            }, "codex-stderr");
            outThread.setDaemon(true);
            errThread.setDaemon(true);
        }

        void start() {
            outThread.start();
            errThread.start();
        }

        void await(long millis) {
            try {
                outThread.join(millis);
                errThread.join(millis);
            } catch (InterruptedException err) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] stdout() {
            return out.toByteArray();
        }

        byte[] stderr() {
            return err.toByteArray();
        }

        private static void copy(InputStream input, ByteArrayOutputStream sink) {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    sink.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // 进程被 kill 时流会提前断，已经读到的内容照样有用
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // 已关闭
                }
            }
        }
    }

    /** 事件流每行都是一个 JSON 对象；这里只做「解析成 Map / List」的最小封装。 */
    static final class ParseSupport {

        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private ParseSupport() {
        }

        static Object parse(String text) {
            try {
                return MAPPER.readValue(text, Object.class);
            } catch (IOException err) {
                throw new IllegalArgumentException(err.getMessage(), err);
            }
        }
    }
}
