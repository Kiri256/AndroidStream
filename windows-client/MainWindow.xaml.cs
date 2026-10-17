using System.Net.WebSockets;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using NAudio.Wave;
using SIPSorceryMedia.FFmpeg;

namespace AndroidStream.Client;

public partial class MainWindow : Window
{
    private PhoneSession? session;
    private CancellationTokenSource? cancellation;
    private WriteableBitmap? bitmap;
    private PhoneSession.VideoFrame? queuedFrame;
    private Point? queuedMove;
    private int activeGesture;
    private bool pointerDown, closing;
    private volatile bool playAudio;
    private int frameCallbackQueued;
    private readonly DispatcherTimer moveTimer = new() { Interval = TimeSpan.FromMilliseconds(16) };
    private readonly DispatcherTimer heartbeatTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly DispatcherTimer statsTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private WaveOutEvent? audioOut;
    private BufferedWaveProvider? audioBuffer;

    public MainWindow()
    {
        InitializeComponent();
        moveTimer.Tick += (_, _) => FlushMove();
        moveTimer.Start();
        heartbeatTimer.Tick += (_, _) => session?.SendControl(new { type = "heartbeat" });
        heartbeatTimer.Start();
        statsTimer.Tick += (_, _) => UpdateStats();
        statsTimer.Start();
        Closing += WindowClosing;
    }

    private async void ConnectClick(object sender, RoutedEventArgs e)
    {
        if (!Uri.TryCreate(Address.Text.Trim(), UriKind.Absolute, out var uri)
            || uri.Scheme != "ws" || string.IsNullOrWhiteSpace(uri.Host))
        {
            Status.Text = "请输入手机地址，例如 ws://192.168.1.2:8080/ws";
            return;
        }
        try
        {
            FFmpegInit.Initialise();
            var selected = (Codec.SelectedItem as System.Windows.Controls.ComboBoxItem)?.Tag?.ToString() ?? "H264";
            session = new PhoneSession(selected);
            session.StatusChanged += text => Dispatcher.BeginInvoke(() => Status.Text = text);
            session.Error += text => Dispatcher.BeginInvoke(() => Status.Text = text);
            session.FrameReceived += ShowFrame;
            session.AudioReceived += PlayAudio;
            session.Ready += () => Dispatcher.BeginInvoke(EnableRequestedAudio);
            cancellation = new CancellationTokenSource();
            ConnectButton.IsEnabled = false; DisconnectButton.IsEnabled = true;
            _ = Task.Run(async () =>
            {
                try { await session.ConnectAsync(uri.ToString(), cancellation.Token); }
                catch (OperationCanceledException) { }
                catch (Exception ex) { await Dispatcher.InvokeAsync(() => Status.Text = ex.Message); }
            });
        }
        catch (Exception ex)
        {
            Status.Text = "客户端初始化失败：" + ex.Message;
            await StopSession();
        }
    }

    private async void DisconnectClick(object sender, RoutedEventArgs e) => await StopSession();

    private async Task StopSession()
    {
        cancellation?.Cancel();
        cancellation?.Dispose(); cancellation = null;
        if (session != null) await session.DisposeAsync();
        session = null;
        pointerDown = false; queuedMove = null;
        Interlocked.Exchange(ref queuedFrame, null)?.Dispose();
        StopAudio();
        ScreenImage.Source = null; bitmap = null;
        ConnectButton.IsEnabled = true; DisconnectButton.IsEnabled = false;
        Status.Text = "已断开";
    }

    private void ShowFrame(PhoneSession.VideoFrame frame)
    {
        Interlocked.Exchange(ref queuedFrame, frame)?.Dispose();
        if (Interlocked.Exchange(ref frameCallbackQueued, 1) != 0) return;
        Dispatcher.BeginInvoke(RenderLatestFrame, DispatcherPriority.Render);
    }

