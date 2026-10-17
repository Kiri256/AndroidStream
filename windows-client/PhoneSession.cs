using System.Net.WebSockets;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Buffers;
using FFmpeg.AutoGen;
using SIPSorcery.Net;
using SIPSorceryMedia.Abstractions;
using SIPSorceryMedia.FFmpeg;

namespace AndroidStream.Client;

internal sealed class PhoneSession : IAsyncDisposable
{
    private readonly ClientWebSocket socket = new();
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly List<RTCIceCandidateInit> queuedCandidates = [];
    private readonly string codec;
    private RTCPeerConnection? peer;
    private RTCDataChannel? control, motion;
    private FFmpegVideoEncoder? decoder;
    private bool remoteReady, closed;
    private int sequence, gesture;
    private bool offerSent;
    private readonly List<RTCIceCandidate> localCandidates = [];
    private long receivedBytes;
    private long decodedFrames;
    private long pingAt;

    public event Action<string>? StatusChanged;
    public event Action<string>? Error;
    public event Action<VideoFrame>? FrameReceived;
    public event Action<byte[]>? AudioReceived;
    public event Action? Ready;

    public sealed class VideoFrame(byte[] pixels, int width, int height, int stride, int size) : IDisposable
    {
        public byte[] Pixels { get; } = pixels;
        public int Width { get; } = width;
        public int Height { get; } = height;
        public int Stride { get; } = stride;
        public int Size { get; } = size;
        public void Dispose() => ArrayPool<byte>.Shared.Return(Pixels);
    }
    public int Generation { get; private set; }
    public int DisplayGeneration { get; private set; }
    public int Width { get; private set; }
    public int Height { get; private set; }
    public (long Bytes, long Frames) TakeVideoCounters() =>
        (Interlocked.Exchange(ref receivedBytes, 0), Interlocked.Exchange(ref decodedFrames, 0));

    public PhoneSession(string codec) => this.codec = codec;

