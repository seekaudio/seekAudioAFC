package org.appspot.apprtc;

import static org.appspot.apprtc.PeerConnectionClient.peerConnection;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.appspot.apprtc.util.SocketManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

public class DirectRTCClient implements AppRTCClient, TCPChannelClient.TCPChannelEvents {
  private static final String TAG = "DirectRTCClient";
  private static final int DEFAULT_PORT = 38888;

  // 增强IP地址匹配模式
  static final Pattern IP_PATTERN = Pattern.compile(
          "((\\d{1,3}\\.){3}\\d{1,3}(:\\d{1,5})?|" +  // IPv4
                  "\\[([0-9a-fA-F:]+)\\](:\\d{1,5})?|" +      // IPv6
                  "localhost(:\\d{1,5})?)"                     // localhost
  );

  private final ExecutorService executor;
  private final SignalingEvents events;
  @Nullable public static TCPChannelClient tcpClient;
  private RoomConnectionParameters connectionParameters;
  private SignalingParameters signalingParameters;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private List<IceCandidate> pendingIceCandidates = new ArrayList<>();

  private enum ConnectionState { NEW, CONNECTED, CLOSED, ERROR }
  private ConnectionState roomState = ConnectionState.NEW;

  // 添加一个变量来跟踪已处理的offer
  private String lastProcessedOffer = null;

  public DirectRTCClient(SignalingEvents events) {
    this.events = events;
    this.executor = java.util.concurrent.Executors.newSingleThreadExecutor();
  }

  @Override
  public void connectToRoom(RoomConnectionParameters connectionParameters) {
    this.connectionParameters = connectionParameters;
    connectToRoomInternal();
  }


  private void connectToRoomInternal() {
    roomState = ConnectionState.NEW;
    String endpoint = connectionParameters.roomId;

    // 验证IP地址格式
    Matcher matcher = IP_PATTERN.matcher(endpoint);
    if (!matcher.matches()) {
      reportError("无效的IP地址格式: " + endpoint);
      return;
    }

    // 解析IP和端口
    String[] parts = endpoint.split(":");
    String ip = parts[0];
    int port = DEFAULT_PORT;

    if (parts.length > 1) {
      try {
        port = Integer.parseInt(parts[1]);
        if (port <= 0 || port > 65535) {
          reportError("端口号必须在1-65535之间");
          return;
        }
      } catch (NumberFormatException e) {
        reportError("无效的端口号: " + parts[1]);
        return;
      }
    }

    tcpClient = new TCPChannelClient(executor, this, ip, port);
    Log.d(TAG, "[socket]TCPChannelClient created");
  }

  @Override
  public void disconnectFromRoom() {
    executor.execute(this::disconnectFromRoomInternal);
  }

  private void disconnectFromRoomInternal() {
    roomState = ConnectionState.CLOSED;
    if (tcpClient != null) {
      tcpClient.disconnect();
      tcpClient = null;
    }
    // 不要在这里关闭executor，让TCP客户端自己处理
    // executor.shutdown();
  }

  @Override
  public void sendOfferSdp(final SessionDescription sdp) {
    executor.execute(() -> {
      if (roomState != ConnectionState.CONNECTED) {
        reportError("Sending offer SDP in non connected state");
        return;
      }
      sendSessionDescription(sdp);
    });
  }

  @Override
  public void sendAnswerSdp(final SessionDescription sdp) {
    executor.execute(() -> sendSessionDescription(sdp));
  }

  private void sendSessionDescription(SessionDescription sdp) {
    try {
      JSONObject json = new JSONObject();
      json.put("sdp", sdp.description);
      sendMessage(sdp.type.canonicalForm(),json.toString());
    } catch (JSONException e) {
      reportError("Failed to create SDP JSON: " + e.getMessage());
    }
  }

  @Override
  public void sendLocalIceCandidate(final IceCandidate candidate) {
    executor.execute(() -> {
      if (roomState != ConnectionState.CONNECTED) {
        reportError("Sending ICE candidate in non connected state");
        return;
      }
      sendIceCandidate(candidate);
    });
  }

  @Override
  public void sendLocalIceCandidateRemovals(final IceCandidate[] candidates) {
    executor.execute(() -> {
      if (roomState != ConnectionState.CONNECTED) {
        reportError("Sending ICE candidate removals in non connected state");
        return;
      }
      try {
        JSONObject json = new JSONObject();
        json.put("type", "remove-candidates");
        JSONArray jsonArray = new JSONArray();
        for (IceCandidate candidate : candidates) {
          jsonArray.put(toJsonCandidate(candidate));
        }
        json.put("candidates", jsonArray);
        sendMessage("remove-candidates", json.toString());
      } catch (JSONException e) {
        reportError("Failed to create remove-candidates JSON: " + e.getMessage());
      }
    });
  }

