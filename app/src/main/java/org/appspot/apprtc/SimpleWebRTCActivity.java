package org.appspot.apprtc;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SimpleWebRTCActivity extends Activity {
    private static final String TAG = "WebRTC-Demo";
    private static final int PORT = 18888;
    private static final int REQUEST_PERMISSIONS = 1;
    
    // UI 元素
    private EditText ipEditText;
    private Button callButton, hangupButton;
    private TextView statusTextView;
    private FrameLayout localVideoContainer, remoteVideoContainer;
    
    // WebRTC 组件
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideoTrack;
    private SurfaceViewRenderer localRenderer, remoteRenderer;
    private EglBase eglBase;
    
    // 网络通信
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Socket signalingSocket;
    private ServerSocket serverSocket;
    private BufferedReader reader;
    private PrintWriter writer;
    
    // 状态管理
    private boolean isInitiator = false;
    private boolean isCallActive = false;
    private String remoteIp = "";
    
    // 需要申请的权限
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webrtc);
        
        // 初始化UI
        initUI();
        
        // 检查并申请权限
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        } else {
            initializeWebRTC();
        }
        
        // 启动监听服务（等待来电）
        startSignalingServer();
    }

    // 初始化UI
    private void initUI() {
        ipEditText = findViewById(R.id.ipEditText);
        callButton = findViewById(R.id.callButton);
        hangupButton = findViewById(R.id.hangupButton);
        statusTextView = findViewById(R.id.statusTextView);
        localVideoContainer = findViewById(R.id.localVideoContainer);
        remoteVideoContainer = findViewById(R.id.remoteVideoContainer);
        
        callButton.setOnClickListener(v -> startCall());
        hangupButton.setOnClickListener(v -> hangup());
        
        // 初始化视频渲染器
        eglBase = EglBase.create();
        localRenderer = new SurfaceViewRenderer(this);
        remoteRenderer = new SurfaceViewRenderer(this);
        
        localVideoContainer.addView(localRenderer);
        remoteVideoContainer.addView(remoteRenderer);
        
        localRenderer.init(eglBase.getEglBaseContext(), null);
        remoteRenderer.init(eglBase.getEglBaseContext(), null);
        localRenderer.setMirror(true);
        localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        
        updateUIState(false);
    }

    // 检查权限
    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                initializeWebRTC();
            } else {
                Toast.makeText(this, "需要所有权限才能使用音视频通话", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    // 初始化WebRTC
    private void initializeWebRTC() {
        Log.d(TAG, "初始化WebRTC...");
        
        // 初始化PeerConnectionFactory
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions());
        
        // 创建PeerConnectionFactory
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(),
                        true,  // 启用硬件编码
                        true)) // 启用H.264
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        
        Log.d(TAG, "PeerConnectionFactory 初始化完成");
    }

    // 启动信令服务器（监听来电）
    private void startSignalingServer() {
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "信令服务器已启动，监听端口: " + PORT);
                
                while (!Thread.interrupted()) {
                    Socket clientSocket = serverSocket.accept();
                    String remoteAddress = clientSocket.getInetAddress().getHostAddress();
                    Log.d(TAG, "收到来自 " + remoteAddress + " 的来电连接");
                    
                    // 拒绝自连接
                    if (isSelfAddress(remoteAddress)) {
                        Log.w(TAG, "拒绝自连接: " + remoteAddress);
                        clientSocket.close();
                        continue;
                    }
                    
                    // 处理新连接
                    handleNewConnection(clientSocket);
                }
            } catch (IOException e) {
                if (!"socket closed".equals(e.getMessage())) {
                    Log.e(TAG, "信令服务器错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    // 检查是否是自己的IP
    private boolean isSelfAddress(String ip) {
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals(getLocalIpAddress());
    }

    // 获取本地IP地址
    private String getLocalIpAddress() {
        try {
            for (java.net.NetworkInterface networkInterface : 
                    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress inetAddress : 
                        java.util.Collections.list(networkInterface.getInetAddresses())) {
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "获取本地IP失败", ex);
        }
        return "";
    }

    // 处理新连接
    private void handleNewConnection(Socket clientSocket) {
        try {
            // 关闭之前的连接（如果有）
            if (signalingSocket != null && !signalingSocket.isClosed()) {
                signalingSocket.close();
            }
            
            signalingSocket = clientSocket;
            remoteIp = clientSocket.getInetAddress().getHostAddress();
            reader = new BufferedReader(new InputStreamReader(signalingSocket.getInputStream()));
            writer = new PrintWriter(signalingSocket.getOutputStream(), true);
            
            Log.d(TAG, "与 " + remoteIp + " 建立信令连接");
            
            // 更新UI状态
            runOnUiThread(() -> {
                statusTextView.setText("来电中...");
                callButton.setEnabled(false);
            });
            
            // 监听消息
            String message;
            while ((message = reader.readLine()) != null) {
                Log.d(TAG, "收到消息: " + message);
                handleSignalingMessage(message);
            }
        } catch (IOException e) {
            Log.e(TAG, "信令连接错误: " + e.getMessage());
            runOnUiThread(this::resetConnection);
        }
    }

    // 处理信令消息
    private void handleSignalingMessage(String message) {
        try {
            if (message.startsWith("offer")) {
                // 处理Offer
                String sdp = message.substring(message.indexOf(':') + 1);
                Log.d(TAG, "收到Offer SDP");
                
                runOnUiThread(() -> {
                    if (!isCallActive) {
                        startCallAsCallee();
                    }
                    setRemoteDescription(new SessionDescription(SessionDescription.Type.OFFER, sdp));
                    createAnswer();
                });
            } else if (message.startsWith("answer")) {
                // 处理Answer
                String sdp = message.substring(message.indexOf(':') + 1);
                Log.d(TAG, "收到Answer SDP");
                setRemoteDescription(new SessionDescription(SessionDescription.Type.ANSWER, sdp));
            } else if (message.startsWith("candidate")) {
                // 处理ICE Candidate
                String[] parts = message.split(":", 2);
                if (parts.length == 2) {
                    String candidateStr = parts[1];
                    Log.d(TAG, "收到ICE Candidate: " + candidateStr);
                    addIceCandidate(candidateStr);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "处理信令消息错误: " + e.getMessage());
        }
    }

    // 开始呼叫
    private void startCall() {
        remoteIp = ipEditText.getText().toString().trim();
        if (remoteIp.isEmpty()) {
            Toast.makeText(this, "请输入对方IP地址", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Log.d(TAG, "尝试连接到: " + remoteIp);
        statusTextView.setText("正在连接...");
        callButton.setEnabled(false);
        
        executor.execute(() -> {
            try {
                // 连接到对方
                Socket socket = new Socket(remoteIp, PORT);
                Log.d(TAG, "成功连接到: " + remoteIp);
                
                signalingSocket = socket;
                reader = new BufferedReader(new InputStreamReader(signalingSocket.getInputStream()));
                writer = new PrintWriter(signalingSocket.getOutputStream(), true);
                
                isInitiator = true;
                
                runOnUiThread(() -> {
                    statusTextView.setText("连接成功，创建通话...");
                    startCallAsCaller();
                });
            } catch (IOException e) {
                Log.e(TAG, "连接失败: " + e.getMessage());
                runOnUiThread(() -> {
                    statusTextView.setText("连接失败: " + e.getMessage());
                    callButton.setEnabled(true);
                });
            }
        });
    }

    // 作为主叫方开始通话
    private void startCallAsCaller() {
        Log.d(TAG, "作为主叫方开始通话");
        isCallActive = true;
        updateUIState(true);
        
        // 创建PeerConnection
        createPeerConnection();
        
        // 添加本地媒体流
        addLocalMediaStream();
        
        // 创建Offer
        createOffer();
    }

    // 作为被叫方开始通话
    private void startCallAsCallee() {
        Log.d(TAG, "作为被叫方开始通话");
        isCallActive = true;
        updateUIState(true);
        
        // 创建PeerConnection
        createPeerConnection();
        
        // 添加本地媒体流
        addLocalMediaStream();
    }

    // 创建PeerConnection
    private void createPeerConnection() {
        Log.d(TAG, "创建PeerConnection...");
        
        // ICE服务器配置（局域网直连不需要STUN/TURN）
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        
        // PeerConnection配置
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        
        // 创建PeerConnection
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "信令状态改变: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "ICE连接状态改变: " + iceConnectionState);
                runOnUiThread(() -> {
                    if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                        statusTextView.setText("通话已连接");
                    } else if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                        statusTextView.setText("连接断开");
                    }
                });
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "ICE收集状态改变: " + iceGatheringState);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "生成ICE Candidate: " + iceCandidate.sdp);
                // 发送ICE Candidate给对端
                sendSignalingMessage("candidate:" + iceCandidate.sdp);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

            @Override
            public void onAddStream(MediaStream mediaStream) {
                // 已弃用，使用onAddTrack
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {}

            @Override
            public void onDataChannel(org.webrtc.DataChannel dataChannel) {}

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "需要重新协商");
            }

            // 修复点1: 使用onAddTrack接收远端视频流
            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "收到远程轨道");
                
                // 获取媒体轨道
                MediaStreamTrack track = rtpReceiver.track();
                
                if (track instanceof VideoTrack) {
                    Log.d(TAG, "收到远程视频轨道");
                    VideoTrack remoteVideoTrack = (VideoTrack) track;
                    
                    runOnUiThread(() -> {
                        // 修复点2: 确保在主线程设置远程渲染器
                        remoteVideoTrack.addSink(remoteRenderer);
                        statusTextView.setText("已连接");
                        Log.d(TAG, "远程视频轨道已添加到渲染器");
                    });
                }
            }
        });
        
        Log.d(TAG, "PeerConnection 创建成功");
    }

    // 添加本地媒体流
    private void addLocalMediaStream() {
        Log.d(TAG, "添加本地媒体流...");
        
        // 创建本地媒体流
        MediaStream localStream = peerConnectionFactory.createLocalMediaStream("ARDAMS");
        
        // 添加音频轨道
        audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        AudioTrack audioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource);
        localStream.addTrack(audioTrack);
        
        // 添加视频轨道
        videoCapturer = createCameraCapturer();
        if (videoCapturer != null) {
            videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
            
            // 修复点3: 正确初始化视频捕获
            SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread", 
                    eglBase.getEglBaseContext()
            );
            videoCapturer.initialize(
                    surfaceTextureHelper,
                    getApplicationContext(), 
                    videoSource.getCapturerObserver()
            );
            videoCapturer.startCapture(640, 480, 30);
            
            localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource);
            localStream.addTrack(localVideoTrack);
            
            // 显示本地视频预览
            runOnUiThread(() -> {
                localVideoTrack.addSink(localRenderer);
                Log.d(TAG, "本地视频轨道已添加到渲染器");
            });
        }
        
        // 将媒体流添加到PeerConnection
        peerConnection.addStream(localStream);
        Log.d(TAG, "本地媒体流已添加");
    }

    // 创建相机捕获器
    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(this)) {
            enumerator = new Camera2Enumerator(this);
        } else {
            enumerator = new Camera1Enumerator(true);
        }
        
        String[] deviceNames = enumerator.getDeviceNames();
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        
        // 没有前置摄像头，尝试后置
        for (String deviceName : deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        
        return null;
    }

    // 创建Offer
    private void createOffer() {
        Log.d(TAG, "创建Offer...");
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "Offer创建成功");
                setLocalDescription(sessionDescription);
                sendSignalingMessage("offer:" + sessionDescription.description);
            }
        }, constraints);
    }

    // 创建Answer
    private void createAnswer() {
        Log.d(TAG, "创建Answer...");
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "Answer创建成功");
                setLocalDescription(sessionDescription);
                sendSignalingMessage("answer:" + sessionDescription.description);
            }
        }, constraints);
    }

    // 设置本地描述
    private void setLocalDescription(SessionDescription sessionDescription) {
        peerConnection.setLocalDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                Log.d(TAG, "设置本地描述成功: " + sessionDescription.type);
            }
        }, sessionDescription);
    }

    // 设置远程描述
    private void setRemoteDescription(SessionDescription sessionDescription) {
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                Log.d(TAG, "设置远程描述成功: " + sessionDescription.type);
            }
        }, sessionDescription);
    }

    // 添加ICE Candidate
    private void addIceCandidate(String candidateStr) {
        String[] parts = candidateStr.split(" ", 2);
        if (parts.length < 2) return;
        
        IceCandidate candidate = new IceCandidate(
                parts[0], // sdpMid
                Integer.parseInt(parts[1]), // sdpMLineIndex
                parts[2] // sdp
        );
        
        peerConnection.addIceCandidate(candidate);
    }

    // 发送信令消息
    private void sendSignalingMessage(String message) {
        if (writer != null && !writer.checkError()) {
            Log.d(TAG, "发送消息: " + message);
            writer.println(message);
        }
    }

    // 挂断通话
    private void hangup() {
        Log.d(TAG, "挂断通话");
        
        // 修复点4: 正确释放资源
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
            } catch (InterruptedException e) {
                Log.e(TAG, "停止视频捕获失败", e);
            }
            videoCapturer = null;
        }
        
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        
        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        
        resetConnection();
        updateUIState(false);
        statusTextView.setText("通话已结束");
    }

    // 重置连接
    private void resetConnection() {
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (signalingSocket != null && !signalingSocket.isClosed()) signalingSocket.close();
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "关闭连接错误: " + e.getMessage());
        }
        
        signalingSocket = null;
        reader = null;
        writer = null;
        isCallActive = false;
        
        // 重新启动监听服务
        new Handler(Looper.getMainLooper()).postDelayed(this::startSignalingServer, 1000);
    }

    // 更新UI状态
    private void updateUIState(boolean callActive) {
        runOnUiThread(() -> {
            callButton.setEnabled(!callActive);
            hangupButton.setEnabled(callActive);
            ipEditText.setEnabled(!callActive);
            
            if (!callActive) {
                localRenderer.clearImage();
                remoteRenderer.clearImage();
                statusTextView.setText("就绪");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hangup();
        if (executor != null) {
            executor.shutdownNow();
        }
        
        // 修复点5: 正确释放渲染器资源
        if (localRenderer != null) {
            localRenderer.release();
            localRenderer = null;
        }
        if (remoteRenderer != null) {
            remoteRenderer.release();
            remoteRenderer = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    // 简单的SDP观察者
    private static class SimpleSdpObserver implements org.webrtc.SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {}

        @Override
        public void onSetSuccess() {}

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "创建SDP失败: " + s);
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "设置SDP失败: " + s);
        }
    }
}