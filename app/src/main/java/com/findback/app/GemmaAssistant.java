package com.findback.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.ai.edge.litertlm.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One engine, one bounded request. Models are external to APK and never loaded on the UI thread. */
public final class GemmaAssistant implements AutoCloseable {
    public static final String MODEL_NAME="gemma-4-E2B-it.litertlm";
    public static final String MODEL_SHA256="181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c";
    public static final long MODEL_BYTES=2588147712L;
    private final File model, cache;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicInteger generation=new AtomicInteger();
    private volatile Conversation active;
    private volatile boolean closed;
    private Engine engine;
    private String backend="";
    public interface Listener {
        void onStatus(String message);
        void onPartial(String text);
        void onComplete(String text,long firstTokenMs,long totalMs);
        void onError(String message);
    }
    public GemmaAssistant(Context c){model=resolveModelFile(c);cache=new File(c.getCacheDir(),"gemma");}
    public static File resolveModelFile(Context c){return new File(c.getNoBackupFilesDir(),"models/"+MODEL_NAME);}
    public File modelFile(){return model;}
    public boolean installed(){return model.isFile()&&model.length()==MODEL_BYTES;}
    public boolean isBusy(){return busy.get();}
    @androidx.annotation.OptIn(markerClass=ExperimentalApi.class)
    private Engine initialize(Backend b){
        ExperimentalFlags.INSTANCE.setVisualTokenBudget(280);
        Engine candidate=new Engine(new EngineConfig(model.getAbsolutePath(),b,b,null,1024,1,cache.getAbsolutePath()));
        try{candidate.initialize();return candidate;}catch(RuntimeException|LinkageError e){try{candidate.close();}catch(Exception ignored){}throw e;}
    }
    public void ask(byte[] jpeg,String question,AssistLanguage language,Listener listener){
        if(closed)return;
        if(!installed()){listener.onError("Enable offline assistance to describe your surroundings.");return;}
        if(!busy.compareAndSet(false,true)){listener.onError("Your assistant is still working. Please wait a moment.");return;}
        int ticket=generation.get();
        executor.execute(()->{
            long start=SystemClock.elapsedRealtime();AtomicLong first=new AtomicLong(-1);AtomicBoolean ended=new AtomicBoolean();CountDownLatch done=new CountDownLatch(1);StringBuilder answer=new StringBuilder();AtomicReference<String> failure=new AtomicReference<>();
            Conversation conversation=null;
            try{
                if(engine==null){post(ticket,()->listener.onStatus("Preparing your assistant…"));cache.mkdirs();
                    try{engine=initialize(new Backend.GPU());backend="GPU";}catch(RuntimeException|LinkageError gpu){post(ticket,()->listener.onStatus("Preparing assistance for your phone…"));engine=initialize(new Backend.CPU(2,null));backend="CPU";}
                }
                if(closed||generation.get()!=ticket)return;
                post(ticket,()->listener.onStatus("Understanding your surroundings…"));
                ConversationConfig config=new ConversationConfig(Contents.Companion.of(GemmaPrompts.system(language)),Collections.emptyList(),Collections.emptyList(),new SamplerConfig(16,.9,.2,0),false,null,Collections.emptyMap(),null,false,64,new ThinkingConfig(false,0),false);
                conversation=engine.createConversation(config);active=conversation;
                final long requestStart=SystemClock.elapsedRealtime();
                Message message=Message.Companion.of(new Content.ImageBytes(jpeg),new Content.Text(GemmaPrompts.question(question)));
                conversation.sendMessageAsync(message,new MessageCallback(){
                    @Override public void onMessage(Message chunk){
                        if(ended.get()||generation.get()!=ticket||chunk.getContents().toString().isEmpty())return;
                        first.compareAndSet(-1,SystemClock.elapsedRealtime()-requestStart);
                        synchronized(answer){answer.append(chunk.getContents().toString());String partial=answer.toString();post(ticket,()->listener.onPartial(partial));}
                    }
                    @Override public void onDone(){ended.set(true);done.countDown();}
                    @Override public void onError(Throwable e){failure.set("I could not understand this view. Hold the camera steady and try again.");ended.set(true);done.countDown();}
                });
                if(!done.await(30,TimeUnit.SECONDS)){conversation.cancelProcess();done.await(5,TimeUnit.SECONDS);failure.set("That took longer than expected. Try again, or use Find and Read for immediate feedback.");}
                ended.set(true);
                if(failure.get()!=null)post(ticket,()->listener.onError(failure.get()));
                else{String text; synchronized(answer){text=answer.toString().trim();}long total=SystemClock.elapsedRealtime()-start;
                    if(text.isEmpty())post(ticket,()->listener.onError("I could not describe this view. Please try again."));else post(ticket,()->listener.onComplete(text,first.get(),total));}
            }catch(Exception|LinkageError e){post(ticket,()->listener.onError("Scene descriptions could not start. Close other apps and retry. You can still find objects and read text."));}
            finally{ended.set(true);active=null;if(conversation!=null)try{conversation.close();}catch(Exception ignored){}busy.set(false);}
        });
    }
    private void post(int ticket,Runnable action){main.post(()->{if(!closed&&generation.get()==ticket)action.run();});}
    /** Cancellation invalidates callbacks; resource destruction stays on the engine's serial executor. */
    public void unload(){generation.incrementAndGet();Conversation c=active;if(c!=null)try{c.cancelProcess();}catch(Exception ignored){}
        if(!closed)executor.execute(()->{if(engine!=null){try{engine.close();}catch(Exception ignored){}engine=null;}});
    }
    @Override public void close(){unload();closed=true;executor.shutdown();}
}
