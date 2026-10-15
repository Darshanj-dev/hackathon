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

public class MainActivity extends ComponentActivity implements android.hardware.SensorEventListener {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private GemmaAssistant gemma;
    private volatile AssistLanguage language=AssistLanguage.ENGLISH;
    private volatile boolean scanGemma=false;
    private volatile String pendingQuestion="";
    private volatile AssistLanguage pendingLanguage=AssistLanguage.ENGLISH;
    private EditText question;
    private TextView gemmaStatus,languageStatus;
    private boolean voiceAvailable=true;
    private TextView panelHint, responseHeading, cameraBadge;
    private ProgressBar progress;
    private final AtomicBoolean interpretationRequested=new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger interpretationEpoch=new java.util.concurrent.atomic.AtomicInteger();
    private LinearLayout recovery;
    private Button languageButton;
    private boolean cameraReady=false, frontCamera=false;
    private ProcessCameraProvider cameraProvider;
    private final ExecutorService initialization=Executors.newSingleThreadExecutor();
    private Button primary,findTab,readTab,describeTab,placeTab,verifyButton,confirmButton;
    private final PlaceSession place=new PlaceSession();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private android.hardware.SensorManager sensors;
    private volatile boolean scanPlace=false,verifyPlace=false;
    private long lastMotion=0;
    private final java.util.concurrent.atomic.AtomicInteger sceneEpoch=new java.util.concurrent.atomic.AtomicInteger();
    private final Runnable expiry=new Runnable(){public void run(){if(destroyed||paused)return;if(place.expire(SystemClock.elapsedRealtime())){updatePlaceControls();if(mode.equals("place"))publish(place.message(),true);}ui.postDelayed(this,500);}};
    private LinearLayout inputArea;
    private ScrollView page;
    private final com.google.mlkit.vision.text.TextRecognizer hindiReader=TextRecognition.getClient(new com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build());
    private final AtomicBoolean busy=new AtomicBoolean();
    private final com.google.mlkit.vision.text.TextRecognizer reader=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private DetectionOverlay overlay; private volatile boolean paused=false; private PreviewView preview; private TextView status,result,modeLabel; private EditText target;
    private TextToSpeech speech; private boolean speechReady=false; private volatile boolean scanText=false, destroyed=false;
    private volatile boolean searchActive=false;
    private volatile String mode="find", requested="bottle";
    private String lastMessage="Ready when you are. Find an object, read text, or describe your surroundings.",lastCue="";
    private long lastFrame=0,lastSpoken=0,lastSaved=0; private String savedTarget="",savedPosition=""; private volatile YoloDetector detector;
    private final int accent=Color.rgb(26,76,70);
    private final int ink=Color.rgb(17,17,17), muted=Color.rgb(99,99,105);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        gemma=new GemmaAssistant(this);sensors=(android.hardware.SensorManager)getSystemService(SENSOR_SERVICE);
        speech=new TextToSpeech(this,s->runOnUiThread(()->{speechReady=s==TextToSpeech.SUCCESS;if(speechReady){configureVoice();speech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener(){public void onStart(String id){}public void onDone(String id){runOnUiThread(()->{if(!destroyed)status.setText("Guidance complete · Replay to hear again");});}public void onError(String id){runOnUiThread(()->{if(!destroyed)status.setText("Spoken guidance unavailable · Text remains available");});}});}}));
        buildUi();
        initialization.execute(()->{try{YoloDetector loaded=new YoloDetector(this);if(destroyed){loaded.close();return;}detector=loaded;}catch(Exception|LinkageError e){android.util.Log.e("Saathi","Object detector initialization failed",e);runOnUiThread(()->status.setText("Object finding unavailable · Read remains available"));}});
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},10);
    }
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(250,250,248));
        root.setPadding(dp(24),dp(12),dp(24),dp(28));
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(24),insets.getSystemWindowInsetTop()+dp(12),dp(24),insets.getSystemWindowInsetBottom()+dp(28));return insets;});
        page=new ScrollView(this);page.setFillViewport(true);page.setClipToPadding(false);page.addView(root);setContentView(page);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header);
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=text("Saathi",30,ink);title.setTypeface(null,Typeface.BOLD);title.setLetterSpacing(-.04f);brand.addView(title);
        TextView subtitle=text("Your world, understood.",15,muted);subtitle.setPadding(0,dp(4),0,0);brand.addView(subtitle);
        Button locale=button("English ▾",false,()->new android.app.AlertDialog.Builder(this).setTitle("Response language").setSingleChoiceItems(new String[]{"English","हिन्दी","ಕನ್ನಡ"},language.ordinal(),(dialog,which)->{language=AssistLanguage.values()[which];getPreferences(MODE_PRIVATE).edit().putInt("language",which).apply();gemma.unload();configureVoice();dialog.dismiss();}).setNegativeButton("Close",null).show());languageButton=locale;header.addView(locale,new LinearLayout.LayoutParams(dp(108),dp(48)));
        language=AssistLanguage.values()[Math.min(2,Math.max(0,getPreferences(MODE_PRIVATE).getInt("language",0)))];
        languageStatus=text("English · Spoken guidance",12,muted);languageStatus.setPadding(0,dp(18),0,dp(12));root.addView(languageStatus);
        FrameLayout camera=new FrameLayout(this);camera.setBackground(shape(Color.rgb(242,242,244),20,false));camera.setClipToOutline(true);root.addView(camera,new LinearLayout.LayoutParams(-1,dp(280)));camera.post(()->{android.view.ViewGroup.LayoutParams params=camera.getLayoutParams();params.height=camera.getWidth()*3/4;camera.setLayoutParams(params);});
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);camera.addView(preview,new FrameLayout.LayoutParams(-1,-1));overlay=new DetectionOverlay(this);camera.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        TextView live=text("  Starting camera…  ",11,ink);cameraBadge=live;live.setTypeface(null,Typeface.BOLD);live.setGravity(Gravity.CENTER);live.setBackground(shape(Color.WHITE,8,false));FrameLayout.LayoutParams liveParams=new FrameLayout.LayoutParams(-2,dp(30),Gravity.TOP|Gravity.START);liveParams.setMargins(dp(12),dp(12),0,0);camera.addView(live,liveParams);
        Button switchCamera=button("Switch",false,()->{if(busy.get()||gemma.isBusy())return;stopAssistance();frontCamera=!frontCamera;startCamera();});switchCamera.setContentDescription("Switch front and back camera");FrameLayout.LayoutParams switchParams=new FrameLayout.LayoutParams(dp(80),dp(48),Gravity.BOTTOM|Gravity.END);switchParams.setMargins(0,0,dp(12),dp(12));camera.addView(switchCamera,switchParams);
        status=text("Preparing camera…",12,muted);status.setPadding(0,dp(10),0,dp(20));root.addView(status);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(4),dp(4),dp(4),dp(4));tabs.setBackground(shape(Color.rgb(244,244,245),14,false));HorizontalScrollView modeScroll=new HorizontalScrollView(this);modeScroll.setHorizontalScrollBarEnabled(false);modeScroll.setFillViewport(true);modeScroll.addView(tabs,new HorizontalScrollView.LayoutParams(-1,dp(76)));root.addView(modeScroll,new LinearLayout.LayoutParams(-1,dp(76)));
        placeTab=tab(tabs,"Places",()->selectMode("place"));findTab=tab(tabs,"Find",()->selectMode("find"));readTab=tab(tabs,"Read",()->selectMode("read"));describeTab=tab(tabs,"Describe",()->selectMode("describe"));
        modeLabel=text("What would you like to find?",19,ink);modeLabel.setTypeface(null,Typeface.BOLD);modeLabel.setPadding(0,dp(24),0,dp(12));root.addView(modeLabel);
        panelHint=text("",14,muted);panelHint.setPadding(0,0,0,dp(16));root.addView(panelHint);
        inputArea=new LinearLayout(this);inputArea.setOrientation(LinearLayout.VERTICAL);root.addView(inputArea);
        target=field("Object to find","bottle");inputArea.addView(target,new LinearLayout.LayoutParams(-1,dp(58)));
        question=field("What would you like to know?","");inputArea.addView(question,new LinearLayout.LayoutParams(-1,dp(58)));question.setVisibility(View.GONE);
        LinearLayout actionRow=new LinearLayout(this);LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,dp(76));actionParams.setMargins(0,dp(8),0,0);actionParams.height=-2;actionRow.setMinimumHeight(dp(76));root.addView(actionRow,actionParams);
        primary=button("Find object",true,this::performAction);LinearLayout.LayoutParams primaryParams=new LinearLayout.LayoutParams(0,-2,1);primary.setMinHeight(dp(58));primaryParams.setMargins(0,dp(8),dp(8),0);actionRow.addView(primary,primaryParams);
        Button voice=button("Speak",false,this::listen);voice.setCompoundDrawables(null,new ActionIcon("Speak",ink,dp(20)),null,null);voice.setTextSize(12);voice.setContentDescription("Speak a request");LinearLayout.LayoutParams voiceParams=new LinearLayout.LayoutParams(dp(92),dp(58));voiceParams.setMargins(0,dp(8),0,0);actionRow.addView(voice,voiceParams);
        LinearLayout response=new LinearLayout(this);response.setOrientation(LinearLayout.VERTICAL);response.setPadding(dp(20),dp(20),dp(20),dp(12));response.setBackground(shape(Color.rgb(247,247,248),18,false));root.addView(response);
        TextView responseTitle=text("READY TO HELP",11,muted);responseHeading=responseTitle;responseTitle.setLetterSpacing(.12f);responseTitle.setTypeface(null,Typeface.BOLD);response.addView(responseTitle);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);response.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));
        result=text(lastMessage,20,ink);result.setLineSpacing(dp(4),1);result.setPadding(0,dp(12),0,dp(8));result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);response.addView(result);
        recovery=new LinearLayout(this);recovery.setOrientation(LinearLayout.HORIZONTAL);recovery.setVisibility(View.GONE);response.addView(recovery);addButton(recovery,"Try again",this::performAction);addButton(recovery,"Other assistance",()->selectMode("read"));
        LinearLayout audio=new LinearLayout(this);response.addView(audio);addButton(audio,"Replay",()->say(lastMessage));addButton(audio,"Stop",()->{stopAssistance();publish("Stopped. Ready whenever you are.",false);});
        LinearLayout verification=new LinearLayout(this);root.addView(verification);
        verifyButton=button("Verify entrance",false,()->{if(place.requestVerification(SystemClock.elapsedRealtime())){updatePlaceControls();gemma.unload();interpretationEpoch.incrementAndGet();interpretationRequested.set(false);verifyPlace=true;scanPlace=true;processing("Verifying the entrance label… Hold steady.");}else publish("Scan a washroom sign first.",true);});
        confirmButton=button("Confirm entrance",true,()->{interpretationEpoch.incrementAndGet();gemma.unload();place.confirm(SystemClock.elapsedRealtime());updatePlaceControls();publish(place.message(),true);});
        verification.addView(verifyButton,new LinearLayout.LayoutParams(0,dp(54),1));LinearLayout.LayoutParams confirmParams=new LinearLayout.LayoutParams(0,dp(54),1);confirmParams.setMargins(dp(8),0,0,0);verification.addView(confirmButton,confirmParams);
        gemmaStatus=text("",12,muted);gemmaStatus.setPadding(0,dp(10),0,0);gemmaStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(gemmaStatus);
        LinearLayout extras=new LinearLayout(this);root.addView(extras);addButton(extras,"Evidence",this::showEvidence);addButton(extras,"Cancel",()->{stopAssistance();place.cancel();updatePlaceControls();publish(place.message(),true);});addButton(extras,"Help",this::showHelp);
        TextView footnote=text("Camera positions are approximate. Continue using your usual mobility support.",12,muted);footnote.setLineSpacing(dp(3),1);footnote.setPadding(0,dp(16),0,0);root.addView(footnote);
        selectMode("describe");
    }
    private void stopAssistance(){interpretationEpoch.incrementAndGet();interpretationRequested.set(false);ui.removeCallbacks(captureTimeout);if(progress!=null)setResponseState("READY TO HELP",false,false);sceneEpoch.incrementAndGet();scanPlace=false;searchActive=false;scanText=false;scanGemma=false;place.pause();gemma.unload();overlay.update(null,1,1);result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);gemmaStatus.setText("");if(speech!=null)speech.stop();}
    private void selectMode(String selected){stopAssistance();mode=selected;
        boolean find=selected.equals("find"),read=selected.equals("read"),places=selected.equals("place");
        target.setVisibility(find?View.VISIBLE:View.GONE);target.setEnabled(!places);if(places)target.setText("Washroom");else if(find&&target.getText().toString().equals("Washroom"))target.setText("bottle");
        question.setVisibility(selected.equals("describe")?View.VISIBLE:View.GONE);inputArea.setVisibility(read||places?View.GONE:View.VISIBLE);
        modeLabel.setText(places?"Find a washroom":find?"What would you like to find?":read?"Point your camera at text":"Understand your surroundings");
        primary.setText(places?"Identify washroom signs":find?"Find object":read?"Read text":"Describe my surroundings");
        panelHint.setText(places?"Scan an English washroom, restroom or toilet sign, then verify the entrance label.":find?"Choose an everyday object, then scan slowly.":read?"Hold printed text in view. English and Hindi scripts supported.":"Get a spoken description of what’s around you.");
        publish(places?"Aim at a readable washroom sign to begin.":find?"Which object would you like to locate?":read?"Point your camera at a sign, page or label.":"I’m ready to help you explore your surroundings.",false);
        styleTab(placeTab,places);styleTab(findTab,find);styleTab(readTab,read);styleTab(describeTab,selected.equals("describe"));updatePlaceControls();responseHeading.setText("READY TO HELP");
        if(selected.equals("describe")&&!gemma.installed()){showError("Scene descriptions aren’t available on this installation. Choose Find, Read or Places for assistance.");responseHeading.setText("SCENE DESCRIPTIONS UNAVAILABLE");primary.setEnabled(false);primary.setAlpha(.5f);}
    }
    private void updatePlaceControls(){if(verifyButton==null)return;boolean visible=mode.equals("place");long now=SystemClock.elapsedRealtime();verifyButton.setVisibility(visible&&place.canVerify(now)?View.VISIBLE:View.GONE);confirmButton.setVisibility(visible&&place.canConfirm(now)?View.VISIBLE:View.GONE);verifyButton.setEnabled(place.canVerify(now));confirmButton.setEnabled(place.canConfirm(now));verifyButton.setAlpha(verifyButton.isEnabled()?1f:.4f);confirmButton.setAlpha(confirmButton.isEnabled()?1f:.4f);}
    private void showEvidence(){StringBuilder details=new StringBuilder();long now=SystemClock.elapsedRealtime();for(PlaceSession.Evidence evidence:place.evidence())details.append("OCR · ").append(Math.max(0,(now-evidence.capturedAt())/1000)).append(" seconds ago\n").append(evidence.text()).append("\n\n");new android.app.AlertDialog.Builder(this).setTitle("Recent sign evidence").setMessage(details.length()==0?"No sign observations saved in this session.":details.toString()+"Past observations do not establish a current direction.").setPositiveButton("Close",null).show();}
    private void showHelp(){new android.app.AlertDialog.Builder(this).setTitle("Here to help").setMessage("Use Saathi while stationary. Left and right describe the camera view. Scan a sign, verify a fresh entrance label, then confirm it yourself. Use your usual mobility support.\n\nRead speaks printed text. Find locates supported object categories. Describe explains a single camera view.").setPositiveButton("Close",null).setNeutralButton("Open phone dialer",(d,w)->{try{startActivity(new Intent(Intent.ACTION_DIAL));}catch(ActivityNotFoundException e){publish("A phone dialer is not available.",false);}}).show();}
    private GradientDrawable shape(int color,int radius,boolean border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border)d.setStroke(dp(1),Color.rgb(220,220,224));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density);}
    private EditText field(String description,String initial){EditText e=new EditText(this);e.setSingleLine(true);e.setText(initial);e.setHint(description);e.setContentDescription(description);e.setTextSize(17);e.setTextColor(ink);e.setHintTextColor(muted);e.setPadding(dp(16),0,dp(16),0);e.setBackground(shape(Color.WHITE,12,true));return e;}
    private Button button(String label,boolean solid,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);b.setTextColor(solid?Color.WHITE:ink);b.setMinHeight(dp(48));b.setPadding(dp(12),0,dp(12),0);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000),shape(solid?accent:Color.WHITE,12,!solid),null));b.setStateListAnimator(null);b.setOnClickListener(v->action.run());return b;}
    private Button tab(LinearLayout row,String label,Runnable action){Button b=button(label,false,action);b.setTextSize(13);b.setGravity(Gravity.CENTER);b.setCompoundDrawablePadding(dp(4));b.setPadding(dp(3),dp(6),dp(3),dp(6));b.setMinWidth(dp(88));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-1,1);p.setMargins(dp(2),0,dp(2),0);row.addView(b,p);return b;}
    private void styleTab(Button b,boolean selected){b.setTextColor(selected?Color.WHITE:ink);b.setBackground(shape(selected?accent:Color.TRANSPARENT,10,false));b.setCompoundDrawables(null,new ActionIcon(b.getText().toString(),selected?Color.WHITE:ink,dp(20)),null,null);b.setSelected(selected);b.setContentDescription(b.getText()+ (selected?", selected":""));}
    private void addButton(LinearLayout parent,String label,Runnable action){Button b=button(label,false,action);b.setTextSize(13);if(label.equals("Replay")||label.equals("Stop"))b.setCompoundDrawables(new ActionIcon(label,ink,dp(18)),null,null,null);b.setBackgroundColor(Color.TRANSPARENT);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),1);parent.addView(b,p);}
    private void performAction(){if(busy.get()||scanText||scanPlace||scanGemma)return;if(gemma.isBusy()){gemmaStatus.setText("Understanding this image… Please wait, or tap Stop.");return;}if(!ensureCamera())return;if(mode.equals("describe"))askScene();else scan();}
    private boolean ensureCamera(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){new android.app.AlertDialog.Builder(this).setTitle("Allow camera access").setMessage("Saathi uses your camera to find objects, read text and understand your surroundings.").setPositiveButton("Open settings",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())))).setNegativeButton("Close",null).show();return false;}if(!cameraReady){showError("Camera is still starting. Try again in a moment.");startCamera();return false;}return true;}
    private void setResponseState(String heading,boolean processing,boolean error){responseHeading.setText(heading);progress.setVisibility(processing?View.VISIBLE:View.GONE);recovery.setVisibility(error?View.VISIBLE:View.GONE);primary.setEnabled(!processing);primary.setAlpha(processing?.5f:1f);}
    private void processing(String message){setResponseState("WORKING ON IT",true,false);result.setText(message);status.setText(message);ui.removeCallbacks(captureTimeout);ui.postDelayed(captureTimeout,12000);}
    private final Runnable captureTimeout=()->{if(scanText||scanPlace||scanGemma){scanText=false;scanPlace=false;scanGemma=false;showError("No camera frame arrived. Try again, or switch cameras.");}};
    private void showError(String message){runOnUiThread(()->{if(destroyed||paused)return;setResponseState("PLEASE TRY AGAIN",false,true);lastMessage=message;result.setText(message);status.setText("Assistance paused · Try again");});}
    private void scan(){
        interpretationRequested.set(false);interpretationEpoch.incrementAndGet();
        scanGemma=false;
        if(mode.equals("place")){if(place.state()==PlaceSession.State.IDLE||place.state()==PlaceSession.State.PAUSED||place.state()==PlaceSession.State.CONFIRMED)place.begin();place.invalidate("Scanning fresh evidence…");updatePlaceControls();verifyPlace=false;scanPlace=true;processing("Reading washroom signs… Hold steady.");return;}
        if(mode.equals("read")){scanText=true;processing("Reading text… Hold the camera steady.");return;}
        String q=AssistLanguage.canonicalObject(target.getText().toString());
        if(q.equals("phone"))q="cell phone";if(q.equals("sofa"))q="couch";
        if(!Arrays.asList(YoloDetector.LABELS).contains(q)){publish("That object is not supported by this detector. Try bottle, chair, cup, book, or cell phone.",true);return;}
        requested=q;searchActive=true; lastCue="";lastSpoken=0;
        if(detector==null){searchActive=false;pendingQuestion=TaskPrompts.build("find","Requested "+requested+". Detector unavailable; inspect the image directly.");pendingLanguage=language;scanGemma=true;processing("Looking for "+requested+" in the captured image…");}else publish("Looking for "+requested+". Slowly scan the camera.",true);
    }
    @androidx.annotation.OptIn(markerClass=androidx.camera.core.ExperimentalMirrorMode.class)
    private void startCamera(){
        cameraReady=false;cameraBadge.setText("  Starting camera…  ");
        var future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{try{
            var provider=future.get();cameraProvider=provider;cameraReady=false;Preview p=new Preview.Builder().setMirrorMode(MirrorMode.MIRROR_MODE_OFF).setTargetAspectRatio(AspectRatio.RATIO_4_3).build();p.setSurfaceProvider(preview.getSurfaceProvider());
            ImageAnalysis a=new ImageAnalysis.Builder().setTargetResolution(new android.util.Size(1280,960)).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
            a.setAnalyzer(worker,this::analyze);provider.unbindAll();CameraSelector selector=frontCamera?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;if(!provider.hasCamera(selector)){frontCamera=false;selector=CameraSelector.DEFAULT_BACK_CAMERA;}ViewPort viewPort=new ViewPort.Builder(new android.util.Rational(4,3),preview.getDisplay()==null?Surface.ROTATION_0:preview.getDisplay().getRotation()).setScaleType(ViewPort.FILL_CENTER).build();provider.bindToLifecycle(this,selector,new UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(p).addUseCase(a).build());
            preview.getPreviewStreamState().observe(this,state->{cameraReady=state==PreviewView.StreamState.STREAMING;cameraBadge.setText(cameraReady?"  ● Camera live  ":"  Camera starting…  ");if(cameraReady)status.setText("Camera ready · Images stay on your device");});
        }catch(Exception e){android.util.Log.e("Saathi","Camera binding failed",e);cameraReady=false;cameraBadge.setText("  Camera unavailable  ");showError("Camera unavailable. Check camera permission and close other camera apps.");}},androidx.core.content.ContextCompat.getMainExecutor(this));
    }
    private void analyze(ImageProxy image){
        long now=SystemClock.elapsedRealtime();final int frameEpoch=sceneEpoch.get();
        if(destroyed||paused||(!scanPlace&&!scanGemma&&!scanText&&(!searchActive||mode.equals("read")||now-lastFrame<900))||((searchActive&&!scanPlace&&!scanText&&!scanGemma)&&gemma.isBusy())||!busy.compareAndSet(false,true)){image.close();return;}
        lastFrame=now;Bitmap bitmap=null;
        try{
            Bitmap frame=CameraFrames.rgba(image);
            Matrix m=new Matrix();m.postRotate(image.getImageInfo().getRotationDegrees());
            android.graphics.Rect crop=image.getCropRect();bitmap=Bitmap.createBitmap(frame,crop.left,crop.top,crop.width(),crop.height(),m,true);
            if(frame!=bitmap)frame.recycle();
            if(tooDark(bitmap)){boolean explicit=scanPlace||scanText||scanGemma;scanPlace=false;scanText=false;scanGemma=false;place.invalidate("The view is too dark. Aim at a well-lit sign and scan again.");runOnUiThread(this::updatePlaceControls);if(explicit||now-lastSpoken>7000){lastSpoken=now;publish("The view is too dark. Move the camera toward a well-lit sign and hold steady.",true);}busy.set(false);return;}
            if(scanGemma){scanGemma=false;
                int width=bitmap.getWidth(),height=bitmap.getHeight();float ratio=Math.min(1f,512f/Math.max(width,height));Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(width*ratio)),Math.max(1,(int)(height*ratio)),true);
                java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,80,encoded);if(small!=bitmap)small.recycle();
                byte[] jpeg=encoded.toByteArray();String prompt=pendingQuestion;AssistLanguage selected=pendingLanguage;int sceneTicket=sceneEpoch.get();
                runOnUiThread(()->{if(destroyed||paused)return;ui.removeCallbacks(captureTimeout);result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
                    gemma.ask(jpeg,prompt,selected,new GemmaAssistant.Listener(){
                        public void onStatus(String message){gemmaStatus.setText(message);processing(message);ui.removeCallbacks(captureTimeout);}
                        public void onPartial(String text){if(sceneEpoch.get()==sceneTicket)result.setText(text);}
                        public void onComplete(String text,long first,long total){if(sceneEpoch.get()!=sceneTicket)return;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);gemmaStatus.setText("Description ready · "+language.label);publish(text,true);}
                        public void onError(String message){if(sceneEpoch.get()!=sceneTicket)return;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);gemmaStatus.setText("You can also use Find or Read.");showError(message);}
                    });});busy.set(false);
            }else if(scanText||scanPlace){boolean placeRequest=scanPlace,verificationRequest=verifyPlace;scanPlace=false;verifyPlace=false;scanText=false;final Bitmap captured=bitmap;final byte[] taskImage=jpeg(bitmap);final int taskTicket=interpretationEpoch.get();final long capturedAt=now;final int ocrEpoch=sceneEpoch.get();
                (language==AssistLanguage.HINDI?hindiReader:reader).process(InputImage.fromBitmap(captured,0)).addOnSuccessListener(t->{if(destroyed||paused)return;if(sceneEpoch.get()!=ocrEpoch){if(placeRequest&&mode.equals("place")){place.invalidate("The view changed. Hold steady and scan again.");updatePlaceControls();publish(place.message(),true);}return;}if(placeRequest){List<PlaceSession.Evidence> evidence=new ArrayList<>();for(var block:t.getTextBlocks()){android.graphics.Rect box=block.getBoundingBox();String side=box==null?"centre":box.exactCenterX()/captured.getWidth()<.33?"left":box.exactCenterX()/captured.getWidth()>.67?"right":"centre";evidence.add(new PlaceSession.Evidence(block.getText(),side,capturedAt,PlaceSession.hasDirectionalText(t.getText())));}place.observe(evidence,SystemClock.elapsedRealtime(),verificationRequest);updatePlaceControls();publish(place.message(),true);interpret(taskImage,"place",t.getText(),taskTicket);return;}String s=t.getText().trim();publish(s.isEmpty()?"No readable text found. Move closer and improve the lighting.":s,true);interpret(taskImage,"read",s,taskTicket);}).addOnFailureListener(e->{android.util.Log.e("Saathi","OCR failed",e);publish("Text recognition could not finish. Checking the captured image…",false);interpret(taskImage,placeRequest?"place":"read","OCR unavailable; inspect the image directly.",taskTicket);}).addOnCompleteListener(t->{captured.recycle();busy.set(false);}); bitmap=null;
            } else {
                if(detector!=null){var hits=detector.detect(bitmap);YoloDetector.Hit found=null;for(var h:hits)if(h.label().equals(requested)){found=h;break;}
                    final YoloDetector.Hit display=found;final int bw=bitmap.getWidth(),bh=bitmap.getHeight();runOnUiThread(()->{if(sceneEpoch.get()==frameEpoch)overlay.update(display,bw,bh);});
                    if(sceneEpoch.get()!=frameEpoch){busy.set(false);return;}
                    if(found!=null&&!interpretationRequested.get()){byte[] taskImage=jpeg(bitmap);int taskTicket=interpretationEpoch.get();String observation="Requested "+requested+". Detector: "+found.label()+", score "+String.format(Locale.ROOT,"%.2f",found.score());runOnUiThread(()->ui.post(()->interpret(taskImage,"find",observation,taskTicket)));}
                    if(found!=null){String position=found.x()<.33?"left":found.x()>.67?"right":"centre";String cue=language.detected(requested,position);
                        if(now-lastSaved>10000||!requested.equals(savedTarget)||!position.equals(savedPosition)){getPreferences(MODE_PRIVATE).edit().putLong("seen:"+requested,System.currentTimeMillis()).putString("position:"+requested,position).apply();lastSaved=now;savedTarget=requested;savedPosition=position;}
                        if(!cue.equals(lastCue)||now-lastSpoken>7000){lastCue=cue;lastSpoken=now;publish(cue,true);runOnUiThread(()->{Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v!=null&&v.hasVibrator())v.vibrate(VibrationEffect.createOneShot(90,VibrationEffect.DEFAULT_AMPLITUDE));});}
                    }else if(now-lastSpoken>9000){lastSpoken=now;lastCue="";publish(language.notFound(requested),true);if(!interpretationRequested.get()){byte[] taskImage=jpeg(bitmap);int taskTicket=interpretationEpoch.get();String observation="Requested "+requested+". Detector: no matching object.";runOnUiThread(()->ui.post(()->interpret(taskImage,"find",observation,taskTicket)));}}
                }
                busy.set(false);
            }
        }catch(Exception|LinkageError e){busy.set(false);scanText=false;scanPlace=false;scanGemma=false;searchActive=false;android.util.Log.e("Saathi","Camera frame processing failed",e);showError("This camera view could not be processed. Try again or switch cameras.");}
        finally{if(bitmap!=null)bitmap.recycle();image.close();}
    }
    private byte[] jpeg(Bitmap bitmap){
        float ratio=Math.min(1f,512f/Math.max(bitmap.getWidth(),bitmap.getHeight()));Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(bitmap.getWidth()*ratio)),Math.max(1,(int)(bitmap.getHeight()*ratio)),true);
        java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,80,encoded);if(small!=bitmap)small.recycle();return encoded.toByteArray();
    }
    private void interpret(byte[] image,String task,String observation,int ticket){
        if(destroyed||paused||interpretationEpoch.get()!=ticket||!mode.equals(task)||!interpretationRequested.compareAndSet(false,true))return;
        // Each Find request ends with one grounded interpretation rather than repeated model calls.
        if(task.equals("find"))searchActive=false;
        if(!gemma.installed()){
            android.util.Log.e("Saathi","Offline model unavailable for task: "+task+"; run :app:runOnPhone");
            gemmaStatus.setText("Basic recognition only · Full assistance is unavailable on this installation");
            return;
        }
        setResponseState("UNDERSTANDING YOUR VIEW",true,false);gemmaStatus.setText("Understanding the captured image…");
        gemma.ask(image,TaskPrompts.build(task,observation),language,new GemmaAssistant.Listener(){
            private boolean current(){return !destroyed&&!paused&&interpretationEpoch.get()==ticket&&mode.equals(task);}
            public void onStatus(String message){if(current())gemmaStatus.setText(message);}
            public void onPartial(String text){if(current()&&!task.equals("place")){result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);result.setText(text);}}
            public void onComplete(String text,long first,long total){if(!current())return;gemmaStatus.setText("Ready · "+language.label);String completed=text;if(task.equals("read")&&!observation.isBlank()&&!observation.startsWith("OCR unavailable")&&!observation.trim().equalsIgnoreCase(text.trim()))completed=observation+"\n\n"+text;publish(task.equals("place")?"In this captured view: "+text+"\n\n"+place.message():completed,true);}
            public void onError(String message){if(!current())return;setResponseState("RECOGNITION RESULT",false,false);gemmaStatus.setText("Full interpretation could not finish. The recognition result is shown above.");result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);result.setText(lastMessage);}
        });
    }
    private boolean tooDark(Bitmap image){long sum=0;int count=0;for(int y=0;y<image.getHeight();y+=Math.max(1,image.getHeight()/16))for(int x=0;x<image.getWidth();x+=Math.max(1,image.getWidth()/16)){int c=image.getPixel(x,y);sum+=((c>>16)&255)*3+((c>>8)&255)*6+(c&255);count++;}return sum/(count*10L)<22;}
    private void publish(String message,boolean speak){runOnUiThread(()->{if(destroyed||paused)return;ui.removeCallbacks(captureTimeout);setResponseState("YOUR ASSISTANT",false,false);lastMessage=message;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);result.setText(message);android.view.accessibility.AccessibilityManager accessibility=(android.view.accessibility.AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);if(speak&&(accessibility==null||!accessibility.isTouchExplorationEnabled()))say(message);});}
    private void configureVoice(){if(languageStatus==null)return;languageButton.setText(language.label+" ▾");if(!speechReady){languageStatus.setText(language.label+" · Voice preparing");return;}Locale locale=Locale.forLanguageTag(language.tag);int supported=speech.setLanguage(locale);voiceAvailable=supported>=TextToSpeech.LANG_AVAILABLE;languageButton.setText(language.label+" ▾");languageStatus.setText(voiceAvailable?language.label+" · Spoken guidance":"Voice missing for "+language.label+". Install it in Android text-to-speech settings. Text responses remain available.");}
    private void say(String message){if(speechReady&&voiceAvailable){status.setText("Speaking · Tap Stop to pause");speech.speak(message.substring(0,Math.min(message.length(),TextToSpeech.getMaxSpeechInputLength())),TextToSpeech.QUEUE_FLUSH,null,"feedback");}}
    private void askScene(){
        if(gemma.isBusy()){publish("Your assistant is working. Please wait a moment.",false);return;}
        if(!gemma.installed()){android.util.Log.e("Saathi","Offline scene model missing or incomplete; provision using :app:runOnPhone");showError("Scene descriptions aren’t available on this installation. You can still find objects and read text.");return;}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){publish("Enable camera permission in Android app settings first.",false);return;}
        searchActive=false;scanText=false;overlay.update(null,1,1);if(speech!=null)speech.stop();pendingQuestion=GemmaPrompts.question(question.getText().toString());pendingLanguage=language;scanGemma=true;processing("Capturing your surroundings…");gemmaStatus.setText("");
    }
    private void recall(){String q=target.getText().toString().trim().toLowerCase(Locale.ROOT);if(q.equals("phone"))q="cell phone";long time=getPreferences(MODE_PRIVATE).getLong("seen:"+q,0);if(time==0){publish("I have no saved sighting of "+q+" on this device.",true);return;}String at=java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(time));publish("A "+q+" was last detected at "+at+", in the "+getPreferences(MODE_PRIVATE).getString("position:"+q,"centre")+" of the camera view. This is a past observation, not its current location.",true);}
    private void listen(){stopAssistance();status.setText("Listening… Speak your request");Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,language.tag);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Say find a bottle, read text, or repeat");try{startActivityForResult(i,20);}catch(ActivityNotFoundException e){publish("Speech recognition is unavailable. Use the object field and scan button.",true);}}
    @Override protected void onActivityResult(int request,int response,Intent data){super.onActivityResult(request,response,data);if(request==20&&response!=RESULT_OK){status.setText("Listening cancelled · You can type your request");}if(request==20&&response==RESULT_OK&&data!=null){ArrayList<String> values=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);if(values==null||values.isEmpty())return;String s=values.get(0).toLowerCase(Locale.ROOT);if(s.contains("washroom")||s.contains("restroom")||s.contains("toilet")||s.contains("शौचालय")||s.contains("ಶೌಚಾಲಯ")){selectMode("place");scan();}else if(s.contains("read")||s.contains("पढ़")||s.contains("ಓದ")){selectMode("read");scan();}else if(s.contains("repeat")||s.contains("दोबारा")||s.contains("ಮತ್ತೆ"))say(lastMessage);else{String object=AssistLanguage.voiceObject(s);if(object==null){selectMode("describe");question.setText(values.get(0));askScene();}else{selectMode("find");target.setText(object);scan();}}}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==10&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCamera();else {cameraBadge.setText("  Camera access needed  ");showError("Allow camera access to use Saathi. Tap Try again to open camera settings.");}}
    @Override protected void onResume(){super.onResume();paused=false;ui.post(expiry);if(sensors!=null){var gyro=sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);if(gyro!=null)sensors.registerListener(this,gyro,android.hardware.SensorManager.SENSOR_DELAY_UI);}}
    @Override public void onAccuracyChanged(android.hardware.Sensor sensor,int accuracy){}
    @Override public void onSensorChanged(android.hardware.SensorEvent event){float speed=0;for(float value:event.values)speed+=value*value;long now=SystemClock.elapsedRealtime();if(speed>.81f&&now-lastMotion>2000){lastMotion=now;if(place.state()==PlaceSession.State.CANDIDATE||place.state()==PlaceSession.State.VERIFYING){sceneEpoch.incrementAndGet();place.invalidate("Camera moved. Hold steady and rescan the sign before confirming.");updatePlaceControls();if(mode.equals("place"))publish(place.message(),true);}}}
    @Override protected void onPause(){paused=true;interpretationEpoch.incrementAndGet();ui.removeCallbacks(expiry);if(sensors!=null)sensors.unregisterListener(this);place.pause();sceneEpoch.incrementAndGet();super.onPause();scanPlace=false;scanText=false;scanGemma=false;gemma.unload();if(speech!=null)speech.stop();}
    @Override protected void onDestroy(){destroyed=true;ui.removeCallbacks(expiry);ui.removeCallbacks(captureTimeout);initialization.shutdown();gemma.close();worker.execute(()->{try{if(detector!=null)detector.close();}catch(Exception ignored){}});worker.shutdown();reader.close();hindiReader.close();if(speech!=null)speech.shutdown();super.onDestroy();}
}
