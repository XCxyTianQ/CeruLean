// CeruLean 真实玩家加入探针（node + mineflayer）。
//
// 为什么需要它：冒烟套件全是 RCON 驱动，**没有真实客户端**，因此
//   * issue #1（玩家加入即 NPE）当初是用户端的真实客户端才暴露的；
//   * EXP7Plus 的 `PlayerList.updateEntireScoreboard`（登录同步计分板）只能在真客户端上验证。
// 本探针补上这条路径。
//
// 依赖：mineflayer / minecraft-protocol / minecraft-data。本仓库不带 node_modules，
// 通过 NODE_PATH 复用同工作区的 AzureTPA 冒烟依赖：
//   NODE_PATH=/path/to/AzureTPA/smoke/node_modules node tools/smoke/player_join_probe.js <port> <logfile>
const net = require('net');
const fs = require('fs');
const path = require('path');
const mcproto = require('minecraft-protocol');
const mineflayer = require('mineflayer');

const PORT = parseInt(process.argv[2] || '25565', 10);
const LOG = process.argv[3] || 'smoke-join/server-console.log';
const RCON_PORT = 25575, RCON_PASS = 'test1234';
const NAME = 'JoinProbe';
const results = [];
const rec = (label, detail, ok) => {
  results.push({ label, detail, ok });
  console.log(`  [${ok ? 'OK  ' : 'FAIL'}] ${label}  -> ${String(detail).slice(0, 120)}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function rcon(cmd, timeoutMs = 10000) {
  return new Promise((resolve) => {
    let result = '', phase = 'auth';
    const sock = net.createConnection({ host: '127.0.0.1', port: RCON_PORT }, () => write(1, 3, RCON_PASS));
    function write(id, type, body) {
      const payload = Buffer.from(body, 'utf8');
      const buf = Buffer.alloc(4 + 4 + 4 + payload.length + 2);
      buf.writeInt32LE(4 + 4 + payload.length + 2, 0);
      buf.writeInt32LE(id, 4); buf.writeInt32LE(type, 8);
      payload.copy(buf, 12); buf.writeInt16LE(0, 12 + payload.length);
      sock.write(buf);
    }
    sock.on('data', (data) => {
      const type = data.readInt32LE(4);
      const body = data.slice(8, data.length - 2).toString('utf8');
      if (type === 2) {
        if (phase === 'auth') { phase = 'cmd'; write(2, 2, cmd); }
        else { result += body; }
      }
    });
    sock.on('error', () => resolve(''));
    sock.on('close', () => resolve(result));
    setTimeout(() => { try { sock.destroy(); } catch {} resolve(result); }, timeoutMs);
  });
}

async function resolveVersion() {
  const pong = await mcproto.ping({ host: '127.0.0.1', port: PORT });
  console.log(`  [ping] ${JSON.stringify(pong.version)}`);
  const dpPath = require.resolve('minecraft-data/minecraft-data/data/dataPaths.json');
  const dp = JSON.parse(fs.readFileSync(dpPath, 'utf8'));
  const pcDir = path.join(path.dirname(dpPath), 'pc');
  for (const name of Object.keys(dp.pc || {})) {
    try {
      const v = JSON.parse(fs.readFileSync(path.join(pcDir, name, 'version.json'), 'utf8'));
      if (v.protocol === pong.version.protocol || v.version === pong.version.protocol) return name;
    } catch {}
  }
  throw new Error(`未找到协议 ${pong.version.protocol} 对应的 minecraft-data 版本`);
}

(async () => {
  console.log('== CeruLean 真实玩家加入探针');
  const version = await resolveVersion();
  console.log(`  使用 minecraft-data 版本键: ${version}`);

  // 登录前铺好"可被同步"的计分板与队伍数据 —— 这样 updateEntireScoreboard 有东西可发。
  await rcon('scoreboard objectives remove joinsync');
  await rcon('scoreboard objectives add joinsync dummy');
  await rcon(`scoreboard players set ${NAME} joinsync 7`);
  await rcon('team remove joincolor');
  await rcon('team add joincolor');
  await rcon('team modify joincolor color red');
  await rcon(`team join joincolor ${NAME}`);
  await sleep(600);

  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: PORT, username: NAME, version, auth: 'offline',
    viewDistance: 'tiny', checkTimeoutInterval: 600000, plugins: { time: false },
  });
  let kicked = null;
  bot.on('kicked', (r) => { kicked = String(r); });
  bot.on('error', (e) => { kicked = String(e && e.message || e); });

  const spawned = await Promise.race([
    new Promise((r) => bot.once('spawn', () => r(true))),
    sleep(30000).then(() => false),
  ]);
  rec('玩家真的能加入（收到 spawn 事件）', kicked ? `kicked=${kicked.slice(0, 80)}` : 'spawn', spawned && !kicked);
  await sleep(2500);

  const list = await rcon('list');
  rec('RCON list 能列出该玩家', list.trim().slice(0, 80), list.includes(NAME));

  // EXP7Plus：登录同步计分板 —— 客户端应收到 joinsync 目标
  const sb = bot.scoreboard || {};
  const hasObjective = Object.keys(sb).includes('joinsync');
  const sbInfo = Object.keys(sb).length ? Object.keys(sb).join(',') : '(空)';
  rec('EXP7Plus · 登录同步计分板（客户端收到 joinsync 目标）', sbInfo, hasObjective);

  // EXP7Plus：队伍同步（客户端 teamMap）
  const teams = bot.teamMap ? Object.keys(bot.teamMap) : [];
  rec('EXP7Plus · 登录同步队伍（客户端收到 joincolor）', teams.join(',') || '(空)', teams.includes('joincolor'));

  // issue #1 回归：加入过程不得有 NPE
  try {
    const log = fs.readFileSync(LOG, 'utf-8');
    const bad = log.split('\n').filter((l) => /NullPointerException|Exception/.test(l)
      && !/Operating system not supported|No key layers|blocking a tick/.test(l));
    rec('issue #1 回归 · 加入过程无 NPE/异常', `异常行=${bad.length}`, bad.length === 0);
    bad.slice(0, 3).forEach((l) => console.log('     ' + l.trim().slice(0, 150)));
  } catch (e) {
    rec('issue #1 回归 · 加入过程无 NPE/异常', '日志不可读', false);
  }

  try { bot.quit(); } catch {}
  await sleep(800);
  await rcon('team remove joincolor');
  await rcon('scoreboard objectives remove joinsync');

  const fail = results.filter((r) => !r.ok).length;
  console.log(`\n== 小结（玩家加入）：${results.length - fail} 通过 / ${fail} 失败`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error('[fatal]', e && e.message || e); process.exit(1); });
