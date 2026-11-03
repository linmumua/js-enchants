package com.linmumua.jsenchant.script;

/**
 * 一份脚本的<b>不可变</b>内容：源码 + 版本标识，不携带任何运行态。
 *
 * <p>刻意不再持有 Rhino 的 {@code Script}/{@code Scriptable}/函数对象：Folia 下同一份脚本
 * 会被多个 region 线程同时执行，而这些对象都是可变的、只能归一个线程所有。运行态改由
 * {@link ScriptRuntimeCache} 按执行线程各建一份，入口是
 * {@link ScriptEnginePool#invoke(CompiledScript, String, Object...)}。
 *
 * <p>因为本类不可变，{@link ScriptRegistry} 换版本时可以直接整体替换，正在执行的旧引用
 * 仍握着旧实例跑完，调用侧不需要加锁。
 *
 * @param id           附魔 id（同一目录下不允许重名）
 * @param source       源文件内容；重载时用它判断内容是否真的变了——变了的才需要重建运行态
 * @param version      版本标识：只在源码变化时递增，内容没变的重载沿用旧版本号
 * @param lastModified 源文件最后修改时间，仅用于日志与排查
 */
public record CompiledScript(String id, String source, long version, long lastModified) {
}
