package com.linmumua.jsenchant.script;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时脚本表。所有替换都是原子的，正在执行的旧脚本会自然跑完，
 * 因此触发器调用侧无需加锁。
 */
public final class ScriptRegistry {

    private final Map<String, CompiledScript> scripts = new ConcurrentHashMap<>();

    public CompiledScript get(String id) {
        return scripts.get(id);
    }

    /**
     * 原子替换。仅在编译成功后调用，编译失败时调用方必须保留旧版本。
     */
    public void put(CompiledScript script) {
        scripts.put(script.id(), script);
    }

    public CompiledScript remove(String id) {
        return scripts.remove(id);
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(scripts.keySet());
    }

    public int size() {
        return scripts.size();
    }
}