    private void RenderLatestFrame()
    {
        var frame = Interlocked.Exchange(ref queuedFrame, null);
        if (frame != null && !closing)
        {
            try
            {
                if (bitmap == null || bitmap.PixelWidth != frame.Width || bitmap.PixelHeight != frame.Height)
                {
                    bitmap = new WriteableBitmap(frame.Width, frame.Height, 96, 96, PixelFormats.Bgr24, null);
                    ScreenImage.Source = bitmap;
                }
                bitmap.WritePixels(new Int32Rect(0, 0, frame.Width, frame.Height), frame.Pixels,
                    frame.Size, frame.Stride);
            }
            finally { frame.Dispose(); }
        }
        else frame?.Dispose();
        Interlocked.Exchange(ref frameCallbackQueued, 0);
        if (Volatile.Read(ref queuedFrame) != null && Interlocked.Exchange(ref frameCallbackQueued, 1) == 0)
            Dispatcher.BeginInvoke(RenderLatestFrame, DispatcherPriority.Render);
    }

    private void AudioChanged(object sender, RoutedEventArgs e)
    {
        if (session == null) return;
        bool enabled = AudioEnabled.IsChecked == true;
        playAudio = enabled;
        _ = session.SetAudio(enabled, cancellation?.Token ?? CancellationToken.None);
        if (enabled) StartAudio(); else StopAudio();
    }

    private async void EnableRequestedAudio()
    {
        if (AudioEnabled.IsChecked != true || session == null) return;
        playAudio = true;
        StartAudio();
        await session.SetAudio(true, cancellation?.Token ?? CancellationToken.None);
    }

    private void StartAudio()
    {
        try
        {
            if (audioOut != null) return;
            audioBuffer = new BufferedWaveProvider(new WaveFormat(48000, 16, 2))
            {
                BufferDuration = TimeSpan.FromMilliseconds(120),
                DiscardOnBufferOverflow = true
            };
            audioOut = new WaveOutEvent { DesiredLatency = 40 };
            audioOut.Init(audioBuffer); audioOut.Play();
        }
        catch (Exception ex) { Status.Text = "音频初始化失败：" + ex.Message; StopAudio(); }
    }

    private void PlayAudio(byte[] pcm)
    {
        if (!playAudio || audioBuffer == null || pcm.Length == 0 || pcm.Length % 4 != 0) return;
        if (audioBuffer.BufferedBytes > 48000 * 4 / 5) audioBuffer.ClearBuffer();
        audioBuffer.AddSamples(pcm, 0, pcm.Length);
    }

    private void UpdateStats()
    {
        if (session == null) { Details.Text = ""; return; }
        var counters = session.TakeVideoCounters();
        Details.Text = $"{(Codec.SelectedItem as System.Windows.Controls.ComboBoxItem)?.Content} · 帧载荷 {counters.Bytes * 8.0 / 1_000_000:F1} Mbps · 显示 {counters.Frames} FPS";
    }

    private void StopAudio()
    {
        try { audioOut?.Stop(); } catch { }
        audioOut?.Dispose(); audioOut = null; audioBuffer = null;
    }

