package com.findback.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** In-app speech recognition so listening, partial words and cancel are visible inside Saathi. */
final class VoiceInput implements RecognitionListener {
    interface Callback {
        void onListening();
        void onHeard(String partial);
        void onResult(String text);
        void onVoiceError(String message,boolean permissionProblem);
    }
    private final Context context;
    private final Callback callback;
    private SpeechRecognizer recognizer;
    private boolean listening;

    VoiceInput(Context context,Callback callback){this.context=context;this.callback=callback;}
    boolean available(){return SpeechRecognizer.isRecognitionAvailable(context);}
    boolean isListening(){return listening;}

    void start(String languageTag){
        cancel();
        if(recognizer==null){recognizer=SpeechRecognizer.createSpeechRecognizer(context);recognizer.setRecognitionListener(this);}
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,languageTag);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,context.getPackageName());
        listening=true;
        recognizer.startListening(i);
    }
    void cancel(){if(recognizer!=null&&listening)recognizer.cancel();listening=false;}
    void destroy(){cancel();if(recognizer!=null)recognizer.destroy();recognizer=null;}

    @Override public void onReadyForSpeech(Bundle params){callback.onListening();}
    @Override public void onBeginningOfSpeech(){}
    @Override public void onRmsChanged(float rmsdB){}
    @Override public void onBufferReceived(byte[] buffer){}
    @Override public void onEndOfSpeech(){}
    @Override public void onEvent(int eventType,Bundle params){}
    @Override public void onPartialResults(Bundle partial){String s=first(partial);if(listening&&!s.isEmpty())callback.onHeard(s);}
    @Override public void onResults(Bundle results){
        if(!listening)return;
        listening=false;String s=first(results);
        if(s.isEmpty())callback.onVoiceError("I didn't catch that. Tap the microphone and try again.",false);else callback.onResult(s);
    }
    @Override public void onError(int error){
        if(!listening)return;
        listening=false;
        switch(error){
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> callback.onVoiceError("Microphone access is needed to hear your request.",true);
            case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> callback.onVoiceError("I didn't catch that. Tap the microphone and try again.",false);
            case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> callback.onVoiceError("Speech recognition needs a connection for this language. You can type instead.",false);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> callback.onVoiceError("The microphone is busy. Try again in a moment.",false);
            default -> callback.onVoiceError("Voice input isn't available right now. You can type instead.",false);
        }
    }
    private static String first(Bundle b){
        if(b==null)return "";
        ArrayList<String> values=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values==null||values.isEmpty()||values.get(0)==null?"":values.get(0).trim();
    }
}
