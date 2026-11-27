package com.example.flutter_mali_repro.runner;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Standalone self-targeting instrumentation; the measured Flutter process may restart. */
public final class MaliInstrumentation extends Instrumentation {
  private static final String TARGET="com.example.flutter_mali_repro";
  private static final String ACTIVITY=TARGET+"/.MainActivity";
  private static final String APPDIR="files";
  private Bundle arguments;
  private File output;
  private int token=0;
  private final JSONArray collected=new JSONArray();
  private String backend,tab,label;
  private int pass;
  private boolean reuseCardImages;

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);arguments=args==null?new Bundle():args;start();
  }
  @Override public void onStart() {
    shell("pm grant com.example.flutter_mali_repro.runner android.permission.WRITE_EXTERNAL_STORAGE");
    output=new File("/sdcard/Download/mali195767");
    Bundle status=new Bundle();status.putString("class",getClass().getName());status.putString("test","compareRenderers");status.putInt("numtests",1);status.putInt("current",1);sendStatus(1,status);
    String[] settings={"window_animation_scale","transition_animation_scale","animator_duration_scale"};
    String[] old=new String[3]; boolean success=false;
    try {
      check(output!=null,"external output unavailable");output.mkdirs();
      write("environment.txt",shell("id")+shell("getprop ro.build.fingerprint")+shell("getprop ro.product.cpu.abilist")+shell("wm size")+shell("wm density")+shell("pm list features"));
      for(int i=0;i<3;i++){old[i]=shell("settings get global "+settings[i]).trim();shell("settings put global "+settings[i]+" 1");}
      reuseCardImages = Boolean.parseBoolean(arguments.getString("reuseCardImages", "false"));
      int passes=Integer.parseInt(arguments.getString("passes","1"));check(passes>=1&&passes<=3,"passes out of range");
      String[] order=arguments.getString("backendOrder","gles:skia").split(":");
      for(String selected:order) {
        check(selected.equals("gles")||selected.equals("skia"),"unknown backend");backend=selected;
        shell("am force-stop "+TARGET);shell("run-as "+TARGET+" rm -f "+APPDIR+"/ready.json "+APPDIR+"/command-ack.json "+APPDIR+"/results.json");
        String launch=shell("am start -W -n "+ACTIVITY+" --ez enable-impeller "+(backend.equals("gles")?"true":"false")+(Boolean.parseBoolean(arguments.getString("trace","false"))?" --ez trace-systrace true":""));
        write(backend+"-launch.txt",launch);waitJson(APPDIR+"/ready.json",null,20000);
        String pid=shell("pidof "+TARGET).trim();check(pid.matches("[0-9]+"),"missing app pid: "+pid);
        String startLog=shell("logcat -d -v threadtime --pid="+pid);
        verifyBackend(startLog);write(backend+"-runtime.log",startLog+"\nRUNTIME_BACKEND="+(backend.equals("gles")?"impeller_gles":"skia")+"\n");
        boolean tracing=Boolean.parseBoolean(arguments.getString("trace","false"));
        for(String selectedTab:new String[]{"Cards","Text"}) {
          tab=selectedTab;
          for(pass=1;pass<=passes;pass++) {
            label=backend+"-"+tab+"-p"+pass;
            command("reset");swipe(true);SystemClock.sleep(600);swipe(false);SystemClock.sleep(600);
            command("reset");SystemClock.sleep(700);
            if(tracing)startTrace(label);
            if(Boolean.parseBoolean(arguments.getString("sample","false")))startSampling(label);
            command("begin");
            for(int i=0;i<8;i++) {
              long swipeStart=SystemClock.elapsedRealtime();swipe(i<4);
              long rest=1100-(SystemClock.elapsedRealtime()-swipeStart);if(rest>0)SystemClock.sleep(rest);
            }
            SystemClock.sleep(600);command("end");
            JSONObject root=new JSONObject(shell("run-as "+TARGET+" cat "+APPDIR+"/results.json"));JSONArray runs=root.getJSONArray("runs");JSONObject run=runs.getJSONObject(runs.length()-1);
            check(label.equals(run.getString("label")),"stale result: "+label);check(run.getJSONArray("frames").length()>=30,"too few frames "+label);
            check((backend.equals("gles")?"impeller_gles":"skia").equals(run.getString("backend")),"result backend identity mismatch");check(tab.equals(run.getString("tab"))&&pass==run.getInt("pass"),"result identity mismatch");
            if (reuseCardImages && tab.equals("Cards")) {
              JSONObject images = run.getJSONObject("card_images");
              check(images.getInt("measured_captures") > 0, "no card images captured during scroll");
            }
            collected.put(run);write("runner-manifest.json",new JSONObject().put("runs",collected).toString());
            write(label+"-thermal.txt",shell("dumpsys thermalservice")+shell("dumpsys battery"));
            Log.i("MaliReproRunner","PASS "+label+" frames="+run.getJSONArray("frames").length());
            if(tracing){SystemClock.sleep(6500);shell("cp /data/misc/perfetto-traces/"+label+".pftrace "+output.getAbsolutePath()+"/"+label+".pftrace");}
          }
        }
        String endLog=shell("logcat -d -v threadtime --pid="+pid);write(backend+"-full.log",endLog);
      }
      check(collected.length()==order.length*2*passes,"missing results");success=true;
      status.putString("stream","\ncompareRenderers passed, measured passes="+collected.length()+"\n");sendStatus(0,status);
    } catch(Throwable e) {
      Log.e("MaliReproRunner","measurement failed",e);status.putString("stack",Log.getStackTraceString(e));status.putString("stream","\n"+Log.getStackTraceString(e));sendStatus(-2,status);
      try{write("failure.txt",Log.getStackTraceString(e));write("failure-logcat.txt",shell("logcat -d -v threadtime"));}catch(Exception ignored){}
    } finally {
      shell("am force-stop "+TARGET);
      for(int i=0;i<3;i++)if(old[i]!=null&&old[i].matches("[0-9.]+"))shell("settings put global "+settings[i]+" "+old[i]);
      Bundle result=new Bundle();result.putString("stream",success?"\nOK (1 test)\n":"\nFAILURES!!!\nTests run: 1, Failures: 1\n");result.putInt("numtests",1);finish(Activity.RESULT_OK,result);
    }
  }
  private void command(String command)throws Exception {
    String id="t"+(++token);shell("am start -W --activity-single-top -n "+ACTIVITY+" --es mali_command "+command+" --es mali_token "+id+" --es mali_label "+label+" --es mali_tab "+tab+" --es mali_backend "+(backend.equals("gles")?"impeller_gles":"skia")+" --ei mali_pass "+pass+" --ez reuse_card_images "+reuseCardImages);
    JSONObject ack=waitJson(APPDIR+"/command-ack.json",id,20000);check(!ack.has("error"),"command failed "+ack);
  }
  private JSONObject waitJson(String path,String expected,int timeout)throws Exception {
    long deadline=SystemClock.elapsedRealtime()+timeout;
    while(SystemClock.elapsedRealtime()<deadline) {
      try{JSONObject d=new JSONObject(shell("run-as "+TARGET+" cat "+path));if(expected==null||expected.equals(d.optString("token")))return d;}catch(JSONException ignored){}
      SystemClock.sleep(100);
    }
    throw new AssertionError("timeout waiting "+path+" token="+expected);
  }
  private void verifyBackend(String log) {
    if(backend.equals("gles"))check(log.contains("Using the Impeller rendering backend (OpenGLES)"),"GLES backend not proven");
    else check(log.contains("Impeller opt-out deprecated"),"Skia backend opt-out not proven");
  }
  private void swipe(boolean forward){shell("input swipe 300 "+(forward?"820 300 300":"300 300 820")+" 180");}
  private void startTrace(String name)throws Exception {
    String config="buffers { size_kb: 32768 fill_policy: RING_BUFFER }\nduration_ms: 15000\ndata_sources { config { name: \"linux.ftrace\" ftrace_config { ftrace_events: \"sched/sched_switch\" ftrace_events: \"sched/sched_waking\" ftrace_events: \"sched/sched_blocked_reason\" ftrace_events: \"power/cpu_frequency\" atrace_categories: \"gfx\" atrace_categories: \"view\" atrace_apps: \""+TARGET+"\" } } }\ndata_sources { config { name: \"linux.process_stats\" process_stats_config { scan_all_processes_on_start: true } } }\n";
    File file=new File(output,"capture.pbtxt");try(FileOutputStream o=new FileOutputStream(file)){o.write(config.getBytes(StandardCharsets.UTF_8));}
    File script=new File(output,"capture.sh");try(FileOutputStream o=new FileOutputStream(script)){o.write(("#!/system/bin/sh\ncat "+file.getAbsolutePath()+" | perfetto --background --txt -c - -o /data/misc/perfetto-traces/"+name+".pftrace 2>&1\n").getBytes(StandardCharsets.UTF_8));}
    write(name+"-perfetto-start.txt",shell("sh "+script.getAbsolutePath()));
  }
  private void startSampling(String name)throws Exception {
    String base="/data/local/tmp/mali195767-simpleperf record --app "+TARGET+" -e task-clock -f 99 -g --duration 12 -o /data/local/tmp/"+name+".perf.data";
    String scriptText="#!/system/bin/sh\nchmod 755 /data/local/tmp/mali195767-simpleperf\n( ( "+base+" --trace-offcpu || "+base+" ) 2>&1 | cat > "+output.getAbsolutePath()+"/"+name+"-simpleperf.log\ncp /data/local/tmp/"+name+".perf.data "+output.getAbsolutePath()+"/"+name+".perf.data\n) > /dev/null 2>&1 &\necho $!\n";
    File file=new File(output,"sample.sh");try(FileOutputStream o=new FileOutputStream(file)){o.write(scriptText.getBytes(StandardCharsets.UTF_8));}
    write(name+"-sampling-start.txt",shell("sh "+file.getAbsolutePath()));
  }
  private String shell(String cmd) {
    try{ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(cmd);try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)b.write(bytes,0,n);return b.toString("UTF-8");}}
    catch(Exception e){throw new RuntimeException("shell failed: "+cmd,e);}
  }
  private void write(String name,String text)throws IOException{try(FileOutputStream f=new FileOutputStream(new File(output,name))){f.write(text.getBytes(StandardCharsets.UTF_8));}}
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
