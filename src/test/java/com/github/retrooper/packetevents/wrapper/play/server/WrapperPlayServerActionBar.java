package com.github.retrooper.packetevents.wrapper.play.server;

import com.github.retrooper.packetevents.PacketEvents;
import net.kyori.adventure.text.Component;

/**
 * <b>测试桩</b>：签名照 PE 2.14.0 的 {@code WrapperPlayServerActionBar(Component)} 对齐。
 *
 * <p>实现 {@link PacketEvents.WrapperMarker} 是为了让桩保留「{@code sendPacket(Object, Object)}
 * 与 {@code sendPacket(Object, WrapperMarker)} 同时存在」这个形状：脚本传进来的包会命中更具体的
 * 那个重载，和真实运行时的重载选择一致。
 */
public final class WrapperPlayServerActionBar implements PacketEvents.WrapperMarker {

    private final Component text;

    public WrapperPlayServerActionBar(final Component text) {
        this.text = text;
    }

    public Component getActionBarText() {
        return this.text;
    }

    @Override
    public String toString() {
        return "WrapperPlayServerActionBar(" + this.text + ")";
    }
}