    private void NavClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string key } && int.TryParse(key, out int code))
            session?.SendControl(new { type = "key", key = code });
    }

    private async void ScreenPowerClick(object sender, RoutedEventArgs e)
    {
        if (session == null) return;
        bool off = sender is not FrameworkElement { Tag: "on" };
        try
        {
            await session.SetScreenPower(off, cancellation?.Token ?? CancellationToken.None);
            Status.Text = off ? "已请求关闭手机屏幕" : "已请求点亮手机屏幕";
        }
        catch (Exception ex) { Status.Text = "屏幕控制失败：" + ex.Message; }
    }

    private void ScreenMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left || pointerDown || session == null) return;
        var point = GetNormalized(e.GetPosition(ScreenImage));
        if (point == null) return;
        pointerDown = true; activeGesture = session.NextGesture(); queuedMove = null;
        ScreenImage.Focus(); Mouse.Capture(ScreenImage); e.Handled = true;
        SendTouch("down", point.Value, control: true);
    }

    private void ScreenMouseMove(object sender, MouseEventArgs e)
    {
        if (!pointerDown) return;
        queuedMove = GetNormalized(e.GetPosition(ScreenImage));
    }

    private void ScreenMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left || !pointerDown) return;
        var point = GetNormalized(e.GetPosition(ScreenImage));
        queuedMove = null;
        SendTouch("up", point ?? new Point(.5, .5), control: true);
        pointerDown = false; Mouse.Capture(null); e.Handled = true;
    }

    private void ScreenLostCapture(object sender, MouseEventArgs e)
    {
        if (!pointerDown) return;
        pointerDown = false; queuedMove = null;
        session?.SendControl(new { type = "cancelAll" });
    }

    private void FlushMove()
    {
        if (pointerDown && queuedMove is { } point)
        {
            queuedMove = null;
            SendTouch("move", point, control: false);
        }
    }

    private void SendTouch(string action, Point point, bool control)
    {
        if (session == null) return;
        var message = new
        {
            type = "touch", action, gesture = activeGesture, seq = session.NextSequence(),
            generation = session.DisplayGeneration, x = point.X, y = point.Y
        };
        if (control) session.SendControl(message); else session.SendMotion(message);
    }

    private Point? GetNormalized(Point point)
    {
        if (bitmap == null || ScreenImage.ActualWidth <= 0 || ScreenImage.ActualHeight <= 0) return null;
        double scale = Math.Min(ScreenImage.ActualWidth / bitmap.PixelWidth, ScreenImage.ActualHeight / bitmap.PixelHeight);
        double width = bitmap.PixelWidth * scale, height = bitmap.PixelHeight * scale;
        double x = (point.X - (ScreenImage.ActualWidth - width) / 2) / width;
        double y = (point.Y - (ScreenImage.ActualHeight - height) / 2) / height;
        return x < 0 || x > 1 || y < 0 || y > 1 ? null : new Point(x, y);
    }

    private void ScreenKeyDown(object sender, KeyEventArgs e)
    {
        int? key = e.Key switch
        {
            Key.Escape => 4, Key.Back => 4, Key.Enter => 66, Key.Tab => 61,
            Key.Up => 19, Key.Down => 20, Key.Left => 21, Key.Right => 22, Key.Space => 62,
            _ => null
        };
        if (key == null && (int)e.Key >= (int)Key.A && (int)e.Key <= (int)Key.Z)
            key = 29 + (int)e.Key - (int)Key.A;
        if (key == null && (int)e.Key >= (int)Key.D0 && (int)e.Key <= (int)Key.D9)
            key = 7 + (int)e.Key - (int)Key.D0;
        if (e.Key == Key.Back) key = 67;
        if (key is { } code) { session?.SendControl(new { type = "key", key = code }); e.Handled = true; }
    }

    private void ScreenWheel(object sender, MouseWheelEventArgs e)
    {
        if (session == null || pointerDown) return;
        var point = GetNormalized(e.GetPosition(ScreenImage));
        if (point == null) return;
        e.Handled = true;
        int gestureId = session.NextGesture();
        SendTouch("down", point.Value, true, gestureId);
        int steps = Math.Clamp(Math.Abs(e.Delta) / 60, 1, 8);
        double delta = Math.Sign(e.Delta) * Math.Clamp(Math.Abs(e.Delta) / 1200.0, .08, .3);
        for (int i = 1; i <= steps; i++)
        {
            var next = new Point(point.Value.X, Math.Clamp(point.Value.Y - delta * i / steps, .01, .99));
            SendTouch(i == steps ? "up" : "move", next, i == steps, gestureId);
        }
    }

    private void SendTouch(string action, Point point, bool control, int? gestureOverride = null)
    {
        int old = activeGesture;
        if (gestureOverride.HasValue) activeGesture = gestureOverride.Value;
        SendTouch(action, point, control);
        activeGesture = old;
    }

    private async void WindowClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (closing) return;
        e.Cancel = true;
        closing = true; moveTimer.Stop(); heartbeatTimer.Stop(); statsTimer.Stop();
        await StopSession();
        Close();
    }
}
