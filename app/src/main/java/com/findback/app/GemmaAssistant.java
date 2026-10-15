package com.findback.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.ai.edge.litertlm.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One on-device Gemma 4 engine shared by every mode. The engine stays loaded while Saathi is in the
 * foreground; cancelling a request never destroys it. The latest request always wins.
 */
public final class GemmaAssistant implements AutoCloseable {
    public static final String MODEL_NAME="gemma-4-E2B-it.litertlm";
    public static final String MODEL_SHA256="181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c";
    public static final long MODEL_BYTES=2588147712L;
    private static final long ANSWER_TIMEOUT_SECONDS=45;
    private final File model, cache;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private volatile Conversation active;
    private volatile boolean closed;
    private Engine engine;
    private String backend="";
    private boolean preferCpu=false;
    public interface Listener {
        void onStatus(String message);
        void onPartial(String text);
        void onComplete(String text);
        void onError(String message);
    }
    public GemmaAssistant(Context c){model=resolveModelFile(c);cache=new File(c.getCacheDir(),"gemma");}
    public static File resolveModelFile(Context c){return new File(c.getNoBackupFilesDir(),"models/"+MODEL_NAME);}
    public boolean installed(){return model.isFile()&&model.length()==MODEL_BYTES;}
    public boolean isActive(){return active!=null;}

    @androidx.annotation.OptIn(markerClass=ExperimentalApi.class)
    private Engine initialize(Backend b){
        // 140 visual tokens: about 2x faster than 280 with enough detail for signs and labels.
        ExperimentalFlags.INSTANCE.setVisualTokenBudget(140);
        Engine candidate=new Engine(new EngineConfig(model.getAbsolutePath(),b,b,null,1024,1,cache.getAbsolutePath()));
        try{candidate.initialize();return candidate;}catch(RuntimeException|LinkageError e){try{candidate.close();}catch(Exception ignored){}throw e;}
    }

    /** Runs on the executor. GPU first; CPU when the phone's GPU delegate cannot start. */
    private void ensureEngine(Runnable onSlowStart){
        if(engine!=null)return;
        if(onSlowStart!=null)onSlowStart.run();
        cache.mkdirs();
        if(preferCpu){engine=initialize(new Backend.CPU(4,null));backend="CPU";return;}
        try{engine=initialize(new Backend.GPU());backend="GPU";}
        catch(RuntimeException|LinkageError gpu){
            android.util.Log.w("Saathi","GPU initialization failed; using CPU",gpu);
            preferCpu=true;engine=initialize(new Backend.CPU(4,null));backend="CPU";
        }
        android.util.Log.i("Saathi","Gemma ready on "+backend);
    }

    /** Loads the engine ahead of the first request so the first answer is not delayed. */
    public void prepare(){
        if(closed||!installed())return;
        executor.execute(()->{if(closed)return;try{ensureEngine(null);}catch(Exception|LinkageError e){android.util.Log.e("Saathi","Assistant warm-up failed",e);dropEngine();}});
    }

