package com.github.retrooper.packetevents;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>测试桩</b>：只复刻 PacketEvents 2.14.0 里 {@code scripts/packet_notice.js} 用到的那几个
 * 成员（{@code getAPI()} / {@code isInitialized()} / {@code isLoaded()} / {@code getVersion()} /
 * {@code getPlayerManager().sendPacket(...)}）。
 *
 * <p>为什么需要它：脚本直接用全限定名访问 PE，若测试里没有这些类，Rhino 只会把它解析成
 * 一个空的包对象，于是「脚本写对了但类不可见」和「脚本写错了」在测试里无法区分。有桩之后
 * 才能分别驱动「API 可用」「未初始化」「写包抛异常」三条真实分支。
 *
 * <p>只存在于测试源集，不会进 jar；签名照 javap 出来的真实签名对齐，包括
 * {@code sendPacket(Object, Object)} 与更具体的 {@code sendPacket(Object, WrapperPlayServerActionBar)}
 * 这对重载——Rhino 会挑更具体的那个，这层行为必须在测试里保持可复现。
 */
public final class PacketEvents {

    /** 桩要模拟的三条分支。 */
    public enum State {

        /** 一切正常：可以发包。 */
        READY,

        /** 尚未初始化：脚本应报「尚未初始化」并跳过。 */
        NOT_INITIALIZED,

        /** 初始化完成，但写包时抛异常：脚本应报「发送 ActionBar 失败」。 */
        SEND_FAILS
    }

    private static final PlayerManager PLAYER_MANAGER = new PlayerManager();

    private static volatile State state = State.READY;

    private PacketEvents() {
    }

    public static void state(final State value) {
        state = value;
    }

    public static PlayerManager playerManager() {
        return PLAYER_MANAGER;
    }

    /** 每个测试自己收拾现场，避免分支之间互相污染。 */
    public static void reset() {
        state = State.READY;
        PLAYER_MANAGER.clear();
    }

    public static Api getAPI() {
        return new Api(state);
    }

    /** {@code PacketEventsAPI} 的最小投影。 */
    public static final class Api {

        private final State state;

        private Api(final State state) {
            this.state = state;
        }

        public boolean isInitialized() {
            return State.NOT_INITIALIZED != this.state;
        }

        public boolean isLoaded() {
            return true;
        }

        public String getVersion() {
            return "stub-2.14.0";
        }

        public PlayerManager getPlayerManager() {
            return PLAYER_MANAGER;
        }
    }

    /** 记录「包交给了谁」，供断言单播目标。 */
    public static final class PlayerManager {

        private final List<String> targets = Collections.synchronizedList(new ArrayList<>());

        private final List<Object> packets = Collections.synchronizedList(new ArrayList<>());

        public void sendPacket(final Object player, final Object packet) {
            this.record(player, packet);
        }

        public void sendPacket(final Object player, final WrapperMarker marker) {
            this.record(player, marker);
        }

        private void record(final Object player, final Object packet) {
            if (State.SEND_FAILS == state) {
                throw new IllegalStateException("模拟网络层写入失败");
            }

            this.targets.add(String.valueOf(player));
            this.packets.add(packet);
        }

        public String lastTarget() {
            return this.targets.isEmpty() ? null : this.targets.get(this.targets.size() - 1);
        }

        public Object lastPacket() {
            return this.packets.isEmpty() ? null : this.packets.get(this.packets.size() - 1);
        }

        public int sent() {
            return this.packets.size();
        }

        public void clear() {
            this.targets.clear();
            this.packets.clear();
        }
    }

    /** 复现真实包里「包对象是一个更具体的接口实现」的形状，逼出重载选择。 */
    public interface WrapperMarker {
    }
}