  private void sendIceCandidate(IceCandidate candidate) {
    try {
      JSONObject json = new JSONObject();
      json.put("type", "candidate");
      json.put("sdpMLineIndex", candidate.sdpMLineIndex);
      json.put("sdpMid", candidate.sdpMid);
      //json.put("candidate", candidate.sdp);
      json.put("sdp", candidate.sdp);
      sendMessage("candidate", json.toString());
      Log.d(TAG, "[webrtc]" + (signalingParameters.initiator ? "发起方" : "应答方") + "sendIceCandidate 完成: " + json);
    } catch (JSONException e) {
      reportError("Failed to create candidate JSON: " + e.getMessage());
    }
  }

  // TCP事件处理
  @Override
  public void onTCPConnected(Socket socket, boolean isServer) {
    
    roomState = ConnectionState.CONNECTED;
    Log.d(TAG, "TCP连接建立，isServer: " + isServer);
    
    // 确保角色分配正确：客户端是发起方，服务端是被叫方
    boolean isInitiator = !isServer; // 客户端是发起方，服务端是被叫方
    
    signalingParameters = new SignalingParameters(
            new ArrayList<>(), // 仅使用本地ICE候选者
            isInitiator,       // 根据连接角色确定是否为发起方
            null, null, null, null, null
    );
    events.onConnectedToRoom(signalingParameters);
    
    // 只有客户端（发起方）自动发起呼叫
    if (!isServer) {
      Log.d(TAG, "[socket]作为客户端（发起方），自动发起呼叫");
      // 延迟一点时间，确保连接稳定
      executor.execute(() -> {
        try {
          Thread.sleep(500);
          // 检查状态是否仍然是CONNECTED
          if (roomState == ConnectionState.CONNECTED) {
            Log.d(TAG, "通知上层开始创建Offer");
            events.onStartCall();
          } else {
            Log.w(TAG, "状态已改变，取消创建Offer，当前状态: " + roomState);
          }
        } catch (InterruptedException e) {
          Log.w(TAG, "延迟被中断");
        }
      });
    } else {
      Log.d(TAG, "作为服务端（被叫方），等待来电");
      // 服务端等待接收Offer，会触发来电界面
    }
  }


  private void drainPendingIceCandidates() {
    if (peerConnection != null && !pendingIceCandidates.isEmpty()) {
      for (IceCandidate c : pendingIceCandidates) {
        peerConnection.addIceCandidate(c);
        Log.d(TAG, "[WebRTC]#drainPendingIceCandidates addIceCandidate done");
      }
      pendingIceCandidates.clear();
    }
  }

  public   class SimpleSdpObserver implements SdpObserver {
    private final String tag;
    SimpleSdpObserver(String tag) { this.tag = tag; }
    @Override public void onCreateSuccess(SessionDescription sdp) {
      Log.d(TAG,"[WebRTC]" + tag + " onCreateSuccess");
    }
    @Override public void onSetSuccess() {
      Log.d(TAG,"[WebRTC]" + tag + " onSetSuccess");
      drainPendingIceCandidates();
      //只有setRemoteDescription成功后且只有收到offer时才createAnswer
      if (tag.equals("setRemoteDescription-offer")) {
        createAnswer();
      }
    }
    @Override public void onCreateFailure(String error) {
      Log.d(TAG,"[WebRTC]" + tag + " onCreateFailure: " + error);
      Log.e(TAG, "[WebRTC]" + tag + " onCreateFailure: " + error);
    }
    @Override public void onSetFailure(String error) {
      Log.e(TAG, "[WebRTC]" + tag + " onSetFailure: " + error);
    }
  }