    public void ask(byte[] jpeg,String prompt,AssistLanguage language,int maxOutputTokens,Listener listener){
        if(closed)return;
        if(!installed()){listener.onError("Detailed assistance is not installed on this phone.");return;}
        int ticket=generation.incrementAndGet();
        cancelActive();
        executor.execute(()->{
            if(closed||generation.get()!=ticket)return;
            CountDownLatch done=new CountDownLatch(1);StringBuilder answer=new StringBuilder();
            AtomicReference<String> failure=new AtomicReference<>();AtomicBoolean ended=new AtomicBoolean(),looped=new AtomicBoolean();boolean reset=false;
            Conversation conversation=null;long started=android.os.SystemClock.elapsedRealtime();
            try{
                ensureEngine(()->post(ticket,()->listener.onStatus("Getting ready… the first answer takes a little longer")));
                if(closed||generation.get()!=ticket)return;
                ConversationConfig config=new ConversationConfig(Contents.Companion.of(GemmaPrompts.system(language)),Collections.emptyList(),Collections.emptyList(),
                    new SamplerConfig(16,.9,.2,0),false,null,Collections.emptyMap(),null,false,maxOutputTokens,new ThinkingConfig(false,0),false);
                conversation=engine.createConversation(config);active=conversation;
                Message message=Message.Companion.of(new Content.ImageBytes(jpeg),new Content.Text(prompt));
                conversation.sendMessageAsync(message,new MessageCallback(){
                    @Override public void onMessage(Message chunk){
                        String piece=text(chunk);
                        if(ended.get()||generation.get()!=ticket||piece.isEmpty())return;
                        if(answer.length()==0)android.util.Log.i("Saathi","First token after "+(android.os.SystemClock.elapsedRealtime()-started)+" ms");
                        String partial;synchronized(answer){
                            answer.append(piece);
                            int loop=loopStart(answer);
                            if(loop>=0){
                                // Small models can repeat a word forever; keep what came before and stop.
                                answer.setLength(loop);ended.set(true);
                                android.util.Log.w("Saathi","Stopped a repetition loop");
                                looped.set(true);done.countDown();
                            }
                            partial=answer.toString();
                        }
                        post(ticket,()->listener.onPartial(clean(partial)));
                    }
                    @Override public void onDone(){ended.set(true);done.countDown();}
                    @Override public void onError(Throwable e){
                        if(looped.get())return;  // our own cancel after trimming a loop
                        if(generation.get()==ticket)android.util.Log.e("Saathi","Inference failed",e);
                        failure.set("I couldn't finish understanding this view.");ended.set(true);done.countDown();
                    }
                });
                boolean finished=done.await(ANSWER_TIMEOUT_SECONDS,TimeUnit.SECONDS);
                if(looped.get()){conversation.cancelProcess();Thread.sleep(150);}
                else if(!finished){
                    conversation.cancelProcess();done.await(5,TimeUnit.SECONDS);
                    failure.set("That took longer than expected.");reset=true;
                }
                ended.set(true);
                if(generation.get()!=ticket)return;
                String text;synchronized(answer){text=clean(answer.toString());}
                if(failure.get()!=null){String f=failure.get();post(ticket,()->listener.onError(f));}
                else if(text.isEmpty())post(ticket,()->listener.onError("I couldn't describe this view."));
                else{android.util.Log.i("Saathi","Answer in "+(android.os.SystemClock.elapsedRealtime()-started)+" ms, "+text.length()+" chars: "+text.replace('\n',' '));post(ticket,()->listener.onComplete(text));}
            }catch(Exception|LinkageError e){
                reset=true;android.util.Log.e("Saathi","Assistant engine failed",e);
                post(ticket,()->listener.onError("Detailed assistance could not start."));
            }finally{
                ended.set(true);active=null;
                if(conversation!=null)try{conversation.close();}catch(Exception ignored){}
                if(reset)dropEngine();
            }
        });
    }

    private static final java.util.regex.Pattern LOOP=java.util.regex.Pattern.compile("(.{1,24}?)(?:\\s*\\1){4,}\\s*$",java.util.regex.Pattern.DOTALL);
    /** Index where a trailing run of 5+ identical short chunks begins (keeping one copy), or -1. */
    static int loopStart(CharSequence text){
        if(text.length()<20)return -1;
        java.util.regex.Matcher m=LOOP.matcher(text);
        return m.find()?m.start()+m.group(1).length():-1;
    }

    /** Extracts only text parts, independent of how the runtime formats Contents. */
    private static String text(Message chunk){
        StringBuilder out=new StringBuilder();
        for(Content part:chunk.getContents().getContents())if(part instanceof Content.Text t)out.append(t.getText());
        return out.toString();
    }
    static String clean(String raw){return raw.replace("**","").replace("##","").replaceAll("(?m)^\\s*[-*•]\\s+","").trim();}

    private void dropEngine(){if(engine!=null){if(backend.equals("GPU"))preferCpu=true;try{engine.close();}catch(Exception ignored){}engine=null;}}
    private void cancelActive(){Conversation c=active;if(c!=null)try{c.cancelProcess();}catch(Exception ignored){}}
    private void post(int ticket,Runnable action){main.post(()->{if(!closed&&generation.get()==ticket)action.run();});}

    /** Stops the current answer. The engine stays loaded for the next request. */
    public void cancel(){generation.incrementAndGet();cancelActive();}

    /** Frees model memory, e.g. when Saathi leaves the foreground. */
    public void release(){cancel();if(!closed)executor.execute(()->{if(engine!=null){try{engine.close();}catch(Exception ignored){}engine=null;}});}

    @Override public void close(){release();closed=true;executor.shutdown();}
}
