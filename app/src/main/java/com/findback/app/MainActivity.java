package com.findback.app;

import android.Manifest;
import android.animation.LayoutTransition;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.core.resolutionselector.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Saathi home screen: a full-screen camera with floating controls, one shutter, one answer sheet. Every mode captures a frame,
 * grounds it with on-device detection/OCR, and asks the on-device Gemma 4 model for the answer.
 * When Gemma is unavailable, answers come from detection/OCR alone and say so.
 */
public class MainActivity extends ComponentActivity implements android.hardware.SensorEventListener, VoiceInput.Callback {
    private enum Capture { DESCRIBE, READ, PLACE_IDENTIFY, PLACE_SIGN, PLACE_VERIFY, FIND_IMAGE }
    private enum CameraState { STARTING, LIVE, NO_PERMISSION, UNAVAILABLE }
    private record CaptureRequest(Capture kind,int ticket,String query,AssistLanguage language) {}

    private static final int REQUEST_CAMERA=10, REQUEST_VOICE_INTENT=20, REQUEST_MIC=30;
    private static final long FIND_FRAME_MS=500, FIND_QUIET_MS=9000, FIND_LIMIT_MS=90000, CAPTURE_TIMEOUT_MS=8000;
    private static final long YOLO_QUIET_MS=5000, GEMMA_SCAN_GAP_MS=400, STEADY_MS=300;
    /** Finder score that counts as a candidate, and the score that is announced from a single frame. */
    private static final float FINDER_CANDIDATE=0.25f, FINDER_CONFIDENT=0.5f;
    /** Gyroscope rate (rad/s) above which camera frames are likely to be motion-blurred. */
    private static final float SHAKE_RAD_S=0.7f;

    // Engines
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService initialization=Executors.newSingleThreadExecutor();
    private GemmaAssistant gemma;
    private volatile YoloDetector detector;
    private volatile boolean detectorFailed;
    private volatile OpenVocabFinder finder;
    private volatile OpenVocabFinder.Query finderQuery;
    private int finderStreak, confirmAttempts;
    private long lastScoreLog;
    private final TextRecognizer reader=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final TextRecognizer hindiReader=TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
    private TextToSpeech speech;
    private boolean speechReady, voiceAvailable=true;
    private VoiceInput voice;
    private ProcessCameraProvider cameraProvider;

    // State (kept outside views so the layout can be rebuilt on rotation)
    private volatile AssistMode mode=AssistMode.DESCRIBE;
    private volatile AssistLanguage language=AssistLanguage.ENGLISH;
    private final AtomicInteger requestEpoch=new AtomicInteger();
    private final AtomicReference<CaptureRequest> pendingCapture=new AtomicReference<>();
    private final AtomicBoolean frameBusy=new AtomicBoolean();
    private volatile boolean searchActive, paused, destroyed, findInterpreted, findAnswerPending, gemmaScanInFlight;
    private volatile long lastShake, lastGemmaCheck, lastSteadyFrame;
    private int gemmaChecks;
    private volatile String requested="";
    private volatile int searchTicket;
    private String requestedPhrase="", findText="", askText="";
    private long lastFrame, lastSpoken, lastSaved, searchStarted, lastSeen;
    private String lastCue="", savedTarget="", savedPosition="";
    private boolean working, hasAnswer, followUpContext;
    private AssistState state=AssistState.READY;
    private String stateDetail, lastAnnounced="", displayText="", lastMessage="", noticeOverride, utterance="";
    private Runnable retry;
    private CameraState cameraState=CameraState.STARTING;
    private boolean cameraReady, frontCamera, observingStream;
    private final PlaceSession place=new PlaceSession();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private android.hardware.SensorManager sensors;
    private long lastMotion;
    private int utteranceCounter, timeoutTicket;
    private String placeSignature="";

    // Views
    private PreviewView preview;
    private DetectionOverlay overlay;
    private LinearLayout cameraMessage, brandView;
    private ImageView cameraMessageIcon;
    private TextView cameraMessageText, liveChip, result, notice, modeChip;
    private Button cameraMessageAction, languageButton, washroomButton;
    private ImageButton switchButton, replayButton, stopButton, micButton, expandButton, menuButton, galleryButton, shutter;
    private StatusView statusView;
    private BoundedScrollView resultScroll;
    private LinearLayout responseActions;
    private FrameLayout contextSlot;
    private EditText query;
    private PopupWindow menuWindow;
    private boolean sheetExpanded, pickingPhoto;
    private androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickImage;

    private final Runnable expiry=new Runnable(){public void run(){
        if(destroyed||paused)return;
        if(place.expire(SystemClock.elapsedRealtime())&&mode==AssistMode.PLACES&&!working)deliver(place.message(),true);
        refreshResponseActions();
        ui.postDelayed(this,500);
    }};
    private final Runnable captureTimeout=()->{
        CaptureRequest stale=pendingCapture.get();
        if(stale!=null&&stale.ticket()==timeoutTicket&&pendingCapture.compareAndSet(stale,null))
            showError("No camera image arrived. Make sure nothing is covering the camera, then try again.");
    };