  public void sendSignalingMessage(String type, String content) {
    Log.d(TAG, "[Socket] 发送" + type + ": " + content);
    JSONObject obj = new JSONObject();
    try {
      obj.put("type", type);
      obj.put("content", content);
      DataOutputStream dos = new DataOutputStream(SocketManager.getInstance().getSocket().getOutputStream());
      //序列化消息
      byte[] jsonBytes = obj.toString().getBytes(StandardCharsets.UTF_8);
      dos.writeInt(jsonBytes.length);
      dos.write(jsonBytes);
      dos.flush();
    } catch (JSONException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void createAnswer() {
    MediaConstraints constraints = new MediaConstraints();
    constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
    constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
    peerConnection.createAnswer(new SdpObserver() {
      @Override public void onCreateSuccess(SessionDescription sdp) {
        Log.d(TAG,"[WebRTC] Answer创建成功");
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
        //DirectRTCClient.tcpClient.sendSignalingMessage("answer", modifiedSdp);
        sendSignalingMessage("answer", modifiedSdp);
      }
      @Override public void onSetSuccess() { Log.d(TAG,"[WebRTC] setLocalDescription成功"); }
      @Override public void onCreateFailure(String error) { Log.d(TAG,"[WebRTC] Answer创建失败: " + error); }
      @Override public void onSetFailure(String error) { Log.d(TAG,"[WebRTC] setLocalDescription失败: " + error); }
    }, constraints);
  }
  
  @Override
  public void onTCPMessage(String msg) {
    Log.d(TAG, "DirectRTCClient onTCPMessage msg: " + msg);
    try {
      JSONObject json = new JSONObject(msg);
      String type = json.optString("type");
      if (type.isEmpty()) {
        reportError("无效的消息格式: 缺少type字段");
        return;
      }
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

      Log.d(TAG, "[Socket] 处理消息类型: " + type + ", 内容长度: " + ref.content.length());

      switch (type) {
        case "offer":
          uiHandler.post(() -> {
            SessionDescription sdp = new SessionDescription(SessionDescription.Type.OFFER, ref.content);
            Log.d(TAG, "[WebRTC]setRemoteDescription-offer, sdp: [" + ref.content + "]");
            peerConnection.setRemoteDescription(new SimpleSdpObserver("setRemoteDescription-offer"), sdp);
          });
          break;
        case "answer":
          uiHandler.post(() -> {
            SessionDescription sdp = new SessionDescription(SessionDescription.Type.ANSWER, ref.content);
            Log.d(TAG,"[WebRTC]setRemoteDescription-answer, sdp: [" + ref.content + "]");
            peerConnection.setRemoteDescription(new SimpleSdpObserver("setRemoteDescription-answer"), sdp);
          });
          break;
        case "candidate":
          try {
            JSONObject candidateJson = new JSONObject(ref.content);
            IceCandidate candidate = new IceCandidate(candidateJson.optString("sdpMid"),
                    candidateJson.optInt("sdpMLineIndex"),
                    candidateJson.optString("sdp"));
            if (peerConnection != null) {
                      /*peerConnection.addIceCandidate(candidate);
                      Log.d(TAG,"[WebRTC]addIceCandidate");
                  } else {*/
              pendingIceCandidates.add(candidate);

            }
          } catch (JSONException e) {
            throw new RuntimeException(e);
          }
          //}
          break;
      default:
        break;
      }
    } catch (JSONException e) {
      reportError("消息解析错误: " + e.getMessage());
      e.printStackTrace();
    }
  }

  @Override
  public void onTCPError(String description) {
    reportError("TCP连接错误: " + description);
  }

  @Override
  public void onTCPClose() {
    events.onChannelClose();
    // 在连接关闭后关闭executor
    executor.shutdown();
  }

  // 辅助方法
  private void reportError(final String errorMessage) {
    Log.e(TAG, errorMessage);
    executor.execute(() -> {
      if (roomState != ConnectionState.ERROR) {
        roomState = ConnectionState.ERROR;
        events.onChannelError(errorMessage);
      }
    });
  }

  private void sendMessage(final String type, final String message) {
    executor.execute(() -> {
      if (tcpClient != null) {
        tcpClient.sendSignalingMessage(type, message);
      }
    });
  }

  private static JSONObject toJsonCandidate(IceCandidate candidate) throws JSONException {
    JSONObject json = new JSONObject();
    json.put("sdpMid", candidate.sdpMLineIndex);
    json.put("sdpMLineIndex", candidate.sdpMid);
    json.put("sdp", candidate.sdp);
    return json;
  }

  private static IceCandidate toJavaCandidate(JSONObject json) throws JSONException {
    return new IceCandidate(
            json.getString("sdpMid"),
            json.getInt("sdpMLineIndex"),
            json.getString("sdp")
    );
  }


  @Override
  public void handleIncomingMessage(String msg) {
    onTCPMessage(msg);
  }

  @Override
  public void handleTcpConnected(Socket socket, boolean isServer) {
    onTCPConnected(socket, isServer);
  }
}