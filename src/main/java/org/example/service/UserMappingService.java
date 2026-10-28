package org.example.service;

import org.example.config.WeaverSsoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 泛微 CAS 账号 与 FastGPT 用户的映射表
 *
 * 映射文件为 UTF-8 CSV，首行为表头，# 开头为注释：
 *   cas_account,fastgpt_username,member_name,contact,status
 *   xitongceshi.cd,zhangsan,张三,zhangsan@example.com,1
 * - cas_account：CAS 校验返回的账号（必填，匹配时忽略大小写）
 * - fastgpt_username：回传 FastGPT 的用户名（必填）
 * - member_name / contact：可选，覆盖 CAS / HR 同步的姓名和联系方式
 * - status：可选，0 表示停用，其余（含空）表示启用
 *
 * 文件修改后无需重启：每次查询时按修改时间自动重新加载。
 */
@Service
public class UserMappingService {

    private static final Logger log = LoggerFactory.getLogger(UserMappingService.class);

    @Autowired
    private WeaverSsoConfig config;

    private volatile Map<String, UserMapping> mappings = Collections.emptyMap();
    private volatile long loadedModified = -1;

    /**
     * 按 CAS 账号查映射，未配置映射时返回 null
     */
    public UserMapping findByCasAccount(String casAccount) {
        if (casAccount == null) {
            return null;
        }
        reloadIfChanged();
        return mappings.get(casAccount.trim().toLowerCase());
    }

    /**
     * 确保映射存在：已有记录直接返回（含 status=0）；否则追加 CSV 并返回新建记录。
     * 与 reload 共用同一把锁，避免并发双写。
     */
    public synchronized UserMapping ensureAndAppend(String casAccount, String username,
                                                     String memberName, String contact) {
        if (casAccount == null || casAccount.trim().isEmpty()) {
            throw new IllegalArgumentException("casAccount 不能为空");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        reloadIfChanged();
        String key = casAccount.trim().toLowerCase();
        UserMapping existing = mappings.get(key);
        if (existing != null) {
            return existing;
        }

        String file = config.getUserMappingFile();
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("未配置用户映射文件 weaver.sso.user-mapping-file，无法自动建号");
        }
        Path path = Paths.get(file);
        String safeCas = sanitizeCsvField(casAccount.trim());
        String safeUser = sanitizeCsvField(username.trim());
        String safeName = sanitizeCsvField(memberName);
        String safeContact = sanitizeCsvField(contact);
        String line = safeCas + "," + safeUser + "," + nullToEmpty(safeName) + ","
                + nullToEmpty(safeContact) + ",1\n";

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path) || Files.size(path) == 0) {
                Files.write(path,
                        ("cas_account,fastgpt_username,member_name,contact,status\n" + line)
                                .getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                // 确保末尾有换行再追加，避免粘在上一行
                byte[] existingBytes = Files.readAllBytes(path);
                boolean needsNewline = existingBytes.length > 0
                        && existingBytes[existingBytes.length - 1] != '\n';
                String toWrite = (needsNewline ? "\n" : "") + line;
                Files.write(path, toWrite.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            loadedModified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.error("自动建号写入映射文件失败: {}", path.toAbsolutePath(), e);
            throw new IllegalStateException("自动建号写入映射文件失败: " + path.toAbsolutePath()
                    + "（请确认目录可写，Docker 不要使用 :ro 挂载）", e);
        }

        UserMapping created = new UserMapping(safeCas, safeUser, safeName, safeContact, true);
        Map<String, UserMapping> next = new HashMap<>(mappings);
        next.put(key, created);
        mappings = next;
        log.info("自动建号并写入映射表: casAccount={}, username={}, file={}",
                safeCas, safeUser, path.toAbsolutePath());
        return created;
    }

    private static String sanitizeCsvField(String value) {
        if (value == null) {
            return null;
        }
        // 简单 CSV：去掉英文逗号和换行，避免破坏列
        return value.replace(",", " ").replace("\r", " ").replace("\n", " ").trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private synchronized void reloadIfChanged() {
        String file = config.getUserMappingFile();
        if (file == null || file.isEmpty()) {
            log.warn("未配置用户映射文件 weaver.sso.user-mapping-file，所有 CAS 账号都会被视为未映射");
            mappings = Collections.emptyMap();
            return;
        }
        Path path = Paths.get(file);
        long modified;
        try {
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.error("用户映射文件不可读: {}", path.toAbsolutePath(), e);
            mappings = Collections.emptyMap();
            loadedModified = -1;
            return;
        }
        if (modified == loadedModified) {
            return;
        }
        try {
            mappings = parse(Files.readAllLines(path, StandardCharsets.UTF_8));
            loadedModified = modified;
            log.info("已加载用户映射文件: {}, 共 {} 条", path.toAbsolutePath(), mappings.size());
        } catch (IOException e) {
            log.error("读取用户映射文件失败: {}", path.toAbsolutePath(), e);
        }
    }

    private Map<String, UserMapping> parse(List<String> lines) {
        Map<String, UserMapping> result = new HashMap<>();
        boolean header = true;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).replace("﻿", "").trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (header) {
                // 首个非注释行为表头
                header = false;
                continue;
            }
            String[] cols = line.split(",", -1);
            String casAccount = col(cols, 0);
            String username = col(cols, 1);
            if (casAccount == null || username == null) {
                log.warn("用户映射文件第 {} 行缺少 cas_account 或 fastgpt_username，已忽略", i + 1);
                continue;
            }
            String key = casAccount.toLowerCase();
            if (result.containsKey(key)) {
                log.warn("用户映射文件第 {} 行 CAS 账号 [{}] 重复，以后出现的为准", i + 1, casAccount);
            }
            result.put(key, new UserMapping(casAccount, username, col(cols, 2), col(cols, 3),
                    !"0".equals(col(cols, 4))));
        }
        return result;
    }

    private static String col(String[] cols, int index) {
        if (index >= cols.length) {
            return null;
        }
        String value = cols[index].trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 一条映射记录
     */
    public static class UserMapping {
        private final String casAccount;
        private final String username;
        private final String memberName;
        private final String contact;
        private final boolean enabled;

        public UserMapping(String casAccount, String username, String memberName, String contact, boolean enabled) {
            this.casAccount = casAccount;
            this.username = username;
            this.memberName = memberName;
            this.contact = contact;
            this.enabled = enabled;
        }

        public String getCasAccount() { return casAccount; }
        public String getUsername() { return username; }
        public String getMemberName() { return memberName; }
        public String getContact() { return contact; }
        public boolean isEnabled() { return enabled; }
    }
}
