/*
 * app.js — the one and only user-editable application script.
 * Edit this file from the desktop Script Studio at http://127.0.0.1:8765/
 */
(() => {
  const VERSION = '0.1.0';
  const root = document.getElementById('app');
  const status = document.getElementById('runtime-status');

  status.textContent = 'app.js · v' + VERSION;

  root.innerHTML = `
    <section class="shell">
      <h1>Discord WebView Lab</h1>
      <p class="muted">Your hot-swappable JavaScript is running inside Android WebView.</p>
      <p class="muted">Script version: <code>v${VERSION}</code></p>
      <div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:22px">
        <button id="hello" style="background:#5865f2;color:white;border:0;border-radius:8px;padding:11px 16px">Test interaction</button>
        <button id="clock" style="background:#3f4147;color:white;border:0;border-radius:8px;padding:11px 16px">Show device time</button>
      </div>
      <p id="output" class="muted" aria-live="polite">Ready. Change app.js on your PC and press Apply.</p>
      <hr style="border:0;border-top:1px solid #41434a;margin:24px 0">
      <p class="muted">Desktop sync: <code>adb forward tcp:8765 tcp:8765</code></p>
    </section>`;

  document.getElementById('hello').addEventListener('click', () => {
    document.getElementById('output').textContent = 'Interaction handled by app.js v' + VERSION;
  });
  document.getElementById('clock').addEventListener('click', () => {
    document.getElementById('output').textContent = 'Device time: ' + new Date().toLocaleString();
  });
})();
