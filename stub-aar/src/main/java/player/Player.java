package player;

public class Player {
    private PlayerCallback callback;

    public interface PlayerCallback {
        void onServerDiscovered(String name, String address);
        void onConnected(String serverName);
        void onDisconnected();
        void onStateChanged(String state);
        void onMetadata(String title, String artist, String album);
        void onError(String message);
    }

    public Player(String deviceName, PlayerCallback callback) {
        this.callback = callback;
        android.util.Log.d("Player", "Stub player created: " + deviceName);
    }

    public void startDiscovery() {
        android.util.Log.d("Player", "Stub: startDiscovery called");
    }

    public void stopDiscovery() {
        android.util.Log.d("Player", "Stub: stopDiscovery called");
    }

    public void connect(String serverAddress) {
        android.util.Log.d("Player", "Stub: connect called with " + serverAddress);
        if (callback != null) {
            callback.onConnected(serverAddress);
        }
    }

    public void disconnect() {
        android.util.Log.d("Player", "Stub: disconnect called");
        if (callback != null) {
            callback.onDisconnected();
        }
    }

    public void play() {
        android.util.Log.d("Player", "Stub: play called");
        if (callback != null) {
            callback.onStateChanged("playing");
        }
    }

    public void pause() {
        android.util.Log.d("Player", "Stub: pause called");
        if (callback != null) {
            callback.onStateChanged("paused");
        }
    }

    public void stop() {
        android.util.Log.d("Player", "Stub: stop called");
        if (callback != null) {
            callback.onStateChanged("stopped");
        }
    }

    public void setVolume(double volume) {
        android.util.Log.d("Player", "Stub: setVolume called with " + volume);
    }

    public String getState() {
        return "stopped";
    }

    public boolean isConnected() {
        return false;
    }

    public void cleanup() {
        android.util.Log.d("Player", "Stub: cleanup called");
    }
}
