/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.Timer;
import java.util.TimerTask;

import org.appspot.apprtc.util.LogviewHelper;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.ThreadUtils;

/**
 * TCP 信令通道，支持双向监听和连接
 */
public class TCPChannelClient {
  private static final String TAG = "TCPChannelClient";
  private static final int SOCKET_TIMEOUT = 10000;
  private static final int HEARTBEAT_INTERVAL = 5000;
  private static final String HEARTBEAT_MESSAGE = "{\"type\":\"heartbeat\"}";

  private final ExecutorService executor;
  private final ThreadUtils.ThreadChecker executorThreadCheck;
  private final TCPChannelEvents eventListener;
  private final String ip;
  private final int port;
  
  private ServerSocket serverSocket;
  private Socket clientSocket;
  private Socket activeSocket;
  private PrintWriter out;
  private volatile boolean connected = false;
  private Timer heartbeatTimer;
  private Thread serverThread;
  private Thread clientThread;

  /**
   * Callback interface for messages delivered on TCP Connection. All callbacks are invoked from the
   * looper executor thread.
   */
  public interface TCPChannelEvents {
    void onTCPConnected(Socket socket, boolean isServer);
    void onTCPMessage(String message);
    void onTCPError(String description);
    void onTCPClose();
  }

  /**
   * Initializes the TCPChannelClient. If IP is a local IP address, starts a listening server on
   * that IP. If not, instead connects to the IP.
   *
   * @param eventListener Listener that will receive events from the client.
   * @param ip            IP address to listen on or connect to.
   * @param port          Port to listen on or connect to.
   */
  public TCPChannelClient(ExecutorService executor, TCPChannelEvents eventListener, String ip, int port) {
    this.executor = executor;
    executorThreadCheck = new org.webrtc.ThreadUtils.ThreadChecker();
    executorThreadCheck.detachThread();
    this.eventListener = eventListener;
    this.ip = ip;
    this.port = port;

    Log.d("zzy", "ip " + ip + ", " + isCurrentDeviceIp(ip));
    // 根据IP地址决定角色：主动连接方为Caller，被动监听方为Answerer
    if ("0.0.0.0".equals(ip)) {
      startServer(); // 全局监听模式
    } else if (isCurrentDeviceIp(ip)) {  // 判断是否为本地IP
      startServer();    // 作为Answerer监听
    } else {
      startClient();    // 作为Caller主动连接
    }


  }

