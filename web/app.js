'use strict';
const $ = id => document.getElementById(id);
let ws, pc, control, motion, geometry, gesture = null, sequence = 0, gestureId = 0;
let audioContext, audioTime = 0, audioSources = new Set(), pingTimer, heartbeatTimer;
let negotiating = false, candidates = [], mouseMove = null, raf = 0, wheelBusy = false;
let mediaGeneration = 0, reconnectTimer, recoveryTimer, watchdogTimer, retryCount = 0;
let shouldReconnect = true, lastSignal = 0;
let connectionAttempt = 0, hadConnection = false;
let selectedCodec = 'H264';
let phoneCodecs = null;
let statsTimer, statsState;
const statsFields = ['bitrate','picture','rtt','loss','decode','buffer'];
const status = text => { $('status').textContent = text; };
const fail = text => { $('error').textContent = text; };
const ping = () => signal({type:'ping'});
const syncAudio = () => signal({type:'audio',enabled:$('audio').checked && !document.hidden});
function setConnecting(connecting) {
  $('connect').disabled = connecting;
  $('disconnect').disabled = !connecting;
  $('video-codec').disabled = connecting;
}
const codecMimeTypes = {H264:['video/h264'],H265:['video/h265','video/hevc'],AV1:['video/av1']};
function supportedCodecs() {
  const capabilities = RTCRtpReceiver.getCapabilities?.('video')?.codecs ?? [];
  return Object.fromEntries(Object.entries(codecMimeTypes).map(([name, mimes]) =>
    [name, capabilities.filter(codec => mimes.includes(codec.mimeType.toLowerCase()))]));
}
function updateCodecOptions() {
  if (!window.RTCPeerConnection || !window.RTCRtpReceiver?.getCapabilities) return;
  const supported = supportedCodecs();
  const requested = $('video-codec').value || selectedCodec;
  if ([...$('video-codec').options].some(option => option.value === requested)) $('video-codec').value = requested;
  const codec = $('video-codec').value;
  const missing = [];
  if (supported[codec].length === 0) missing.push('当前浏览器未声明该编码的 WebRTC 解码能力');
  if (phoneCodecs !== null && !phoneCodecs.includes(codec)) missing.push('手机当前原生规格未声明该编码的 Surface 硬编能力');
  $('codec-note').textContent = missing.length ? missing.join('；') : '连接时将检查手机硬编与浏览器解码能力';
}
const signal = message => {
  if (['offer','candidate'].includes(message.type)) message.mediaGeneration = mediaGeneration;
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(message));
};
function send(message, channel = control) {
  if (channel?.readyState !== 'open') return false;
  if (channel.bufferedAmount > 65536) {
    if (channel === motion) return false;
    // Disconnect first so cancellation cannot recursively enter this congestion path.
    control = motion = null; gesture = null;
    retryConnection('控制通道拥塞，正在恢复'); return false;
  }
  try { channel.send(JSON.stringify(message)); return true; }
  catch { control = motion = null; gesture = null; retryConnection('控制通道异常，正在恢复'); return false; }
}
function stopAudio() {
  for (const source of audioSources) { try { source.stop(); } catch {} }
  audioSources.clear(); audioTime = 0;
}
function clearMedia() {
  $('screen-off').disabled = $('screen-on').disabled = true;
  stopStats();
  // Teardown must never re-enter recovery when the old control channel is congested.
  const channel = control;
  control = motion = null; gesture = null;
  if (channel?.readyState === 'open' && channel.bufferedAmount <= 65536) {
    try { channel.send(JSON.stringify({type:'cancelAll'})); } catch {}
  }
  clearInterval(heartbeatTimer); clearTimeout(recoveryTimer);
  if (raf) cancelAnimationFrame(raf); raf = 0; mouseMove = null;
  if (pc) { pc.onconnectionstatechange = null; pc.close(); } pc = null;
  control = motion = null; negotiating = false; candidates = []; geometry = null;
  $('video').srcObject = null; $('empty').hidden = false;
}
function cleanup(message = '已断开') {
  $('screen-power-state').textContent = '';
  connectionAttempt++;
  clearMedia();
  clearInterval(pingTimer); clearInterval(watchdogTimer); clearTimeout(reconnectTimer);
  if (ws) { ws.onclose = ws.onmessage = ws.onerror = ws.onopen = null; ws.close(); } ws = null;
  setConnecting(false);
  stopAudio(); status(message);
}
function retryConnection(message) {
  cleanup(message);
  if (!shouldReconnect) return;
  const delay = Math.min(8000, 500 * 2 ** Math.min(retryCount++, 4));
  $('disconnect').disabled = false;
  reconnectTimer = setTimeout(() => connect(true), delay);
}
async function connect(automatic = false) {
  if (automatic !== true) { shouldReconnect = true; retryCount = 0; hadConnection = false; }
  cleanup(); fail('');
  const attempt = connectionAttempt;
  if (!window.RTCPeerConnection) { shouldReconnect = false; fail('浏览器不提供 WebRTC，请使用最新版 Chrome / Edge。'); return; }
  if (automatic !== true) {
    selectedCodec = $('video-codec').value;
    phoneCodecs = null;
    updateCodecOptions();
  }
  if ($('audio').checked) await enableAudio();
  if (!shouldReconnect || attempt !== connectionAttempt) return;
  ws = new WebSocket(`${location.protocol === 'https:' ? 'wss':'ws'}://${location.host}/ws`); ws.binaryType = 'arraybuffer';
  const socket = ws;
  lastSignal = performance.now();
  watchdogTimer = setInterval(() => {
    if (socket === ws && performance.now() - lastSignal > 12000) retryConnection('正在恢复手机连接');
  }, 2000);
  setConnecting(true); status('正在连接');
  ws.onopen = () => { pingTimer = setInterval(ping, 3000); };
  ws.onmessage = async event => {
    if (socket !== ws) return;
    lastSignal = performance.now();
    if (event.data instanceof ArrayBuffer) { playAudio(event.data); return; }
    let handlingGeneration = mediaGeneration;
    try {
      const data = JSON.parse(event.data);
      handlingGeneration = data.mediaGeneration ?? mediaGeneration;
      if (['answer','candidate'].includes(data.type) && data.mediaGeneration !== undefined && data.mediaGeneration !== mediaGeneration) return;
      switch (data.type) {
        case 'ready': {
          clearMedia();
          mediaGeneration = data.mediaGeneration;
          geometry = data.device;
          phoneCodecs = Array.isArray(data.videoCodecs) ? data.videoCodecs : null;
          updateCodecOptions();
          $('screen-power-state').textContent = geometry.screenOff ? '已请求关闭手机屏幕' : '';
          await connectRtc();
          break;
        }
        case 'answer':
          {
            const current = pc;
            if (!current) break;
            await current.setRemoteDescription({type:'answer', sdp:data.sdp});
            if (current !== pc || socket !== ws) break;
            const queued = candidates; candidates = [];
            for (const candidate of queued) { if (current !== pc) break; await current.addIceCandidate(candidate); }
            break;
          }
        case 'candidate':
          if (pc?.remoteDescription) await pc.addIceCandidate(data.candidate);
          else if (candidates.length < 64) candidates.push(data.candidate);
          else throw new Error('ICE 候选数量超限');
          break;
        case 'screen':
          $('screen-power-state').textContent = data.off ? '已请求关闭手机屏幕' : '已恢复手机屏幕显示';
          break;
        case 'error':
          fail(data.message);
          if (data.fatal) { shouldReconnect = false; cleanup('连接已停止，请重试或在手机重启服务'); break; }
          if (data.message.startsWith('已有电脑连接') && (!hadConnection || retryCount > 5)) shouldReconnect = false;
          if (data.message.startsWith('Audio:')) { $('audio').checked = false; stopAudio(); signal({type:'audio',enabled:false}); }
          break;
        case 'reconfigure':
          clearMedia(); mediaGeneration = data.mediaGeneration;
          recoveryTimer = setTimeout(() => retryConnection('正在恢复画面'), 12000);
          status('正在适配屏幕'); break;
        case 'reconnect': retryConnection('正在恢复手机连接'); break;
      }
    } catch (error) {
      if (socket !== ws || handlingGeneration !== mediaGeneration) return;
      shouldReconnect = false;
      fail(`连接处理失败：${error.message}`); cleanup('连接失败，请重试');
    }
  };
  ws.onerror = () => fail('无法连接手机服务，请检查地址、Wi-Fi 与防火墙');
  ws.onclose = () => { if (socket === ws) retryConnection($('error').textContent || '正在恢复手机连接'); };
}
$('connect').addEventListener('click', connect);
$('video-codec').addEventListener('change', updateCodecOptions);
updateCodecOptions();
async function connectRtc() {
  if (negotiating) return; negotiating = true; status('正在建立视频连接');
  pc = new RTCPeerConnection({iceServers:[], bundlePolicy:'max-bundle'});
  const current = pc, epoch = mediaGeneration;
  recoveryTimer = setTimeout(() => { if (current === pc) retryConnection('正在恢复视频连接'); }, 12000);
  const receiver = pc.addTransceiver('video', {direction:'recvonly'});
  const codecs = supportedCodecs()[selectedCodec];
  if (!codecs.length) throw new Error(`浏览器未提供 ${selectedCodec} WebRTC 解码能力`);
  if (receiver.setCodecPreferences) receiver.setCodecPreferences(codecs);
  try { if ('jitterBufferTarget' in receiver.receiver) receiver.receiver.jitterBufferTarget = 0; } catch {}
  control = pc.createDataChannel('control', {ordered:true});
  control.onopen = () => { if (current === pc) $('screen-off').disabled = $('screen-on').disabled = false; };
  control.onclose = () => { if (current === pc) $('screen-off').disabled = $('screen-on').disabled = true; };
  motion = pc.createDataChannel('motion', {ordered:false, maxRetransmits:0});
  pc.onicecandidate = e => { if (current === pc && e.candidate) signal({type:'candidate', candidate:e.candidate.toJSON()}); };
  pc.ontrack = e => {
    if (current !== pc) return;
    $('video').srcObject = new MediaStream([e.track]); $('empty').hidden = true;
    $('video').play().catch(error => {
      if (current === pc && epoch === mediaGeneration) fail(`播放失败：${error.message}`);
    });
  };
  pc.onconnectionstatechange = () => {
    if (current !== pc) return;
    refreshStats();
    if (pc.connectionState === 'connected') {
      clearTimeout(recoveryTimer); retryCount = 0; hadConnection = true; status('已连接'); syncAudio();
    } else if (['failed','disconnected'].includes(pc.connectionState)) {
      cancelTouch(); status('正在恢复视频连接');
      clearTimeout(recoveryTimer);
      recoveryTimer = setTimeout(() => { if (current === pc) retryConnection('正在恢复视频连接'); }, 2500);
    }
  };
  const offer = await current.createOffer();
  if (current !== pc || epoch !== mediaGeneration) return;
  await current.setLocalDescription(offer);
  if (current !== pc || epoch !== mediaGeneration) return;
  signal({type:'offer', videoCodec:selectedCodec, sdp:current.localDescription.sdp});
  heartbeatTimer = setInterval(() => send({type:'heartbeat'}), 500);

}
function point(event) {
  const video = $('video'), r = video.getBoundingClientRect();
  const width = video.videoWidth, height = video.videoHeight;
  if (!width || !height) return null;
  const scale = Math.min(r.width / width, r.height / height);
  const w = width * scale, h = height * scale;
  const x = (event.clientX - r.left - (r.width - w) / 2) / w;
  const y = (event.clientY - r.top - (r.height - h) / 2) / h;
  return {x:Math.max(0, Math.min(1,x)), y:Math.max(0,Math.min(1,y)), inside:x >= 0 && x <= 1 && y >= 0 && y <= 1};
}
function touch(action, point, channel = control) {
  if (!gesture || !geometry) return;
  gesture.point = point;
  send({type:'touch', action, gesture:gesture.id, seq:++sequence, generation:geometry.generation, x:point.x, y:point.y}, channel);
}
function cancelTouch() {
  if (gesture) { touch('cancel', gesture.point); gesture = null; }
  send({type:'cancelAll'});
  mouseMove = null;
}
$('screen').addEventListener('pointerdown', event => {
  if (event.button !== 0 || gesture || control?.readyState !== 'open') return;
  const p = point(event); if (!p?.inside) return;
  event.preventDefault(); $('screen').focus(); $('screen').setPointerCapture(event.pointerId);
  gesture = {id:++gestureId, pointer:event.pointerId, point:p}; touch('down',p);
});
$('screen').addEventListener('pointermove', event => {
  if (!gesture || gesture.pointer !== event.pointerId) return;
  const p = point(event); if (!p) return; mouseMove = p;
  if (!raf) raf = requestAnimationFrame(() => { raf = 0; if (gesture && mouseMove) touch('move',mouseMove,motion); mouseMove = null; });
});
$('screen').addEventListener('pointerup', event => {
  if (!gesture || gesture.pointer !== event.pointerId) return;
  touch('up',point(event) || gesture.point); gesture = null; mouseMove = null;
});
$('screen').addEventListener('pointercancel',cancelTouch);
$('screen').addEventListener('lostpointercapture',() => { if (gesture?.pointer !== -1) cancelTouch(); });
$('screen').addEventListener('contextmenu',e => e.preventDefault());
$('screen').addEventListener('wheel',event => {
  event.preventDefault(); if (gesture || wheelBusy || control?.readyState !== 'open') return;
  const p = point(event); if (!p?.inside) return;
  wheelBusy = true; gesture = {id:++gestureId, pointer:-1, point:p}; touch('down',p);
  if (!gesture) { wheelBusy = false; return; }
  const delta = Math.sign(event.deltaY) * Math.min(.3, Math.max(.08,Math.abs(event.deltaY)/1200));
  let step = 0; const id = gesture.id;
  const timer = setInterval(() => {
    if (!gesture || gesture.id !== id) { clearInterval(timer); wheelBusy = false; return; }
    const next = {x:p.x,y:Math.max(.01,Math.min(.99,p.y-delta*(++step/8)))};
    touch(step === 8 ? 'up':'move',next,step === 8 ? control:motion);
    if (step === 8) { gesture = null; clearInterval(timer); wheelBusy = false; }
  },16);
}, {passive:false});
document.querySelectorAll('[data-key]').forEach(button => button.addEventListener('click',() => send({type:'key',key:Number(button.dataset.key)})));
for (const off of [true, false]) $(off ? 'screen-off' : 'screen-on').addEventListener('click', () => {
  if (send({type:'screen',off})) { fail(''); $('screen-power-state').textContent = '正在切换手机屏幕'; }
});
$('textform').addEventListener('submit',event => { event.preventDefault(); send({type:'text',text:$('text').value}); });
$('screen').addEventListener('keydown',event => {
  if (event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return;
  const keys = {Backspace:67,Enter:66,Tab:61,Escape:4,ArrowUp:19,ArrowDown:20,ArrowLeft:21,ArrowRight:22};
  if (keys[event.key]) { event.preventDefault(); send({type:'key',key:keys[event.key]}); }
  else if (event.key.length === 1) { event.preventDefault(); send({type:'text',text:event.key}); }
});
window.addEventListener('blur',cancelTouch);
document.addEventListener('visibilitychange',() => {
  refreshStats();
  if (document.hidden) { cancelTouch(); stopAudio(); }
  if (document.hidden || control?.readyState === 'open') syncAudio();
});
window.addEventListener('pagehide',() => { shouldReconnect = false; cleanup(); });
window.addEventListener('pageshow',event => { if (event.persisted) connect(); });
$('disconnect').addEventListener('click',() => { shouldReconnect = false; signal({type:'disconnect'}); cleanup(); });
async function enableAudio() {
  try { audioContext ||= new AudioContext({sampleRate:48000,latencyHint:'interactive'}); await audioContext.resume(); }
  catch (e) { fail(`音频不可用：${e.message}`); $('audio').checked = false; }
}
$('audio').addEventListener('change',async () => {
  if ($('audio').checked) await enableAudio(); else stopAudio();
  syncAudio();
});
function playAudio(bytes) {
  if (!$('audio').checked || !audioContext || document.hidden || audioContext.state !== 'running' || !bytes.byteLength || bytes.byteLength % 4) return;
  const count = bytes.byteLength / 4, pcm = new DataView(bytes);
  const buffer = audioContext.createBuffer(2,count,48000);
  for (let ch=0;ch<2;ch++) { const output=buffer.getChannelData(ch); for (let i=0;i<count;i++) output[i]=pcm.getInt16(i*4+ch*2,true)/32768; }
  const now = audioContext.currentTime;
  if (audioTime > now + .15) stopAudio();
  if (audioTime < now) audioTime = now + .035;
  const source=audioContext.createBufferSource(); source.buffer=buffer; source.connect(audioContext.destination);
  audioSources.add(source); source.onended=() => audioSources.delete(source);
  source.start(audioTime); audioTime += count/48000;
}
function renderStats(values = {}) {
  for (const field of statsFields) $('stat-' + field).textContent = values[field] ?? '—';
}
function stopStats() {
  clearTimeout(statsTimer); statsTimer = null; statsState = null;
  renderStats();
}
function refreshStats() {
  stopStats();
  if (!$('stats').open || document.hidden || pc?.connectionState !== 'connected') return;
  statsState = {peer:pc, previous:null};
  pollStats(statsState);
}
async function pollStats(state) {
  try {
    const report = await state.peer.getStats();
    if (state !== statsState) return;
    const result = readVideoStats(report, state.previous);
    state.previous = result.sample;
    renderStats(result.values);
  } catch {
    if (state !== statsState) return;
    state.previous = null; renderStats();
  }
  if (state === statsState) statsTimer = setTimeout(() => pollStats(state), 1000);
}
function readVideoStats(report, previous) {
  let video, pair;
  for (const stat of report.values()) {
    if (stat.type === 'inbound-rtp' && (stat.kind ?? stat.mediaType) === 'video' && !stat.isRemote
        && (!video || stat.bytesReceived > video.bytesReceived)) video = stat;
    if (stat.type === 'transport' && stat.selectedCandidatePairId) pair = report.get(stat.selectedCandidatePairId);
  }
  if (!pair) pair = [...report.values()].find(stat => stat.type === 'candidate-pair' && stat.nominated && stat.state === 'succeeded');
  const values = {};
  const format = (value, digits, unit) => Number.isFinite(value) && value >= 0 ? value.toFixed(digits) + unit : '—';
  values.rtt = format(pair?.currentRoundTripTime * 1000, 1, ' ms');
  if (!video) return {sample:null, values};
  const size = video.frameWidth > 0 && video.frameHeight > 0 ? `${video.frameWidth}×${video.frameHeight}` : '—';
  values.picture = size;
  // Only difference counters belonging to the same stream and a valid, monotonic sample.
  if (!previous || video.id !== previous.id || video.ssrc !== previous.ssrc || video.timestamp <= previous.timestamp
      || video.bytesReceived < previous.bytesReceived || video.framesDecoded < previous.framesDecoded
      || video.packetsReceived < previous.packetsReceived) return {sample:video, values};
  const elapsed = video.timestamp - previous.timestamp;
  const delta = field => Number.isFinite(video[field]) && Number.isFinite(previous[field]) ? video[field] - previous[field] : NaN;
  const frames = delta('framesDecoded'), received = delta('packetsReceived');
  // Late packets can decrease packetsLost; do not display a negative loss rate.
  const lost = Math.max(0, delta('packetsLost'));
  values.bitrate = format(delta('bytesReceived') * 8 / elapsed / 1000, 2, ' Mbps');
  values.picture = `${size} · ${format(frames * 1000 / elapsed, 0, ' FPS')}`;
  values.loss = format(received + lost > 0 ? lost / (received + lost) * 100 : NaN, 1, '%');
  values.decode = format(frames > 0 ? delta('totalDecodeTime') * 1000 / frames : NaN, 1, ' ms');
  const emitted = delta('jitterBufferEmittedCount');
  values.buffer = format(emitted > 0 ? delta('jitterBufferDelay') * 1000 / emitted : NaN, 1, ' ms');
  return {sample:video, values};
}
$('stats').addEventListener('toggle', refreshStats);
