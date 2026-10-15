package com.findback.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends ComponentActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private GemmaAssistant gemma;
    private final ExecutorService importer=Executors.newSingleThreadExecutor();
    private volatile AssistLanguage language=AssistLanguage.ENGLISH;
    private volatile boolean scanGemma=false;
    private volatile String pendingQuestion="";
    private volatile AssistLanguage pendingLanguage=AssistLanguage.ENGLISH;
    private EditText question;
    private TextView gemmaStatus,languageStatus;
    private boolean importing=false, voiceAvailable=true;
    private final com.google.mlkit.vision.text.TextRecognizer hindiReader=TextRecognition.getClient(new com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build());
    private final AtomicBoolean busy=new AtomicBoolean();
    private final com.google.mlkit.vision.text.TextRecognizer reader=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private DetectionOverlay overlay; private volatile boolean paused=false; private PreviewView preview; private TextView status,result,modeLabel; private EditText target;
    private TextToSpeech speech; private boolean speechReady=false; private volatile boolean scanText=false, destroyed=false;
    private volatile boolean searchActive=false;
    private volatile String mode="find", requested="bottle";
    private String lastMessage="Welcome to FindBack. Choose Find or Read, then scan your surroundings.",lastCue="";
    private long lastFrame=0,lastSpoken=0,lastSaved=0; private String savedTarget="",savedPosition=""; private volatile YoloDetector detector;
    private final int ink=Color.rgb(10,16,28), mint=Color.rgb(99,227,193), muted=Color.rgb(172,185,203);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        gemma=new GemmaAssistant(this);
        speech=new TextToSpeech(this,s->runOnUiThread(()->{speechReady=s==TextToSpeech.SUCCESS;if(speechReady)configureVoice();}));
        buildUi();
        worker.execute(()->{try{detector=new YoloDetector(this);runOnUiThread(()->status.setText("ON DEVICE · YOLO READY"));}catch(Exception e){runOnUiThread(()->status.setText("OCR READY · YOLO MODEL NOT INSTALLED"));}});
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},10);
    }
    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);root.setPadding(22,14,22,18);root.setBackgroundColor(ink);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(22,insets.getSystemWindowInsetTop()+14,22,insets.getSystemWindowInsetBottom()+18);return insets;});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
        TextView brand=text("FindBack",32,Color.WHITE);brand.setTypeface(null,Typeface.BOLD);root.addView(brand);
        root.addView(text("Your surroundings, made clearer.",16,muted));
        status=text("STARTING CAMERA…",12,mint);status.setPadding(0,14,0,12);root.addView(status);
        Spinner languages=new Spinner(this);String[] names=Arrays.stream(AssistLanguage.values()).map(l->l.label).toArray(String[]::new);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);languages.setAdapter(adapter);languages.setContentDescription("Response language");root.addView(languages);
        languageStatus=text("Voice: English",13,muted);root.addView(languageStatus);
        languages.setSelection(getPreferences(MODE_PRIVATE).getInt("language",0));
        languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long id){language=AssistLanguage.values()[position];getPreferences(MODE_PRIVATE).edit().putInt("language",position).apply();gemma.unload();configureVoice();}});
        FrameLayout camera=new FrameLayout(this); preview=new PreviewView(this); preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);camera.addView(preview,new FrameLayout.LayoutParams(-1,-1));overlay=new DetectionOverlay(this);camera.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        TextView frameHint=text("Point camera toward objects or signs",14,Color.WHITE);frameHint.setBackgroundColor(0xAA0A101C);frameHint.setPadding(12,8,12,8);FrameLayout.LayoutParams hint=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);camera.addView(frameHint,hint);root.addView(camera,new LinearLayout.LayoutParams(-1,dp(240)));
        LinearLayout tabs=new LinearLayout(this);root.addView(tabs);addButton(tabs,"Find object",()->{mode="find";modeLabel.setText("FIND AN EVERYDAY OBJECT");say("Find mode. Enter an object or use voice command.");});addButton(tabs,"Read text",()->{searchActive=false;mode="read";modeLabel.setText("READ A SIGN OR LABEL");say("Read mode. Point at text and tap scan.");});
        modeLabel=text("FIND AN EVERYDAY OBJECT",12,mint);modeLabel.setPadding(0,14,0,8);root.addView(modeLabel);
        target=new EditText(this);target.setSingleLine(true);target.setText("bottle");target.setTextColor(Color.WHITE);target.setHintTextColor(muted);target.setHint("Object to find, for example chair");target.setContentDescription("Object to find");root.addView(target,new LinearLayout.LayoutParams(-1,dp(56)));
        LinearLayout actions=new LinearLayout(this);root.addView(actions);addButton(actions,"Scan now",this::scan);addButton(actions,"Voice command",this::listen);
        result=text(lastMessage,21,Color.WHITE);result.setPadding(18,18,18,18);result.setBackground(card());result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(result,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout more=new LinearLayout(this);root.addView(more);addButton(more,"Repeat",()->say(lastMessage));addButton(more,"Stop audio",()->{if(speech!=null)speech.stop();});
        LinearLayout memory=new LinearLayout(this);root.addView(memory);addButton(memory,"Last seen",this::recall);addButton(memory,"Pause scan",()->{searchActive=false;scanText=false;overlay.update(null,1,1);publish("Scanning paused.",true);});
        gemmaStatus=text(gemma.installed()?"GEMMA 4 E2B · MODEL IMPORTED":"GEMMA 4 E2B · IMPORT MODEL TO ENABLE",13,mint);gemmaStatus.setPadding(0,14,0,8);root.addView(gemmaStatus);
        question=new EditText(this);question.setText("What is in front of me?");question.setTextColor(Color.WHITE);question.setHintTextColor(muted);question.setHint("Ask about the current scene");question.setContentDescription("Question for Gemma about the current scene");root.addView(question,new LinearLayout.LayoutParams(-1,dp(60)));
        LinearLayout aiActions=new LinearLayout(this);root.addView(aiActions);addButton(aiActions,"Ask Gemma",this::askScene);addButton(aiActions,"Release AI RAM",()->{scanGemma=false;gemma.unload();gemmaStatus.setText("GEMMA · RELEASING MODEL MEMORY");});
        LinearLayout modelActions=new LinearLayout(this);root.addView(modelActions);addButton(modelActions,"Import Gemma",this::chooseModel);addButton(modelActions,"Official model",()->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/6e5c4f1e395deb959c494953478fa5cec4b8008f")));}catch(ActivityNotFoundException e){publish("Open the model link in docs/MAC_SETUP.md on your Mac.",false);}});
        root.addView(text("Positions describe the camera view. This prototype does not assess safe routes or measure distance.",13,muted));
    }
    private GradientDrawable card(){GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(24,36,53));d.setCornerRadius(dp(16));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density);}
    private void addButton(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(ink);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(56));b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mint));b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(62),1);p.setMargins(3,6,3,6);parent.addView(b,p);}
    private void scan(){
        gemma.unload();scanGemma=false;
        if(mode.equals("read")){scanText=true;publish("Hold the camera steady while I read.",true);return;}
        String q=AssistLanguage.canonicalObject(target.getText().toString());
        if(q.equals("phone"))q="cell phone";if(q.equals("sofa"))q="couch";
        if(!Arrays.asList(YoloDetector.LABELS).contains(q)){publish("That object is not supported by this detector. Try bottle, chair, cup, book, or cell phone.",true);return;}
        requested=q;searchActive=true; lastCue="";lastSpoken=0;
        if(detector==null){searchActive=false;publish("Object detection needs the YOLO model. Text reading is available in Read mode.",true);}else publish("Looking for "+requested+". Slowly scan the camera.",true);
    }
    private void startCamera(){
        var future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{try{
            var provider=future.get();Preview p=new Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build();p.setSurfaceProvider(preview.getSurfaceProvider());
            ImageAnalysis a=new ImageAnalysis.Builder().setTargetResolution(new android.util.Size(640,480)).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
            a.setAnalyzer(worker,this::analyze);provider.unbindAll();provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,a);
        }catch(Exception e){publish("Camera unavailable. Check camera permission and close other camera apps.",true);}},androidx.core.content.ContextCompat.getMainExecutor(this));
    }
    private void analyze(ImageProxy image){
        long now=SystemClock.elapsedRealtime();
        if(destroyed||paused||(!scanGemma&&!scanText&&(!searchActive||mode.equals("read")||now-lastFrame<900))||!busy.compareAndSet(false,true)){image.close();return;}
        lastFrame=now;Bitmap bitmap=null;
        try{
            Bitmap frame=image.toBitmap();
            Matrix m=new Matrix();m.postRotate(image.getImageInfo().getRotationDegrees());
            bitmap=Bitmap.createBitmap(frame,0,0,frame.getWidth(),frame.getHeight(),m,true);
            if(frame!=bitmap)frame.recycle();
            if(scanGemma){scanGemma=false;
                int width=bitmap.getWidth(),height=bitmap.getHeight();float ratio=Math.min(1f,512f/Math.max(width,height));Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(width*ratio)),Math.max(1,(int)(height*ratio)),true);
                java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,80,encoded);if(small!=bitmap)small.recycle();
                byte[] jpeg=encoded.toByteArray();String prompt=pendingQuestion;AssistLanguage selected=pendingLanguage;
                runOnUiThread(()->{if(destroyed||paused)return;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
                    gemma.ask(jpeg,prompt,selected,new GemmaAssistant.Listener(){
                        public void onStatus(String message){gemmaStatus.setText(message);}
                        public void onPartial(String text){result.setText(text);}
                        public void onComplete(String text,long first,long total){result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);gemmaStatus.setText(String.format(Locale.ROOT,"GEMMA · first token %.1fs · total %.1fs",first/1000.0,total/1000.0));publish(text,true);}
                        public void onError(String message){result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);gemmaStatus.setText("GEMMA · NOT RUNNING");publish(message,false);}
                    });});busy.set(false);
            }else if(scanText){scanText=false;final Bitmap captured=bitmap;
                (language==AssistLanguage.HINDI?hindiReader:reader).process(InputImage.fromBitmap(captured,0)).addOnSuccessListener(t->{String s=t.getText().trim();publish(s.isEmpty()?"No readable text found. Move closer and improve the lighting.":s,true);}).addOnFailureListener(e->publish("Text could not be read. Please try again.",true)).addOnCompleteListener(t->{captured.recycle();busy.set(false);}); bitmap=null;
            } else {
                if(detector!=null){var hits=detector.detect(bitmap);YoloDetector.Hit found=null;for(var h:hits)if(h.label().equals(requested)){found=h;break;}
                    final YoloDetector.Hit display=found;final int bw=bitmap.getWidth(),bh=bitmap.getHeight();runOnUiThread(()->overlay.update(display,bw,bh));
                    if(found!=null){String position=found.x()<.33?"left":found.x()>.67?"right":"centre";String cue=language.detected(requested,position);
                        if(now-lastSaved>10000||!requested.equals(savedTarget)||!position.equals(savedPosition)){getPreferences(MODE_PRIVATE).edit().putLong("seen:"+requested,System.currentTimeMillis()).putString("position:"+requested,position).apply();lastSaved=now;savedTarget=requested;savedPosition=position;}
                        if(!cue.equals(lastCue)||now-lastSpoken>7000){lastCue=cue;lastSpoken=now;publish(cue,true);runOnUiThread(()->{Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v!=null&&v.hasVibrator())v.vibrate(VibrationEffect.createOneShot(90,VibrationEffect.DEFAULT_AMPLITUDE));});}
                    }else if(now-lastSpoken>9000){lastSpoken=now;lastCue="";publish(language.notFound(requested),true);}
                }
                busy.set(false);
            }
        }catch(Exception e){busy.set(false);publish("Could not process this frame. Try scanning again.",false);}
        finally{if(bitmap!=null)bitmap.recycle();image.close();}
    }
    private void publish(String message,boolean speak){runOnUiThread(()->{if(destroyed||paused)return;lastMessage=message;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);result.setText(message);if(speak)say(message);});}
    private void configureVoice(){if(!speechReady||languageStatus==null)return;Locale locale=Locale.forLanguageTag(language.tag);int supported=speech.setLanguage(locale);voiceAvailable=supported>=TextToSpeech.LANG_AVAILABLE;languageStatus.setText(voiceAvailable?"Voice: "+language.label:"Voice missing for "+language.label+". Install it in Android text-to-speech settings. Text responses remain available.");}
    private void say(String message){if(speechReady&&voiceAvailable)speech.speak(message.substring(0,Math.min(message.length(),TextToSpeech.getMaxSpeechInputLength())),TextToSpeech.QUEUE_FLUSH,null,"feedback");}
    private void askScene(){
        if(importing||gemma.isBusy()){publish("Please finish or cancel the current model operation first.",false);return;}
        if(!gemma.installed()){publish("Import Gemma 4 E2B first. Download and verification instructions are in docs/MAC_SETUP.md.",false);return;}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){publish("Enable camera permission in Android app settings first.",false);return;}
        searchActive=false;scanText=false;overlay.update(null,1,1);if(speech!=null)speech.stop();pendingQuestion=GemmaPrompts.question(question.getText().toString());pendingLanguage=language;scanGemma=true;gemmaStatus.setText("GEMMA · CAPTURING ONE FRAME");
    }
    private void chooseModel(){
        if(importing||gemma.isBusy()){publish("Finish or cancel the current model operation before importing.",false);return;}
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(pick,30);}catch(ActivityNotFoundException e){publish("No Android file picker is available.",false);}
    }
    private void importModel(android.net.Uri uri){
        if(importing)return;importing=true;gemma.unload();gemmaStatus.setText("GEMMA · IMPORTING 2.59 GB · keep app open");
        importer.execute(()->{java.io.File destination=gemma.modelFile();java.io.File temp=new java.io.File(destination.getParentFile(),"import.partial");
            try{destination.getParentFile().mkdirs();if(destination.getParentFile().getUsableSpace()<GemmaAssistant.MODEL_BYTES+134217728L)throw new java.io.IOException("Not enough storage");long count=0;java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                try(java.io.InputStream input=getContentResolver().openInputStream(uri);java.io.OutputStream output=new java.io.FileOutputStream(temp)){
                    if(input==null)throw new java.io.IOException("File unavailable");byte[] buffer=new byte[1024*1024];int n;while((n=input.read(buffer))!=-1){if(destroyed)throw new java.io.IOException("Import cancelled");count+=n;if(count>GemmaAssistant.MODEL_BYTES)throw new java.io.IOException("Wrong model size");output.write(buffer,0,n);digest.update(buffer,0,n);}
                }
                if(count!=GemmaAssistant.MODEL_BYTES)throw new java.io.IOException("Wrong model size");
                StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",value&255));if(!hash.toString().equals(GemmaAssistant.MODEL_SHA256))throw new java.io.IOException("Model checksum mismatch");
                java.nio.file.Files.move(temp.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                runOnUiThread(()->{if(!destroyed)gemmaStatus.setText("GEMMA 4 E2B · IMPORTED · ready to load on request");});
            }catch(Exception e){temp.delete();runOnUiThread(()->{if(!destroyed)gemmaStatus.setText("Import failed: "+e.getMessage()+". Use the pinned model in the Mac guide.");});}
            finally{runOnUiThread(()->importing=false);}
        });
    }
    private void recall(){String q=target.getText().toString().trim().toLowerCase(Locale.ROOT);if(q.equals("phone"))q="cell phone";long time=getPreferences(MODE_PRIVATE).getLong("seen:"+q,0);if(time==0){publish("I have no saved sighting of "+q+" on this device.",true);return;}String at=java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(time));publish("A "+q+" was last detected at "+at+", in the "+getPreferences(MODE_PRIVATE).getString("position:"+q,"centre")+" of the camera view. This is a past observation, not its current location.",true);}
    private void listen(){Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,language.tag);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Say find a bottle, read text, or repeat");try{startActivityForResult(i,20);}catch(ActivityNotFoundException e){publish("Speech recognition is unavailable. Use the object field and scan button.",true);}}
    @Override protected void onActivityResult(int request,int response,Intent data){super.onActivityResult(request,response,data);if(request==30&&response==RESULT_OK&&data!=null&&data.getData()!=null){importModel(data.getData());return;}if(request==20&&response==RESULT_OK&&data!=null){ArrayList<String> values=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);if(values==null||values.isEmpty())return;String s=values.get(0).toLowerCase(Locale.ROOT);if(s.contains("read")||s.contains("पढ़")||s.contains("ಓದ")){searchActive=false;mode="read";modeLabel.setText("READ A SIGN OR LABEL");scan();}else if(s.contains("repeat")||s.contains("दोबारा")||s.contains("ಮತ್ತೆ"))say(lastMessage);else{String object=AssistLanguage.voiceObject(s);if(object==null){question.setText(values.get(0));askScene();}else{mode="find";modeLabel.setText("FIND AN EVERYDAY OBJECT");target.setText(object);scan();}}}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==10&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCamera();else publish("Camera permission is required. Enable it in Android app settings.",true);}
    @Override protected void onResume(){super.onResume();paused=false;}
    @Override protected void onPause(){paused=true;super.onPause();scanText=false;scanGemma=false;gemma.unload();if(speech!=null)speech.stop();}
    @Override protected void onDestroy(){destroyed=true;gemma.close();importer.shutdown();worker.execute(()->{try{if(detector!=null)detector.close();}catch(Exception ignored){}});worker.shutdown();reader.close();hindiReader.close();if(speech!=null)speech.shutdown();super.onDestroy();}
}
