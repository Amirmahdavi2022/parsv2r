package hev.htproxy;

/**
 * The exact class hev-socks5-tunnel binds its native methods to.
 *
 * <p>Its {@code JNI_OnLoad} looks this class up by name and registers the four methods below
 * against it. If the name does not match, {@code JNI_OnLoad} returns an error and loading the
 * library throws, so neither the package nor the class name may be changed. Application code
 * should go through {@link com.parsv2r.app.TunBridge} rather than calling these directly.
 */
public final class TProxyService {

    private TProxyService() {
    }

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    /** Returns {tx packets, tx bytes, rx packets, rx bytes}. */
    public static native long[] TProxyGetStats();
}
