// 分裂箭：射箭瞬间额外生成两支横向偏转的箭，模仿原版多重箭。
//
// 触发时机是 SHOOT（出膛那一刻），这是框架唯一能拿到投射物的时机。
// 因此这里复制的是「刚离弦的箭」，不是「命中后的箭」——落点效果要等命中触发，
// 而框架没有那个时机，所以分裂箭只管数量，不管命中结算。
//
// 线程边界：SHOOT 跨区会被框架直接放弃（投射物只在原线程有效），
// 所以能进到这里就说明投射物属于当前线程，spawnArrow 是安全的。
//
// 偏转角列表与初速比例在 yml 的 options 里（spread-degrees / speed-ratio）。
function onTrigger(ctx) {
  var projectile;
  try {
    projectile = ctx.projectile();
  } catch (e) {
    return;
  }
  if (null == projectile) {
    return;
  }

  var velocity = projectile.getVelocity();
  if (null == velocity) {
    return;
  }

  var speed = Math.sqrt(
      velocity.getX() * velocity.getX()
      + velocity.getY() * velocity.getY()
      + velocity.getZ() * velocity.getZ()
  );
  if (!(speed > 0)) {
    return;
  }

  var world = projectile.getWorld();
  var location = projectile.getLocation();
  var player = ctx.player();
  var spread = ctx.optionList("spread-degrees");
  var ratio = ctx.optionNumber("speed-ratio", 1.0);

  for (var i = 0; i < spread.length; i++) {
    var degrees = parseFloat("" + spread[i]);
    if (isNaN(degrees)) {
      continue;
    }

    // 绕 Y 轴旋转水平分量、保留垂直分量：得到原版那种横向扇形，而不是随机乱飞。
    var radians = degrees * Math.PI / 180;
    var cos = Math.cos(radians);
    var sin = Math.sin(radians);

    var direction = new org.bukkit.util.Vector(
        velocity.getX() * cos - velocity.getZ() * sin,
        velocity.getY(),
        velocity.getX() * sin + velocity.getZ() * cos
    );

    try {
      var extra = world.spawnArrow(location, direction, speed * ratio, 0.0);
      if (null != extra) {
        // 记在射手名下：命中的击杀统计与仇恨才会算到这名玩家头上。
        extra.setShooter(player);
      }
    } catch (e) {
      // 生成失败（世界边界、实体上限）：少一支就少一支，不影响原始那支。
    }
  }
}
