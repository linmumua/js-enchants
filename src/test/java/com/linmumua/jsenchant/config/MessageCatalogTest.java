package com.linmumua.jsenchant.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文案键的一致性检查：把 {@code JsEnchantCommand} 里出现的 {@code command.*} 键
 * 与随插件分发的 {@code messages.yml} 对照。
 *
 * <p>为什么值得单独立一个测试：缺键在运行期只会渲染成「缺少消息键: xxx」，
 * 而这条提示恰恰出现在「服务器已经发布出去、玩家正在用」的时候。测试里直接扫源码，
 * 就不会出现「新增一条提示但漏改 yml」这种只在游戏里才暴露的缺口。
 */
class MessageCatalogTest {

    private static final Path COMMAND_SOURCE = Path.of(
            "src/main/java/com/linmumua/jsenchant/command/JsEnchantCommand.java"
    );

    private static final Path MESSAGES = Path.of("src/main/resources/messages.yml");

    /** 只匹配 {@code "command.xxx"} 这种文案键字面量。 */
    private static final Pattern MESSAGE_KEY = Pattern.compile("\"(command\\.[a-z0-9.\\-]+)\"");

    @Test
    void 命令引用的每个文案键都在默认文案文件里() throws IOException {
        final Set<String> used = collectKeys(Files.readString(COMMAND_SOURCE, StandardCharsets.UTF_8));

        assertFalse(used.isEmpty(), "没扫描到任何文案键，说明扫描规则和源码已经脱节");

        final MessageService service = new MessageService(Logger.getLogger("JsEnchantsTest"), MESSAGES);

        assertTrue(service.load(), "随插件分发的 messages.yml 必须能解析");

        for (final String key : used) {
            assertTrue(service.all().containsKey(key), "messages.yml 缺少命令用到的文案键: " + key);
        }
    }

    private static Set<String> collectKeys(final String source) {
        final Set<String> keys = new LinkedHashSet<>();
        final Matcher matcher = MESSAGE_KEY.matcher(source);

        while (matcher.find()) {
            keys.add(matcher.group(1));
        }

        return keys;
    }
}
