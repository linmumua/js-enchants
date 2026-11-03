package com.linmumua.jsenchant.platform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * 「当前线程是否拥有该区域」的判定入口。
 *
 * <p>抽成接口只有两个用途：生产实现是官方判定（{@link #BUKKIT}），测试可以注入假判定
 * 而不必在无服务端环境里触碰 {@code Bukkit} 的静态入口。判定本身没有兜底逻辑——
 * 归属这件事宁可报错，也不能猜。
 */
public interface RegionOwnership {

    /** 官方判定：Folia 上是区域归属，Paper 上退化为「是否在主线程」。 */
    RegionOwnership BUKKIT = new RegionOwnership() {

        @Override
        public boolean owns(final Entity entity) {
            return Bukkit.isOwnedByCurrentRegion(entity);
        }

        @Override
        public boolean owns(final Location location, final int squareRadiusChunks) {
            return Bukkit.isOwnedByCurrentRegion(location, squareRadiusChunks);
        }
    };

    boolean owns(Entity entity);

    boolean owns(Location location, int squareRadiusChunks);
}
