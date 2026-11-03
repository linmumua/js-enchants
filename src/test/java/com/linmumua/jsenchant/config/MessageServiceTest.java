package com.linmumua.jsenchant.config;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家文案必须集中在 {@code messages.yml}：改一句话不该再重新编译插件。
 * 这里同时钉住两条容易被改坏的约定——嵌套键要能按点号取到，
 * 以及缺键时要有可见提示（发空消息等于「命令没反应」，无法排查）。
 */
class MessageServiceTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsTest");

    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private static final String VALID = """
            command:
              reload:
                done: "<green>已重载：脚本 {scripts} 个，附魔 {enchants} 个。"
              list:
                none: "无"
            """;

    private Path write(final Path dir, final String content) throws IOException {
        final Path file = dir.resolve("messages.yml");

        Files.writeString(file, content, StandardCharsets.UTF_8);

        return file;
    }

    @Test
    void 嵌套键按点号压平并可渲染占位符(@TempDir final Path dir) throws IOException {
        final MessageService service = new MessageService(LOGGER, this.write(dir, VALID));

        assertTrue(service.load());
        assertTrue(service.all().containsKey("command.reload.done"));

        final String text = PLAIN_TEXT.serialize(
                service.component("command.reload.done", "scripts", "3", "enchants", "2")
        );

        assertEquals("已重载：脚本 3 个，附魔 2 个。", text);
    }

    @Test
    void 缺失的键会显示可见提示而不是空消息(@TempDir final Path dir) throws IOException {
        final MessageService service = new MessageService(LOGGER, this.write(dir, VALID));

        assertTrue(service.load());

        final String text = PLAIN_TEXT.serialize(service.component("command.not-exists"));

        assertTrue(text.contains("command.not-exists"), "缺键必须能在游戏里看到键名，实际为: " + text);
    }

    @Test
    void 坏文件保留上一版文案(@TempDir final Path dir) throws IOException {
        final Path file = this.write(dir, VALID);
        final MessageService service = new MessageService(LOGGER, file);

        assertTrue(service.load());

        Files.writeString(file, "command: [oops\n", StandardCharsets.UTF_8);

        assertFalse(service.load(), "坏文案文件必须返回失败");

        final String text = PLAIN_TEXT.serialize(service.component("command.list.none"));

        assertEquals("无", text, "已生效的文案不能被清掉");
    }

    @Test
    void 文件缺失时返回失败(@TempDir final Path dir) {
        final MessageService service = new MessageService(LOGGER, dir.resolve("messages.yml"));

        assertFalse(service.load());
        assertTrue(service.all().isEmpty());
    }
}
