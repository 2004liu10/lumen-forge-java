package com.lumenforge.common.util;

import java.security.SecureRandom;

/**
 * 主键生成器。
 *
 * <p>格式与 Python 版 {@code app/ids.py} 完全一致：{@code 前缀_base36(毫秒时间戳)5位随机}。
 * 前端只按前缀判断记录类型，时间戳部分让同一秒内新建的多条记录仍然天然有序。
 */
public final class Ids {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** 生成 {@code prefix_xxxxx} 形态的主键。 */
    public static String nextId(String prefix) {
        return prefix + "_" + base36(System.currentTimeMillis()) + randomSuffix();
    }

    /** 当前毫秒时间戳，与 Python 版 {@code now_ms()} 同单位。 */
    public static long nowMs() {
        return System.currentTimeMillis();
    }

    private static String base36(long value) {
        if (value == 0L) {
            return "0";
        }
        StringBuilder out = new StringBuilder();
        long rest = value;
        while (rest > 0L) {
            out.append(ALPHABET.charAt((int) (rest % 36L)));
            rest /= 36L;
        }
        return out.reverse().toString();
    }

    private static String randomSuffix() {
        StringBuilder out = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(36)));
        }
        return out.toString();
    }
}