    @Override public void onCreate(Bundle saved){
        // Camera-first: draw under transparent system bars; overlays add the bar insets themselves.
        androidx.activity.EdgeToEdge.enable(this,androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT),androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(saved);
        pickImage=registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),uri->{if(uri!=null)analyzePhoto(uri);});
        gemma=new GemmaAssistant(this);
        voice=new VoiceInput(this,this);
        sensors=(android.hardware.SensorManager)getSystemService(SENSOR_SERVICE);
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        language=AssistLanguage.values()[Math.min(2,Math.max(0,prefs.getInt("language",0)))];
        mode=AssistMode.fromKey(prefs.getString("mode",AssistMode.DESCRIBE.key));
        if(saved!=null){mode=AssistMode.fromKey(saved.getString("mode",mode.key));findText=saved.getString("findText","");askText=saved.getString("askText","");frontCamera=saved.getBoolean("front",false);}
        displayText=idleMessage(mode);
        sheetExpanded=mode==AssistMode.FIND||mode==AssistMode.PLACES;
        speech=new TextToSpeech(this,s->runOnUiThread(()->{
            speechReady=s==TextToSpeech.SUCCESS;
            if(!speechReady){voiceAvailable=false;renderNotice();return;}
            speech.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                @Override public void onStart(String id){runOnUiThread(()->{if(id.equals(utterance)&&!destroyed)setState(AssistState.SPEAKING,null);});}
                @Override public void onDone(String id){runOnUiThread(()->speechEnded(id));}
                @Override public void onStop(String id,boolean interrupted){runOnUiThread(()->speechEnded(id));}
                @Override public void onError(String id){runOnUiThread(()->{speechEnded(id);noticeOverride="Spoken answers aren't available right now. Answers still appear here.";renderNotice();});}
            });
            configureVoice();
        }));
        buildUi();
        initialization.execute(()->{
            try{YoloDetector loaded=new YoloDetector(this);if(destroyed){loaded.close();return;}detector=loaded;}
            catch(Exception|LinkageError e){android.util.Log.e("Saathi","Object detector initialization failed",e);detectorFailed=true;}
            if(!OpenVocabFinder.bundled(this)){android.util.Log.w("Saathi","Open-vocabulary finder files are not bundled; Find uses YOLO and Gemma");return;}
            try{long t=SystemClock.elapsedRealtime();OpenVocabFinder loaded=new OpenVocabFinder(this);if(destroyed){loaded.close();return;}finder=loaded;
                android.util.Log.i("Saathi","Open-vocabulary finder ready in "+(SystemClock.elapsedRealtime()-t)+" ms");}
            catch(Exception|LinkageError e){android.util.Log.e("Saathi","Open-vocabulary finder failed to load",e);}
        });
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();
        else{setCameraState(CameraState.NO_PERMISSION);requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA);}
    }

    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);rememberQuery();
        out.putString("mode",mode.key);out.putString("findText",findText);out.putString("askText",askText);out.putBoolean("front",frontCamera);
    }

    // ───────────────────────────── Layout ─────────────────────────────

    /**
     * Camera-first layout: the preview fills the window edge to edge; the header, answer sheet, mode chip
     * and camera controls float over it on translucent charcoal surfaces, inset from the system bars.
     */
    private void buildUi(){
        if(menuWindow!=null){menuWindow.dismiss();menuWindow=null;}
        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(Theme.CAMERA);
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        overlay=new DetectionOverlay(this);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        buildCameraMessage();
        root.addView(cameraMessage,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));

        LinearLayout header=buildHeader();
        root.addView(header,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        LinearLayout bottom=vertical();bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        int screen=getResources().getDisplayMetrics().widthPixels;
        bottom.addView(buildSheet(),new LinearLayout.LayoutParams(Math.min(screen-dp(24),dp(560)),-2));
        bottom.addView(buildModeChip(),margins(new LinearLayout.LayoutParams(-2,-2),0,dp(12),0,0));
        bottom.addView(buildControls(),margins(new LinearLayout.LayoutParams(Math.min(screen-dp(24),dp(480)),-2),0,dp(8),0,0));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        resultScroll.setMaxHeight((int)(getResources().getDisplayMetrics().heightPixels*(landscape?.22f:.3f)));

        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top,bottomInset,left,right;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
                top=bars.top;left=bars.left;right=bars.right;bottomInset=Math.max(bars.bottom,ime.bottom);
            }else{top=insets.getSystemWindowInsetTop();bottomInset=insets.getSystemWindowInsetBottom();left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();}
            header.setPadding(left+dp(12),top+dp(8),right+dp(12),0);
            bottom.setPadding(left,0,right,bottomInset+dp(16));
            return insets;
        });
        setContentView(root);
        renderAll();
    }

    private LinearLayout buildHeader(){
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setBackground(Theme.rounded(this,Theme.OVERLAY,24,0,0));brand.setPadding(dp(6),dp(6),dp(14),dp(6));brand.setMinimumHeight(dp(48));
        ImageView logo=new ImageView(this);logo.setImageDrawable(new ActionIcon("describe",Color.WHITE,dp(20)));logo.setScaleType(ImageView.ScaleType.CENTER);
        logo.setBackground(Theme.rounded(this,Theme.BRAND,18,0,0));
        brand.addView(logo,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout words=vertical();
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Theme.text(this,"Saathi",16,Theme.ON_DARK,true);titleRow.addView(title);
        liveChip=Theme.text(this,"LIVE",10,Theme.MINT,true);liveChip.setLetterSpacing(.1f);liveChip.setCompoundDrawablePadding(dp(3));
        liveChip.setCompoundDrawables(new ActionIcon("dot",Theme.MINT,dp(9)),null,null,null);
        titleRow.addView(liveChip,margins(new LinearLayout.LayoutParams(-2,-2),dp(8),0,0,0));
        words.addView(titleRow);
        words.addView(Theme.text(this,"Your world, understood.",11,Theme.ON_DARK_MUTED,false));
        brand.addView(words,margins(new LinearLayout.LayoutParams(-2,-2),dp(8),0,0,0));
        brandView=brand;brand.setFocusable(true);
        for(View child:new View[]{logo,words,title,liveChip})child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if(Build.VERSION.SDK_INT>=28)brand.setAccessibilityHeading(true);
        header.addView(brand,new LinearLayout.LayoutParams(-2,-2));
        header.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));

        languageButton=new Button(this);
        languageButton.setAllCaps(false);languageButton.setTextSize(14);languageButton.setTextColor(Theme.ON_DARK);languageButton.setTypeface(Theme.medium());
        languageButton.setMinHeight(dp(48));languageButton.setMinimumHeight(dp(48));languageButton.setMinWidth(0);languageButton.setMinimumWidth(0);
        languageButton.setPadding(dp(12),0,dp(14),0);languageButton.setCompoundDrawablePadding(dp(6));
        languageButton.setCompoundDrawables(new ActionIcon("globe",Theme.ON_DARK,dp(18)),null,null,null);
        languageButton.setBackground(Theme.controlOnDark(this,Theme.OVERLAY,24,0,0));languageButton.setStateListAnimator(null);
        languageButton.setOnClickListener(v->chooseLanguage());
        header.addView(languageButton,new LinearLayout.LayoutParams(-2,dp(48)));
        menuButton=iconButton("more",Theme.ON_DARK,"Features and settings",()->openMenu());
        menuButton.setBackground(Theme.controlOnDark(this,Theme.OVERLAY,24,0,0));
        header.addView(menuButton,margins(new LinearLayout.LayoutParams(dp(48),dp(48)),dp(8),0,0,0));
        return header;
    }

    private void buildCameraMessage(){
        cameraMessage=vertical();cameraMessage.setGravity(Gravity.CENTER);cameraMessage.setPadding(dp(32),dp(16),dp(32),dp(16));
        cameraMessageIcon=new ImageView(this);cameraMessage.addView(cameraMessageIcon,new LinearLayout.LayoutParams(dp(40),dp(40)));
        cameraMessageText=Theme.text(this,"",16,Color.WHITE,false);cameraMessageText.setGravity(Gravity.CENTER);cameraMessageText.setPadding(0,dp(12),0,dp(16));
        cameraMessage.addView(cameraMessageText,new LinearLayout.LayoutParams(-2,-2));
        cameraMessageAction=new Button(this);cameraMessageAction.setAllCaps(false);cameraMessageAction.setTextSize(15);cameraMessageAction.setTypeface(Theme.medium());
        cameraMessageAction.setTextColor(Theme.INK);cameraMessageAction.setBackground(Theme.controlOnDark(this,Color.WHITE,24,0,0));cameraMessageAction.setStateListAnimator(null);
        cameraMessageAction.setMinHeight(dp(48));cameraMessageAction.setMinimumHeight(dp(48));cameraMessageAction.setPadding(dp(24),0,dp(24),0);
        cameraMessage.addView(cameraMessageAction,new LinearLayout.LayoutParams(-2,dp(48)));
    }

    /** Answer sheet: a one-line status bar when collapsed; the full answer, input and actions when expanded. */
    private View buildSheet(){
        LinearLayout sheet=vertical();
        sheet.setBackground(Theme.rounded(this,Theme.OVERLAY_STRONG,24,0,0));
        sheet.setPadding(dp(16),dp(2),dp(4),dp(10));
        if(!Theme.reducedMotion(this)){LayoutTransition t=new LayoutTransition();t.enableTransitionType(LayoutTransition.CHANGING);t.setDuration(200);sheet.setLayoutTransition(t);}
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        statusView=new StatusView(this,true);top.addView(statusView,new LinearLayout.LayoutParams(0,-2,1));
        replayButton=iconButton("speaker",Theme.ON_DARK,"Read answer aloud",this::replay);top.addView(replayButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        stopButton=iconButton("stop",Theme.ON_DARK,"Stop",()->stopAll(true));top.addView(stopButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        micButton=iconButton("mic",Theme.ON_BRAND,"Speak a request",this::onMicPressed);top.addView(micButton,margins(new LinearLayout.LayoutParams(dp(48),dp(48)),dp(2),0,dp(2),0));
        expandButton=iconButton("chevron_up",Theme.ON_DARK,"Show full answer",()->setSheetExpanded(!sheetExpanded));top.addView(expandButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        sheet.addView(top,new LinearLayout.LayoutParams(-1,-2));
        result=Theme.text(this,"",17,Theme.ON_DARK,false);result.setLineSpacing(0,1.25f);result.setPadding(0,0,dp(12),0);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        result.setOnClickListener(v->{if(!sheetExpanded)setSheetExpanded(true);});
        resultScroll=new BoundedScrollView(this,dp(200));resultScroll.addView(result);
        sheet.addView(resultScroll,new LinearLayout.LayoutParams(-1,-2));
        notice=Theme.text(this,"",13,Theme.ON_DARK_MUTED,false);notice.setPadding(0,dp(8),dp(12),0);notice.setLineSpacing(0,1.15f);
        sheet.addView(notice,new LinearLayout.LayoutParams(-1,-2));
        buildContextSlot();
        sheet.addView(contextSlot,margins(new LinearLayout.LayoutParams(-1,-2),0,dp(12),dp(12),0));
        responseActions=new LinearLayout(this);responseActions.setGravity(Gravity.CENTER_VERTICAL);
        sheet.addView(responseActions,margins(new LinearLayout.LayoutParams(-1,-2),0,dp(10),dp(12),0));
        return sheet;
    }

    private void buildContextSlot(){
        contextSlot=new FrameLayout(this);
        query=new EditText(this);query.setSingleLine(true);query.setTextSize(16);query.setTextColor(Theme.ON_DARK);query.setHintTextColor(Theme.ON_DARK_MUTED);
        query.setMinHeight(dp(52));query.setPadding(dp(16),dp(12),dp(16),dp(12));
        query.setBackground(Theme.controlOnDark(this,0x1FFFFFFF,14,0x52FFFFFF,1));
        query.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        query.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH||action==EditorInfo.IME_ACTION_GO||action==EditorInfo.IME_ACTION_DONE){hideKeyboard();runPrimary();return true;}return false;});
        contextSlot.addView(query,new FrameLayout.LayoutParams(-1,-2));
        washroomButton=darkButton("Washroom signs","door",false,this::scanWashroom);
        washroomButton.setContentDescription("Find a washroom using signs");
        contextSlot.addView(washroomButton,new FrameLayout.LayoutParams(-1,-2));
        query.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){}
            public void afterTextChanged(android.text.Editable e){if(mode==AssistMode.DESCRIBE&&shutter!=null&&!working)renderShutter();}
        });
    }

    private View buildModeChip(){
        modeChip=Theme.text(this,"",13,Theme.ON_DARK,true);modeChip.setLetterSpacing(.08f);
        modeChip.setGravity(Gravity.CENTER_VERTICAL);modeChip.setCompoundDrawablePadding(dp(8));
        modeChip.setPadding(dp(14),0,dp(16),0);modeChip.setMinHeight(dp(44));
        modeChip.setBackground(Theme.controlOnDark(this,Theme.OVERLAY,22,0,0));
        modeChip.setFocusable(true);modeChip.setClickable(true);
        modeChip.setOnClickListener(v->openMenu());
        return modeChip;
    }

    /** Gallery, shutter and camera switch, kept above the gesture area. */
    private View buildControls(){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        galleryButton=iconButton("gallery",Theme.ON_DARK,"Choose a photo to analyse",this::pickPhoto);
        galleryButton.setBackground(Theme.controlOnDark(this,Theme.OVERLAY,30,0x52FFFFFF,1));
        FrameLayout left=new FrameLayout(this);left.addView(galleryButton,new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER));
        row.addView(left,new LinearLayout.LayoutParams(0,dp(88),1));
        shutter=new ImageButton(this);shutter.setScaleType(ImageView.ScaleType.FIT_XY);
        shutter.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF),null,Theme.rounded(this,0xFF000000,44,0,0)));
        shutter.setPadding(0,0,0,0);shutter.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);runPrimary();});
        row.addView(shutter,new LinearLayout.LayoutParams(dp(84),dp(84)));
        switchButton=iconButton("switch",Theme.ON_DARK,"Switch camera",this::switchCamera);
        switchButton.setBackground(Theme.controlOnDark(this,Theme.OVERLAY,30,0x52FFFFFF,1));
        FrameLayout right=new FrameLayout(this);right.addView(switchButton,new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER));
        row.addView(right,new LinearLayout.LayoutParams(0,dp(88),1));
        return row;
    }

    // ───────────────────────────── Feature menu ─────────────────────────────

    /** Anchored dropdown under ⋮: the four modes (active one checked), then Language and Settings. */
    private void openMenu(){
        if(menuWindow!=null&&menuWindow.isShowing()){menuWindow.dismiss();return;}
        LinearLayout panel=vertical();
        panel.setBackground(Theme.rounded(this,Theme.MENU,20,Theme.DIVIDER_ON_DARK,1));
        panel.setPadding(dp(8),dp(8),dp(8),dp(8));
        TextView heading=Theme.text(this,"ASSIST MODES",11,Theme.ON_DARK_MUTED,true);heading.setLetterSpacing(.1f);heading.setPadding(dp(12),dp(6),dp(12),dp(6));
        heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(heading);
        PopupWindow window=new PopupWindow(panel,dp(296),ViewGroup.LayoutParams.WRAP_CONTENT,true);
        AssistMode[] order={AssistMode.READ,AssistMode.PLACES,AssistMode.FIND,AssistMode.DESCRIBE};
        for(AssistMode m:order)panel.addView(menuItem(m.icon,m.label,modeHint(m),true,m==mode,()->{window.dismiss();chooseMode(m);}));
        View divider=new View(this);divider.setBackgroundColor(Theme.DIVIDER_ON_DARK);
        panel.addView(divider,margins(new LinearLayout.LayoutParams(-1,dp(1)),dp(12),dp(6),dp(12),dp(6)));
        panel.addView(menuItem("globe","Language",language.label,false,false,()->{window.dismiss();chooseLanguage();}));
        panel.addView(menuItem("settings","Settings","Speech, vibration and help",false,false,()->{window.dismiss();showSettings();}));
        window.setOutsideTouchable(true);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        window.setElevation(dp(16));
        window.setOnDismissListener(()->{if(menuWindow==window)menuWindow=null;});
        menuWindow=window;
        window.showAsDropDown(menuButton,0,dp(8),Gravity.END);
        if(!Theme.reducedMotion(this)){
            panel.setAlpha(0f);panel.setScaleX(.94f);panel.setScaleY(.94f);
            panel.post(()->{panel.setPivotX(panel.getWidth());panel.setPivotY(0);
                panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();});
        }
    }

    private View menuItem(String icon,String title,String subtitle,boolean isMode,boolean selected,Runnable action){
        LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);
        item.setMinimumHeight(dp(56));item.setPadding(dp(12),dp(8),dp(12),dp(8));
        item.setBackground(Theme.controlOnDark(this,selected?0x2EBFE7DD:0x00000000,14,0,0));
        item.setFocusable(true);item.setClickable(true);
        ImageView glyph=new ImageView(this);glyph.setImageDrawable(new ActionIcon(icon,selected?Theme.MINT:Theme.ON_DARK,dp(22)));glyph.setScaleType(ImageView.ScaleType.CENTER);
        item.addView(glyph,new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout words=vertical();
        TextView name=Theme.text(this,title,16,selected?Theme.MINT:Theme.ON_DARK,selected);words.addView(name);
        if(subtitle!=null)words.addView(Theme.text(this,subtitle,12,Theme.ON_DARK_MUTED,false));
        item.addView(words,margins(new LinearLayout.LayoutParams(0,-2,1),dp(14),0,dp(8),0));
        if(selected){ImageView check=new ImageView(this);check.setImageDrawable(new ActionIcon("check",Theme.MINT,dp(20)));item.addView(check,new LinearLayout.LayoutParams(dp(20),dp(20)));}
        for(int i=0;i<item.getChildCount();i++)item.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        item.setContentDescription(title+(subtitle==null?"":", "+subtitle));
        item.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);
                if(isMode){info.setClassName("android.widget.RadioButton");info.setCheckable(true);info.setChecked(selected);}
                else info.setClassName("android.widget.Button");
            }
        });
        item.setOnClickListener(v->action.run());
        return item;
    }

    private String modeHint(AssistMode m){
        return switch(m){
            case READ -> "Read signs, labels and pages";
            case PLACES -> "Identify where you are";
            case FIND -> "Locate any object";
            case DESCRIBE -> "Explain what's in front of you";
        };
    }

    /** Mode change from the menu, with a short spoken confirmation of what to do next. */
    private void chooseMode(AssistMode m){
        boolean changed=m!=mode;
        selectMode(m);
        String confirm=m.label+" mode. "+switch(m){
            case READ -> "Point at text and tap the shutter.";
            case PLACES -> "Point around you and tap the shutter.";
            case FIND -> "Say or type what you're looking for.";
            case DESCRIBE -> "Point the camera and tap the shutter.";
        };
        if(!changed)return;
        if(accessibilityActive())modeChip.announceForAccessibility(confirm);else say(confirm,false);
        if(m==AssistMode.FIND&&findText.isBlank())focusQuery();
    }

    // ───────────────────────────── Settings ─────────────────────────────

    private void showSettings(){
        SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        LinearLayout body=vertical();body.setPadding(dp(24),dp(8),dp(24),0);
        Switch speak=settingSwitch("Speak answers aloud",prefs.getBoolean("speak",true));
        Switch buzz=settingSwitch("Vibrate when something is found",prefs.getBoolean("vibrate",true));
        body.addView(speak);body.addView(buzz);
        TextView rateLabel=Theme.text(this,"Speech speed",14,Theme.MUTED,true);rateLabel.setPadding(0,dp(16),0,dp(4));body.addView(rateLabel);
        RadioGroup rates=new RadioGroup(this);rates.setOrientation(RadioGroup.HORIZONTAL);
        String[] names={"Slow","Normal","Fast"};float[] values={.8f,1f,1.25f};
        float current=prefs.getFloat("rate",1f);
        for(int i=0;i<3;i++){RadioButton r=new RadioButton(this);r.setText(names[i]);r.setTextSize(16);r.setId(View.generateViewId());r.setMinHeight(dp(48));r.setPadding(0,0,dp(16),0);rates.addView(r);if(Math.abs(values[i]-current)<.01f)r.setChecked(true);}
        body.addView(rates);
        new android.app.AlertDialog.Builder(this).setTitle("Settings").setView(body)
            .setPositiveButton("Done",(d,w)->{
                int checked=rates.indexOfChild(rates.findViewById(rates.getCheckedRadioButtonId()));
                prefs.edit().putBoolean("speak",speak.isChecked()).putBoolean("vibrate",buzz.isChecked()).putFloat("rate",values[Math.max(0,checked)]).apply();
                configureVoice();
            })
            .setNeutralButton("How to use",(d,w)->showHelp())
            .setNegativeButton("Sign evidence",(d,w)->showEvidence()).show();
    }
    private Switch settingSwitch(String label,boolean on){
        Switch s=new Switch(this);s.setText(label);s.setTextSize(16);s.setChecked(on);s.setMinHeight(dp(56));
        return s;
    }

    // ───────────────────────────── Rendering ─────────────────────────────

    private void renderAll(){
        renderModeChip();
        renderContext();
        renderShutter();
        renderMic();
        statusView.set(state,stateDetail);
        result.setText(displayText);
        renderAudioControls();
        renderNotice();
        placeSignature="";refreshResponseActions();
        renderSheet();
        renderCamera();
        configureVoice();
    }

    private void renderModeChip(){
        modeChip.setText(mode.label.toUpperCase(Locale.ROOT));
        modeChip.setCompoundDrawables(new ActionIcon(mode.icon,Theme.MINT,dp(18)),null,new ActionIcon("chevron_down",Theme.ON_DARK_MUTED,dp(16)),null);
        modeChip.setContentDescription("Mode: "+mode.label+". Double tap to change mode.");
    }

    private void setSheetExpanded(boolean expanded){
        if(sheetExpanded==expanded)return;
        sheetExpanded=expanded;renderSheet();
        if(!expanded)hideKeyboard();
    }

    private void renderSheet(){
        if(result==null)return;
        boolean e=sheetExpanded;
        result.setMaxLines(e?Integer.MAX_VALUE:2);
        result.setEllipsize(e?null:android.text.TextUtils.TruncateAt.END);
        resultScroll.setVisibility(displayText.isEmpty()?View.GONE:View.VISIBLE);
        if(!e)resultScroll.scrollTo(0,0);
        expandButton.setImageDrawable(new ActionIcon(e?"chevron_down":"chevron_up",Theme.ON_DARK,dp(22)));
        expandButton.setContentDescription(e?"Collapse answer":"Show full answer");
        renderContext();renderNotice();placeSignature="";refreshResponseActions();
    }

    private void renderContext(){
        boolean find=mode==AssistMode.FIND,describe=mode==AssistMode.DESCRIBE;
        contextSlot.setVisibility(sheetExpanded&&mode!=AssistMode.READ?View.VISIBLE:View.GONE);
        query.setVisibility(find||describe?View.VISIBLE:View.GONE);
        washroomButton.setVisibility(mode==AssistMode.PLACES?View.VISIBLE:View.GONE);
        if(find){query.setHint("What are you looking for?");query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);if(!query.getText().toString().equals(findText))query.setText(findText);}
        if(describe){query.setHint("Ask about something specific (optional)");query.setImeOptions(EditorInfo.IME_ACTION_GO);if(!query.getText().toString().equals(askText))query.setText(askText);}
    }

    private void renderShutter(){
        boolean busy=working||searchActive;
        boolean asking=mode==AssistMode.DESCRIBE&&query!=null&&query.getText().toString().trim().length()>0;
        shutter.setImageDrawable(new ShutterDrawable(this,asking?"chat":mode.icon,busy));
        shutter.setContentDescription(busy?(searchActive?"Stop searching":"Stop"):asking?"Ask your question":mode.action);
    }

    private void renderMic(){
        boolean listening=voice!=null&&voice.isListening();
        micButton.setImageDrawable(new ActionIcon(listening?"stop":"mic",listening?Theme.INK:Theme.ON_BRAND,dp(22)));
        micButton.setBackground(Theme.controlOnDark(this,listening?Theme.MINT:Theme.BRAND,24,0,0));
        micButton.setContentDescription(listening?"Stop listening":"Speak a request");
    }

    private void renderAudioControls(){
        replayButton.setVisibility(hasAnswer&&!lastMessage.isEmpty()?View.VISIBLE:View.GONE);
        boolean speaking=speech!=null&&speechReady&&speech.isSpeaking();
        stopButton.setVisibility(speaking||working||searchActive||state==AssistState.SPEAKING?View.VISIBLE:View.GONE);
        stopButton.setContentDescription(state==AssistState.SPEAKING?"Stop speaking":"Stop");
    }

    private String baseNotice(){
        if(!speechReady&&speech!=null&&!voiceAvailable)return "Spoken answers aren't available on this phone. Answers appear here.";
        if(speechReady&&!voiceAvailable)return "No "+language.promptName+" voice is installed. Add one in Android text-to-speech settings. Answers still appear here.";
        if(!gemma.installed()&&mode!=AssistMode.FIND)return "Detailed AI answers aren't installed on this phone, so Saathi uses basic recognition.";
        if(mode==AssistMode.FIND&&detectorFailed&&!gemma.installed())return "Object finding isn't available on this installation.";
        return "";
    }
    private void renderNotice(){
        if(notice==null)return;
        String text=noticeOverride!=null?noticeOverride:baseNotice();
        notice.setText(text);notice.setVisibility(sheetExpanded&&!text.isEmpty()?View.VISIBLE:View.GONE);
    }

    private void renderCamera(){
        if(preview==null)return;
        CameraState s=cameraState;
        boolean live=s==CameraState.LIVE;
        liveChip.setVisibility(live?View.VISIBLE:View.GONE);
        switchButton.setEnabled(live);switchButton.setAlpha(live?1f:.5f);
        cameraMessage.setVisibility(live?View.GONE:View.VISIBLE);
        switch(s){
            case STARTING -> {cameraMessageIcon.setImageDrawable(new ActionIcon("describe",0xCCFFFFFF,dp(40)));cameraMessageText.setText("Starting camera…");cameraMessageAction.setVisibility(View.GONE);}
            case NO_PERMISSION -> {cameraMessageIcon.setImageDrawable(new ActionIcon("camera_off",Color.WHITE,dp(40)));cameraMessageText.setText("Saathi needs the camera to see your surroundings. Images stay on your phone.");
                cameraMessageAction.setText("Allow camera");cameraMessageAction.setVisibility(View.VISIBLE);cameraMessageAction.setOnClickListener(v->requestCamera());}
            case UNAVAILABLE -> {cameraMessageIcon.setImageDrawable(new ActionIcon("camera_off",Color.WHITE,dp(40)));cameraMessageText.setText("The camera isn't available. Close other camera apps and try again.");
                cameraMessageAction.setText("Try again");cameraMessageAction.setVisibility(View.VISIBLE);cameraMessageAction.setOnClickListener(v->startCamera());}
            case LIVE -> {}
        }
        String camera=switch(s){
            case LIVE -> (frontCamera?"Front":"Back")+" camera, live";
            case STARTING -> "Camera starting";
            case NO_PERMISSION -> "Camera access needed";
            case UNAVAILABLE -> "Camera unavailable";
        };
        preview.setContentDescription(camera+" view");
        brandView.setContentDescription("Saathi. Your world, understood. "+camera+".");
        switchButton.setContentDescription(frontCamera?"Switch to back camera":"Switch to front camera");
    }

    private void setCameraState(CameraState s){cameraState=s;if(preview!=null)renderCamera();}

    private void setState(AssistState s,String detail){
        state=s;stateDetail=detail;
        if(statusView==null)return;
        statusView.set(s,detail);renderAudioControls();
        String spoken=detail==null?s.label:detail;
        boolean worthAnnouncing=s==AssistState.PROCESSING||s==AssistState.LISTENING||s==AssistState.ERROR||s==AssistState.PAUSED;
        if(worthAnnouncing&&!spoken.equals(lastAnnounced)&&accessibilityActive())statusView.announceForAccessibility(spoken);
        lastAnnounced=worthAnnouncing?spoken:"";
    }

    private void refreshResponseActions(){
        if(responseActions==null)return;
        long now=SystemClock.elapsedRealtime();
        boolean places=mode==AssistMode.PLACES;
        boolean canVerify=places&&!working&&place.canVerify(now),canConfirm=places&&!working&&place.canConfirm(now);
        // Verification is time-limited, so its controls bring the sheet up on their own.
        if((canVerify||canConfirm)&&!sheetExpanded){sheetExpanded=true;renderSheet();return;}
        PlaceSession.State ps=place.state();
        boolean placeTask=places&&ps!=PlaceSession.State.IDLE&&ps!=PlaceSession.State.CONFIRMED;
        boolean tryAgain=state==AssistState.ERROR&&retry!=null;
        boolean followUp=hasAnswer&&!working&&!searchActive&&!placeTask&&state!=AssistState.ERROR;
        boolean dismiss=hasAnswer&&!working&&!searchActive&&!placeTask;
        String signature=canVerify+"|"+canConfirm+"|"+placeTask+"|"+tryAgain+"|"+followUp+"|"+dismiss+"|"+mode+"|"+sheetExpanded;
        if(signature.equals(placeSignature))return;
        placeSignature=signature;
        responseActions.removeAllViews();
        if(sheetExpanded){
            if(tryAgain)addAction("Try again","replay",true,()->{Runnable r=retry;retry=null;if(r!=null)r.run();});
            if(canConfirm)addAction("Confirm entrance","door",true,this::confirmEntrance);
            if(canVerify)addAction("Verify entrance","door",false,this::verifyEntrance);
            if(placeTask)addAction("End task",null,false,()->{stopAll(false);place.cancel();deliver(place.message(),true);});
            if(followUp)addAction("Ask a follow-up","chat",false,this::askFollowUp);
            if(dismiss)addAction("Dismiss","close",false,this::dismissAnswer);
        }
        responseActions.setVisibility(responseActions.getChildCount()>0?View.VISIBLE:View.GONE);
    }
    private void addAction(String label,String icon,boolean solid,Runnable action){
        Button b=darkButton(label,icon,solid,action);b.setTextSize(14);b.setPadding(dp(10),dp(6),dp(10),dp(6));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
        if(responseActions.getChildCount()>0)p.setMargins(dp(8),0,0,0);
        responseActions.addView(b,p);
    }

    private void dismissAnswer(){
        stopSpeech();hasAnswer=false;lastMessage="";noticeOverride=null;
        displayText=idleMessage(mode);result.setText(displayText);
        setState(AssistState.READY,null);
        setSheetExpanded(false);renderAudioControls();refreshResponseActions();
    }

    private void focusQuery(){
        setSheetExpanded(true);
        query.requestFocus();
        InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(imm!=null)query.post(()->imm.showSoftInput(query,InputMethodManager.SHOW_IMPLICIT));
    }

    // ───────────────────────────── Gallery ─────────────────────────────

    private void pickPhoto(){
        if(mode==AssistMode.FIND&&VoiceCommands.objectPhrase(currentQuery()).isEmpty()){
            deliver("Type what you're looking for first, then choose the photo.",true);hasAnswer=false;refreshResponseActions();focusQuery();return;
        }
        pickingPhoto=true;
        try{pickImage.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
            .setMediaType(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());}
        catch(ActivityNotFoundException e){pickingPhoto=false;showError("No photo picker is available on this phone.",null);}
    }
    private String currentQuery(){rememberQuery();return mode==AssistMode.FIND?findText:askText;}

    /** Runs the active mode on a picked photo, through the same detection, OCR and Gemma path as the camera. */
    private void analyzePhoto(android.net.Uri uri){
        rememberQuery();cancelWork();
        Capture kind=switch(mode){case DESCRIBE->Capture.DESCRIBE;case READ->Capture.READ;case PLACES->Capture.PLACE_IDENTIFY;case FIND->Capture.FIND_IMAGE;};
        String text=mode==AssistMode.FIND?VoiceCommands.objectPhrase(findText):askText.trim();
        int ticket=requestEpoch.incrementAndGet();previousAnswer="";
        beginWorking("Looking at your photo…");
        CaptureRequest request=new CaptureRequest(kind,ticket,text,language);
        worker.execute(()->{
            Bitmap bitmap;
            try{bitmap=decodePhoto(uri);}
            catch(Exception|OutOfMemoryError e){android.util.Log.e("Saathi","Photo decode failed",e);ui.post(()->{if(current(request))showError("That photo couldn't be opened. Try another one.",this::pickPhoto);});return;}
            for(int i=0;i<60&&!frameBusy.compareAndSet(false,true);i++)try{Thread.sleep(50);}catch(InterruptedException ignored){}
            processCapture(request,bitmap);
        });
    }

    private Bitmap decodePhoto(android.net.Uri uri) throws Exception {
        int max=1600;
        if(Build.VERSION.SDK_INT>=28){
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),uri),(decoder,info,source)->{
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w=info.getSize().getWidth(),h=info.getSize().getHeight();float s=Math.min(1f,(float)max/Math.max(w,h));
                decoder.setTargetSize(Math.max(1,Math.round(w*s)),Math.max(1,Math.round(h*s)));
            }).copy(Bitmap.Config.ARGB_8888,false);
        }
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(java.io.InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/(opts.inSampleSize*2)>=max)opts.inSampleSize*=2;
        Bitmap raw;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){raw=BitmapFactory.decodeStream(in,null,opts);}
        if(raw==null)throw new java.io.IOException("Unreadable image");
        int rotation;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){
            int o=new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_NORMAL);
            rotation=o==android.media.ExifInterface.ORIENTATION_ROTATE_90?90:o==android.media.ExifInterface.ORIENTATION_ROTATE_180?180:o==android.media.ExifInterface.ORIENTATION_ROTATE_270?270:0;}
        if(rotation==0)return raw;
        Matrix m=new Matrix();m.postRotate(rotation);
        Bitmap out=Bitmap.createBitmap(raw,0,0,raw.getWidth(),raw.getHeight(),m,true);if(out!=raw)raw.recycle();
        return out;
    }

    // ───────────────────────────── Modes & primary action ─────────────────────────────

    private void selectMode(AssistMode selected){
        if(selected==mode)return;
        rememberQuery();
        cancelWork();stopSpeech();
        if(mode==AssistMode.PLACES)place.pause();
        mode=selected;getPreferences(MODE_PRIVATE).edit().putString("mode",selected.key).apply();
        hasAnswer=false;retry=null;noticeOverride=null;
        displayText=idleMessage(selected);
        setState(AssistState.READY,null);
        // Find needs a target and Places offers the washroom flow, so those open the sheet.
        sheetExpanded=selected==AssistMode.FIND||selected==AssistMode.PLACES;
        renderAll();
    }

    private String idleMessage(AssistMode m){
        return switch(m){
            case DESCRIBE -> "Point your camera at something and I'll help you understand it.";
            case FIND -> "Tell me what you're looking for, then move the phone slowly.";
            case READ -> "Point your camera at a sign, label or page.";
            case PLACES -> "Point your camera around you and I'll tell you what kind of place this is.";
        };
    }

    private void rememberQuery(){
        if(query==null)return;
        if(mode==AssistMode.FIND)findText=query.getText().toString();
        if(mode==AssistMode.DESCRIBE)askText=query.getText().toString();
    }

    private void runPrimary(){
        if(working||searchActive){stopAll(true);return;}
        rememberQuery();hideKeyboard();
        if(!ensureCamera())return;
        switch(mode){
            case DESCRIBE -> {String q=askText.trim();String previous=followUpContext&&hasAnswer?lastMessage:"";followUpContext=false;
                requestCapture(Capture.DESCRIBE,q,q.isEmpty()?"Looking at your surroundings…":"Looking for your answer…",previous);}
            case READ -> requestCapture(Capture.READ,"","Reading visible text…",null);
            case PLACES -> requestCapture(Capture.PLACE_IDENTIFY,"","Identifying this place…",null);
            case FIND -> startFind(findText);
        }
    }

    private String previousAnswer="";
    private void requestCapture(Capture kind,String text,String message,String previous){
        cancelWork();
        int ticket=requestEpoch.incrementAndGet();
        previousAnswer=previous==null?"":previous;
        beginWorking(message);
        timeoutTicket=ticket;
        pendingCapture.set(new CaptureRequest(kind,ticket,text,language));
        ui.postDelayed(captureTimeout,CAPTURE_TIMEOUT_MS);
    }

    private void beginWorking(String message){
        working=true;hasAnswer=false;retry=null;noticeOverride=null;
        stopSpeech();
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        setState(AssistState.PROCESSING,message);
        renderShutter();renderNotice();refreshResponseActions();
    }

    /** Shows streamed text without announcing every token to TalkBack. */
    private void showStreaming(String text){
        displayText=text;result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);result.setText(text);
        if(!sheetExpanded)setSheetExpanded(true);else resultScroll.setVisibility(View.VISIBLE);
    }

    /** Final answer: on screen, spoken (or announced by TalkBack), and replayable. */
    private void deliver(String text,boolean speak){
        if(destroyed||paused)return;
        ui.removeCallbacks(captureTimeout);
        working=searchActive;
        lastMessage=text;displayText=text;hasAnswer=true;retry=null;
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        result.setText(text);resultScroll.scrollTo(0,0);
        setSheetExpanded(true);renderSheet();
        setState(searchActive?AssistState.PROCESSING:AssistState.READY,searchActive?searchingLabel():null);
        renderShutter();refreshResponseActions();renderNotice();
        if(speak)say(text,false);
    }

    private void showError(String message){showError(message,this::runPrimary);}
    private void showError(String message,Runnable recovery){
        if(destroyed||paused)return;
        ui.removeCallbacks(captureTimeout);
        working=false;searchActive=false;overlay.update(null,1,1);
        retry=recovery;displayText=message;lastMessage=message;hasAnswer=false;
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);result.setText(message);
        setState(AssistState.ERROR,null);
        setSheetExpanded(true);renderSheet();
        renderShutter();refreshResponseActions();
        say(message,false);
    }

    private void cancelWork(){
        requestEpoch.incrementAndGet();
        pendingCapture.set(null);
        ui.removeCallbacks(captureTimeout);
        searchActive=false;findAnswerPending=false;gemmaScanInFlight=false;
        gemma.cancel();
        if(overlay!=null)overlay.update(null,1,1);
        working=false;
        if(shutter!=null){renderShutter();renderAudioControls();}
    }

    private void stopAll(boolean announce){
        boolean wasBusy=working||searchActive;
        cancelWork();stopSpeech();
        if(voice.isListening()){voice.cancel();renderMic();}
        if(wasBusy&&!hasAnswer){displayText=idleMessage(mode);result.setText(displayText);}
        setState(announce?AssistState.PAUSED:AssistState.READY,announce?"Assistance paused · Tap the shutter to continue":null);
        refreshResponseActions();
    }

    private boolean ensureCamera(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestCamera();return false;}
        if(!cameraReady){
            if(cameraState==CameraState.UNAVAILABLE)startCamera();
            showError("The camera is still starting. Try again in a moment.");return false;
        }
        return true;
    }

    // ───────────────────────────── Find ─────────────────────────────

    private void startFind(String raw){
        String phrase=VoiceCommands.objectPhrase(raw);
        if(phrase.isEmpty()){
            displayText="What are you looking for? Type it, or tap the microphone and say it.";
            deliver(displayText,true);hasAnswer=false;refreshResponseActions();
            focusQuery();return;
        }
        String label=AssistLanguage.voiceObject(phrase);
        if(label==null){String exact=AssistLanguage.canonicalObject(phrase);if(Arrays.asList(YoloDetector.LABELS).contains(exact))label=exact;}
        // The open-vocabulary finder reads English phrases; localised everyday names map to English first.
        String english=phrase.matches("[\\p{ASCII}]+")?phrase:label;
        OpenVocabFinder open=english!=null?finder:null;
        boolean yolo=open==null&&label!=null&&detector!=null, ai=gemma.installed();
        if(open==null&&!yolo&&!ai){
            deliver(label!=null&&!detectorFailed?"Object finding is still starting. Try again in a moment.":"I can't look for “"+phrase+"” with basic recognition. I can find everyday things like a bottle, chair, cup, phone, book, bag, laptop or person.",true);
            return;
        }
        cancelWork();
        int ticket=requestEpoch.incrementAndGet();
        requestedPhrase=phrase;requested=yolo?label:"";
        finderQuery=null;finderStreak=0;confirmAttempts=0;
        searchTicket=ticket;findInterpreted=false;gemmaScanInFlight=false;lastCue="";lastSpoken=0;lastSeen=0;lastGemmaCheck=0;lastSteadyFrame=SystemClock.elapsedRealtime();gemmaChecks=0;
        searchStarted=SystemClock.elapsedRealtime();
        searchActive=true;
        beginWorking(searchingLabel());
        displayText="Turn the phone slowly from side to side. I'll tell you when I see "+article(phrase)+".";
        result.setText(displayText);
        say("Looking for "+phrase+". Turn slowly.",false);
        if(open!=null){
            String[] phrases=OpenVocabFinder.phrasesFor(english);
            initialization.execute(()->{
                try{long t=SystemClock.elapsedRealtime();OpenVocabFinder.Query q=open.prepare(phrases);
                    if(searchActive&&ticket==requestEpoch.get())finderQuery=q;
                    android.util.Log.i("Saathi","Finder query "+Arrays.toString(phrases)+" encoded in "+(SystemClock.elapsedRealtime()-t)+" ms");}
                catch(Exception|LinkageError e){android.util.Log.e("Saathi","Finder text encoding failed",e);}
            });
        }
    }
    private String searchingLabel(){return "Searching for "+requestedPhrase;}
    private static String article(String s){return s.endsWith("s")?s:(("aeiou".indexOf(s.charAt(0))>=0)?"an ":"a ")+s;}
    private boolean steady(long now){return now-lastShake>=STEADY_MS;}

    /**
     * Worker thread, one frame per tick. A detector (open-vocabulary finder, or YOLO for its classes)
     * gives fast live cues; Gemma confirms the first sighting and keeps checking steady frames when
     * the detector is quiet.
     */
    private void processSearchFrame(Bitmap bitmap,long now){
        try{
            int ticket=searchTicket;
            if(tooDark(bitmap)){
                if(now-lastSpoken>8000){lastSpoken=now;ui.post(()->{if(searchActive&&ticket==requestEpoch.get())say("It's too dark to see. Move toward some light.",false);});}
                return;
            }
            OpenVocabFinder.Query q=finderQuery;OpenVocabFinder f=finder;
            boolean open=q!=null&&f!=null, yolo=!open&&!requested.isEmpty()&&detector!=null;
            boolean detecting=open||yolo;
            YoloDetector.Hit found=null;
            if(open){
                long t=SystemClock.elapsedRealtime();
                List<YoloDetector.Hit> hits=f.detect(bitmap,q,FINDER_CANDIDATE);
                YoloDetector.Hit top=hits.isEmpty()?null:hits.get(0);
                if(now-lastScoreLog>2000){lastScoreLog=now;android.util.Log.i("Saathi","Finder frame "+(SystemClock.elapsedRealtime()-t)+" ms, best "+(top==null?"none":top.label()+" "+String.format(Locale.ROOT,"%.2f",top.score())));}
                // Two candidate frames in a row, or one confident frame, before speaking.
                finderStreak=top==null?0:finderStreak+1;
                if(top!=null&&(top.score()>=FINDER_CONFIDENT||finderStreak>=2))found=new YoloDetector.Hit(requestedPhrase,top.score(),top.x(),top.y(),top.w(),top.h());
            }else if(yolo){
                for(YoloDetector.Hit h:detector.detect(bitmap))if(h.label().equals(requested)){found=h;break;}
            }
            if(detecting){
                final YoloDetector.Hit display=found;final int bw=bitmap.getWidth(),bh=bitmap.getHeight();
                ui.post(()->{if(searchActive&&ticket==requestEpoch.get())overlay.update(display,bw,bh);});
            }
            if(!searchActive||ticket!=requestEpoch.get())return;
            if(found!=null){
                lastSeen=now;
                String position=SceneSummary.side(found.x());
                String name=yolo?requested:requestedPhrase;
                remember(name,position,now);
                String cue=language.detected(name,position);
                if(!findInterpreted&&gemma.installed()&&confirmAttempts<3){
                    findInterpreted=true;findAnswerPending=true;confirmAttempts++;
                    byte[] image=jpeg(bitmap);String hint="a detector box for it is on the "+position;
                    lastCue=cue;lastSpoken=now;
                    ui.post(()->{if(!searchActive||ticket!=requestEpoch.get())return;vibrate();deliver(cue,true);confirmFind(image,hint,ticket);});
                }else if(!findAnswerPending&&(!cue.equals(lastCue)||now-lastSpoken>7000)){
                    lastCue=cue;lastSpoken=now;
                    ui.post(()->{if(searchActive&&ticket==requestEpoch.get()){vibrate();deliver(cue,true);}});
                }
                return;
            }
            boolean detectorQuiet=!detecting||now-Math.max(searchStarted,lastSeen)>YOLO_QUIET_MS;
            if(steady(now))lastSteadyFrame=now;
            if(detectorQuiet&&gemma.installed()&&!gemmaScanInFlight&&!findAnswerPending&&now-lastGemmaCheck>=GEMMA_SCAN_GAP_MS&&steady(now)){
                gemmaScanInFlight=true;lastGemmaCheck=now;
                byte[] image=jpeg(bitmap);
                String hint=detecting?"a fast detector does not see it in this frame":"";
                ui.post(()->{if(searchActive&&ticket==requestEpoch.get())gemmaCheck(image,hint,ticket);else gemmaScanInFlight=false;});
            }else if(now-lastSteadyFrame>4000&&now-lastSpoken>8000){
                // Every frame is blurred: ask the person to slow down instead of guessing.
                lastSpoken=now;
                ui.post(()->{if(searchActive&&ticket==requestEpoch.get())say("Move a little slower so I can see clearly.",false);});
            }else if(!gemma.installed()&&detectorQuiet&&now-lastSpoken>FIND_QUIET_MS){
                lastSpoken=now;String name=yolo?requested:requestedPhrase;
                ui.post(()->{if(searchActive&&ticket==requestEpoch.get())deliver(language.notFound(name),true);});
            }
            if(now-searchStarted>FIND_LIMIT_MS)ui.post(()->{if(searchActive&&ticket==requestEpoch.get()){searchActive=false;gemma.cancel();overlay.update(null,1,1);
                deliver("I couldn't find "+article(requestedPhrase)+" in the last 90 seconds, so I stopped to save battery. Tap Find to search again.",true);}});
        }catch(Exception|LinkageError e){
            android.util.Log.e("Saathi","Object search frame failed",e);
        }finally{bitmap.recycle();frameBusy.set(false);}
    }

    /** Gemma double-checks the detector's first sighting and says what the item is next to. */
    private void confirmFind(byte[] image,String hint,int ticket){
        gemma.ask(image,GemmaPrompts.findCheck(requestedPhrase,hint,language),language,60,new GemmaAssistant.Listener(){
            boolean current(){return searchActive&&ticket==requestEpoch.get()&&!paused;}
            public void onStatus(String m){}
            public void onPartial(String t){}
            public void onComplete(String t){
                if(!current())return;
                findAnswerPending=false;
                FindCheck check=FindCheck.parse(t);
                long now=SystemClock.elapsedRealtime();
                android.util.Log.i("Saathi","Gemma confirmation: "+(check.found()?"FOUND "+check.side():"not confirmed"));
                if(check.found()){
                    if(!check.detail().isEmpty()){lastSpoken=now;deliver(language.detected(requestedPhrase,check.side())+" "+check.detail(),true);}
                }else{
                    // Possibly a look-alike. Keep scanning and allow another confirmation.
                    findInterpreted=false;lastSpoken=now;
                    deliver("I'm not sure that was the "+requestedPhrase+". Keep turning slowly.",true);
                }
            }
            public void onError(String m){if(current())findAnswerPending=false;}
        });
    }

    private void remember(String label,String position,long now){
        if(now-lastSaved>10000||!label.equals(savedTarget)||!position.equals(savedPosition)){
            getPreferences(MODE_PRIVATE).edit().putLong("seen:"+label,System.currentTimeMillis()).putString("position:"+label,position).apply();
            lastSaved=now;savedTarget=label;savedPosition=position;
        }
    }

    /** One quick Gemma look at a steady frame. Silent when not found; speaks and vibrates when found. */
    private void gemmaCheck(byte[] image,String hint,int ticket){
        gemma.ask(image,GemmaPrompts.findCheck(requestedPhrase,hint,language),language,48,new GemmaAssistant.Listener(){
            boolean done;
            boolean current(){return searchActive&&ticket==requestEpoch.get()&&!paused;}
            public void onStatus(String m){if(current())setState(AssistState.PROCESSING,m);}
            // The verdict is in the first words; stop generating as soon as it says NOT_FOUND.
            public void onPartial(String t){if(!done&&t.toUpperCase(Locale.ROOT).contains("NOT")&&t.toUpperCase(Locale.ROOT).contains("FOUND")){onComplete("NOT_FOUND");gemma.cancel();}}
            public void onComplete(String t){
                if(done)return;done=true;
                gemmaScanInFlight=false;
                if(!current())return;
                gemmaChecks++;
                FindCheck check=FindCheck.parse(t);
                long now=SystemClock.elapsedRealtime();
                android.util.Log.i("Saathi","Find check "+gemmaChecks+": "+(check.found()?"FOUND "+check.side():"not found"));
                if(!check.found()){
                    if(state!=AssistState.SPEAKING)setState(AssistState.PROCESSING,searchingLabel()+" · checked "+gemmaChecks+(gemmaChecks==1?" view":" views"));
                    if(now-lastSpoken>12000&&now-lastSeen>12000){lastSpoken=now;say("Still looking. Keep turning slowly.",false);}
                    return;
                }
                lastSeen=now;
                String cue=language.detected(requestedPhrase,check.side());
                String message=check.detail().isEmpty()?cue:cue+" "+check.detail();
                if(!cue.equals(lastCue)||now-lastSpoken>8000){lastCue=cue;lastSpoken=now;vibrate();deliver(message,true);}
            }
            public void onError(String m){if(done)return;done=true;gemmaScanInFlight=false;if(current())android.util.Log.w("Saathi","Find check failed: "+m);}
        });
    }

    private void vibrate(){
        if(!getPreferences(MODE_PRIVATE).getBoolean("vibrate",true))return;
        Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);
        if(v!=null&&v.hasVibrator())v.vibrate(VibrationEffect.createOneShot(90,VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void recall(String raw){
        String q=AssistLanguage.canonicalObject(VoiceCommands.objectPhrase(raw));
        if(q.isEmpty()){deliver("Which object? For example, say: where did I last see my bottle.",true);return;}
        long time=getPreferences(MODE_PRIVATE).getLong("seen:"+q,0);
        if(time==0){deliver("I haven't seen a "+q+" on this phone yet.",true);return;}
        String at=java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(time));
        deliver("I last saw a "+q+" at "+at+", on the "+getPreferences(MODE_PRIVATE).getString("position:"+q,"centre")+" of the camera view. It may have moved since then.",true);
    }

    // ───────────────────────────── Places ─────────────────────────────

    private void scanWashroom(){
        if(working||searchActive)cancelWork();
        if(!ensureCamera())return;
        PlaceSession.State s=place.state();
        if(s==PlaceSession.State.IDLE||s==PlaceSession.State.PAUSED||s==PlaceSession.State.CONFIRMED)place.begin();
        place.invalidate("Scanning fresh evidence…");
        requestCapture(Capture.PLACE_SIGN,"","Reading washroom signs… hold steady",null);
    }
    private void verifyEntrance(){
        if(place.requestVerification(SystemClock.elapsedRealtime()))requestCapture(Capture.PLACE_VERIFY,"","Verifying the entrance label… hold steady",null);
        else deliver("Scan a washroom sign first.",true);
    }
    private void confirmEntrance(){
        cancelWork();place.confirm(SystemClock.elapsedRealtime());deliver(place.message(),true);
    }

    // ───────────────────────────── Camera frames ─────────────────────────────

    private void analyze(ImageProxy image){
        try{
            if(destroyed||paused)return;
            long now=SystemClock.elapsedRealtime();
            CaptureRequest request=pendingCapture.get();
            boolean scan=request==null&&searchActive&&(detector!=null||finder!=null||gemma.installed())&&now-lastFrame>=FIND_FRAME_MS;
            if(request==null&&!scan)return;
            if(!frameBusy.compareAndSet(false,true))return;
            if(request!=null&&!pendingCapture.compareAndSet(request,null)){frameBusy.set(false);return;}
            lastFrame=now;
            Bitmap bitmap;
            try{bitmap=upright(image);}catch(Exception|LinkageError e){frameBusy.set(false);throw e;}
            if(request!=null)processCapture(request,bitmap);else processSearchFrame(bitmap,now);
        }catch(Exception|LinkageError e){
            android.util.Log.e("Saathi","Camera frame processing failed",e);
            ui.post(()->showError("This camera view couldn't be processed. Try again, or switch cameras."));
        }finally{image.close();}
    }

    private Bitmap upright(ImageProxy image){
        Bitmap frame=CameraFrames.rgba(image);
        Matrix m=new Matrix();m.postRotate(image.getImageInfo().getRotationDegrees());
        Rect crop=image.getCropRect();
        Bitmap out=Bitmap.createBitmap(frame,crop.left,crop.top,crop.width(),crop.height(),m,true);
        if(out!=frame)frame.recycle();
        return out;
    }

    /** Worker thread. Releases frameBusy and the bitmap on every path. */
    private void processCapture(CaptureRequest request,Bitmap bitmap){
        boolean handedOff=false;
        try{
            if(tooDark(bitmap)){
                ui.post(()->{
                    if(!current(request))return;
                    if(request.kind()==Capture.PLACE_SIGN||request.kind()==Capture.PLACE_VERIFY)place.invalidate("The view is too dark. Aim at a well-lit sign and scan again.");
                    deliver("It's too dark to see clearly. Turn on a light or face a brighter area, then try again.",true);
                });
                return;
            }
            boolean detect=request.kind()==Capture.DESCRIBE||request.kind()==Capture.PLACE_IDENTIFY;
            YoloDetector d=detector;
            List<YoloDetector.Hit> hits=detect&&d!=null?d.detect(bitmap):List.of();
            byte[] image=jpeg(bitmap);
            int width=bitmap.getWidth();
            if(request.kind()==Capture.FIND_IMAGE){
                // Photo search: the open-vocabulary finder (or YOLO for its classes) locates the target first.
                List<YoloDetector.Hit> found=new ArrayList<>();OpenVocabFinder f=finder;String phrase=request.query();
                if(f!=null&&phrase.matches("[\\p{ASCII}]+"))found.addAll(f.detect(bitmap,f.prepare(OpenVocabFinder.phrasesFor(phrase)),.3f));
                else if(d!=null){String label=AssistLanguage.voiceObject(phrase);for(YoloDetector.Hit h:d.detect(bitmap))if(h.label().equals(label)){found.add(h);break;}}
                ui.post(()->onCaptured(request,image,found,null,width));
                return;
            }
            TextRecognizer recognizer=request.language()==AssistLanguage.HINDI?hindiReader:reader;
            Bitmap ocrFrame=bitmap;
            var task=recognizer.process(InputImage.fromBitmap(ocrFrame,0));
            handedOff=true;
            task.addOnSuccessListener(text->onCaptured(request,image,hits,text,width))
                .addOnFailureListener(e->{android.util.Log.e("Saathi","Text recognition failed",e);onCaptured(request,image,hits,null,width);})
                .addOnCompleteListener(t->{ocrFrame.recycle();frameBusy.set(false);});
        }catch(Exception|LinkageError e){
            android.util.Log.e("Saathi","Capture processing failed",e);
            ui.post(()->{if(current(request))showError("This camera view couldn't be processed. Try again.");});
        }finally{if(!handedOff){bitmap.recycle();frameBusy.set(false);}}
    }

    private boolean current(CaptureRequest r){return !destroyed&&!paused&&r.ticket()==requestEpoch.get();}

    /** Main thread. Detection and OCR are ready; hand the frame to Gemma with them as hints. */
    private void onCaptured(CaptureRequest request,byte[] image,List<YoloDetector.Hit> hits,Text text,int width){
        if(!current(request))return;
        ui.removeCallbacks(captureTimeout);
        String ocr=text==null?"":text.getText().trim();
        String objects=SceneSummary.hints(hits);
        AssistLanguage lang=request.language();
        switch(request.kind()){
            case DESCRIBE -> {
                String basic=SceneSummary.describe(hits,ocr,lang);
                if(!gemma.installed()){deliver(basic,true);return;}
                setState(AssistState.PROCESSING,request.query().isEmpty()?"Understanding your surroundings…":"Answering your question…");
                askGemma(request,image,GemmaPrompts.describe(request.query(),objects,ocr,previousAnswer),160,basic);
            }
            case PLACE_IDENTIFY -> {
                String basic=SceneSummary.place(hits,ocr,lang);
                if(!gemma.installed()){deliver(basic,true);return;}
                setState(AssistState.PROCESSING,"Working out where you are…");
                askGemma(request,image,GemmaPrompts.identifyPlace(objects,ocr),150,basic);
            }
            case READ -> {
                String basic=ocr.isEmpty()?"I couldn't find readable text. Move a little closer, hold steady and make sure there is light.":ocr;
                if(!gemma.installed()){deliver(basic,true);return;}
                if(!ocr.isEmpty())showStreaming(ocr);
                setState(AssistState.PROCESSING,"Reading the text…");
                askGemma(request,image,GemmaPrompts.read(ocr),220,basic);
            }
            case FIND_IMAGE -> {
                String phrase=request.query();
                String basic=hits.isEmpty()?"I couldn't spot "+article(phrase)+" in this photo."
                    :lang.detected(phrase,SceneSummary.side(hits.get(0).x())).replace("camera view","photo").replace("the camera","the photo");
                if(!gemma.installed()){deliver(basic,true);return;}
                setState(AssistState.PROCESSING,"Looking for "+phrase+" in your photo…");
                askGemma(request,image,GemmaPrompts.find(phrase,hits.isEmpty()?"":phrase+" detected ("+SceneSummary.side(hits.get(0).x())+")"),140,basic);
            }
            case PLACE_SIGN, PLACE_VERIFY -> {
                if(text==null){place.invalidate("Text recognition couldn't finish. Hold steady and scan the sign again.");deliver(place.message(),true);return;}
                List<PlaceSession.Evidence> evidence=new ArrayList<>();
                long capturedAt=SystemClock.elapsedRealtime();
                for(Text.TextBlock block:text.getTextBlocks()){
                    Rect box=block.getBoundingBox();
                    String side=box==null?"centre":SceneSummary.side(box.exactCenterX()/width);
                    evidence.add(new PlaceSession.Evidence(block.getText(),side,capturedAt,PlaceSession.hasDirectionalText(text.getText())));
                }
                place.observe(evidence,capturedAt,request.kind()==Capture.PLACE_VERIFY);
                String message=place.message();
                deliver(message,true);
                if(gemma.installed()&&!ocr.isEmpty())explainSign(request,image,ocr,message);
            }
        }
    }

    /** Sign explanation is descriptive only; it is appended and never changes the verification state. */
    private void explainSign(CaptureRequest request,byte[] image,String ocr,String message){
        noticeOverride="Checking what this sign says…";renderNotice();
        gemma.ask(image,GemmaPrompts.signs(ocr),request.language(),120,new GemmaAssistant.Listener(){
            boolean current(){return MainActivity.this.current(request)&&mode==AssistMode.PLACES;}
            public void onStatus(String m){}
            public void onPartial(String t){}
            public void onComplete(String t){if(!current())return;noticeOverride=null;String combined=message+"\n\nAbout this sign: "+t;lastMessage=combined;displayText=combined;result.setText(combined);renderNotice();say("About this sign: "+t,true);}
            public void onError(String m){if(!current())return;noticeOverride=null;renderNotice();}
        });
    }

    private void askGemma(CaptureRequest request,byte[] image,String prompt,int maxTokens,String fallback){
        gemma.ask(image,prompt,request.language(),maxTokens,new GemmaAssistant.Listener(){
            public void onStatus(String m){if(current(request))setState(AssistState.PROCESSING,m);}
            public void onPartial(String t){if(current(request))showStreaming(t);}
            public void onComplete(String t){if(current(request))deliver(t,true);}
            public void onError(String m){
                if(!current(request))return;
                android.util.Log.w("Saathi","Gemma unavailable for "+request.kind()+": "+m);
                if(fallback==null){showError(m+" Try again.");return;}
                deliver(fallback,true);
                noticeOverride="The detailed answer wasn't available this time, so this one uses basic recognition.";renderNotice();
            }
        });
    }

    private byte[] jpeg(Bitmap bitmap){
        float ratio=Math.min(1f,768f/Math.max(bitmap.getWidth(),bitmap.getHeight()));
        Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(bitmap.getWidth()*ratio)),Math.max(1,(int)(bitmap.getHeight()*ratio)),true);
        java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,85,encoded);
        if(small!=bitmap)small.recycle();
        return encoded.toByteArray();
    }

    private boolean tooDark(Bitmap image){
        long sum=0;int count=0;
        for(int y=0;y<image.getHeight();y+=Math.max(1,image.getHeight()/16))for(int x=0;x<image.getWidth();x+=Math.max(1,image.getWidth()/16)){
            int c=image.getPixel(x,y);sum+=((c>>16)&255)*3+((c>>8)&255)*6+(c&255);count++;
        }
        return sum/(count*10L)<22;
    }

    // ───────────────────────────── Camera ─────────────────────────────

    @androidx.annotation.OptIn(markerClass=ExperimentalMirrorMode.class)
    private void startCamera(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){setCameraState(CameraState.NO_PERMISSION);return;}
        cameraReady=false;setCameraState(CameraState.STARTING);
        var future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            try{
                ProcessCameraProvider provider=future.get();cameraProvider=provider;
                ResolutionSelector resolution=new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(new ResolutionStrategy(new android.util.Size(1280,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                Preview p=new Preview.Builder().setMirrorMode(MirrorMode.MIRROR_MODE_OFF).setResolutionSelector(resolution).build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis a=new ImageAnalysis.Builder().setResolutionSelector(resolution)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                a.setAnalyzer(worker,this::analyze);
                CameraSelector selector=frontCamera?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;
                if(!provider.hasCamera(selector)){frontCamera=false;selector=CameraSelector.DEFAULT_BACK_CAMERA;}
                provider.unbindAll();
                provider.bindToLifecycle(this,selector,p,a);
                preview.getPreviewStreamState().removeObservers(this);
                preview.getPreviewStreamState().observe(this,s->{
                    cameraReady=s==PreviewView.StreamState.STREAMING;
                    setCameraState(cameraReady?CameraState.LIVE:CameraState.STARTING);
                });
            }catch(Exception e){
                android.util.Log.e("Saathi","Camera binding failed",e);
                cameraReady=false;setCameraState(CameraState.UNAVAILABLE);
            }
        },androidx.core.content.ContextCompat.getMainExecutor(this));
    }

    private void switchCamera(){
        cancelWork();
        frontCamera=!frontCamera;
        startCamera();
        if(accessibilityActive())preview.announceForAccessibility(frontCamera?"Front camera":"Back camera");
    }

    private void requestCamera(){
        if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)||!getPreferences(MODE_PRIVATE).getBoolean("cameraAsked",false)){
            getPreferences(MODE_PRIVATE).edit().putBoolean("cameraAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA);
        }else{
            try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())));}
            catch(ActivityNotFoundException e){showError("Open Android Settings, then allow camera access for Saathi.",null);}
        }
    }

    // ───────────────────────────── Speech output ─────────────────────────────

    private boolean accessibilityActive(){
        AccessibilityManager a=(AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);
        return a!=null&&a.isTouchExplorationEnabled();
    }

    /** With TalkBack on, results are read by TalkBack via the live region instead of a second voice. */
    private void say(String message,boolean queue){
        if(!getPreferences(MODE_PRIVATE).getBoolean("speak",true))return;
        speak(message,queue);
    }
    private void speak(String message,boolean queue){
        if(accessibilityActive()||!speechReady||!voiceAvailable||message==null||message.isBlank())return;
        utterance="saathi-"+(++utteranceCounter);
        speech.speak(message.substring(0,Math.min(message.length(),TextToSpeech.getMaxSpeechInputLength())),queue?TextToSpeech.QUEUE_ADD:TextToSpeech.QUEUE_FLUSH,null,utterance);
    }
    private void speechEnded(String id){
        if(destroyed||!id.equals(utterance))return;
        if(state==AssistState.SPEAKING)setState(searchActive||working?AssistState.PROCESSING:AssistState.READY,searchActive?searchingLabel():working?stateDetail:null);
        else renderAudioControls();
    }
    private void stopSpeech(){utterance="";if(speech!=null&&speechReady)speech.stop();if(state==AssistState.SPEAKING)setState(AssistState.READY,null);}
    private void replay(){
        if(lastMessage.isEmpty())return;
        // Replay is an explicit request, so it speaks even when automatic speech is off.
        if(accessibilityActive())result.announceForAccessibility(lastMessage);else speak(lastMessage,false);
    }

    private void configureVoice(){
        if(languageButton==null)return;
        languageButton.setText(switch(language){case ENGLISH->"EN";case HINDI->"हि";case KANNADA->"ಕ";});
        languageButton.setContentDescription("Response language, "+language.promptName+". Double tap to change.");
        if(speechReady){
            // Prefer the regional voice (en-IN), otherwise any installed voice for the language.
            Locale regional=Locale.forLanguageTag(language.tag);
            int supported=speech.setLanguage(regional);
            if(supported<TextToSpeech.LANG_AVAILABLE)supported=speech.setLanguage(new Locale(regional.getLanguage()));
            voiceAvailable=supported>=TextToSpeech.LANG_AVAILABLE;
            speech.setSpeechRate(getPreferences(MODE_PRIVATE).getFloat("rate",1f));
        }
        renderNotice();
    }

    private void chooseLanguage(){
        String[] labels=new String[AssistLanguage.values().length];
        for(int i=0;i<labels.length;i++)labels[i]=AssistLanguage.values()[i].label;
        new android.app.AlertDialog.Builder(this).setTitle("Response language")
            .setSingleChoiceItems(labels,language.ordinal(),(dialog,which)->{
                language=AssistLanguage.values()[which];
                getPreferences(MODE_PRIVATE).edit().putInt("language",which).apply();
                configureVoice();dialog.dismiss();
                if(accessibilityActive())languageButton.announceForAccessibility("Answers will be in "+language.promptName);
            }).setNegativeButton("Close",null).show();
    }

    private void showHelp(){
        String body="Point the camera, then tap the round shutter button at the bottom. Saathi speaks the answer and shows it in the panel above the shutter.\n\n"
            +"Tap ⋮ (top right) or the mode label above the shutter to switch mode:\n"
            +"Read — reads signs, labels and pages aloud.\n"
            +"Places — tells you what kind of place you're in. Washroom signs helps you find and verify a washroom entrance.\n"
            +"Find — say or type any object, then turn slowly. You'll feel a vibration when it's seen.\n"
            +"Describe — explains what is in front of you. You can ask a question.\n\n"
            +"The gallery button analyses a photo you choose. The microphone in the answer panel takes spoken requests like “find my keys”, “read this”, “where am I” or “repeat”.\n\n"
            +"Left and right refer to the camera view. Saathi can't judge distances or tell you a path is safe, so keep using your usual mobility support.";
        new android.app.AlertDialog.Builder(this).setTitle("Using Saathi").setMessage(body)
            .setPositiveButton("Close",null)
            .setNeutralButton("Open phone dialer",(d,w)->{try{startActivity(new Intent(Intent.ACTION_DIAL));}catch(ActivityNotFoundException e){deliver("A phone dialer isn't available.",true);}})
            .setNegativeButton("Sign evidence",(d,w)->showEvidence()).show();
    }

    private void showEvidence(){
        StringBuilder details=new StringBuilder();long now=SystemClock.elapsedRealtime();
        for(PlaceSession.Evidence e:place.evidence())details.append(Math.max(0,(now-e.capturedAt())/1000)).append(" seconds ago\n").append(e.text()).append("\n\n");
        new android.app.AlertDialog.Builder(this).setTitle("Recent sign evidence")
            .setMessage(details.length()==0?"No washroom signs have been scanned in this session.":details+"Past observations don't show where things are now.")
            .setPositiveButton("Close",null).show();
    }

    // ───────────────────────────── Voice input ─────────────────────────────

    private void onMicPressed(){
        if(voice.isListening()){voice.cancel();renderMic();setState(AssistState.READY,"Stopped listening");return;}
        stopSpeech();
        if(!voice.available()){listenWithSystemDialog();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_MIC);return;}
        startListening();
    }
    private void startListening(){
        if(working||searchActive)cancelWork();
        voice.start(language.tag);
        setState(AssistState.LISTENING,"Listening… say what you need");
        renderMic();
    }
    private void listenWithSystemDialog(){
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,language.tag);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Say what you need, like “find my bag”");
        try{startActivityForResult(i,REQUEST_VOICE_INTENT);}
        catch(ActivityNotFoundException e){deliver("Voice input isn't available on this phone. You can type your request instead.",true);}
    }
    @Override public void onListening(){setState(AssistState.LISTENING,"Listening… say what you need");renderMic();}
    @Override public void onHeard(String partial){if(statusView!=null)statusView.set(AssistState.LISTENING,"“"+partial+"”");}
    @Override public void onResult(String text){renderMic();handleSpoken(text);}
    @Override public void onVoiceError(String message,boolean permissionProblem){
        renderMic();
        if(permissionProblem){listenWithSystemDialog();return;}
        noticeOverride=null;
        setState(AssistState.READY,null);
        displayText=message;result.setText(message);say(message,false);
    }

    private void handleSpoken(String text){
        noticeOverride="You said: “"+text+"”";
        VoiceCommands.Command c=VoiceCommands.parse(text,mode);
        switch(c.action()){
            case STOP -> stopAll(true);
            case REPEAT -> {setState(AssistState.READY,null);replay();}
            case READ -> {selectMode(AssistMode.READ);runPrimary();}
            case WASHROOM -> {selectMode(AssistMode.PLACES);scanWashroom();}
            case IDENTIFY_PLACE -> {selectMode(AssistMode.PLACES);runPrimary();}
            case RECALL -> recall(c.argument());
            case FIND -> {selectMode(AssistMode.FIND);findText=c.argument();query.setText(findText);runPrimary();}
            case DESCRIBE -> {boolean followUp=mode==AssistMode.DESCRIBE&&hasAnswer;selectMode(AssistMode.DESCRIBE);askText=c.argument();query.setText(askText);followUpContext=followUp;runPrimary();}
        }
        noticeOverride="You said: “"+text+"”";renderNotice();
    }

    private void askFollowUp(){
        String keep=lastMessage;
        if(mode!=AssistMode.DESCRIBE){selectMode(AssistMode.DESCRIBE);lastMessage=keep;displayText=keep;hasAnswer=true;result.setText(keep);renderAudioControls();refreshResponseActions();}
        followUpContext=true;askText="";query.setText("");
        focusQuery();
        if(accessibilityActive())query.announceForAccessibility("Type your question, or use the microphone.");
    }

    @Override protected void onActivityResult(int request,int response,Intent data){
        super.onActivityResult(request,response,data);
        if(request!=REQUEST_VOICE_INTENT)return;
        if(response!=RESULT_OK||data==null){setState(AssistState.READY,"Listening cancelled");return;}
        ArrayList<String> values=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if(values!=null&&!values.isEmpty())handleSpoken(values.get(0));
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        boolean granted=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED;
        if(request==REQUEST_CAMERA){if(granted)startCamera();else setCameraState(CameraState.NO_PERMISSION);}
        if(request==REQUEST_MIC){if(granted)startListening();else listenWithSystemDialog();}
    }

    // ───────────────────────────── Lifecycle ─────────────────────────────

    @Override public void onConfigurationChanged(Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        rememberQuery();
        buildUi();
        if(cameraProvider!=null)cameraProvider.unbindAll();
        startCamera();
    }

    @Override protected void onResume(){
        super.onResume();paused=false;pickingPhoto=false;
        ui.post(expiry);
        if(sensors!=null){var gyro=sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);if(gyro!=null)sensors.registerListener(this,gyro,android.hardware.SensorManager.SENSOR_DELAY_UI);}
        if(cameraState==CameraState.NO_PERMISSION&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();
        renderNotice();
        // Load Gemma shortly after the camera starts so the first answer is fast.
        ui.postDelayed(()->{if(!paused&&!destroyed)gemma.prepare();},1500);
        if(state==AssistState.PAUSED||state==AssistState.SPEAKING||state==AssistState.LISTENING)setState(AssistState.READY,null);
    }
    @Override public void onAccuracyChanged(android.hardware.Sensor sensor,int accuracy){}
    @Override public void onSensorChanged(android.hardware.SensorEvent event){
        float speed=0;for(float value:event.values)speed+=value*value;
        long now=SystemClock.elapsedRealtime();
        if(speed>SHAKE_RAD_S*SHAKE_RAD_S)lastShake=now;
        if(speed>.81f&&now-lastMotion>2000){
            lastMotion=now;
            PlaceSession.State s=place.state();
            if(s==PlaceSession.State.CANDIDATE||s==PlaceSession.State.VERIFYING){
                CaptureRequest pending=pendingCapture.get();
                if(working&&pending!=null&&(pending.kind()==Capture.PLACE_SIGN||pending.kind()==Capture.PLACE_VERIFY))cancelWork();
                place.invalidate("Camera moved. Hold steady and rescan the sign before confirming.");
                if(mode==AssistMode.PLACES)deliver(place.message(),true);
            }
        }
    }
    @Override protected void onPause(){
        paused=true;
        ui.removeCallbacks(expiry);
        if(sensors!=null)sensors.unregisterListener(this);
        place.pause();
        cancelWork();
        if(voice!=null){voice.cancel();}
        if(speech!=null)speech.stop();
        super.onPause();
    }
    @Override protected void onStop(){
        // Free the model's memory while Saathi is in the background; it reloads on return.
        if(!isChangingConfigurations()&&!pickingPhoto)gemma.release();
        super.onStop();
    }
    @Override protected void onDestroy(){
        destroyed=true;
        ui.removeCallbacksAndMessages(null);
        initialization.shutdown();gemma.close();voice.destroy();
        worker.execute(()->{try{if(detector!=null)detector.close();if(finder!=null)finder.close();}catch(Exception ignored){}});
        worker.shutdown();reader.close();hindiReader.close();
        if(speech!=null)speech.shutdown();
        super.onDestroy();
    }

    // ───────────────────────────── View helpers ─────────────────────────────

    private int dp(int value){return Theme.dp(this,value);}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private static <T extends ViewGroup.MarginLayoutParams> T margins(T p,int l,int t,int r,int b){p.setMargins(l,t,r,b);return p;}

    private Button button(String label,String icon,boolean solid,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Theme.medium());
        int fg=solid?Theme.ON_BRAND:Theme.INK;b.setTextColor(fg);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(14),0,dp(14),0);b.setGravity(Gravity.CENTER);b.setCompoundDrawablePadding(dp(8));
        if(icon!=null)b.setCompoundDrawables(new ActionIcon(icon,fg,dp(20)),null,null,null);
        b.setBackground(solid?Theme.control(this,Theme.BRAND,Theme.RADIUS_CONTROL,0,0):Theme.control(this,Theme.SURFACE,Theme.RADIUS_CONTROL,Theme.BORDER,1));
        b.setStateListAnimator(null);b.setMaxLines(2);
        b.setOnClickListener(v->action.run());
        return b;
    }

    /** Button on the dark camera overlays: solid teal for the main action, outlined otherwise. */
    private Button darkButton(String label,String icon,boolean solid,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Theme.medium());
        b.setTextColor(Theme.ON_DARK);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(14),0,dp(14),0);b.setGravity(Gravity.CENTER);b.setCompoundDrawablePadding(dp(8));
        if(icon!=null)b.setCompoundDrawables(new ActionIcon(icon,solid?Theme.ON_DARK:Theme.MINT,dp(20)),null,null,null);
        b.setBackground(solid?Theme.controlOnDark(this,Theme.BRAND,14,0,0):Theme.controlOnDark(this,0x14FFFFFF,14,0x5CFFFFFF,1));
        b.setStateListAnimator(null);b.setMaxLines(2);
        b.setOnClickListener(v->action.run());
        return b;
    }

    private ImageButton iconButton(String icon,int color,String description,Runnable action){
        ImageButton b=new ImageButton(this);
        b.setImageDrawable(new ActionIcon(icon,color,dp(22)));b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(Theme.control(this,0x00FFFFFF,24,0,0));
        b.setContentDescription(description);b.setMinimumWidth(dp(48));b.setMinimumHeight(dp(48));
        b.setOnClickListener(v->action.run());
        return b;
    }

    private void hideKeyboard(){
        InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(imm!=null&&query!=null)imm.hideSoftInputFromWindow(query.getWindowToken(),0);
    }
}
