// Drives the debug build on an Android emulator: adb for the phone side,
// and the WebView's DevTools connection for the page inside the app.
// Needs Node 22+ (built-in fetch and WebSocket) and adb on the PATH.
const { execFileSync } = require('child_process');
const fs = require('fs'), path = require('path');

const PKG = 'io.github.dharivinod.moneytracker', ACT = PKG + '/.MainActivity';
const ROOT = path.join(__dirname, '..'), OUT = path.join(__dirname, 'out');
const APK = v => path.join(ROOT, 'app', 'build', 'outputs', 'apk', v, `app-${v}.apk`);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const adb = (...a) => execFileSync('adb', a, { encoding: 'utf8', maxBuffer: 64 << 20 });
const sh = cmd => { try { return adb('shell', cmd); } catch (e) { return String(e.stdout || ''); } };
const shot = name => { try { fs.writeFileSync(path.join(OUT, name + '.png'), execFileSync('adb', ['exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 })); } catch (e) { console.log('info  screenshot failed: ' + e.message); } };

let pass = 0, failed = 0;
const check = (name, ok, extra) => { ok ? pass++ : failed++; console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '  -> ' + extra : '')); };

// ---- page side: a minimal DevTools client ----
let ws = null, seq = 0;
const waiting = new Map();
async function attach() {
  if (ws) { try { ws.close(); } catch (e) {} ws = null; }
  try { adb('forward', '--remove-all'); } catch (e) {}
  for (let i = 0; i < 60; i++) {
    const pid = sh('pidof ' + PKG).trim().split(/\s+/)[0];
    if (pid) {
      try {
        adb('forward', 'tcp:9222', 'localabstract:webview_devtools_remote_' + pid);
        const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
        const page = pages.find(p => /appassets/.test(p.url));
        if (page) {
          ws = new WebSocket(page.webSocketDebuggerUrl);
          await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('socket error')); });
          ws.onmessage = e => { const m = JSON.parse(e.data), w = waiting.get(m.id); if (w) { waiting.delete(m.id); m.error ? w.rej(new Error(m.error.message)) : w.res(m.result); } };
          return;
        }
      } catch (e) {}
    }
    await sleep(1000);
  }
  throw new Error('could not connect to the page inside the app');
}
const send = (method, params) => new Promise((res, rej) => { const id = ++seq; waiting.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params })); });
async function js(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error('page error: ' + ((r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text));
  return r.result.value;
}
const until = async (expr, ms = 15000) => { for (const end = Date.now() + ms; Date.now() < end;) { try { if (await js(expr)) return true; } catch (e) {} await sleep(300); } return false; };
const pin = async d => { for (const c of d) { await js(`document.querySelector('#lock [data-k="${c}"]').click()`); await sleep(120); } await sleep(1300); };
const locked = () => js(`document.body.classList.contains('locked')`);
const title = () => js(`(document.querySelector('#lock h2') || {}).textContent || ''`);

// ---- phone side ----
const resumed = () => /(topResumedActivity|mResumedActivity|ResumedActivity)[^\n]*moneytracker/.test(sh('dumpsys activity activities'));
const serviceUp = () => { const s = sh('dumpsys activity services ' + PKG); return /ShakeService/.test(s) && /isForeground=true/.test(s); };
async function shake() {
  adb('emu', 'sensor', 'set', 'acceleration', '0:30:0');
  await sleep(2600);
  adb('emu', 'sensor', 'set', 'acceleration', '0:9.81:0');
  await sleep(1500);
}
async function tapText(re) {
  sh('uiautomator dump /sdcard/ui.xml');
  const xml = sh('cat /sdcard/ui.xml');
  fs.writeFileSync(path.join(OUT, 'last-ui.xml'), xml);
  const node = (xml.match(/<node [^>]*>/g) || []).find(n => re.test((/ text="([^"]*)"/.exec(n) || [])[1] || ''));
  const b = node && /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(node);
  if (!b) return false;
  sh(`input tap ${(+b[1] + +b[3]) >> 1} ${(+b[2] + +b[4]) >> 1}`);
  return true;
}

(async () => {
  fs.rmSync(OUT, { recursive: true, force: true }); fs.mkdirSync(OUT, { recursive: true });
  sh('svc power stayon true'); sh('input keyevent KEYCODE_WAKEUP'); sh('wm dismiss-keyguard');
  try { adb('uninstall', PKG); } catch (e) {}
  adb('install', '-r', APK('debug'));
  sh(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`);
  adb('logcat', '-c');

  // ---------- first open ----------
  sh('am start -n ' + ACT);
  await attach();
  check('first open shows "Create a PIN"', await until(`document.querySelector('#lock h2') && document.querySelector('#lock h2').textContent === 'Create a PIN'`), await title());
  const env = await js(`({ native: !!NATIVE, mode: MODE, origin: location.origin, secure: isSecureContext && !!crypto.subtle, shake: NATIVE.shakeEnabled(), overlay: NATIVE.overlayAllowed(), bio: NATIVE.bioAvailable() })`);
  check('page runs inside the app, on-device mode, secure origin', env.native && env.mode === 'local' && env.secure && env.origin === 'https://appassets.androidplatform.net', JSON.stringify(env));
  check('shake is on by default, pop-up permission not yet given', env.shake === true && env.overlay === false);
  await sleep(1500); shot('1-first-open');
  await pin('1234');
  check('asks to confirm the PIN', (await title()) === 'Confirm your PIN', await title());
  await pin('1234');
  if (await js(`!!document.querySelector('#lock [data-k="bio-skip"]')`)) { await js(`document.querySelector('#lock [data-k="bio-skip"]').click()`); await sleep(600); }
  check('unlocked after creating the PIN', !(await locked()));
  check('status line reads "Saved on this device"', (await js(`document.querySelector('#sync span').textContent`)) === 'Saved on this device');
  check('shake listener is running', serviceUp());

  // ---------- add an entry ----------
  await js(`openAdd({})`); await sleep(500);
  await js(`document.querySelector('#a-amt').value = '250.50'; document.querySelector('#a-cats [data-v="Groceries"]').click(); document.querySelector('#a-note').value = 'Milk & bread'; document.querySelector('#a-save').click()`);
  await sleep(600);
  check('an expense is saved in the app storage', await js(`S.tx.length === 1 && JSON.parse(localStorage.getItem('mt-local')).tx.length === 1`));
  shot('2-home');

  // ---------- shake while the app is open ----------
  console.log('info  accelerometer before: ' + (() => { try { return adb('emu', 'sensor', 'get', 'acceleration').trim().split('\n')[0]; } catch (e) { return 'n/a'; } })());
  await shake();
  check('shake with the app open -> quick add (amount + category only)', await until(`document.querySelector('#sh-add').classList.contains('on') && document.querySelector('#a-more').classList.contains('closed')`, 5000));
  shot('3-quick-add');
  await js(`document.querySelector('#a-amt').value = '99'; document.querySelector('#a-cats [data-v="Food & Dining"]').click(); document.querySelector('#a-save').click()`);
  await sleep(600);
  check('quick add saves the entry', await js(`S.tx.length === 2 && S.tx.some(t => t.amount === 99 && t.source === 'Quick add')`));

  // ---------- shake from the home screen, before the pop-up permission ----------
  sh('input keyevent KEYCODE_HOME'); await sleep(4500);
  check('app is in the background', !resumed());
  await shake();
  check('without the pop-up permission a shake posts a "tap to add" notification', /Tap to open quick add/.test(sh('dumpsys notification --noredact')) && !resumed());

  // ---------- shake from the home screen, with the permission ----------
  sh(`appops set ${PKG} SYSTEM_ALERT_WINDOW allow`); await sleep(4500);
  await shake();
  check('with the permission a shake opens the app by itself', resumed());
  check('...and quick add is showing', await until(`document.querySelector('#sh-add').classList.contains('on')`, 5000));
  shot('4-quick-add-from-background');
  await js(`closeSheet()`);

  // ---------- data survives the app being killed ----------
  sh('am force-stop ' + PKG); await sleep(1000);
  sh('am start -n ' + ACT);
  await attach();
  check('after a restart the app asks for the PIN', await until(`document.querySelector('#lock h2') && document.querySelector('#lock h2').textContent === 'Welcome back'`), await title());
  await pin('9999');
  check('wrong PIN is refused', (await locked()) && /4 tries left/.test(await js(`document.querySelector('#lk-err').textContent`)));
  await pin('1234');
  check('correct PIN unlocks and both entries are still there', !(await locked()) && await js(`S.tx.length === 2`));
  check('shake listener restarted with the app', serviceUp());

  // ---------- auto-lock ----------
  sh('input keyevent KEYCODE_HOME'); await sleep(65000);
  sh('am start -n ' + ACT); await sleep(2000);
  check('after a minute in the background the app locks itself', await locked());
  await pin('1234');
  check('unlocks again', !(await locked()));

  // ---------- settings: shake switch and Excel file ----------
  await js(`openSettings()`); await sleep(700);
  const set = await js(`document.querySelector('#set-body').innerText`);
  check('settings shows the shake switch and the Excel button, no MacroDroid steps', /Shake to add/.test(set) && /Download Excel file/.test(set) && !/MacroDroid/.test(set) && !/Display over other apps/.test(set));
  shot('5-settings');
  await js(`document.querySelector('#s-shake').click()`); await sleep(1500);
  check('switching shake off stops the listener', (await js(`NATIVE.shakeEnabled()`)) === false && !serviceUp());
  await js(`document.querySelector('#s-shake').click()`); await sleep(1500);
  check('switching it on starts it again', (await js(`NATIVE.shakeEnabled()`)) === true && serviceUp());

  await js(`document.querySelector('[data-s="xlsx"]').click()`); await sleep(4000);
  shot('6-save-screen');
  const tapped = await tapText(/^save$/i);
  await sleep(4000);
  const file = sh('ls /sdcard/Download/').split(/\s+/).find(f => /^Money-Tracker-.*\.xlsx$/.test(f));
  check('the phone\'s save screen stores the Excel file in Downloads', tapped && !!file, file || 'save button found: ' + tapped);
  if (file) adb('pull', '/sdcard/Download/' + file, path.join(OUT, 'export.xlsx'));
  check('the app is told the file was saved', await until(`!!localStorage.getItem('mt-exported')`, 5000));
  fs.writeFileSync(path.join(OUT, 'expected.json'), JSON.stringify(await js(`({ tx: S.tx, rec: S.rec, bud: S.bud })`)));
  await js(`closeSheet()`); await sleep(400);

  // ---------- back button ----------
  await js(`openAdd({})`); await sleep(600);
  sh('input keyevent KEYCODE_BACK'); await sleep(900);
  check('Back closes an open sheet and stays in the app', (await js(`!document.querySelector('.sheet.on')`)) && resumed());
  sh('input keyevent KEYCODE_BACK'); await sleep(1500);
  check('Back on the home view sends the app to the background', !resumed());

  // ---------- nothing crashed ----------
  const crash = adb('logcat', '-d', '-b', 'crash');
  check('no crashes in the debug build', !/moneytracker/.test(crash), crash.split('\n').slice(0, 12).join(' | '));

  // ---------- the signed release build starts too ----------
  adb('uninstall', PKG);
  adb('install', APK('release'));
  sh(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`);
  adb('logcat', '-c');
  sh('am start -n ' + ACT); await sleep(8000);
  shot('7-release-build');
  check('release build installs, starts and stays running', !!sh('pidof ' + PKG).trim() && resumed());
  check('release build starts its shake listener', serviceUp());
  await shake();
  check('release build reacts to a shake', /shake detected/.test(adb('logcat', '-d', '-s', 'MoneyShake:I')));
  const crash2 = adb('logcat', '-d', '-b', 'crash');
  check('no crashes in the release build', !/moneytracker/.test(crash2), crash2.split('\n').slice(0, 12).join(' | '));

  console.log(`\n${pass} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error('TEST CRASHED:', e); try { shot('crash'); fs.writeFileSync(path.join(OUT, 'logcat.txt'), adb('logcat', '-d')); } catch (x) {} process.exit(2); });
