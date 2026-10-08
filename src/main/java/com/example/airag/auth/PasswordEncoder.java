package com.example.airag.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.stereotype.Component;

/**
 * 密码哈希工具:PBKDF2WithHmacSHA256(JDK 自带,无需额外依赖)。
 *
 * <p>存储格式:{@code pbkdf2$<迭代次数>$<saltBase64>$<hashBase64>}</p>
 *
 * <p>也可以直接用这个类生成哈希,手工插入用户:</p>
 * <pre>java -cp target/classes com.example.airag.auth.PasswordEncoder 你的密码</pre>
 */
@Component
public class PasswordEncoder {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int PART_COUNT = 4;

    private final SecureRandom secureRandom = new SecureRandom();

    /** 生成带随机盐的密码哈希。 */
    public String encode(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt, ITERATIONS);
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    /** 校验密码,使用常量时间比较。 */
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$");
        if (parts.length != PART_COUNT || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return false;
        }
        if (iterations <= 0) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        byte[] actual = pbkdf2(rawPassword, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 判断一个字符串是不是本类生成的哈希格式({@code pbkdf2$<迭代>$<盐>$<哈希>})。
     *
     * <p><b>用途是排障,不是校验。</b>直接往数据库里插账号时,很容易把明文、MD5 或者
     * 别处复制来的哈希填进 {@code password_hash},结果 {@link #matches} 永远返回 false,
     * 登录只报一句"账号或密码错误" —— 现场完全看不出原因。有了这个方法,
     * 登录失败时可以顺手判断一下"是不是存储格式压根就不对",并在服务端日志里说清楚。</p>
     */
    public static boolean isEncoded(String encodedPassword) {
        if (encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$");
        if (parts.length != PART_COUNT || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            if (Integer.parseInt(parts[1]) <= 0) {
                return false;
            }
            Base64.getDecoder().decode(parts[2]);
            Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return true;
    }

    private byte[] pbkdf2(String rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("密码哈希计算失败", ex);
        } finally {
            spec.clearPassword();
        }
    }

    /** 格式正确的哈希长度,只用于日志里的对照说明。 */
    private static final int ENCODED_LENGTH = 83;

    /**
     * 给日志用的一句话描述:<b>只报长度,绝不回显内容</b>。
     *
     * <p>这里之所以要刻意克制,是因为 {@code password_hash} 格式不对时,里面的值往往就是
     * <b>明文密码</b> —— 把它打进日志文件等于把密码泄露到另一个地方。而长度已经足够判断了:
     * 格式正确的哈希是 83 字符左右,对不上就说明不是本系统生成的。</p>
     */
    public static String describeUnusable(String encodedPassword) {
        int length = encodedPassword == null ? 0 : encodedPassword.length();
        return "当前值 " + length + " 字符(格式正确的哈希约 " + ENCODED_LENGTH + " 字符)";
    }

    /**
     * 命令行生成哈希,便于手工写 SQL 插入账号。
     *
     * <pre>
     * java -cp target/classes com.example.airag.auth.PasswordEncoder 明文密码
     * java -cp target/classes com.example.airag.auth.PasswordEncoder 明文密码 zhangsan
     * </pre>
     *
     * <p>带账号的那个形式会连一条可直接粘贴执行的 {@code UPDATE} 语句一起打出来 ——
     * 给库里已有的账号重设密码时省得自己拼字符串。</p>
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("用法:");
            System.out.println("  java com.example.airag.auth.PasswordEncoder <明文密码>");
            System.out.println("      -> 只输出哈希");
            System.out.println("  java com.example.airag.auth.PasswordEncoder <明文密码> <账号>");
            System.out.println("      -> 输出哈希,并给出可直接执行的 UPDATE 语句");
            return;
        }
        String hash = new PasswordEncoder().encode(args[0]);
        System.out.println("哈希:");
        System.out.println(hash);
        if (args.length >= 2) {
            String username = args[1].replace("'", "''");   // 防手滑打断引号
            System.out.println();
            System.out.println("把下面这条语句执行一下,就能用新密码登录 [" + args[1] + "] 了:");
            System.out.println("UPDATE sys_user SET password_hash = '" + hash
                    + "' WHERE username = '" + username + "';");
            System.out.println();
            System.out.println("顺便确认一下这个账号没有被禁用且角色正确:");
            System.out.println("SELECT id, username, role, status FROM sys_user WHERE username = '"
                    + username + "';");
        }
    }
}