    public async Task ConnectAsync(string address, CancellationToken cancellation)
    {
        await socket.ConnectAsync(new Uri(address), cancellation);
        StatusChanged?.Invoke("正在连接手机");
        _ = Task.Run(() => ReadLoop(cancellation), cancellation);
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(3));
        while (!closed && socket.State == WebSocketState.Open && !cancellation.IsCancellationRequested)
        {
            await SendJson(new { type = "ping" }, cancellation);
            await timer.WaitForNextTickAsync(cancellation);
        }
    }

    private async Task ReadLoop(CancellationToken cancellation)
    {
        var buffer = new byte[64 * 1024];
        try
        {
            while (!closed && socket.State == WebSocketState.Open && !cancellation.IsCancellationRequested)
            {
                using var data = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancellation);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    data.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Binary)
                {
                    AudioReceived?.Invoke(data.ToArray());
                    continue;
                }

                using var json = JsonDocument.Parse(data.ToArray());
                var root = json.RootElement;
                var type = root.GetProperty("type").GetString();
                switch (type)
                {
                    case "ready":
                        Generation = root.GetProperty("mediaGeneration").GetInt32();
                        var device = root.GetProperty("device");
                        Width = device.GetProperty("width").GetInt32();
                        Height = device.GetProperty("height").GetInt32();
                        DisplayGeneration = device.GetProperty("generation").GetInt32();
                        await StartRtc(cancellation);
                        Ready?.Invoke();
                        break;
                    case "answer":
                        if (peer == null || root.TryGetProperty("mediaGeneration", out var answerGeneration)
                            && answerGeneration.GetInt32() != Generation) break;
                        peer.setRemoteDescription(new RTCSessionDescriptionInit
                        {
                            type = RTCSdpType.answer,
                            sdp = root.GetProperty("sdp").GetString()
                        });
                        remoteReady = true;
                        foreach (var candidate in queuedCandidates) peer.addIceCandidate(candidate);
                        queuedCandidates.Clear();
                        break;
                    case "candidate":
                        if (root.TryGetProperty("mediaGeneration", out var candidateGeneration)
                            && candidateGeneration.GetInt32() != Generation) break;
                        var c = root.GetProperty("candidate");
                        var ice = new RTCIceCandidateInit
                        {
                            candidate = c.GetProperty("candidate").GetString(),
                            sdpMid = c.TryGetProperty("sdpMid", out var mid) ? mid.GetString() : null,
                            sdpMLineIndex = c.TryGetProperty("sdpMLineIndex", out var index) ? checked((ushort)index.GetInt32()) : (ushort)0
                        };
                        if (remoteReady) peer?.addIceCandidate(ice);
                        else if (queuedCandidates.Count < 64) queuedCandidates.Add(ice);
                        break;
                    case "reconfigure":
                        StatusChanged?.Invoke("手机显示模式变化，正在重连");
                        break;
                    case "screen":
                        StatusChanged?.Invoke(root.GetProperty("off").GetBoolean()
                            ? "手机屏幕已请求关闭" : "手机屏幕已恢复");
                        break;
                    case "error":
                        Error?.Invoke(root.GetProperty("message").GetString() ?? "手机服务错误");
                        break;
                    case "pong":
                        pingAt = Environment.TickCount64;
                        break;
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { if (!closed) Error?.Invoke("信令连接失败：" + ex.Message); }
    }

    private async Task StartRtc(CancellationToken cancellation)
    {
        if (peer != null) await CloseRtc();
        remoteReady = false;
        offerSent = false;
        localCandidates.Clear();
        queuedCandidates.Clear();
        peer = new RTCPeerConnection(new RTCConfiguration { iceServers = [] });
        var current = peer;
        var format = codec == "H265"
            ? new VideoFormat(96, "H265", 90000, "profile-id=1;level-id=186;tx-mode=SRST")
            : new VideoFormat(96, "H264", 90000, "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e034");
        current.addTrack(new MediaStreamTrack(format, MediaStreamStatusEnum.RecvOnly));
        current.OnVideoFrameReceived += (remote, timestamp, frame, videoFormat) => DecodeFrame(frame);
        current.onicecandidate += candidate =>
        {
            if (candidate == null || closed) return;
            if (!offerSent) localCandidates.Add(candidate);
            else _ = SendCandidate(candidate, cancellation);
        };
        current.onconnectionstatechange += state => StatusChanged?.Invoke("WebRTC：" + state);
        control = await current.createDataChannel("control", new RTCDataChannelInit { ordered = true });
        motion = await current.createDataChannel("motion", new RTCDataChannelInit { ordered = false, maxRetransmits = 0 });
        var offer = current.createOffer(null);
        await current.setLocalDescription(offer);
        await SendJson(new { type = "offer", videoCodec = codec, mediaGeneration = Generation, sdp = current.localDescription.sdp }, cancellation);
        offerSent = true;
        foreach (var candidate in localCandidates) await SendCandidate(candidate, cancellation);
        localCandidates.Clear();
        StatusChanged?.Invoke("正在协商 " + codec);
    }

    private Task SendCandidate(RTCIceCandidate candidate, CancellationToken cancellation) => SendJson(new
    {
        type = "candidate", mediaGeneration = Generation,
        candidate = new { candidate = candidate.candidate, sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex }
    }, cancellation);

    private void DecodeFrame(byte[] encoded)
    {
        if (closed) return;
        Interlocked.Add(ref receivedBytes, encoded.Length);
        try
        {
            decoder ??= new FFmpegVideoEncoder(HWDeviceType: AVHWDeviceType.AV_HWDEVICE_TYPE_D3D11VA);
            var format = Enum.Parse<VideoCodecsEnum>(codec, ignoreCase: true);
            foreach (var image in decoder.DecodeVideoFaster(encoded, VideoPixelFormatsEnum.Bgr, format))
            {
                Interlocked.Increment(ref decodedFrames);
                var pixels = ArrayPool<byte>.Shared.Rent(image.BufferSize);
                int size;
                try { size = image.CopyTo(pixels); }
                catch { ArrayPool<byte>.Shared.Return(pixels); throw; }
                var frame = new VideoFrame(pixels, image.Width, image.Height, image.Stride, size);
                if (FrameReceived == null) frame.Dispose(); else FrameReceived.Invoke(frame);
            }
        }
        catch (Exception ex) { Error?.Invoke("D3D11VA " + codec + " 解码失败：" + ex.Message); }
    }

    private async Task SendJson(object value, CancellationToken cancellation = default)
    {
        if (socket.State != WebSocketState.Open) return;
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value);
        await sendLock.WaitAsync(cancellation);
        try { await socket.SendAsync(bytes, WebSocketMessageType.Text, true, cancellation); }
        finally { sendLock.Release(); }
    }

    public bool SendControl(object value) => SendChannel(control, value);
    public bool SendMotion(object value) => SendChannel(motion, value);
    private static bool SendChannel(RTCDataChannel? channel, object value)
    {
        if (channel == null || channel.readyState != RTCDataChannelState.open) return false;
        channel.send(JsonSerializer.Serialize(value));
        return true;
    }

    public int NextSequence() => Interlocked.Increment(ref sequence);
    public int NextGesture() => Interlocked.Increment(ref gesture);
    public Task SetAudio(bool enabled, CancellationToken cancellation) => SendJson(new { type = "audio", enabled }, cancellation);
    public Task SetScreenPower(bool off, CancellationToken cancellation) =>
        SendJson(new { type = "screen", off }, cancellation);

    private async Task CloseRtc()
    {
        remoteReady = false;
        control = motion = null;
        if (peer != null) { peer.Close("client closed"); peer.Dispose(); peer = null; }
        decoder?.Dispose(); decoder = null;
        await Task.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        if (closed) return;
        closed = true;
        try { await SendJson(new { type = "disconnect" }); } catch { }
        await CloseRtc();
        if (socket.State == WebSocketState.Open) await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "closed", CancellationToken.None);
        socket.Dispose(); sendLock.Dispose();
    }
}