  /**
   * 检查IP是否匹配本机任一网络接口的IP
   */
  private boolean isCurrentDeviceIp(String ip) {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface iface = interfaces.nextElement();
        // 跳过回环和未启用的接口
        if (iface.isLoopback() || !iface.isUp()) continue;

        Enumeration<InetAddress> addresses = iface.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress addr = addresses.nextElement();
          if (addr.getHostAddress().equals(ip)) {
            return true;
          }
        }
      }
    } catch (SocketException e) {
      Log.e(TAG, "获取网络接口失败", e);
    }
    return false;
  }

  private void startServer() {
    serverThread = new Thread(() -> {
      try {
        serverSocket = new ServerSocket(port, 0, InetAddress.getByName("0.0.0.0"));
        Log.d(TAG, "开始监听端口: " + port);
        
        Socket socket = serverSocket.accept();
        Log.d(TAG, "收到连接: " + socket.getInetAddress().getHostAddress());
        
        synchronized (this) {
          if (connected) {
            socket.close();
            return;
          }
          connected = true;
          activeSocket = socket;
          setupSocket(socket, true);
        }
        
      } catch (IOException e) {
        Log.w(TAG, "监听失败: " + e.getMessage());
      }
    });
    serverThread.start();
  }

  private void startClient() {
    clientThread = new Thread(() -> {
      try {
        Thread.sleep(100); // 稍微延迟，让服务器先启动
        clientSocket = new Socket();
        clientSocket.connect(new InetSocketAddress(ip, port), SOCKET_TIMEOUT);
        Log.d(TAG, "连接成功: " + ip + ":" + port);
        
        synchronized (this) {
          if (connected) {
            clientSocket.close();
            return;
          }
          connected = true;
          activeSocket = clientSocket;
          setupSocket(clientSocket, false);
        }
        
      } catch (Exception e) {
        Log.w(TAG, "[socket]连接失败: " + e.getMessage());
      }
    });
    clientThread.start();
  }

  private void setupSocket(Socket socket, boolean isServer) {
    try {
      socket.setKeepAlive(true);
      socket.setTcpNoDelay(true);
      

      Log.d(TAG, "Socket设置完成，isServer: " + isServer);
      // 在UI线程中触发连接事件
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        eventListener.onTCPConnected(socket, isServer);
      });
      //startHeartbeat();

      listenSocket();
      
    } catch (IOException e) {
      Log.e(TAG, "设置socket失败: " + e.getMessage());
      eventListener.onTCPError("设置socket失败: " + e.getMessage());
    }
  }

  private void startHeartbeat() {
    heartbeatTimer = new Timer();
    heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        send(HEARTBEAT_MESSAGE);
      }
    }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL);
  }

  private void listenSocket() {
    new Thread(() -> {
      try {
        while (!Thread.interrupted()) {
          DataInputStream dis = new DataInputStream(activeSocket.getInputStream());
          int msgLen = dis.readInt();
          byte[] buffer = new byte[msgLen];
          Log.d(TAG,"[socket]阻塞读取直至缓冲区（长度:" + msgLen + ")填满");
          dis.readFully(buffer);
          String msgStr = new String(buffer, StandardCharsets.UTF_8);
          eventListener.onTCPMessage(msgStr);
        }

      } catch (IOException e) {
        Log.d(TAG,"[Socket] 监听异常: " + e.getMessage());
      }
    }).start();
  }




  /**
   * Sends a message on the socket.
   *
   * @param message Message to be sent.
   */
  public void send(String message) {
    // 直接发送，不依赖executor
    try {
      if (activeSocket != null && !activeSocket.isClosed() && out != null) {
        Log.d(TAG, "发送消息: " + message);
        out.println(message);
        if (out.checkError()) {
          Log.e(TAG, "发送消息时发生错误");
          new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            eventListener.onTCPError("发送错误");
          });
          disconnect();
        }
      } else {
        Log.w(TAG, "Socket未连接，无法发送消息");
      }
    } catch (Exception e) {
      Log.e(TAG, "发送消息失败: " + e.getMessage());
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        eventListener.onTCPError("发送失败: " + e.getMessage());
      });
      disconnect();
    }
  }

  public void sendSignalingMessage(String type, String content) {
    Log.d(TAG, "[Socket] 发送" + type + ": " + content);
    JSONObject obj = new JSONObject();
    try {
      obj.put("type", type);
      obj.put("content", content);
      DataOutputStream dos = new DataOutputStream(activeSocket.getOutputStream());
      //序列化消息
      byte[] jsonBytes = obj.toString().getBytes(StandardCharsets.UTF_8);
      dos.writeInt(jsonBytes.length);
      dos.write(jsonBytes);
      dos.flush();
    } catch (JSONException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Disconnects the client if not already disconnected. This will fire the onTCPClose event.
   */
  public void disconnect() {
    // 直接执行断开操作，不依赖executor
    Log.d(TAG, "断开连接");
    connected = false;
    
    if (heartbeatTimer != null) {
      heartbeatTimer.cancel();
      heartbeatTimer = null;
    }
    
    try {
      if (out != null) {
        out.close();
        out = null;
      }
      if (activeSocket != null) {
        activeSocket.close();
        activeSocket = null;
      }
      if (clientSocket != null) {
        clientSocket.close();
        clientSocket = null;
      }
      if (serverSocket != null) {
        serverSocket.close();
        serverSocket = null;
      }
    } catch (IOException e) {
      Log.e(TAG, "关闭连接时发生错误: " + e.getMessage());
    }
    
    // 在UI线程中触发事件
    if (eventListener != null) {
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        eventListener.onTCPClose();
      });
    }
  }

  /**
   * Helper method for firing onTCPError events. Calls onTCPError on the executor thread.
   */
  private void reportError(final String message) {
    Log.e(TAG, "TCP Error: " + message);
    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
      eventListener.onTCPError(message);
    });
  }
}
