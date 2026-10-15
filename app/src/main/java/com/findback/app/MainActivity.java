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
    private final int ink=Color.rgb(17,17,17), muted=Color.rgb(99,99,105);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        gemma=new GemmaAssistant(this);sensors=(android.hardware.SensorManager)getSystemService(SENSOR_SERVICE);
        speech=new TextToSpeech(this,s->runOnUiThread(()->{speechReady=s==TextToSpeech.SUCCESS;if(speechReady)configureVoice();}));
        buildUi();
        worker.execute(()->{try{detector=new YoloDetector(this);runOnUiThread(()->status.setText("Camera assistance ready"));}catch(Exception e){runOnUiThread(()->status.setText("Text reading available"));}});
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},10);
    }
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(24),dp(12),dp(24),dp(28));
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(24),insets.getSystemWindowInsetTop()+dp(12),dp(24),insets.getSystemWindowInsetBottom()+dp(28));return insets;});
        page=new ScrollView(this);page.setFillViewport(true);page.setClipToPadding(false);page.addView(root);setContentView(page);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header);
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=text("Saathi",30,ink);title.setTypeface(null,Typeface.BOLD);title.setLetterSpacing(-.04f);brand.addView(title);
        TextView subtitle=text("Verify the next step.",15,muted);subtitle.setPadding(0,dp(4),0,0);brand.addView(subtitle);
        Button locale=button("Language",false,()->new android.app.AlertDialog.Builder(this).setTitle("Response language").setSingleChoiceItems(new String[]{"English","हिन्दी","ಕನ್ನಡ"},language.ordinal(),(dialog,which)->{language=AssistLanguage.values()[which];getPreferences(MODE_PRIVATE).edit().putInt("language",which).apply();gemma.unload();configureVoice();dialog.dismiss();}).setNegativeButton("Close",null).show());header.addView(locale,new LinearLayout.LayoutParams(dp(108),dp(48)));
        language=AssistLanguage.values()[Math.min(2,Math.max(0,getPreferences(MODE_PRIVATE).getInt("language",0)))];
        languageStatus=text("English · Spoken guidance",12,muted);languageStatus.setPadding(0,dp(18),0,dp(12));root.addView(languageStatus);
        FrameLayout camera=new FrameLayout(this);camera.setBackground(shape(Color.rgb(242,242,244),20,false));camera.setClipToOutline(true);root.addView(camera,new LinearLayout.LayoutParams(-1,dp(236)));
        preview=new PreviewView(this);preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);camera.addView(preview,new FrameLayout.LayoutParams(-1,-1));overlay=new DetectionOverlay(this);camera.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        TextView live=text("  CAMERA VIEW  ",11,ink);live.setTypeface(null,Typeface.BOLD);live.setGravity(Gravity.CENTER);live.setBackground(shape(Color.WHITE,8,false));FrameLayout.LayoutParams liveParams=new FrameLayout.LayoutParams(-2,dp(30),Gravity.TOP|Gravity.START);liveParams.setMargins(dp(12),dp(12),0,0);camera.addView(live,liveParams);
        status=text("Preparing camera…",12,muted);status.setPadding(0,dp(10),0,dp(20));root.addView(status);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(4),dp(4),dp(4),dp(4));tabs.setBackground(shape(Color.rgb(244,244,245),14,false));root.addView(tabs,new LinearLayout.LayoutParams(-1,dp(58)));
        placeTab=tab(tabs,"Places",()->selectMode("place"));findTab=tab(tabs,"Find",()->selectMode("find"));readTab=tab(tabs,"Read",()->selectMode("read"));describeTab=tab(tabs,"Describe",()->selectMode("describe"));
        modeLabel=text("What would you like to find?",19,ink);modeLabel.setTypeface(null,Typeface.BOLD);modeLabel.setPadding(0,dp(24),0,dp(12));root.addView(modeLabel);
        inputArea=new LinearLayout(this);inputArea.setOrientation(LinearLayout.VERTICAL);root.addView(inputArea);
        target=field("Object to find","bottle");inputArea.addView(target,new LinearLayout.LayoutParams(-1,dp(58)));
        question=field("Ask about your surroundings","What is in front of me?");inputArea.addView(question,new LinearLayout.LayoutParams(-1,dp(58)));question.setVisibility(View.GONE);
        LinearLayout actionRow=new LinearLayout(this);LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,dp(76));actionParams.setMargins(0,dp(8),0,0);root.addView(actionRow,actionParams);
        primary=button("Find object",true,()->{if(mode.equals("describe"))askScene();else scan();});LinearLayout.LayoutParams primaryParams=new LinearLayout.LayoutParams(0,dp(58),1);primaryParams.setMargins(0,dp(8),dp(8),0);actionRow.addView(primary,primaryParams);
        Button voice=button("Speak",false,this::listen);voice.setContentDescription("Speak a request");LinearLayout.LayoutParams voiceParams=new LinearLayout.LayoutParams(dp(92),dp(58));voiceParams.setMargins(0,dp(8),0,0);actionRow.addView(voice,voiceParams);
        LinearLayout response=new LinearLayout(this);response.setOrientation(LinearLayout.VERTICAL);response.setPadding(dp(20),dp(20),dp(20),dp(12));response.setBackground(shape(Color.rgb(247,247,248),18,false));root.addView(response);
        TextView responseTitle=text("YOUR ASSISTANT",11,muted);responseTitle.setLetterSpacing(.12f);responseTitle.setTypeface(null,Typeface.BOLD);response.addView(responseTitle);
        result=text(lastMessage,20,ink);result.setLineSpacing(dp(4),1);result.setPadding(0,dp(12),0,dp(8));result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);response.addView(result);
        LinearLayout audio=new LinearLayout(this);response.addView(audio);addButton(audio,"Replay",()->say(lastMessage));addButton(audio,"Stop",this::stopAssistance);
        LinearLayout verification=new LinearLayout(this);root.addView(verification);
        verifyButton=button("Verify entrance",false,()->{if(place.requestVerification(SystemClock.elapsedRealtime())){updatePlaceControls();verifyPlace=true;scanPlace=true;publish("Aim at the entrance label and hold steady for a fresh scan.",true);}else publish("Scan a washroom sign first.",true);});
        confirmButton=button("Confirm entrance",true,()->{place.confirm(SystemClock.elapsedRealtime());updatePlaceControls();publish(place.message(),true);});
        verification.addView(verifyButton,new LinearLayout.LayoutParams(0,dp(54),1));LinearLayout.LayoutParams confirmParams=new LinearLayout.LayoutParams(0,dp(54),1);confirmParams.setMargins(dp(8),0,0,0);verification.addView(confirmButton,confirmParams);
        gemmaStatus=text("",12,muted);gemmaStatus.setPadding(0,dp(10),0,0);gemmaStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(gemmaStatus);
        LinearLayout extras=new LinearLayout(this);root.addView(extras);addButton(extras,"Evidence",this::showEvidence);addButton(extras,"Cancel",()->{stopAssistance();place.cancel();updatePlaceControls();publish(place.message(),true);});addButton(extras,"Help",this::showHelp);
        TextView footnote=text("Camera positions are approximate. Continue using your usual mobility support.",12,muted);footnote.setLineSpacing(dp(3),1);footnote.setPadding(0,dp(16),0,0);root.addView(footnote);
        selectMode("place");
    }
    private void stopAssistance(){sceneEpoch.incrementAndGet();scanPlace=false;searchActive=false;scanText=false;scanGemma=false;place.pause();gemma.unload();overlay.update(null,1,1);result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);gemmaStatus.setText("");if(speech!=null)speech.stop();}
    private void selectMode(String selected){stopAssistance();mode=selected;
        boolean find=selected.equals("find"),read=selected.equals("read"),places=selected.equals("place");
        target.setVisibility(find||places?View.VISIBLE:View.GONE);target.setEnabled(!places);if(places)target.setText("Washroom");else if(find&&target.getText().toString().equals("Washroom"))target.setText("bottle");
        question.setVisibility(selected.equals("describe")?View.VISIBLE:View.GONE);inputArea.setVisibility(read?View.GONE:View.VISIBLE);
        modeLabel.setText(places?"Find and verify a place":find?"What would you like to find?":read?"Point your camera at text":"Understand your surroundings");
        primary.setText(places?"Scan washroom signs":find?"Find object":read?"Read aloud":"Describe scene");
        styleTab(placeTab,places);styleTab(findTab,find);styleTab(readTab,read);styleTab(describeTab,selected.equals("describe"));updatePlaceControls();
    }
    private void updatePlaceControls(){if(verifyButton==null)return;boolean visible=mode.equals("place");long now=SystemClock.elapsedRealtime();verifyButton.setVisibility(visible&&place.canVerify(now)?View.VISIBLE:View.GONE);confirmButton.setVisibility(visible&&place.canConfirm(now)?View.VISIBLE:View.GONE);verifyButton.setEnabled(place.canVerify(now));confirmButton.setEnabled(place.canConfirm(now));verifyButton.setAlpha(verifyButton.isEnabled()?1f:.4f);confirmButton.setAlpha(confirmButton.isEnabled()?1f:.4f);}
    private void showEvidence(){StringBuilder details=new StringBuilder();long now=SystemClock.elapsedRealtime();for(PlaceSession.Evidence evidence:place.evidence())details.append("OCR · ").append(Math.max(0,(now-evidence.capturedAt())/1000)).append(" seconds ago\n").append(evidence.text()).append("\n\n");new android.app.AlertDialog.Builder(this).setTitle("Recent sign evidence").setMessage(details.length()==0?"No sign observations saved in this session.":details.toString()+"Past observations do not establish a current direction.").setPositiveButton("Close",null).show();}
    private void showHelp(){new android.app.AlertDialog.Builder(this).setTitle("Here to help").setMessage("Use Saathi while stationary. Left and right describe the camera view. Scan a sign, verify a fresh entrance label, then confirm it yourself. Use your usual mobility support.\n\nRead speaks printed text. Find locates supported object categories. Describe explains a single camera view.").setPositiveButton("Close",null).setNeutralButton("Open phone dialer",(d,w)->{try{startActivity(new Intent(Intent.ACTION_DIAL));}catch(ActivityNotFoundException e){publish("A phone dialer is not available.",false);}}).show();}
    private GradientDrawable shape(int color,int radius,boolean border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border)d.setStroke(dp(1),Color.rgb(220,220,224));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density);}
    private EditText field(String description,String initial){EditText e=new EditText(this);e.setSingleLine(true);e.setText(initial);e.setHint(description);e.setContentDescription(description);e.setTextSize(17);e.setTextColor(ink);e.setHintTextColor(muted);e.setPadding(dp(16),0,dp(16),0);e.setBackground(shape(Color.WHITE,12,true));return e;}
    private Button button(String label,boolean solid,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);b.setTextColor(solid?Color.WHITE:ink);b.setMinHeight(dp(48));b.setPadding(dp(12),0,dp(12),0);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000),shape(solid?ink:Color.WHITE,12,!solid),null));b.setStateListAnimator(null);b.setOnClickListener(v->action.run());return b;}
    private Button tab(LinearLayout row,String label,Runnable action){Button b=button(label,false,action);b.setTextSize(13);b.setPadding(dp(3),0,dp(3),0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),0,dp(2),0);row.addView(b,p);return b;}
    private void styleTab(Button b,boolean selected){b.setTextColor(selected?Color.WHITE:ink);b.setBackground(shape(selected?ink:Color.TRANSPARENT,10,false));b.setSelected(selected);}
    private void addButton(LinearLayout parent,String label,Runnable action){Button b=button(label,false,action);b.setTextSize(13);b.setBackgroundColor(Color.TRANSPARENT);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),1);parent.addView(b,p);}
    private void scan(){
        gemma.unload();scanGemma=false;
        if(mode.equals("place")){if(place.state()==PlaceSession.State.IDLE||place.state()==PlaceSession.State.PAUSED||place.state()==PlaceSession.State.CONFIRMED)place.begin();place.invalidate("Scanning fresh evidence…");updatePlaceControls();verifyPlace=false;scanPlace=true;publish("Searching for washroom signs. Hold the phone steady while stationary.",true);return;}
        if(mode.equals("read")){scanText=true;publish("Hold the camera steady while I read.",true);return;}
        String q=AssistLanguage.canonicalObject(target.getText().toString());
        if(q.equals("phone"))q="cell phone";if(q.equals("sofa"))q="couch";
        if(!Arrays.asList(YoloDetector.LABELS).contains(q)){publish("That object is not supported by this detector. Try bottle, chair, cup, book, or cell phone.",true);return;}
        requested=q;searchActive=true; lastCue="";lastSpoken=0;
        if(detector==null){searchActive=false;publish("Object finding is unavailable. You can still use Read to hear printed text.",true);}else publish("Looking for "+requested+". Slowly scan the camera.",true);
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
        if(destroyed||paused||(!scanPlace&&!scanGemma&&!scanText&&(!searchActive||mode.equals("read")||now-lastFrame<900))||!busy.compareAndSet(false,true)){image.close();return;}
        lastFrame=now;Bitmap bitmap=null;
        try{
            Bitmap frame=image.toBitmap();
            Matrix m=new Matrix();m.postRotate(image.getImageInfo().getRotationDegrees());
            bitmap=Bitmap.createBitmap(frame,0,0,frame.getWidth(),frame.getHeight(),m,true);
            if(frame!=bitmap)frame.recycle();
            if(tooDark(bitmap)){boolean explicit=scanPlace||scanText||scanGemma;scanPlace=false;scanText=false;scanGemma=false;place.invalidate("The view is too dark. Aim at a well-lit sign and scan again.");runOnUiThread(this::updatePlaceControls);if(explicit||now-lastSpoken>7000){lastSpoken=now;publish("The view is too dark. Move the camera toward a well-lit sign and hold steady.",true);}busy.set(false);return;}
            if(scanGemma){scanGemma=false;
                int width=bitmap.getWidth(),height=bitmap.getHeight();float ratio=Math.min(1f,512f/Math.max(width,height));Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(width*ratio)),Math.max(1,(int)(height*ratio)),true);
                java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,80,encoded);if(small!=bitmap)small.recycle();
                byte[] jpeg=encoded.toByteArray();String prompt=pendingQuestion;AssistLanguage selected=pendingLanguage;int sceneTicket=sceneEpoch.get();
                runOnUiThread(()->{if(destroyed||paused)return;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
                    gemma.ask(jpeg,prompt,selected,new GemmaAssistant.Listener(){
                        public void onStatus(String message){gemmaStatus.setText(message);}
                        public void onPartial(String text){if(sceneEpoch.get()==sceneTicket)result.setText(text);}
                        public void onComplete(String text,long first,long total){if(sceneEpoch.get()!=sceneTicket)return;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);gemmaStatus.setText("Description ready · "+language.label);publish(text,true);}
                        public void onError(String message){result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);gemmaStatus.setText("Please try another view");publish(message,false);}
                    });});busy.set(false);
            }else if(scanText||scanPlace){boolean placeRequest=scanPlace,verificationRequest=verifyPlace;scanPlace=false;verifyPlace=false;scanText=false;final Bitmap captured=bitmap;final long capturedAt=now;final int ocrEpoch=sceneEpoch.get();
                (language==AssistLanguage.HINDI?hindiReader:reader).process(InputImage.fromBitmap(captured,0)).addOnSuccessListener(t->{if(destroyed||paused)return;if(sceneEpoch.get()!=ocrEpoch){if(placeRequest&&mode.equals("place")){place.invalidate("The view changed. Hold steady and scan again.");updatePlaceControls();publish(place.message(),true);}return;}if(placeRequest){List<PlaceSession.Evidence> evidence=new ArrayList<>();for(var block:t.getTextBlocks()){android.graphics.Rect box=block.getBoundingBox();String side=box==null?"centre":box.exactCenterX()/captured.getWidth()<.33?"left":box.exactCenterX()/captured.getWidth()>.67?"right":"centre";evidence.add(new PlaceSession.Evidence(block.getText(),side,capturedAt,PlaceSession.hasDirectionalText(t.getText())));}place.observe(evidence,SystemClock.elapsedRealtime(),verificationRequest);updatePlaceControls();publish(place.message(),true);return;}String s=t.getText().trim();publish(s.isEmpty()?"No readable text found. Move closer and improve the lighting.":s,true);}).addOnFailureListener(e->publish("Text could not be read. Please try again.",true)).addOnCompleteListener(t->{captured.recycle();busy.set(false);}); bitmap=null;
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
    private boolean tooDark(Bitmap image){long sum=0;int count=0;for(int y=0;y<image.getHeight();y+=Math.max(1,image.getHeight()/16))for(int x=0;x<image.getWidth();x+=Math.max(1,image.getWidth()/16)){int c=image.getPixel(x,y);sum+=((c>>16)&255)*3+((c>>8)&255)*6+(c&255);count++;}return sum/(count*10L)<22;}
    private void publish(String message,boolean speak){runOnUiThread(()->{if(destroyed||paused)return;lastMessage=message;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);result.setText(message);android.view.accessibility.AccessibilityManager accessibility=(android.view.accessibility.AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);if(speak&&(accessibility==null||!accessibility.isTouchExplorationEnabled()))say(message);});}
    private void configureVoice(){if(!speechReady||languageStatus==null)return;Locale locale=Locale.forLanguageTag(language.tag);int supported=speech.setLanguage(locale);voiceAvailable=supported>=TextToSpeech.LANG_AVAILABLE;languageStatus.setText(voiceAvailable?language.label+" · Spoken guidance":"Voice missing for "+language.label+". Install it in Android text-to-speech settings. Text responses remain available.");}
    private void say(String message){if(speechReady&&voiceAvailable)speech.speak(message.substring(0,Math.min(message.length(),TextToSpeech.getMaxSpeechInputLength())),TextToSpeech.QUEUE_FLUSH,null,"feedback");}
    private void askScene(){
        if(gemma.isBusy()){publish("Your assistant is working. Please wait a moment.",false);return;}
        if(!gemma.installed()){publish("Scene assistance is not ready on this device. Please contact your app provider.",false);return;}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){publish("Enable camera permission in Android app settings first.",false);return;}
        searchActive=false;scanText=false;overlay.update(null,1,1);if(speech!=null)speech.stop();pendingQuestion=GemmaPrompts.question(question.getText().toString());pendingLanguage=language;scanGemma=true;gemmaStatus.setText("Taking a closer look…");
    }
    private void recall(){String q=target.getText().toString().trim().toLowerCase(Locale.ROOT);if(q.equals("phone"))q="cell phone";long time=getPreferences(MODE_PRIVATE).getLong("seen:"+q,0);if(time==0){publish("I have no saved sighting of "+q+" on this device.",true);return;}String at=java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(time));publish("A "+q+" was last detected at "+at+", in the "+getPreferences(MODE_PRIVATE).getString("position:"+q,"centre")+" of the camera view. This is a past observation, not its current location.",true);}
    private void listen(){Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,language.tag);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Say find a bottle, read text, or repeat");try{startActivityForResult(i,20);}catch(ActivityNotFoundException e){publish("Speech recognition is unavailable. Use the object field and scan button.",true);}}
    @Override protected void onActivityResult(int request,int response,Intent data){super.onActivityResult(request,response,data);if(request==20&&response==RESULT_OK&&data!=null){ArrayList<String> values=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);if(values==null||values.isEmpty())return;String s=values.get(0).toLowerCase(Locale.ROOT);if(s.contains("washroom")||s.contains("restroom")||s.contains("toilet")||s.contains("शौचालय")||s.contains("ಶೌಚಾಲಯ")){selectMode("place");scan();}else if(s.contains("read")||s.contains("पढ़")||s.contains("ಓದ")){selectMode("read");scan();}else if(s.contains("repeat")||s.contains("दोबारा")||s.contains("ಮತ್ತೆ"))say(lastMessage);else{String object=AssistLanguage.voiceObject(s);if(object==null){selectMode("describe");question.setText(values.get(0));askScene();}else{selectMode("find");target.setText(object);scan();}}}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==10&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCamera();else publish("Camera permission is required. Enable it in Android app settings.",true);}
    @Override protected void onResume(){super.onResume();paused=false;ui.post(expiry);if(sensors!=null){var gyro=sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);if(gyro!=null)sensors.registerListener(this,gyro,android.hardware.SensorManager.SENSOR_DELAY_UI);}}
    @Override public void onAccuracyChanged(android.hardware.Sensor sensor,int accuracy){}
    @Override public void onSensorChanged(android.hardware.SensorEvent event){float speed=0;for(float value:event.values)speed+=value*value;long now=SystemClock.elapsedRealtime();if(speed>.81f&&now-lastMotion>2000){lastMotion=now;sceneEpoch.incrementAndGet();if(gemma.isBusy()){gemma.unload();gemmaStatus.setText("Camera moved. Hold steady and describe again.");}if(place.state()==PlaceSession.State.CANDIDATE||place.state()==PlaceSession.State.VERIFYING){place.invalidate("Camera moved. Hold steady and rescan the sign before confirming.");updatePlaceControls();if(mode.equals("place"))publish(place.message(),true);}}}
    @Override protected void onPause(){paused=true;ui.removeCallbacks(expiry);if(sensors!=null)sensors.unregisterListener(this);place.pause();sceneEpoch.incrementAndGet();super.onPause();scanPlace=false;scanText=false;scanGemma=false;gemma.unload();if(speech!=null)speech.stop();}
    @Override protected void onDestroy(){destroyed=true;gemma.close();worker.execute(()->{try{if(detector!=null)detector.close();}catch(Exception ignored){}});worker.shutdown();reader.close();hindiReader.close();if(speech!=null)speech.shutdown();super.onDestroy();}
}
