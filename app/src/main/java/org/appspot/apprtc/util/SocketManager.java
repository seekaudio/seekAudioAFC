package org.appspot.apprtc.util;

import java.net.Socket;

public class SocketManager {
    private static SocketManager instance;
    private Socket socket;

    private SocketManager() {}

    public static synchronized SocketManager getInstance() {
        if (instance == null) {
            instance = new SocketManager();
        }
        return instance;
    }

    public void setSocket(Socket socket) {
        this.socket = socket;
    }

    public Socket getSocket() {
        return socket;
    }
}