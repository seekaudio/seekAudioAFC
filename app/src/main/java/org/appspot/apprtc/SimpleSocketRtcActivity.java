package org.appspot.apprtc;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleSocketRtcActivity extends Activity {
    private static final String TAG = "SimpleSocketRTC";
    private static final int SOCKET_PORT = 19999;
    /*private static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private static final String AUDIO_TRACK_ID = "ARDAMSa0";*/
    private static final String AUDIO_TRACK_ID = "audio1";
    private static final String VIDEO_TRACK_ID = "video1";

    // UI
    private EditText ipInput;
    private Button connectBtn;
    private TextView logView;
    private SurfaceViewRenderer localRenderer, remoteRenderer;

    // Socket
    private ServerSocket serverSocket;
    private Socket socket;
    private PrintWriter out;
    private OutputStream os;
    private BufferedReader in;
    private InputStream is;
    private Thread socketThread;

    // WebRTC
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private EglBase eglBase;
    private VideoTrack localVideoTrack;
    private VideoTrack remoteVideoTrack;
    private AudioTrack localAudioTrack;
    private Handler uiHandler;

    private final List<IceCandidate> pendingIceCandidates = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET}, 1);
        uiHandler = new Handler(Looper.getMainLooper());
        setupUI();
        initWebRTC();
        startServer();
    }

    private void setupUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        ipInput = new EditText(this);
        ipInput.setHint("输入对方IP");
        ipInput.setInputType(InputType.TYPE_CLASS_PHONE);
        root.addView(ipInput);

        connectBtn = new Button(this);
        connectBtn.setText("连接");
        root.addView(connectBtn);

        localRenderer = new SurfaceViewRenderer(this);
        remoteRenderer = new SurfaceViewRenderer(this);
        root.addView(localRenderer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400));
        root.addView(remoteRenderer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400));

        logView = new TextView(this);
        logView.setMinHeight(200);
        logView.setMovementMethod(ScrollingMovementMethod.getInstance());
        logView.setGravity(Gravity.BOTTOM);
        logView.setPadding(0, 10, 0, 10);
        root.addView(logView);
        textView = logView;

        setContentView(root);

        connectBtn.setOnClickListener(v -> {
            String ip = ipInput.getText().toString().trim();
            if (!ip.isEmpty()) {
                connectToPeer(ip);
            }
        });

        setupTextView();
    }

    private TextView textView;
    private ActionMode actionMode;
    private void setupTextView() {
        // 启用文本选择功能
        textView.setTextIsSelectable(true);

        // 设置长按监听器
        textView.setOnLongClickListener(v -> {

            // 启动自定义 ActionMode
            if (actionMode == null) {
                actionMode = startActionMode(actionModeCallback);
            }
            return true;
        });
    }

    // ActionMode 回调
    private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // 加载自定义菜单
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.textview_context_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.menu_save) {
                // 保存到本地
                saveTextToFile(textView.getText().toString());
                mode.finish();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
        }
    };

    // 保存文本到文件
    private void saveTextToFile(String text) {
        String fileName = "saved_text" /*+ System.currentTimeMillis()*/ + ".txt";
        try (FileOutputStream fos = openFileOutput(fileName, Context.MODE_PRIVATE)) { //在应用目录files/下
            fos.write(text.getBytes());
            Toast.makeText(this, "保存成功: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }


    private void log(String msg) {
        Log.d(TAG, msg);
        uiHandler.post(() -> logView.append(msg + "\n"));
    }

    // region Socket
    private void startServer() {
        socketThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(SOCKET_PORT);
                log("[Socket] 等待对方连接...");
                socket = serverSocket.accept();
                log("[Socket] 对方已连接: " + socket.getInetAddress());
                setupSocketStreams();
                listenSocket();
            } catch (IOException e) {
                log("[Socket] 服务器异常: " + e.getMessage());
            }
        });
        socketThread.start();
    }

    private void connectToPeer(String ip) {
        new Thread(() -> {
            try {
                socket = new Socket(ip, SOCKET_PORT);
                log("[Socket] 已连接到对方: " + ip);
                setupSocketStreams();
                listenSocket();
                // 主动方发起WebRTC协商
                uiHandler.post(this::createOffer);
            } catch (IOException e) {
                log("[Socket] 连接失败: " + e.getMessage());
            }
        }).start();
    }

    private void setupSocketStreams() throws IOException {
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        os = socket.getOutputStream();
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        is = socket.getInputStream();
    }

    private void listenSocket() {
        new Thread(() -> {
            try {
                /*StringBuilder buffer = new StringBuilder();
                char[] readBuf = new char[1024];
                int len;
                while ((len = in.read(readBuf)) != -1) {
                    buffer.append(readBuf, 0, len);
                    int idx;
                    // 处理所有完整的消息
                    while ((idx = buffer.indexOf("\n\n")) != -1) {
                        String msg = buffer.substring(0, idx);
                        buffer.delete(0, idx + 2); // 跳过分隔符
                        log("[Socket] 收到完整消息: " + msg);
                        handleSignalingMessage(msg);
                    }
                }*/

                while (!Thread.interrupted()) {
                    DataInputStream dis = new DataInputStream(is);
                    int msgLen = dis.readInt();
                    byte[] buffer = new byte[msgLen];
                    log("[socket]阻塞读取直至缓冲区（长度:" + msgLen + ")填满");
                    dis.readFully(buffer);
                    String msgStr = new String(buffer, StandardCharsets.UTF_8);
                    handleSignalingMessage(msgStr);
                }

            } catch (IOException e) {
                log("[Socket] 监听异常: " + e.getMessage());
            }
        }).start();
    }

    private void sendSignalingMessage(String msg) {
        log("[Socket] 发送: " + msg);
        out.print(msg + "\n\n"); // 用print而不是println，添加分隔符
        out.flush();
    }

    private void sendSignalingMessage(String type, String content) {
        log("[Socket] 发送: " + content);
        JSONObject obj = new JSONObject();
        try {
            obj.put("type", type);
            obj.put("content", content);
            DataOutputStream dos = new DataOutputStream(os);
            //序列化消息
            byte[] jsonBytes = obj.toString().getBytes(StandardCharsets.UTF_8);
            dos.writeInt(jsonBytes.length);
            dos.write(jsonBytes);
            out.flush();
        } catch (JSONException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    // endregion

    // region WebRTC
    private void initWebRTC() {
        eglBase = EglBase.create();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        );
        // Disable network monitor to enable
        // connection through Androids own hotspot.
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;

        /*boolean enableIntelVp8Encoder = true;
        boolean enableH264HighProfile = true;
        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), enableIntelVp8Encoder, enableH264HighProfile);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());*/

        VideoEncoderFactory encoderFactory = new SoftwareVideoEncoderFactory();
        VideoDecoderFactory decoderFactory = new SoftwareVideoDecoderFactory();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                //关键：要设置编解码工厂，否则即使回调了视频轨道，也是m=video 0(端口)（被拒绝）远端黑屏
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        createPeerConnection();
        localRenderer.init(eglBase.getEglBaseContext(), null);
        remoteRenderer.init(eglBase.getEglBaseContext(), null);
        log("[WebRTC]WebRTC相关初始化完成,factory: " + factory + ", pc: " + peerConnection + ", localRender: " + localRenderer + ", remoteRender: " + remoteRenderer);
    }

    /*private void createPeerConnection() { //也可以，但createPeerConnection没必要用三参构造
        // 初始化视频采集
        VideoCapturer capturer = createCameraCapturer();
        SurfaceTextureHelper surfaceHelper = getSurfaceTextureHelper();
        VideoSource videoSource = factory.createVideoSource(capturer.isScreencast());

        capturer.initialize(surfaceHelper, getApplicationContext(), videoSource.getCapturerObserver());
        capturer.startCapture(640, 480, 30); // 提高帧率

        // 创建视频轨道
        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        localVideoTrack.setEnabled(true);
        localVideoTrack.addSink(localRenderer);

        // 创建音频轨道
        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);

        // 配置PeerConnection
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;

        // 关键：设置视频编解码优先级
        MediaConstraints sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        peerConnection = factory.createPeerConnection(config, sdpConstraints,new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) { log(
                    "[WebRTC] 信令状态: " + signalingState);
            }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                log("[WebRTC] ICE连接状态: " + newState);
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    uiHandler.post(() -> {
                        log("[WebRTC] ICE连接已建立");
                        remoteRenderer.setVisibility(View.VISIBLE); // 确保渲染器可见
                    });
                }
            }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
                log("[WebRTC] ICE收集状态: " + newState);
            }
            @Override public void onIceCandidate(IceCandidate candidate) {
                log("[WebRTC] 生成ICE: " + candidate);
                //sendSignalingMessage("ice|" + candidate.sdpMid + "|" + candidate.sdpMLineIndex + "|" + candidate.sdp);
                JSONObject candidateJson = new JSONObject();
                try {
                    candidateJson.put("sdpMid", candidate.sdpMid);
                    candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    candidateJson.put("sdp", candidate.sdp);
                } catch (JSONException e) {
                    log("[WebRTC] onIceCandidate error: " + e.getMessage());
                    throw new RuntimeException(e);
                }

                sendSignalingMessage("candidate", candidateJson.toString());
            }
            @Override public void onAddStream(MediaStream stream) {
                log("[WebRTC] onAddStream 收到远端流" + stream.videoTracks.size() + "个");
                *//*if (stream.videoTracks.size() > 0) {
                    stream.videoTracks.get(0).addSink(remoteRenderer);
                }*//*
            }

            @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, MediaStream[] mediaStreams) {
                log("[WebRTC]收到远程轨道");

                // 获取媒体轨道
                MediaStreamTrack track = receiver.track();
                log("视频轨道状态：" + track.state() + ", enabled:" + track.enabled());
                log("支持的编解码：" + peerConnection.getTransceivers());
                if (track instanceof VideoTrack) {
                    log("收到远程视频轨道");
                    VideoTrack remoteVideoTrack = (VideoTrack) track;

                    runOnUiThread(() -> {
                        // 确保在主线程设置远程渲染器
                        remoteVideoTrack.addSink(remoteRenderer);
                        log("远程视频轨道已添加到渲染器");
                    });
                }
            }

            *//*@Override
            public void onTrack(RtpTransceiver transceiver) {
                if (transceiver.getReceiver().track() instanceof VideoTrack) {
                    runOnUiThread(() -> {
                        VideoTrack remoteVideoTrack = (VideoTrack) transceiver.getReceiver().track();
                        assert remoteVideoTrack != null;
                        remoteVideoTrack.addSink(remoteRenderer);
                        log("[WebRTC] 已将远端视频轨道addSink到remoteRenderer");

                    });
                }
            }*//*


            @Override public void onDataChannel(org.webrtc.DataChannel dc) {}
            @Override public void onRenegotiationNeeded() { log("[WebRTC] 需要重新协商"); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {}
            @Override public void onSelectedCandidatePairChanged(org.webrtc.CandidatePairChangeEvent event) {}
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState newState) {}

            @Override public void onIceConnectionReceivingChange(boolean receiving) { log(
                    "[WebRTC] onIceConnectionReceivingChange: " + receiving);
            }
        });
        // 添加媒体轨道
        peerConnection.addTrack(localVideoTrack, Collections.singletonList("stream1"));
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream1"));

        drainPendingIceCandidates();
    }*/


    private void createPeerConnection() {
        //List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        //config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        config.enableCpuOveruseDetection = false;
        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) { log(
                    "[WebRTC] 信令状态: " + signalingState);
            }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                log("[WebRTC] ICE连接状态: " + newState);
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    uiHandler.post(() -> {
                        log("[WebRTC] ICE连接已建立");
                        remoteRenderer.setVisibility(View.VISIBLE); // 确保渲染器可见
                    });
                }
            }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
                log("[WebRTC] ICE收集状态: " + newState);
            }
            @Override public void onIceCandidate(IceCandidate candidate) {
                log("[WebRTC] 生成ICE: " + candidate);
                //sendSignalingMessage("ice|" + candidate.sdpMid + "|" + candidate.sdpMLineIndex + "|" + candidate.sdp);
                JSONObject candidateJson = new JSONObject();
                try {
                    candidateJson.put("sdpMid", candidate.sdpMid);
                    candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    candidateJson.put("sdp", candidate.sdp);
                } catch (JSONException e) {
                    log("[WebRTC] onIceCandidate error: " + e.getMessage());
                    throw new RuntimeException(e);
                }

                sendSignalingMessage("candidate", candidateJson.toString());
            }
            @Override public void onAddStream(MediaStream stream) {
                log("[WebRTC] onAddStream 收到远端流");
                log(stream.videoTracks.size() + "个");
                if (stream.videoTracks.size() > 0) {
                    stream.videoTracks.get(0).addSink(remoteRenderer);
                }
            }

            @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, MediaStream[] mediaStreams) {
                log("[WebRTC]收到远程轨道");

                // 获取媒体轨道
                MediaStreamTrack track = receiver.track();

                if (track instanceof VideoTrack) {
                    log("收到远程视频轨道");
                    VideoTrack remoteVideoTrack = (VideoTrack) track;

                    runOnUiThread(() -> {
                        // 修复点2: 确保在主线程设置远程渲染器
                        remoteVideoTrack.addSink(remoteRenderer);
                        log("远程视频轨道已添加到渲染器");
                    });
                }
            }

            /*@Override
            public void onTrack(RtpTransceiver transceiver) {
                if (transceiver.getReceiver().track() instanceof VideoTrack) {
                    runOnUiThread(() -> {
                        VideoTrack remoteVideoTrack = (VideoTrack) transceiver.getReceiver().track();
                        assert remoteVideoTrack != null;
                        remoteVideoTrack.addSink(remoteRenderer);
                        log("[WebRTC] 已将远端视频轨道addSink到remoteRenderer");

                    });
                }
            }*/


            @Override public void onDataChannel(org.webrtc.DataChannel dc) {}
            @Override public void onRenegotiationNeeded() { log("[WebRTC] 需要重新协商"); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {}
            @Override public void onSelectedCandidatePairChanged(org.webrtc.CandidatePairChangeEvent event) {}
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState newState) {}

            @Override public void onIceConnectionReceivingChange(boolean receiving) { log(
                    "[WebRTC] onIceConnectionReceivingChange: " + receiving);
            }
        });

        // 本地视频
        VideoCapturer capturer = createCameraCapturer();
        VideoSource videoSource = factory.createVideoSource(capturer.isScreencast());
        capturer.initialize(getSurfaceTextureHelper(), this, videoSource.getCapturerObserver());
        capturer.startCapture(640, 480, 15);
        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        localVideoTrack.addSink(localRenderer);

        // 本地音频
        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);

        /*// 废弃的 addStream 方式, 跟编解码工厂一起出现会闪退：E  initFreeFormResolutionArgs failed, device is marble
        MediaStream stream = factory.createLocalMediaStream("ARDAMS");
        stream.addTrack(localVideoTrack);
        stream.addTrack(localAudioTrack);
        peerConnection.addStream(stream);
        peerConnection.addTrack(localVideoTrack, Collections.singletonList("ARDAMS"));
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("ARDAMS"));*/

        //peerConnection.addTransceiver(localVideoTrack, new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV));
        //peerConnection.addTransceiver(localAudioTrack, new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV));
        peerConnection.addTrack(localVideoTrack, Collections.singletonList("stream1"));
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream1"));
        drainPendingIceCandidates();
    }


    // 获取SurfaceTextureHelper（简化实现）
    private SurfaceTextureHelper getSurfaceTextureHelper() {
        return SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
    }

    private VideoCapturer createCameraCapturer() {
        Camera1Enumerator enumerator = new Camera1Enumerator(false);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                //return enumerator.createCapturer(name, null);
                VideoCapturer capturer = enumerator.createCapturer(name, null);
                if (capturer != null) {
                    log("使用前置摄像头: " + name);
                    return capturer;
                }
            }
        }
        throw new RuntimeException("没有可用摄像头");
    }

    private void createOffer() {
        //if (peerConnection == null) createPeerConnection();
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        constraints.optional.add(new MediaConstraints.KeyValuePair("googCpuOveruseDetection", "false"));

        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                log("[WebRTC] Offer创建成功");
                peerConnection.setLocalDescription(this, sdp);
                // 发送完整的 SDP 消息
                //sendSignalingMessage("offer|" + sdp.description);
                sendSignalingMessage("offer", sdp.description);
                log("[WebRTC]发送offer sdp: [" + sdp.description + "]");
            }
            @Override public void onSetSuccess() { log("[WebRTC] setLocalDescription成功"); }
            @Override public void onCreateFailure(String error) { log("[WebRTC] Offer创建失败: " + error); }
            @Override public void onSetFailure(String error) { log("[WebRTC] setLocalDescription失败: " + error); }
        }, constraints);
    }

    private void createAnswer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        peerConnection.createAnswer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                log("[WebRTC] Answer创建成功");
                /*peerConnection.setLocalDescription(this, sdp);
                // 发送完整的 SDP 消息
                //sendSignalingMessage("answer|" + sdp.description);
                sendSignalingMessage("answer", sdp.description);*/
                // 强制修改SDP为sendrecv模式
                String modifiedSdp = sdp.description.replace(
                        "a=inactive", "a=sendrecv").replace(
                        "a=recvonly", "a=sendrecv");
                SessionDescription modifiedDesc = new SessionDescription(
                        sdp.type, modifiedSdp);
                peerConnection.setLocalDescription(this, modifiedDesc);
                sendSignalingMessage("answer", modifiedSdp);
            }
            @Override public void onSetSuccess() { log("[WebRTC] setLocalDescription成功"); }
            @Override public void onCreateFailure(String error) { log("[WebRTC] Answer创建失败: " + error); }
            @Override public void onSetFailure(String error) { log("[WebRTC] setLocalDescription失败: " + error); }
        }, constraints);
    }

    private void handleSignalingMessage(String msg) {
        /*// 消息格式: type|...  例如 offer|sdp, answer|sdp, ice|mid|mline|sdp
        String[] parts = msg.split("\\|", 2);
        if (parts.length < 2) return;
        String type = parts[0];
        String content = parts[1];*/
        String type = "unknown";
        var ref = new Object() {
            String content = "";
        };
        try {
            JSONObject object = new JSONObject(msg);
            type = object.optString("type");
            ref.content = object.optString("content");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        
        log("[Socket] 处理消息类型: " + type + ", 内容长度: " + ref.content.length());
        
        switch (type) {
            case "offer":
                uiHandler.post(() -> {
                    //if (peerConnection == null) createPeerConnection();
                    SessionDescription sdp = new SessionDescription(SessionDescription.Type.OFFER, ref.content);
                    log("[WebRTC]setRemoteDescription-offer, sdp: [" + ref.content + "]");
                    peerConnection.setRemoteDescription(new SimpleSdpObserver("setRemoteDescription-offer"), sdp);
                });
                break;
            case "answer":
                uiHandler.post(() -> {
                    SessionDescription sdp = new SessionDescription(SessionDescription.Type.ANSWER, ref.content);
                    log("[WebRTC]setRemoteDescription-answer, sdp: [" + ref.content + "]");
                    peerConnection.setRemoteDescription(new SimpleSdpObserver("setRemoteDescription-answer"), sdp);
                });
                break;
            case "candidate":
                //String[] iceParts = ref.content.split("\\|", 3);
                //if (iceParts.length == 3) {
                //    IceCandidate candidate = new IceCandidate(iceParts[0], Integer.parseInt(iceParts[1]), iceParts[2]);
                try {
                    JSONObject candidateJson = new JSONObject(ref.content);
                    IceCandidate candidate = new IceCandidate(candidateJson.optString("sdpMid"),
                            candidateJson.optInt("sdpMLineIndex"),
                            candidateJson.optString("sdp"));
                    if (peerConnection != null) {
                        /*peerConnection.addIceCandidate(candidate);
                        log("[WebRTC]addIceCandidate");
                    } else {*/
                        pendingIceCandidates.add(candidate);
                        log("[WebRTC]缓存Candidate");
                    }
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                //}
                break;
        }
    }

    private void drainPendingIceCandidates() {
        if (peerConnection != null && !pendingIceCandidates.isEmpty()) {
            for (IceCandidate c : pendingIceCandidates) {
                peerConnection.addIceCandidate(c);
                log("[WebRTC]#drainPendingIceCandidates addIceCandidate done");
            }
            pendingIceCandidates.clear();
        }
    }
    // endregion

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (peerConnection != null) peerConnection.close();
        if (localRenderer != null) localRenderer.release();
        if (remoteRenderer != null) remoteRenderer.release();
        if (eglBase != null) eglBase.release();
    }

    // 简化版SdpObserver，带日志
    private  class SimpleSdpObserver implements SdpObserver {
        private final String tag;
        SimpleSdpObserver(String tag) { this.tag = tag; }
        @Override public void onCreateSuccess(SessionDescription sdp) {
            log("[WebRTC]" + tag + " onCreateSuccess");
        }
        @Override public void onSetSuccess() {
            log("[WebRTC]" + tag + " onSetSuccess");
            drainPendingIceCandidates();
            //只有setRemoteDescription成功后且只有收到offer时才createAnswer
            if (tag.equals("setRemoteDescription-offer")) {
                createAnswer();
            }
        }
        @Override public void onCreateFailure(String error) {
            log("[WebRTC]" + tag + " onCreateFailure: " + error);
            Log.e(TAG, "[WebRTC]" + tag + " onCreateFailure: " + error);
        }
        @Override public void onSetFailure(String error) {
            log("[WebRTC]" + tag + " onSetFailure: " + error);
            Log.e(TAG, "[WebRTC]" + tag + " onSetFailure: " + error);
        }
    }
} 