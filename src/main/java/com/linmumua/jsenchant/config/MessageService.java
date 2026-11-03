package com.linmumua.jsenchant.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code messages.yml} 的读取层：所有玩家可见文案的唯一来源。
 *
 * <p>嵌套节点按点号压平成 {@code command.reload.done} 这样的键，服主改文案时不需要
 * 记 Java 侧的任何常量。值一律按 MiniMessage 渲染——与 Lore 展示使用同一套语法，
 * 避免「Lore 能用 &lt;gold&gt;、聊天提示却要写 §6」这种割裂。
 *
 * <p>与 {@link ConfigService} 相同的失败原则：文件坏掉时保留上一版文案并告警。
 * 唯一不同的是缺少某个键时的行为——它会渲染成一条明显的「缺少消息键」提示并记日志，
 * 而不是发一条空消息，否则「命令没反应」将无从排查。
 */
public final class MessageService {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final int CODE_POINT_LIMIT = 1 << 18;

    private final Logger logger;
    private final Path file;

    private volatile Map<String, String> messages = Map.of();

    public MessageService(final Logger logger, final Path file) {
        this.logger = logger;
        this.file = file;
    }

    /** 当前生效文案表，键为压平后的点号路径。只读。 */
    public Map<String, String> all() {
        return this.messages;
    }

    /**
     * 重新读取文案文件。
     *
     * @return 成功替换文案表时返回 {@code true}；文件缺失、为空或内容非法时返回
     *         {@code false}，并保留上一版文案
     */
    public boolean load() {
        if (!Files.isRegularFile(this.file)) {
            this.logger.warning("文案文件不存在，已保留上一版文案: " + this.file);

            return false;
        }

        final Map<String, String> parsed = new LinkedHashMap<>();

        try (final Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            final Object root = newYaml().load(reader);

            if (null == root) {
                this.logger.warning("文案文件为空，已保留上一版文案: " + this.file.getFileName());

                return false;
            }

            if (!(root instanceof Map<?, ?> map)) {
                this.logger.warning("文案文件根节点不是映射，已保留上一版文案: " + this.file.getFileName());

                return false;
            }

            flatten("", map, parsed);
        } catch (final IOException exception) {
            this.logger.log(Level.SEVERE, "读取文案文件失败，已保留上一版文案: " + this.file.getFileName(), exception);

            return false;
        } catch (final RuntimeException exception) {
            this.logger.log(
                    Level.SEVERE,
                    "文案文件无效，已保留上一版文案: " + this.file.getFileName() + " (" + exception.getMessage() + ")",
                    exception
            );

            return false;
        }

        this.messages = Collections.unmodifiableMap(parsed);

        return true;
    }

    /**
     * 取一条文案的原始模板并替换占位符，不做 MiniMessage 渲染。
     *
     * <p>用于把一条文案嵌进另一条文案（例如「无」这种占位值），
     * 渲染交给最外层那一次 {@link #component(String, String...)}。
     *
     * @param key          压平后的点号路径，例如 {@code command.reload.done}
     * @param placeholders 交替出现的占位符名与值，例如 {@code "scripts", "3"}；
     *                     模板中的 {@code {scripts}} 会被替换
     */
    public String raw(final String key, final String... placeholders) {
        if (0 != (placeholders.length % 2)) {
            throw new IllegalArgumentException("占位符必须成对出现: " + key);
        }

        final String template = this.messages.get(key);

        if (null == template) {
            this.logger.warning("缺少消息键: " + key + " (" + this.file.getFileName() + ")");

            return "缺少消息键: " + key;
        }

        String text = template;

        for (int index = 0; index < placeholders.length; index += 2) {
            text = text.replace("{" + placeholders[index] + "}", placeholders[index + 1]);
        }

        return text;
    }

    /** 取一条文案并渲染成组件，参数同 {@link #raw(String, String...)}。 */
    public Component component(final String key, final String... placeholders) {
        final String text = this.raw(key, placeholders);

        try {
            return MINI_MESSAGE.deserialize(text);
        } catch (final RuntimeException exception) {
            // MiniMessage 标签写坏时不能吞掉整条提示，退回纯文本让玩家仍能看到内容。
            this.logger.log(Level.WARNING, "消息键 " + key + " 的 MiniMessage 格式无效，已按纯文本发送", exception);

            return Component.text(text);
        }
    }

    private static void flatten(final String prefix, final Map<?, ?> source, final Map<String, String> target) {
        for (final Map.Entry<?, ?> entry : source.entrySet()) {
            final String key = prefix + entry.getKey();
            final Object value = entry.getValue();

            if (value instanceof Map<?, ?> nested) {
                flatten(key + ".", nested, target);

                continue;
            }

            if (value instanceof String text) {
                target.put(key, text);

                continue;
            }

            // null 或列表/数字等结构在此处没有意义，宁可留痕也不要静默丢弃。
            if (null != value) {
                target.put(key, String.valueOf(value));
            }
        }
    }

    private static Yaml newYaml() {
        final LoaderOptions options = new LoaderOptions();

        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(CODE_POINT_LIMIT);
        options.setProcessComments(false);

        return new Yaml(
                new SafeConstructor(options),
                new Representer(new DumperOptions()),
                new DumperOptions(),
                options
        );
    }
}
