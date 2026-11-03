package com.linmumua.jsenchant.script;

import com.linmumua.jsenchant.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随 jar 分发的示例脚本与默认配置必须自洽，否则故障是「静默」的。
 *
 * <p>这个测试来自一次真实排查：{@code packet_notice} 在服上完全没反应，日志却一行没有。
 * 原因是三件事必须同时成立，而任何一件不成立都只表现为「游戏里看不到」：
 * <ol>
 *   <li>脚本引用的 Java 类被 {@code config.yml} 的 {@code scripts.allowed-packages} 放行
 *       （沙箱是<b>整体替换</b>，改配置很容易漏掉一条前缀）；</li>
 *   <li>{@code plugin.yml} 把 {@code packetevents} 声明成软依赖——Rhino 用本插件的类加载器
 *       解析 Java 类，没有声明就解析不到，{@code PacketEvents.getAPI()} 会变成
 *       「在一个空的包对象上找方法」；</li>
 *   <li>脚本自己不把异常吞掉。</li>
 * </ol>
 *
 * <p>前两件是资源文件里的约定，编译器管不到，所以在这里钉住：脚本引用了哪些类，
 * 就从脚本里现读，不让测试跟着配置一起漂。
 */
class PacketNoticeContractTest {

    private static final Logger LOGGER = Logger.getLogger("JsEnchantsContractTest");

    private static final String SCRIPT = "scripts/packet_notice.js";

    /** 至少两段小写包名 + 一个大写开头的类名；够用来认全限定类名，又不会匹配到方法调用。 */
    private static final Pattern CLASS_NAME = Pattern.compile("(?:[a-z][A-Za-z0-9_]*\\.){2,}[A-Z][A-Za-z0-9_]*");

    @Test
    void 示例脚本引用的每个类都必须被默认沙箱放行(@TempDir final Path dir) throws IOException {
        final ConfigService config = new ConfigService(LOGGER, copy("config.yml", dir));

        assertTrue(config.load(), "随包分发的 config.yml 必须能被正式解析器读出");

        final ScriptSandbox sandbox = new ScriptSandbox(config.current().allowedPackages());
        final Set<String> referenced = classNames(text(SCRIPT));

        assertFalse(referenced.isEmpty(), "没在 " + SCRIPT + " 里认出任何 Java 类名，正则已经跟不上脚本写法");

        for (final String className : referenced) {
            assertTrue(
                    sandbox.visibleToScripts(className),
                    "scripts.allowed-packages 没有放行 " + className
                            + "：脚本会当场抛异常，而该异常若被脚本吞掉就只剩「附魔触发了但没有任何效果」"
            );
        }
    }

    @Test
    void pluginyml必须把packetevents声明为软依赖() throws IOException {
        final Map<String, Object> root = yaml(text("plugin.yml"));
        final Object softdepend = root.get("softdepend");

        assertNotNull(softdepend, "plugin.yml 必须声明 softdepend，否则 PacketEvents 的类不会进入本插件的类加载器");

        assertTrue(
                strings(softdepend).contains("packetevents"),
                "softdepend 必须包含 packetevents（实际: " + softdepend + "）；"
                        + "缺了它脚本里 new WrapperPlayServerActionBar(...) 会解析成空包对象，"
                        + "表现就是「在游戏里看不到，服务端也没有任何报错」"
        );

        // 只允许软依赖：没装 PacketEvents 时附魔仍要能触发，由脚本把原因写进日志。
        final Object depend = root.get("depend");

        assertFalse(
                null != depend && strings(depend).contains("packetevents"),
                "PacketEvents 只是可选的展示依赖，写成硬依赖 depend 会让整个插件在它缺失时拒绝启用"
        );
    }

    /** 从脚本源码里读出所有全限定类名。 */
    private static Set<String> classNames(final String source) {
        final Set<String> found = new LinkedHashSet<>();
        final Matcher matcher = CLASS_NAME.matcher(source);

        while (matcher.find()) {
            found.add(matcher.group());
        }

        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(final String source) {
        return (Map<String, Object>) new Yaml().load(source);
    }

    /** {@code softdepend} 既可以写成列表，也能写成 {@code [a, b]}，两种都接受。 */
    private static List<String> strings(final Object node) {
        if (node instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }

        return List.of(String.valueOf(node));
    }

    private static String text(final String resource) throws IOException {
        try (final InputStream stream = stream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path copy(final String resource, final Path dir) throws IOException {
        final Path target = dir.resolve(resource);

        Files.createDirectories(target.getParent());

        try (final InputStream stream = stream(resource)) {
            Files.copy(stream, target);
        }

        return target;
    }

    private static InputStream stream(final String resource) throws IOException {
        final InputStream stream = PacketNoticeContractTest.class.getClassLoader().getResourceAsStream(resource);

        if (null == stream) {
            throw new IOException("测试类路径上找不到资源: " + resource);
        }

        return stream;
    }
}
