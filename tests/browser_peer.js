// Browser smoke-test peer. Actual RTCPeerConnection/video/audio, simulated phone signaling.
window.__test = {inputs:[], audioPackets:0, offers:[]};
class PhoneSocket {
  static OPEN = 1;
  constructor() {
    this.readyState = 0; this.pending = [];
    this.generation = 1;
    this.geometry = {device:'Simulated phone',width:1200,height:2670,generation:1};
    window.__test.socket = this;
    setTimeout(() => {
      this.readyState = 1; this.onopen?.();
      this.emit({type:'ready',device:this.geometry,mediaGeneration:this.generation});
    }, 50);
  }
  emit(message) { this.onmessage?.({data:JSON.stringify(message)}); }
  async send(text) {
    const message = JSON.parse(text);
    if (message.type === 'offer') {
      if (message.mediaGeneration !== this.generation) return;
      window.__test.offers.push(message);
      const generation = this.generation;
      this.peer = new RTCPeerConnection({iceServers:[]});
      const peer = this.peer;
      window.__test.peer = this.peer;
      this.peer.onicecandidate = event => { if (event.candidate) this.emit({type:'candidate',candidate:event.candidate.toJSON(),mediaGeneration:generation}); };
      this.peer.ondatachannel = event => { event.channel.onmessage = e => {
        const input = JSON.parse(e.data); window.__test.inputs.push(input);
        if (input.type === 'screen' && event.channel.label === 'control') {
          this.geometry.screenOff = input.off;
          this.emit({type:'screen',off:input.off});
        }
      }; };
      await peer.setRemoteDescription({type:'offer',sdp:message.sdp});
      if (peer !== this.peer) return;
      for (const candidate of this.pending) await peer.addIceCandidate(candidate);
      this.pending = [];
      if (peer !== this.peer) return;
      const canvas = document.createElement('canvas');
      canvas.width=this.geometry.width > this.geometry.height ? 800 : 360;
      canvas.height=this.geometry.width > this.geometry.height ? 360 : 800;
      const context=canvas.getContext('2d'); let count=0;
      this.paint = setInterval(() => {
        context.fillStyle='#176a54'; context.fillRect(0,0,canvas.width,canvas.height);
        context.fillStyle='white'; context.font='32px sans-serif'; context.fillText(`Phone ${++count}`,40,160);
      },33);
      const stream=canvas.captureStream(30); this.stream=stream;
      peer.addTrack(stream.getVideoTracks()[0],stream);
      const answer=await peer.createAnswer();
      if (peer !== this.peer) return;
      await peer.setLocalDescription(answer);
      if (peer !== this.peer) return;
      this.emit({type:'answer',sdp:peer.localDescription.sdp,mediaGeneration:generation});
    } else if (message.type === 'candidate') {
      if (message.mediaGeneration !== this.generation) return;
      if (this.peer?.remoteDescription) await this.peer.addIceCandidate(message.candidate); else this.pending.push(message.candidate);
    } else if (message.type === 'audio') {
      clearInterval(this.audio);
      if (message.enabled) this.audio=setInterval(() => {
        const pcm=new Int16Array(1920);
        for(let i=0;i<960;i++) pcm[i*2]=pcm[i*2+1]=Math.sin(i*2*Math.PI*440/48000)*1000;
        window.__test.audioPackets++; this.onmessage?.({data:pcm.buffer});
      },20);
    } else if (message.type === 'ping') {
      this.emit({type:'pong'});
    }
    else if (message.type === 'disconnect') this.close();
  }
  close() {
    this.readyState=3; clearInterval(this.audio); this.stopMedia();
  }
  stopMedia() {
    clearInterval(this.paint);
    this.stream?.getTracks().forEach(track=>track.stop()); this.peer?.close();
    this.peer = null; this.pending = [];
  }
  rotate(width,height) {
    this.generation++;
    this.emit({type:'reconfigure',mediaGeneration:this.generation});
    this.stopMedia();
    this.geometry = {...this.geometry,width,height,generation:this.generation};
    this.emit({type:'ready',device:this.geometry,mediaGeneration:this.generation});
  }
}
window.WebSocket = PhoneSocket;
