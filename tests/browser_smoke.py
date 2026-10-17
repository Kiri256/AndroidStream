"""Actual Edge WebRTC loopback + UI smoke test; no Android/hardware claim.

Run: python -m pip install playwright
     python tests/browser_smoke.py
"""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
import threading
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def end_headers(self):
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'")
        super().end_headers()


def check_stats_calculations(page):
    cases = page.evaluate("""() => {
      const previous = {id:'video',ssrc:42,type:'inbound-rtp',kind:'video',timestamp:1000,
        bytesReceived:1000,framesDecoded:10,packetsReceived:100,packetsLost:1,
        totalDecodeTime:.02,jitterBufferDelay:.2,jitterBufferEmittedCount:10,frameWidth:1920,frameHeight:864};
      const current = {...previous,timestamp:2000,bytesReceived:1001000,framesDecoded:70,
        packetsReceived:198,packetsLost:3,totalDecodeTime:.14,jitterBufferDelay:1.4,jitterBufferEmittedCount:70};
      const report = video => new Map([
        ['other',{type:'candidate-pair',nominated:true,state:'succeeded',currentRoundTripTime:.9}],
        ['transport',{type:'transport',selectedCandidatePairId:'selected'}],
        ['selected',{type:'candidate-pair',currentRoundTripTime:.012}],
        ['audio',{type:'inbound-rtp',kind:'audio',bytesReceived:9000000}],['video',video]]);
      const read = (video, before=previous) => readVideoStats(report(video), before).values;
      return {
        normal:read(current), first:read(current,null),
        reset:read({...current,bytesReceived:0}), stream:read({...current,ssrc:43}),
        repeated:read({...current,timestamp:1000}),
        idle:read({...previous,timestamp:2000}),
        late:read({...current,packetsLost:0}),
        missing:read({...current,totalDecodeTime:undefined,jitterBufferDelay:undefined,packetsLost:undefined}),
        noVideo:readVideoStats(new Map(),previous)
      };
    }""")
    assert cases['normal'] == {'bitrate':'8.00 Mbps','picture':'1920×864 · 60 FPS',
                              'rtt':'12.0 ms','loss':'2.0%','decode':'2.0 ms','buffer':'20.0 ms'}, cases
    for name in ['first', 'reset', 'stream', 'repeated']:
        assert 'bitrate' not in cases[name], (name, cases[name])
    assert cases['idle']['bitrate'] == '0.00 Mbps'
    assert cases['idle']['picture'].endswith('0 FPS')
    assert cases['idle']['decode'] == cases['idle']['buffer'] == cases['idle']['loss'] == '—'
    assert cases['late']['loss'] == '0.0%'
    assert cases['missing']['decode'] == cases['missing']['buffer'] == cases['missing']['loss'] == '—'
    assert cases['noVideo']['sample'] is None


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(ROOT / "web")))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with sync_playwright() as playwright:
            edge = os.environ.get("ANDROIDSTREAM_BROWSER", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
            browser = playwright.chromium.launch(executable_path=edge, headless=True, args=[
                "--autoplay-policy=no-user-gesture-required", "--host-resolver-rules=MAP androidstream.test 127.0.0.1", "--no-proxy-server"])
            page = browser.new_page(viewport={"width":1280,"height":900})
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.add_init_script(path=str(ROOT / "tests" / "browser_peer.js"))
            page.goto(f"http://androidstream.test:{server.server_port}")
            assert not page.evaluate("window.isSecureContext"), "Test must cover plain LAN-like HTTP"
            assert page.locator("#code").count() == 0
            assert page.locator("#pair").count() == 0
            assert page.locator("#transport").count() == 0
            assert page.locator("canvas").count() == 0
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接'", timeout=20000)
            page.wait_for_function("() => document.getElementById('video').videoWidth > 0 && control?.readyState === 'open' && motion?.readyState === 'open'", timeout=15000)
            assert page.locator("select, #settings, #diagnostics, #session-config").count() == 0
            assert page.evaluate("typeof sessionConfig === 'undefined'")
            page.locator('#screen-off').click()
            page.wait_for_function("() => window.__test.inputs.some(x => x.type === 'screen' && x.off === true)")
            page.wait_for_function("() => document.getElementById('screen-power-state').textContent === '已请求关闭手机屏幕'")
            page.locator('#screen-on').click()
            page.wait_for_function("() => window.__test.inputs.some(x => x.type === 'screen' && x.off === false)")
            page.wait_for_function("() => document.getElementById('screen-power-state').textContent === '已恢复手机屏幕显示'")
            check_stats_calculations(page)
            page.wait_for_function("() => document.getElementById('stat-bitrate').textContent.endsWith(' Mbps') && document.getElementById('stat-picture').textContent.includes('FPS')",timeout=10000)
            assert page.locator('#stat-picture').inner_text().startswith('360×800')
            assert page.locator('#stat-rtt').inner_text().endswith(' ms')
            # Closed and hidden panels stop polling, without changing the connection.
            page.locator('#stats > summary').click()
            page.wait_for_function("() => statsState === null && statsTimer === null")
            page.evaluate("() => { window.__test.statsGetter = pc.getStats; window.__test.statsCalls = 0; pc.getStats = function() { window.__test.statsCalls++; return window.__test.statsGetter.call(this); }; }")
            page.wait_for_timeout(1200)
            assert page.evaluate("window.__test.statsCalls === 0")
            page.locator('#stats > summary').click()
            page.wait_for_function("() => window.__test.statsCalls >= 2")
            page.evaluate("Object.defineProperty(document,'hidden',{value:true,configurable:true}); document.dispatchEvent(new Event('visibilitychange'))")
            page.wait_for_function("() => statsState === null && statsTimer === null")
            page.evaluate("window.__test.statsCalls = 0")
            page.wait_for_timeout(1200)
            assert page.evaluate("window.__test.statsCalls === 0")
            page.evaluate("delete document.hidden; document.dispatchEvent(new Event('visibilitychange'))")
            page.wait_for_function("() => window.__test.statsCalls >= 2")
            # A pending result from the old session must not refill a cleared panel.
            page.evaluate("() => { pc.getStats = () => new Promise(resolve => window.__test.finishStats = resolve); }")
            page.wait_for_function("() => typeof window.__test.finishStats === 'function'")
            page.locator('#stats > summary').click()
            page.wait_for_function("() => statsState === null")
            page.evaluate("() => { window.__test.finishStats(new Map([['video',{id:'old',type:'inbound-rtp',kind:'video',frameWidth:888,frameHeight:999}]])); pc.getStats = window.__test.statsGetter; }")
            assert page.locator('#stat-picture').text_content() == '—', ascii(page.locator('#stat-picture').text_content())
            # Missing/rejected browser stats must not interrupt playback.
            page.evaluate("() => { pc.getStats = () => Promise.reject(new Error('simulated stats failure')); }")
            page.locator('#stats > summary').click()
            page.wait_for_timeout(100)
            assert page.locator('#status').inner_text() == '已连接'
            assert page.locator('#error').inner_text() == ''
            page.evaluate("() => { pc.getStats = window.__test.statsGetter; }")
            page.wait_for_function("() => document.getElementById('stat-bitrate').textContent.endsWith(' Mbps')",timeout=10000)
            page.screenshot(path=str(ROOT/'.tools'/'minimal-desktop.png'))
            page.locator('#text-entry > summary').click()
            page.locator("#audio").check()
            box = page.locator("#video").bounding_box()
            x,y = box['x']+box['width']/2, box['y']+box['height']/2
            page.mouse.move(x,y)
            page.mouse.down()
            page.mouse.move(x+30,y+40,steps=5)
            page.mouse.up()
            page.wait_for_function("() => window.__test.inputs.some(x=>x.action==='up')")
            page.locator("[data-key='3']").click()
            page.locator("#text").fill("hello")
            page.locator("#textform button").click()
            page.wait_for_function("() => window.__test.inputs.some(x=>x.type==='text' && x.text==='hello')")
            page.wait_for_function("() => window.__test.audioPackets > 4 && audioSources.size > 0")
            assert page.evaluate("!('codec' in window.__test.offers[0]) && !('profile' in window.__test.offers[0])")
            assert page.evaluate("audioTime - audioContext.currentTime < .2"), "Audio buffering grew"
            page.evaluate("window.__test.originalSocket = ws; window.__test.originalPc = pc; window.__test.socket.rotate(2670,1200)")
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接' && document.getElementById('video').videoWidth === 800", timeout=20000)
            page.wait_for_function("() => document.getElementById('stat-picture').textContent.startsWith('800×360')",timeout=10000)
            assert page.evaluate("ws === window.__test.originalSocket && pc !== window.__test.originalPc && window.__test.originalPc.connectionState === 'closed'")
            assert page.locator('#audio').is_checked()
            page.wait_for_function("() => audioSources.size > 0")
            page.evaluate("playAudio(new ArrayBuffer(0))")
            page.evaluate("window.__test.socket.emit({type:'answer',sdp:'stale invalid SDP',mediaGeneration:1}); window.__test.socket.emit({type:'candidate',candidate:{candidate:'stale'},mediaGeneration:1})")
            page.wait_for_timeout(100)
            assert page.locator('#error').inner_text() == ''
            box = page.locator('#video').bounding_box()
            page.mouse.move(box['x']+box['width']/2,box['y']+box['height']/2)
            page.mouse.down()
            page.wait_for_function("() => gesture !== null")
            page.evaluate("window.__test.socket.rotate(1200,2670); window.__test.socket.rotate(2670,1200); window.__test.socket.rotate(1200,2670)")
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接' && document.getElementById('video').videoWidth === 360 && geometry?.generation === 5", timeout=20000)
            assert page.evaluate("gesture === null && ws === window.__test.originalSocket")
            page.mouse.up()
            page.mouse.click(x,y)
            page.wait_for_function("() => window.__test.inputs.some(x=>x.type==='touch' && x.action==='up' && x.generation===5)")
            page.locator("#audio").uncheck()
            assert page.evaluate("audioSources.size === 0"), "Mute left playing audio"
            page.locator("#disconnect").click()
            page.wait_for_timeout(700)
            assert page.evaluate("ws === null"), "Manual disconnect unexpectedly reconnected"
            assert page.locator("#connect").is_enabled()
            assert page.evaluate("pc === null && ws === null && gesture === null")
            assert page.evaluate("statsState === null && statsTimer === null")
            assert page.locator('#stat-bitrate').inner_text() == '—'
            page.locator("#connect").click()
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接'", timeout=20000)
            page.evaluate("window.__test.previousSocket = ws; ws.close(); ws.onclose?.()")
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接' && ws !== window.__test.previousSocket", timeout=20000)
            page.evaluate("window.__test.previousSocket = ws; Object.defineProperty(pc,'connectionState',{value:'disconnected',configurable:true}); pc.onconnectionstatechange()")
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接' && ws !== window.__test.previousSocket", timeout=20000)
            page.locator("#disconnect").click()
            page.locator('#connect').click()
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接'",timeout=20000)
            page.evaluate("window.__test.previousSocket = ws; Object.defineProperty(control,'bufferedAmount',{value:70000,configurable:true}); cancelTouch()")
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接' && ws !== window.__test.previousSocket",timeout=20000)
            page.wait_for_function("() => control?.readyState === 'open' && document.getElementById('video').videoWidth > 0")
            # A wheel down may synchronously tear down the gesture on congestion.
            page.evaluate("window.__test.previousSocket = ws; Object.defineProperty(control,'bufferedAmount',{value:70000,configurable:true})")
            box = page.locator('#video').bounding_box()
            page.mouse.move(box['x']+box['width']/2,box['y']+box['height']/2)
            page.mouse.wheel(0,120)
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接' && ws !== window.__test.previousSocket",timeout=20000)
            page.wait_for_function("() => control?.readyState === 'open'")
            assert page.evaluate("!wheelBusy && gesture === null")
            # Manual teardown on a blocked channel must not recursively schedule recovery.
            page.evaluate("Object.defineProperty(control,'bufferedAmount',{value:70000,configurable:true})")
            page.locator('#disconnect').click()
            page.wait_for_timeout(800)
            assert page.evaluate("ws === null && pc === null"), 'Congested manual disconnect reconnected'
            page.locator('#connect').click()
            page.wait_for_function("() => document.getElementById('status').textContent === '已连接'",timeout=20000)
            page.evaluate("window.__test.socket.emit({type:'error',fatal:true,message:'Capture: simulated failure'})")
            page.wait_for_function("() => ws === null && pc === null")
            page.wait_for_timeout(800)
            assert page.evaluate("ws === null"), 'Fatal capture error started a retry loop'
            assert 'Capture: simulated failure' in page.locator('#error').inner_text()
            assert not errors, errors
            print(f"PASS: Edge {browser.version}; real WebRTC video, touch, navigation, text, PCM audio, statistics calculations/lifecycle, mute, disconnect")
            print("Phone signaling/capture is simulated; Android device validation is still required.")
            browser.close()
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
