# JsEnchants

脚本驱动的自定义附魔框架，支持 Folia。

用 YAML 定义附魔、用 JavaScript 写效果，改完 `/jse reload` 即生效——不用重新编译，也不用重启服务器。

## 特性

- **附魔即配置**：一个附魔 = 一份 `enchants/*.yml` + 一份 `scripts/*.js`。等级、概率、冷却、生效槽位都由 YAML 声明，效果逻辑交给脚本。
- **热重载**：附魔定义与脚本都挂在文件监听上，改动落盘即重载；运行中的实例不受影响。
- **Folia 就绪**：所有实体、方块、世界状态读写都经 `RegionTasks` 按区域路由。跨区触发不会静默失效——要么安全重放到目标区域线程，要么明确报错。
- **脚本沙箱**：Rhino 引擎 + `ClassShutter` 白名单，只放行声明过的包前缀；脚本作用域按线程独立，同一脚本可在多个区域线程并发执行。
- **参数外置**：示例附魔的数值全部在 `options` 里，凑平衡不用碰代码。

## 环境

- Paper / Folia 26.3（`compileOnly paper-api:26.3`）
- Java 25
- 平台装载由 TabooLib 负责（构建期内嵌裁剪运行时，无需额外安装前置插件）

## 构建

```bash
gradle build
```

产物：`build/libs/js-enchants-<version>.jar`（TabooLib 注入并重定位 Rhino 后的最终 jar）。

## 目录

```
src/main/resources/
  enchants/     附魔定义（等级、概率、冷却、物品、参数）
  scripts/      效果脚本（onTrigger / onHover）
  config.yml    全局配置（脚本沙箱白名单、事件黑名单、战利品书等）
```

## 附魔示例

| id | 名称 | 触发 | 说明 |
|---|---|---|---|
| `vein_miner` | 连锁采集 | MINE | 潜行触发，按连接关系连锁开采；额外扣耐久与饱食度 |
| `auto_smelt` | 自动熔炼 | MINE | 挖到的方块直接产出冶炼成品，映射表可配置 |
| `fireball` | Fireball | ATTACK | 概率在目标处受控爆炸 |
| `execute` | 斩杀 | ATTACK | 概率处决低血量非玩家生物 |
| `lifesteal` | 吸血 | ATTACK | 按本次伤害比例回血 |
| `lost_blade` | 迷失之刃 | ATTACK | 附加缓慢，高等级追加反胃 |
| `frostbite` | 冻结 | ATTACK | 细雪式寒冷 + 挖掘疲劳 |
| `disarm` | 缴械 | ATTACK | 打掉目标主手物品（掉落或交换） |
| `multishot` | 分裂箭 | SHOOT | 出膛瞬间复制偏转箭矢 |
| `packet_notice` | 以太回声 | 自定义事件 | 演示 PacketEvents 单播 ActionBar |

## 脚本 API 速览

```js
// 上下文
ctx.player() / ctx.victim() / ctx.block() / ctx.projectile() / ctx.level()
ctx.optionNumber(key, fallback) / ctx.optionFlag(...) / ctx.optionText(...) / ctx.byLevel(key, fallback)

// 延迟与周期（回调写成顶层具名函数；到期时执行热重载后的最新版本）
// 每名玩家每个附魔同时最多 16 条回调链，超出时返回 false
ctx.delay(40, "explode")
ctx.repeat(20, 5, "tick")      // times<=0 表示一直重复，直到玩家下线

// 效果
ctx.potion(entity, "SLOWNESS", 60, 1) / ctx.ignite(entity, 60) / ctx.knockback(entity, 1.2)
ctx.nearby(4) / ctx.blocksAround(2)

// 持久状态（落在玩家 PDC，按附魔 id 隔离）
ctx.state(key) / ctx.setState(key, value) / ctx.addState(key, 1)

// 物品与显示
ctx.give(material, n) / ctx.take(material, n) / ctx.drop(material, n)
ctx.actionBar(text) / ctx.title(main, sub)

// 方块（相对触发者，跨区直接拒绝）
ctx.setBlock(dx, dy, dz, material) / ctx.breakBlock(dx, dy, dz)
```

完整写法示例见 `scripts/example.js`（options 取值、持久计数、延迟回调）。

## 许可

[MIT](LICENSE)
